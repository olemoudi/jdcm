#!/usr/bin/env bash
# jdcm-fetch.sh: reads the public JSON of juegosdeportivos.ecmadrid.org for the groups the JDCM web app
# follows, and publishes it to the public repo olemoudi/jdcm-data (one commit, force-pushed). The web
# app (https://makensi.es/jdcm) reads it from raw.githubusercontent.com and does all the parsing and
# the standings itself (pwa/src/core).
#
# Why here: the site sends no CORS headers (a browser cannot read it) and answers only Spanish home
# connections (measured 2026-11-16: GitHub's runners and 16 of 17 test servers time out), so it runs
# on a small device at home (a Raspberry Pi), from a systemd user timer (see INSTALL.md).
#
# Politeness, the Android app's rules: one request at a time, 1.5 s apart, an honest User-Agent, at
# most 60 requests and 4 minutes a run, nothing at night. A body that is not JSON is asked once more;
# a 5xx, a 429 or a timeout ends the run at once, and so do two odd answers in a row.
#
# Per run and group: the current round (IdJornada=0, which brings the previous and next ones with
# their matches); any other round holding a match from 3 days ago to 6 days ahead (a replay keeps its
# old round); once a day, the rest of the season. Once a day too, the grouped calendar of each sport
# (3 requests) for the app's list of teams. A round is stored only if its answer is for the round and
# group asked and holds at least half the matches held (or the same smaller count twice running).
#
# Usage: jdcm-fetch.sh [--now] [--full] [--no-push]
#   --now      run even at night (a manual run; the timer never passes it)
#   --full     also re-read every round of the season now
#   --no-push  do everything but publish (a dry run; the result is kept in the state folder)
# Environment (all optional): JDCM_PUMP_SSH=default uses your own ssh setup instead of the deploy key
# (e.g. `ssk run olemoudi -- env JDCM_PUMP_SSH=default ./jdcm-fetch.sh --now`); JDCM_DATA_REMOTE,
# JDCM_GROUPS_URL, JDCM_SITE, JDCM_FETCH_HOME/STATE/CONFIG and JDCM_FETCH_MAX_REQUESTS for tests.
set -euo pipefail

main() {
  VERSION=1
  UA="JDCM-PWA-fetch/$VERSION (+https://github.com/olemoudi/jdcm)"
  SITE=${JDCM_SITE:-https://juegosdeportivos.ecmadrid.org}
  GROUPS_URL=${JDCM_GROUPS_URL:-https://raw.githubusercontent.com/olemoudi/jdcm/main/pwa/groups.json}
  DATA_REMOTE=${JDCM_DATA_REMOTE:-git@github.com:olemoudi/jdcm-data.git}
  SSH_MODE=${JDCM_PUMP_SSH:-deploy-key}
  HOME_DIR=${JDCM_FETCH_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/jdcm-fetch}
  STATE_DIR=${JDCM_FETCH_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/jdcm-fetch}
  CONFIG_DIR=${JDCM_FETCH_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/jdcm-fetch}
  MAX_REQUESTS=${JDCM_FETCH_MAX_REQUESTS:-60}
  BUDGET_MS=$(( ${JDCM_FETCH_BUDGET_S:-240} * 1000 ))
  GAP_MS=${JDCM_FETCH_GAP_MS:-1500}

  local now_flag=0 full_flag=0 push=1
  for a in "$@"; do
    case "$a" in
      --now) now_flag=1 ;;
      --full) full_flag=1 ;;
      --no-push) push=0 ;;
      -h|--help) sed -n '2,30p' "$0"; return 0 ;;
      *) echo "unknown option $a (see --help)" >&2; return 2 ;;
    esac
  done

  mkdir -p "$HOME_DIR" "$STATE_DIR"
  setup_log

  # One run at a time (a slow run must not overlap the next timer).
  exec 9>"$STATE_DIR/lock"
  if ! flock -n 9; then log "another run is still going: skipped"; return 0; fi

  local hour
  hour=$(TZ=Europe/Madrid date +%H)
  if (( now_flag == 0 )) && { (( 10#$hour < 7 )) || (( 10#$hour > 21 )); }; then
    log "it is ${hour} h in Madrid: nothing at night"
    return 0
  fi

  for tool in curl jq git flock; do
    command -v "$tool" >/dev/null || { log "E missing $tool (apt install curl jq git)"; return 1; }
  done

  # Work in RAM when there is a runtime dir (tmpfs): a Raspberry Pi's SD card is spared.
  WORK=$(mktemp -d "${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/jdcm-fetch.XXXXXX")
  trap 'rm -rf "$WORK"' EXIT
  STARTED_MS=$(now_ms)
  TODAY=$(TZ=Europe/Madrid date +%F)
  FROM=$(TZ=Europe/Madrid date -d "$TODAY -3 days" +%F)
  TO=$(TZ=Europe/Madrid date -d "$TODAY +6 days" +%F)
  REQ=0 ODD_IN_A_ROW=0 LAST_REQ_MS=0 STOPPED="" OK_ANSWERS=0 STORED=0 REFUSED=0
  log "start (v$VERSION) today=$TODAY window=$FROM..$TO"

  if [[ $SSH_MODE != default ]]; then
    [[ -f $CONFIG_DIR/deploy_key ]] || { log "E no deploy key at $CONFIG_DIR/deploy_key (run install.sh, or JDCM_PUMP_SSH=default)"; return 1; }
    local hosts="$CONFIG_DIR/known_hosts" check=yes
    [[ -s $hosts ]] || check=accept-new
    export GIT_SSH_COMMAND="ssh -i $CONFIG_DIR/deploy_key -o IdentitiesOnly=yes -o BatchMode=yes -o UserKnownHostsFile=$hosts -o StrictHostKeyChecking=$check"
  fi

  wait_for_network
  read_groups || return 1
  read_previous || return 1

  local D="$WORK/data/v1"
  mkdir -p "$D/groups" "$D/catalog"

  # Groups no longer followed go.
  local g
  for g in "$D"/groups/*/; do
    [[ -d $g ]] || continue
    g=$(basename "$g")
    jq -e --argjson g "$g" 'any(.groups[]; .groupId == $g)' "$WORK/groups.json" >/dev/null || { log "group=$g no longer followed: removed"; rm -rf "${D:?}/groups/$g"; }
  done

  local ids
  ids=$(jq -r '.groups[].groupId' "$WORK/groups.json")
  # 1. This weekend: the current round of every group (it brings the previous and next ones).
  for g in $ids; do
    [[ -n $STOPPED ]] && break
    init_meta "$g"
    fetch_round "$g" 0 || true
  done
  # 2. Rounds holding a match near today that step 1 did not bring (a replay keeps its old round).
  for g in $ids; do
    [[ -n $STOPPED ]] && break
    local r
    for r in $(near_rounds "$g"); do
      [[ -n $STOPPED ]] && break
      fresh_this_run "$g" "$r" && continue
      fetch_round "$g" "$r" || true
    done
  done
  # 3. Once a day: the grouped calendar of each sport, for the app's list of teams.
  local sport
  for sport in 1 2 3; do
    [[ -n $STOPPED ]] && break
    fetch_catalog "$sport" || true
  done
  # 4. Once a day: the rest of the season, closest to today first (each answer brings two neighbours).
  for g in $ids; do
    [[ -n $STOPPED ]] && break
    local due
    due=$(daily_rounds "$g" "$full_flag")
    [[ -z $due ]] && continue
    local complete=1
    for r in $due; do
      [[ -n $STOPPED ]] && { complete=0; break; }
      fresh_this_run "$g" "$r" && continue
      fetch_round "$g" "$r" || true
    done
    [[ -n $STOPPED ]] && complete=0
    (( complete )) && meta_set "$g" --arg d "$TODAY" '.fullDay = $d'
  done

  [[ -n $STOPPED ]] && log "W stopped: $STOPPED; the rest waits for the next run"
  log "requests=$REQ answers=$OK_ANSWERS stored=$STORED refused=$REFUSED"

  local publish=0
  (( OK_ANSWERS > 0 )) && publish=1
  if (( publish )); then
    write_views
  fi
  if (( publish == 0 )); then
    log "nothing to publish (no answer from the site)"
    write_last_run no
    return 0
  fi
  if (( push == 0 )); then
    rm -rf "$STATE_DIR/no-push"
    cp -r "$WORK/data" "$STATE_DIR/no-push"
    rm -rf "$STATE_DIR/no-push/.git"
    log "--no-push: the result is in $STATE_DIR/no-push"
    write_last_run "no (--no-push)"
    return 0
  fi
  if publish_data; then write_last_run yes; else write_last_run "FAILED"; return 1; fi
}

# --- plumbing ------------------------------------------------------------------------------------

now_ms() { date +%s%3N; }

log() { printf '%s %s\n' "$(date '+%F %T')" "$*"; }

# One log file, rotated at 256 KB (three kept): a few KB a day on the SD card. When run by hand the
# lines also go to the terminal; under the timer only to the file (no second copy in the journal).
setup_log() {
  LOG="$STATE_DIR/fetch.log"
  if [[ -f $LOG ]] && (( $(stat -c %s "$LOG") > 262144 )); then
    mv -f "$LOG.2" "$LOG.3" 2>/dev/null || true
    mv -f "$LOG.1" "$LOG.2" 2>/dev/null || true
    mv -f "$LOG" "$LOG.1"
  fi
  if [[ -t 1 ]]; then exec > >(tee -a "$LOG") 2>&1; else exec >>"$LOG" 2>&1; fi
}

# At boot (a missed run fired late) the network may come a little after the timer.
wait_for_network() {
  local i
  for i in 1 2 3 4 5 6; do
    getent hosts github.com >/dev/null 2>&1 && return 0
    sleep 10
  done
  log "W no DNS yet; trying anyway"
}

# groups.json from jdcm main: the owner adds a group by committing there. The last good copy is kept
# (written only when it changes) for when GitHub cannot be reached.
read_groups() {
  local cached="$HOME_DIR/groups.json"
  if curl -fsS --max-time 30 -A "$UA" -o "$WORK/groups.new" "$GROUPS_URL" 2>"$WORK/curl.err" \
    && jq -e '(.groups | type == "array") and all(.groups[]; (.groupId|type) == "number" and .groupId > 0 and .sportId > 0 and .competitionId > 0 and .categoryId > 0)' "$WORK/groups.new" >/dev/null; then
    cmp -s "$WORK/groups.new" "$cached" || cp "$WORK/groups.new" "$cached"
  else
    log "W could not read groups.json ($(head -c 120 "$WORK/curl.err" 2>/dev/null)); using the last good copy"
  fi
  [[ -f $cached ]] || { log "E no groups.json"; return 1; }
  cp "$cached" "$WORK/groups.json"
  log "groups: $(jq -c '[.groups[].groupId]' "$WORK/groups.json")"
}

# What was published last time is this run's memory: a shallow clone into the work folder.
read_previous() {
  local rc=0
  git ls-remote --exit-code --heads "$DATA_REMOTE" main >/dev/null 2>"$WORK/git.err" || rc=$?
  if (( rc == 2 )); then
    log "no data published yet: first run"
    mkdir -p "$WORK/data"
    return 0
  elif (( rc != 0 )); then
    log "E cannot read $DATA_REMOTE: $(tr '\n' ' ' < "$WORK/git.err" | head -c 200)"
    return 1
  fi
  if ! git clone -q --depth 1 --branch main "$DATA_REMOTE" "$WORK/data" 2>"$WORK/git.err"; then
    log "E cannot clone $DATA_REMOTE: $(tr '\n' ' ' < "$WORK/git.err" | head -c 200)"
    return 1
  fi
}

# One request to the site, politely. Writes the decoded JSON (the MVC actions answer a JSON string
# holding JSON) to $2. Returns 0 on an answer, 1 for an odd answer (this request only), 2 when the
# run must stop (the site down, refusing, slow; two odd answers in a row; the budget spent).
site_get() {
  local query=$1 out=$2 label=$3 attempt rc code
  for attempt in 1 2; do
    if (( REQ >= MAX_REQUESTS )); then STOPPED="budget of $MAX_REQUESTS requests"; return 2; fi
    if (( $(now_ms) - STARTED_MS > BUDGET_MS )); then STOPPED="time budget"; return 2; fi
    local pause=$(( LAST_REQ_MS + GAP_MS - $(now_ms) ))
    if (( pause > 0 )); then sleep "$(( pause / 1000 )).$(printf '%03d' $(( pause % 1000 )))"; fi
    REQ=$((REQ + 1))
    rc=0
    code=$(curl -sS -o "$WORK/body" -w '%{http_code}' --max-time 30 --connect-timeout 15 --max-filesize 4194304 \
      -A "$UA" -H 'Accept: application/json, text/javascript, */*; q=0.01' -H 'X-Requested-With: XMLHttpRequest' \
      "$SITE/$query" 2>"$WORK/curl.err") || rc=$?
    LAST_REQ_MS=$(now_ms)
    if (( rc == 63 )); then log "W $label: answer over 4 MB"; odd_answer; return $?; fi
    if (( rc != 0 )); then STOPPED="site down (curl $rc: $(head -c 80 "$WORK/curl.err" | tr '\n' ' '))"; return 2; fi
    if [[ $code == 429 || $code == 5* ]]; then STOPPED="site down (HTTP $code)"; return 2; fi
    if [[ $code != 2* ]]; then log "W $label: HTTP $code"; odd_answer; return $?; fi
    if jq -c 'if type == "string" then fromjson else . end' "$WORK/body" >"$out" 2>/dev/null && [[ -s $out ]]; then
      ODD_IN_A_ROW=0
      OK_ANSWERS=$((OK_ANSWERS + 1))
      return 0
    fi
    if (( attempt == 1 )); then log "W $label: not JSON ($(head -c 40 "$WORK/body" | tr -c '[:print:]' ' ')), asking once more"; fi
  done
  log "W $label: not JSON twice"
  odd_answer
}

odd_answer() {
  ODD_IN_A_ROW=$((ODD_IN_A_ROW + 1))
  if (( ODD_IN_A_ROW >= 2 )); then STOPPED="two odd answers in a row"; return 2; fi
  return 1
}

gdir() { printf '%s' "$WORK/data/v1/groups/$1"; }

# A group's meta.json: the site's round list, when each round was accepted, how many matches it
# holds, refused counts (shrinkSeen), the last attempt and error, the day of the last full pass.
init_meta() {
  local g=$1 dir
  dir=$(gdir "$g")
  mkdir -p "$dir"
  local ref
  ref=$(jq -c --argjson g "$g" '.groups[] | select(.groupId == $g) | {groupId, sportId, competitionId, categoryId, label: (.label // "")}' "$WORK/groups.json")
  if [[ ! -f $dir/meta.json ]]; then
    jq -n --argjson ref "$ref" '{v: 1, groupId: $ref.groupId, ref: ($ref | del(.label)), label: $ref.label, group: {}, rounds: [],
      fetchedAt: {}, count: {}, shrinkSeen: {}, currentRoundId: 0, lastAttempt: 0, lastError: null, fullDay: ""}' >"$dir/meta.json"
  else
    meta_set "$g" --argjson ref "$ref" '.ref = ($ref | del(.label)) | .label = $ref.label'
  fi
}

meta_set() {
  local g=$1; shift
  local f
  f="$(gdir "$g")/meta.json"
  jq -c "$@" "$f" >"$f.tmp" && mv "$f.tmp" "$f"
}

# Answered during this run already (a neighbour of an earlier answer, accepted or refused).
fresh_this_run() {
  jq -e --arg r "$2" --argjson t "$STARTED_MS" '(.fetchedAt[$r] // 0) >= $t or (.shrinkSeen[$r].at // 0) >= $t' "$(gdir "$1")/meta.json" >/dev/null
}

# One round of a group ([round] 0 = the one the site calls current), checked and stored.
fetch_round() {
  local g=$1 r=$2 dir ref q rc=0
  dir=$(gdir "$g")
  ref=$(jq -r '.ref | "IdDeporte=\(.sportId)&IdCompeticion=\(.competitionId)&IdCategoria=\(.categoryId)&IdGrupo=\(.groupId)"' "$dir/meta.json")
  q="Calendarios/ObtenerDatosJornadaGrupo?$ref&IdJornada=$r&IdEquipo=0"
  meta_set "$g" --argjson t "$(now_ms)" '.lastAttempt = $t'
  site_get "$q" "$WORK/answer.json" "group=$g round=$r" || rc=$?
  if (( rc != 0 )); then
    meta_set "$g" --arg e "${STOPPED:-odd answer}" '.lastError = $e'
    return "$rc"
  fi
  store_answer "$g" "$r"
}

# The defensive part (core's GroupSnapshot.merge rules): store each round of the answer (the asked
# one and its two neighbours) only if it is for the round and group asked and is not much smaller
# than what is held; the site's round list only if it is plausible. Everything decided in one jq.
store_answer() {
  local g=$1 asked=$2 dir
  dir=$(gdir "$g")
  if ! jq -c --argjson g "$g" --argjson asked "$asked" --argjson run "$STARTED_MS" --slurpfile meta "$dir/meta.json" -f <(cat <<'JQ'
def num: if type == "number" then . elif type == "string" then (tonumber? // 0) else 0 end;
def rdate: (.Fecha // "") | if type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}") then .[0:10] else null end;
def roundOf: {id: (.Id | num), number: (.NumeroJornada | num), date: rdate};
$meta[0] as $m
| if type != "object" or ((has("jornada") or has("jornadas")) | not) then {error: "not a round page"}
  elif $asked != 0 and ((.jornada.Id? // 0) | num) != $asked then {error: "asked round \($asked), got \((.jornada.Id? // 0) | num)"}
  else
    . as $a
    | [ ["asked", $a.jornada], ["previous", $a.previous], ["next", $a.next] ]
    | map(select((.[1] | type) == "object" and ((.[1].Id // 0) | num) > 0 and ((.[1].Enfrentamientos | type) == "array")))
    | map(.[1] as $r
        | ($r.Enfrentamientos | map(select(type == "object"))) as $all
        | ($all | map(select(((.IdGrupo // 0) | num) == $g or ((.IdGrupo // 0) | num) == 0))) as $ours
        | ($r | roundOf) as $ro
        | ($m.count[$ro.id | tostring] // 0) as $held
        | ($ours | length) as $n
        | ($m.shrinkSeen[$ro.id | tostring] // {}) as $seen
        | (($all | length) > 0 and $n == 0) as $stranger
        | {kind: .[0], round: $ro, obj: ($r | .Enfrentamientos = $ours), n: $n, held: $held,
           # The same smaller count believed only if a previous run saw it too (not seconds ago).
           ok: (($stranger | not) and ($held == 0 or $n * 2 >= $held or (($seen.n // -1) == $n and ($seen.at // $run) < $run))),
           shrink: ($stranger | not),
           why: (if $stranger then "an answer for another group" else "\($n) matches where \($held) are held" end)})
    | . as $rounds
    | {rounds: $rounds,
       list: ([$a.jornadas[]? | select(type == "object") | roundOf | select(.id > 0)] | sort_by(.number)),
       current: (if $asked == 0 then (($a.jornada.Id? // 0) | num) else 0 end),
       group: ([$rounds[] | .obj.Enfrentamientos[]] | first // {} | {name: (.Grupo // ""), competition: (.Competicion // ""), category: (.Categoria // ""), sport: (.Deporte // "")})}
  end
JQ
) "$WORK/answer.json" >"$WORK/decision.json" 2>"$WORK/jq.err"; then
    log "W group=$g round=$asked: the answer could not be read ($(head -c 100 "$WORK/jq.err"))"
    odd_answer || true
    return 1
  fi
  local err
  err=$(jq -r '.error // empty' "$WORK/decision.json")
  if [[ -n $err ]]; then
    log "W group=$g round=$asked: $err; nothing stored"
    meta_set "$g" --arg e "$err" '.lastError = $e'
    REFUSED=$((REFUSED + 1))
    odd_answer || true
    return 1
  fi
  # Accepted rounds: their files.
  local id json n
  while IFS=$'\t' read -r id n json; do
    printf '%s\n' "$json" >"$dir/r$id.json"
    STORED=$((STORED + 1))
  done < <(jq -r '.rounds[] | select(.ok) | "\(.round.id)\t\(.n)\t\(.obj | tojson)"' "$WORK/decision.json")
  while IFS=$'\t' read -r id why; do
    log "W group=$g round=$id refused: $why; the previous copy stays"
    REFUSED=$((REFUSED + 1))
  done < <(jq -r '.rounds[] | select(.ok | not) | "\(.round.id)\t\(.why)"' "$WORK/decision.json")
  # The meta: stamps, counts, refused counts, the round list (only a plausible one), names.
  meta_set "$g" --argjson t "$(now_ms)" --argjson run "$STARTED_MS" --slurpfile d "$WORK/decision.json" '
    $d[0] as $d
    | reduce ($d.rounds[] | select(.ok)) as $r (.; .fetchedAt[$r.round.id | tostring] = $t | .count[$r.round.id | tostring] = $r.n | del(.shrinkSeen[$r.round.id | tostring]))
    | reduce ($d.rounds[] | select((.ok | not) and .shrink)) as $r (.; .shrinkSeen[$r.round.id | tostring] |= (if (.n // -1) == $r.n then . else {n: $r.n, at: $run} end))
    | ([$d.rounds[] | select(.ok) | .round.id]) as $got
    | (if ($d.list | length) > 0 and ($got - [$d.list[].id] | length) == 0 and ((.rounds | length) < 4 or ($d.list | length) * 2 >= (.rounds | length))
       then .rounds = $d.list else . end)
    | (if $d.current > 0 and ([$d.rounds[] | select(.ok and .kind == "asked") | .round.id] | index($d.current)) != null then .currentRoundId = $d.current else . end)
    | (if ($d.group.name // "") != "" then .group = $d.group else . end)
    | .lastError = null'
  prune_rounds "$g"
  log "group=$g round=$asked stored=$(jq -c '[.rounds[] | select(.ok) | .round.id]' "$WORK/decision.json")"
}

# Rounds the site no longer lists go, unless they hold a result (core: never drop a played round).
prune_rounds() {
  local g=$1 dir f id
  dir=$(gdir "$g")
  for f in "$dir"/r*.json; do
    [[ -f $f ]] || continue
    id=${f##*/r}; id=${id%.json}
    jq -e --argjson id "$id" 'any(.rounds[]; .id == $id)' "$dir/meta.json" >/dev/null && continue
    if jq -e 'any(.Enfrentamientos[]?; ((.IdEstadoPartido // 0) == 10 or (.IdEstadoPartido // 0) == 11 or (.IdEquipoGanador // 0) != 0)
              and (.ResultadoEquipoLocal | type) == "number" and (.ResultadoEquipoVisitante | type) == "number")' "$f" >/dev/null; then
      meta_set "$g" --argjson id "$id" --slurpfile r "$f" '.rounds = (.rounds + [{id: $id, number: ($r[0].NumeroJornada // 0), date: (($r[0].Fecha // "")[0:10])}] | unique_by(.id) | sort_by(.number))'
      continue
    fi
    log "group=$g round=$id no longer listed by the site: removed"
    rm -f "$f"
    meta_set "$g" --arg id "$id" 'del(.fetchedAt[$id], .count[$id], .shrinkSeen[$id])'
  done
}

# Rounds with a match (or the round's own date) from 3 days ago to 6 days ahead, nearest first.
near_rounds() {
  local dir
  dir=$(gdir "$1")
  local files=("$dir"/r*.json)
  [[ -f ${files[0]} ]] || return 0
  jq -rn --arg from "$FROM" --arg to "$TO" --arg today "$TODAY" '
    def day: if type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}") then .[0:10] else null end;
    def epoch: strptime("%Y-%m-%d") | mktime;
    ($today | epoch) as $t
    | [inputs | {id: .Id, days: ([(.Fecha | day)] + [.Enfrentamientos[]? | (.FechaHora | day)] | map(select(. != null and . >= $from and . <= $to)))}
      | select(.days | length > 0)]
    | sort_by([.days[] | epoch - $t | fabs] | min) | .[].id' "${files[@]}"
}

# Once a day (or every round older than 24 h): the rest of the season, nearest to today first.
daily_rounds() {
  local g=$1 full=$2
  jq -r --arg today "$TODAY" --argjson now "$(now_ms)" --argjson full "$full" '
    . as $m
    | (.fullDay != $today or $full == 1) as $pass
    | [.rounds[] | . as $r | select($pass or ($now - ($m.fetchedAt[$r.id | tostring] // 0)) > 86400000)
       | {id, dist: (if .date then ((.date | strptime("%Y-%m-%d") | mktime) - ($today | strptime("%Y-%m-%d") | mktime)) | fabs else 1e12 end)}]
    | sort_by(.dist) | .[].id' "$(gdir "$g")/meta.json"
}

# The grouped calendar of a sport (one request, once a day), trimmed to the teams of each group, and
# merged with what was held: a group missing from today's answer stays in the list.
fetch_catalog() {
  local sport=$1 f="$WORK/data/v1/catalog/$1.json" number rc=0
  if [[ -f $f ]] && jq -e --arg d "$TODAY" '.day == $d' "$f" >/dev/null; then return 0; fi
  number=$(jq -rs --argjson s "$sport" '[.[] | select(.ref.sportId == $s) | . as $m | .rounds[] | select(.id == $m.currentRoundId) | .number] | first // 1' "$WORK"/data/v1/groups/*/meta.json 2>/dev/null || echo 1)
  [[ $number =~ ^[0-9]+$ ]] && (( number > 0 )) || number=1
  site_get "Calendarios/ObtenerCalendarioAgrupado?IdDeporte=$sport&IdCompeticion=-1&IdCategoria=0&NumeroJornada=$number&IdEquipo=0" "$WORK/grouped.json" "catalog sport=$sport" || rc=$?
  (( rc == 0 )) || return "$rc"
  local held='[]'
  [[ -f $f ]] && held=$(jq -c '.competitions // []' "$f")
  if ! jq -c --argjson held "$held" --argjson s "$sport" --arg d "$TODAY" --argjson t "$(now_ms)" --argjson n "$number" '
      def trim: [.[]? | select(type == "object") | {IdCompeticion, Competicion, Categorias: [.Categorias[]? | {IdCategoria, Categoria,
        Grupos: [.Grupos[]? | {IdGrupo, Grupo, Enfrentamientos: [.Enfrentamientos[]? | {Id, IdGrupo, IdEquipoLocal, EquipoLocal, URLEscudoEquipoLocal, IdEquipoVisitante, EquipoVisitante, URLEscudoEquipoVisitante}]}]}]}];
      def flat: [.[] as $c | $c.Categorias[] as $k | $k.Grupos[] | {c: $c.IdCompeticion, cn: $c.Competicion, k: $k.IdCategoria, kn: $k.Categoria, g: .}];
      def nest: group_by(.c) | map({IdCompeticion: .[0].c, Competicion: .[0].cn,
        Categorias: (group_by(.k) | map({IdCategoria: .[0].k, Categoria: .[0].kn, Grupos: map(.g)}))});
      if type != "array" then error("not a grouped calendar") else . end
      | (trim | flat) as $new
      | ($new | map(.g.IdGrupo)) as $ids
      | ($held | flat | map(select(.g.IdGrupo as $i | $ids | index($i) | not))) as $kept
      | {v: 1, sportId: $s, day: $d, fetchedAt: $t, number: $n, groups: ($new | length), kept: ($kept | length),
         competitions: ($new + $kept | nest)}' "$WORK/grouped.json" >"$WORK/catalog.json" 2>"$WORK/jq.err"; then
    log "W catalog sport=$sport: not a grouped calendar ($(head -c 100 "$WORK/jq.err")); the previous list stays"
    odd_answer || true
    return 1
  fi
  mv "$WORK/catalog.json" "$f"
  STORED=$((STORED + 1))
  log "catalog sport=$sport round=$number groups=$(jq .groups "$f") kept=$(jq .kept "$f")"
}

# What the web app reads: per group, all.json (meta + every listed round, one request instead of
# twenty), and index.json.
write_views() {
  local D="$WORK/data/v1" g dir files
  for dir in "$D"/groups/*/; do
    [[ -d $dir ]] || continue
    files=("$dir"r*.json)
    if [[ -f ${files[0]} ]]; then
      jq -cn --slurpfile meta "${dir}meta.json" '$meta[0] as $m | {v: 1, meta: $m,
        rounds: (reduce inputs as $r ({}; if any($m.rounds[]; .id == $r.Id) then .[$r.Id | tostring] = $r else . end))}' "${files[@]}" >"${dir}all.json"
    else
      jq -c '{v: 1, meta: ., rounds: {}}' "${dir}meta.json" >"${dir}all.json"
    fi
  done
  local metas=("$D"/groups/*/meta.json)
  [[ -f ${metas[0]} ]] || metas=(/dev/null) # never let jq wait on stdin
  jq -cn --argjson ms "$(now_ms)" --arg at "$(date -u +%FT%TZ)" --arg next "$(next_run)" \
    --argjson req "$REQ" --argjson stored "$STORED" --argjson refused "$REFUSED" --arg stopped "$STOPPED" \
    --slurpfile groups "$WORK/groups.json" '
    [inputs] as $metas
    | {v: 1, generatedAt: $at, generatedMs: $ms, nextRun: $next, refreshHours: 3, refreshFromHour: 7, refreshToHour: 19,
       groups: [$groups[0].groups[] | . as $g | ($metas | map(select(.groupId == $g.groupId)) | first) as $m
         | {groupId, sportId, competitionId, categoryId, label: (.label // ""),
            newestFetch: ([$m.fetchedAt[]?] | max // 0), lastAttempt: ($m.lastAttempt // 0), lastError: ($m.lastError // null)}],
       lastRun: {requests: $req, stored: $stored, refused: $refused, stopped: (if $stopped == "" then null else $stopped end)}}' \
    "${metas[@]}" >"$D/index.json"
  cat >"$WORK/data/README.md" <<'EOF'
# JDCM data

What the JDCM web app (https://makensi.es/jdcm) reads: the public JSON of
juegosdeportivos.ecmadrid.org for the groups in `pwa/groups.json` of https://github.com/olemoudi/jdcm,
read by a small device at home (`pwa/pi/jdcm-fetch.sh`) and force-pushed here as one commit every run.
Never edit it by hand.

- `v1/index.json`: the groups, when the data was published, the last run.
- `v1/groups/<id>/r<round>.json`: one round as the site sent it (its matches), kept only if plausible.
- `v1/groups/<id>/meta.json`: the round list, when each round was read, refusals, the last error.
- `v1/groups/<id>/all.json`: meta and rounds together (what the app downloads).
- `v1/catalog/<sport>.json`: every group and team of a sport (to add a child).
EOF
}

# The next scheduled run, as a hint for the app ("07:07, 10:07, 13:07, 16:07, 19:07" Madrid).
next_run() {
  local h t now
  now=$(date +%s)
  for h in 07 10 13 16 19; do
    t=$(TZ=Europe/Madrid date -d "$TODAY $h:07" +%s)
    if (( t > now )); then TZ=Europe/Madrid date -d "@$t" +%FT%T%:z; return; fi
  done
  TZ=Europe/Madrid date -d "$TODAY 07:07 +1 day" +%FT%T%:z
}

write_last_run() {
  printf 'at=%s\nrequests=%s\nanswers=%s\nstored=%s\nrefused=%s\npublished=%s\nstopped=%s\n' \
    "$(date '+%F %T')" "$REQ" "$OK_ANSWERS" "$STORED" "$REFUSED" "$1" "$STOPPED" >"$STATE_DIR/last-run"
}

# One commit, force-pushed: the history of jdcm-data never grows.
publish_data() {
  local dir="$WORK/data"
  rm -rf "$dir/.git"
  git -C "$dir" init -q -b main
  git -C "$dir" add -A
  git -C "$dir" -c user.name="JDCM fetch" -c user.email="jdcm-fetch@users.noreply.github.com" \
    commit -q -m "data $(date -u +%FT%TZ): $REQ requests, $STORED stored"
  if git -C "$dir" push -q --force "$DATA_REMOTE" HEAD:refs/heads/main 2>"$WORK/git.err"; then
    log "published to $DATA_REMOTE"
  else
    log "E push failed: $(tr '\n' ' ' < "$WORK/git.err" | head -c 300)"
    return 1
  fi
}

main "$@"
