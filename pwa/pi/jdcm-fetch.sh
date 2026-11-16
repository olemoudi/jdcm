#!/usr/bin/env bash
# jdcm-fetch.sh: reads the public JSON of juegosdeportivos.ecmadrid.org for EVERY group of the
# competition (~109 in three sports) and publishes it to the public repo olemoudi/jdcm-data (one
# commit, force-pushed). The web app (https://makensi.es/jdcm) reads it from raw.githubusercontent.com
# and does all the parsing, merging and standings itself (pwa/src/core).
#
# Why here: the site sends no CORS headers (a browser cannot read it) and answers only Spanish home
# connections (measured 2026-11-16: GitHub's runners and 16 of 17 test servers time out), so it runs
# on a small device at home (a Raspberry Pi), from a systemd user timer (see INSTALL.md).
#
# Polite by design. The grouped calendar (one request per sport and round number) carries every
# group's schedule and match states, but no scores; scores need one request per group and round. So:
#   1. every run: the grouped calendar of the round numbers being played (matches from 3 days ago to
#      6 days ahead), and the one before while any of its matches is still open;
#   2. every run: a group's own round, only for groups with a match that has started, is not
#      cancelled, has no final result yet (from the last 14 days) and that the grouped calendar
#      already shows as played; oldest first; a group that brings no new result is not asked again;
#   3. once a day (the first run): the grouped calendars of the next three weeks;
#   4. once a week: the grouped calendars of every other round number (spread over runs if needed);
#   5. groups never read on their own: their round 1 (it brings round 2 and the season's list).
# One request at a time 1.5 s apart, an honest User-Agent, at most 150 requests and 8 minutes a run,
# nothing at night. A body that is not JSON is asked once more; a 5xx, a 429 or a timeout ends the
# run, and so do two odd answers in a row. Answers are taken defensively (core's merge rules): a
# group's round that comes back with fewer than half the matches held is refused unless an earlier
# run saw the same count.
#
# Usage: jdcm-fetch.sh [--now] [--no-push]
#   --now      run even at night (a manual run; the timer never passes it)
#   --no-push  do everything but publish (a dry run; the result is kept in the state folder)
# Environment (all optional): JDCM_PUMP_SSH=default uses your own ssh setup instead of the deploy key
# (e.g. `ssk run olemoudi -- env JDCM_PUMP_SSH=default ./jdcm-fetch.sh --now`); JDCM_DATA_REMOTE,
# JDCM_SITE, JDCM_FETCH_HOME/STATE/CONFIG, JDCM_FETCH_MAX_REQUESTS and JDCM_FETCH_GAP_MS for tests.
set -euo pipefail

main() {
  VERSION=2
  UA="JDCM-PWA-fetch/$VERSION (+https://github.com/olemoudi/jdcm)"
  SITE=${JDCM_SITE:-https://juegosdeportivos.ecmadrid.org}
  DATA_REMOTE=${JDCM_DATA_REMOTE:-git@github.com:olemoudi/jdcm-data.git}
  SSH_MODE=${JDCM_PUMP_SSH:-deploy-key}
  HOME_DIR=${JDCM_FETCH_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/jdcm-fetch}
  STATE_DIR=${JDCM_FETCH_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/jdcm-fetch}
  CONFIG_DIR=${JDCM_FETCH_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/jdcm-fetch}
  MAX_REQUESTS=${JDCM_FETCH_MAX_REQUESTS:-150}
  BUDGET_MS=$(( ${JDCM_FETCH_BUDGET_S:-480} * 1000 ))
  GAP_MS=${JDCM_FETCH_GAP_MS:-1500}
  SPORTS="1 2 3"

  local now_flag=0 push=1
  for a in "$@"; do
    case "$a" in
      --now) now_flag=1 ;;
      --no-push) push=0 ;;
      -h|--help) sed -n '2,32p' "$0"; return 0 ;;
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
  DAY_START_MS=$(( $(TZ=Europe/Madrid date -d "$TODAY 00:00" +%s) * 1000 ))
  FROM=$(TZ=Europe/Madrid date -d "$TODAY -3 days" +%F)
  TO=$(TZ=Europe/Madrid date -d "$TODAY +6 days" +%F)
  NEAR_TO=$(TZ=Europe/Madrid date -d "$TODAY +21 days" +%F)
  RECENT=$(TZ=Europe/Madrid date -d "$TODAY -14 days" +%F)
  STARTED_BEFORE=$(TZ=Europe/Madrid date -d '-60 min' +%FT%T) # a match that kicked off an hour ago
  REQ=0 ODD_IN_A_ROW=0 LAST_REQ_MS=0 STOPPED="" OK_ANSWERS=0 STORED=0 REFUSED=0 TIER=boot
  declare -gA TIER_REQ=([boot]=0 [t1]=0 [t2]=0 [t3]=0 [t4]=0 [t5]=0)
  declare -gA GROUPED_DONE=() # "sport:number" asked during this run
  log "start (v$VERSION) today=$TODAY window=$FROM..$TO"

  if [[ $SSH_MODE != default ]]; then
    [[ -f $CONFIG_DIR/deploy_key ]] || { log "E no deploy key at $CONFIG_DIR/deploy_key (run install.sh, or JDCM_PUMP_SSH=default)"; return 1; }
    local hosts="$CONFIG_DIR/known_hosts" check=yes
    [[ -s $hosts ]] || check=accept-new
    export GIT_SSH_COMMAND="ssh -i $CONFIG_DIR/deploy_key -o IdentitiesOnly=yes -o BatchMode=yes -o UserKnownHostsFile=$hosts -o StrictHostKeyChecking=$check"
  fi

  wait_for_network
  read_previous || return 1
  D="$WORK/data/v1"
  mkdir -p "$D/groups" "$D/catalog" "$D/state"
  local s
  for s in $SPORTS; do
    [[ -f $D/state/sched-$s.json ]] || echo '{"v":1,"matches":{},"rounds":{},"groups":{},"shrink":{}}' >"$D/state/sched-$s.json"
  done

  # 0. A sport never read: its first round number lists every group (and the season starts with it).
  TIER=boot
  for s in $SPORTS; do
    [[ -n $STOPPED ]] && break
    jq -e '.rounds | length == 0' "$D/state/sched-$s.json" >/dev/null && fetch_grouped "$s" 1 || true
  done
  load_refs

  # 1. The round numbers being played, per sport (and the previous one while it is open).
  TIER=t1
  for s in $SPORTS; do
    local n
    for n in $(plan_numbers "$s" window); do
      [[ -n $STOPPED ]] && break 2
      fetch_grouped "$s" "$n" || true
    done
  done
  load_refs

  # 2. Results: a group's own round where the grouped calendar shows a match played without our result.
  TIER=t2
  declare -A asked_out=()
  local g r
  while read -r g r; do
    [[ -n $STOPPED ]] && break
    [[ -z $g || -n ${asked_out[$g]:-} ]] && continue
    fresh_this_run "$g" "$r" && continue
    local before after
    before=$(finals_count "$g")
    fetch_round "$g" "$r" || true
    after=$(finals_count "$g")
    (( after > before )) || asked_out[$g]=1 # nothing new: not again this run
  done < <(plan_results)

  # 5. Groups never read on their own: round 1 (brings round 2 and the list of the season).
  TIER=t5
  while read -r g r; do
    [[ -n $STOPPED ]] && break
    [[ -z $g ]] && continue
    fresh_this_run "$g" "$r" && continue
    fetch_round "$g" "$r" || true
  done < <(plan_backfill)

  # 3. Once a day: the next three weeks. 4. Once a week: every other round number, oldest first.
  local tier
  for tier in t3 t4; do
    TIER=$tier
    while read -r s n; do
      [[ -n $STOPPED ]] && break 2
      [[ -z $s ]] && continue
      fetch_grouped "$s" "$n" || true
    done < <(for s in $SPORTS; do plan_numbers "$s" "$tier" | sed "s/^/$s /"; done | sort -s -k3n | cut -d' ' -f1,2)
  done

  [[ -n $STOPPED ]] && log "W stopped: $STOPPED; the rest waits for the next run"
  log "requests=$REQ (boot ${TIER_REQ[boot]}, now ${TIER_REQ[t1]}, results ${TIER_REQ[t2]}, backfill ${TIER_REQ[t5]}, daily ${TIER_REQ[t3]}, weekly ${TIER_REQ[t4]}) answers=$OK_ANSWERS stored=$STORED refused=$REFUSED"

  if (( OK_ANSWERS == 0 )); then
    log "nothing to publish (no answer from the site)"
    write_last_run no
    return 0
  fi
  write_views
  if (( push == 0 )); then
    rm -rf "$STATE_DIR/no-push"
    cp -r "$WORK/data" "$STATE_DIR/no-push"
    rm -rf "$STATE_DIR/no-push/.git"
    log "--no-push: the result is in $STATE_DIR/no-push"
    write_last_run "no (--no-push)"
    return 0
  fi
  if publish_data; then write_last_run yes; else write_last_run FAILED; return 1; fi
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

# What was published last time is this run's memory: a shallow clone into the work folder. The new
# commit is built in it, so the push sends only the files that changed.
read_previous() {
  local rc=0
  git ls-remote --exit-code --heads "$DATA_REMOTE" main >/dev/null 2>"$WORK/git.err" || rc=$?
  if (( rc == 2 )); then
    log "no data published yet: first run"
    git init -q -b main "$WORK/data"
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
    TIER_REQ[$TIER]=$(( ${TIER_REQ[$TIER]} + 1 ))
    rc=0
    code=$(curl -sS -o "$WORK/body" -w '%{http_code}' --max-time 30 --connect-timeout 15 --max-filesize 4194304 \
      -A "$UA" -H 'Accept: application/json, text/javascript, */*; q=0.01' -H 'X-Requested-With: XMLHttpRequest' \
      "$SITE/$query" 2>"$WORK/curl.err") || rc=$?
    LAST_REQ_MS=$(now_ms)
    if (( rc == 63 )); then log "W $label: answer over 4 MB"; odd_answer; return $?; fi
    if (( rc != 0 )); then STOPPED="site down (curl $rc: $(head -c 80 "$WORK/curl.err" | tr '\n' ' '))"; return 2; fi
    if [[ $code == 429 || $code == 5* ]]; then STOPPED="site down (HTTP $code)"; return 2; fi
    if [[ $code != 2* ]]; then log "W $label: HTTP $code"; odd_answer; return $?; fi
    # (The odd-answer streak is reset by the caller once the answer is taken: a JSON answer for the
    # wrong round or group is as odd as a login page.)
    if jq -c 'if type == "string" then fromjson else . end' "$WORK/body" >"$out" 2>/dev/null && [[ -s $out ]]; then
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

# Shared jq definitions: tolerant numbers and dates, and core's idea of a final result
# (core/model/Model.kt Match.hasResult, and MatchStatus.of: the state's name first, then its id).
JQ_DEFS='
def num: if type == "number" then . elif type == "string" then (tonumber? // 0) else 0 end;
def isnum: type == "number" or (type == "string" and ((tonumber? // null) != null));
def day: if type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}") then .[0:10] else null end;
def state: ((.EstadoPartido // "") | ascii_downcase) as $n | (.IdEstadoPartido | num) as $i
  | if ($n | test("desprogram")) then "UNSCHEDULED"
    elif ($n | test("aplaz|suspend")) then "POSTPONED"
    elif ($n | test("no celebrado|incomplet")) then "NOT_HELD"
    elif ($n | test("pendiente")) then "SCHEDULED"
    elif ($n | test("jugado|finaliz|celebrado|disputado|acta|cerrado")) then "PLAYED"
    elif $i == 0 then "SCHEDULED" elif $i == 1 then "POSTPONED" elif $i == 6 then "NOT_HELD"
    elif $i == 10 or $i == 11 then "PLAYED" elif $i == 12 then "UNSCHEDULED" else "UNKNOWN" end;
def cancelled: state as $s | $s == "POSTPONED" or $s == "UNSCHEDULED" or $s == "NOT_HELD";
def final: state as $s
  | ((.IdEquipoLocal | num) > 0 and (.IdEquipoVisitante | num) > 0 and (.ResultadoEquipoLocal | isnum) and (.ResultadoEquipoVisitante | isnum))
    and (if $s == "POSTPONED" or $s == "UNSCHEDULED" then false
         elif $s == "PLAYED" then true
         elif $s == "NOT_HELD" then (.IdEquipoGanador | num) != 0 or ((.ResultadoEquipoLocal | num) + (.ResultadoEquipoVisitante | num)) > 0
         else (.Cerrado == true) or (.IdEquipoGanador | num) != 0 or ((.ResultadoEquipoLocal | num) + (.ResultadoEquipoVisitante | num)) > 0 end);
'

gdir() { printf '%s' "$D/groups/$1"; }

# The four ids the site wants for a group, from the grouped calendars: REF[group]="sport comp cat".
load_refs() {
  declare -gA REF=()
  local g s c k
  while read -r g s c k; do
    [[ -n $g ]] && REF[$g]="$s $c $k"
  done < <(for s in $SPORTS; do jq -r --argjson s "$s" '.groups | to_entries[] | "\(.key) \($s) \(.value.c) \(.value.k)"' "$D/state/sched-$s.json"; done)
}

# --- the grouped calendar: schedules and states of every group, no scores --------------------------

# One sport's round number: every group's matches of that round (trimmed to what the app reads),
# merged into state/sched-<sport>.json defensively per group: a group whose answer has fewer than
# half the matches held for that round is refused unless an earlier run saw the same count.
# Linear passes and object lookups only (a 250 KB answer and a 1-2 MB state on a Pi 3, jq 1.6).
fetch_grouped() {
  local s=$1 n=$2 rc=0 f="$D/state/sched-$1.json"
  [[ -n ${GROUPED_DONE[$s:$n]:-} ]] && return 0
  GROUPED_DONE[$s:$n]=1
  site_get "Calendarios/ObtenerCalendarioAgrupado?IdDeporte=$s&IdCompeticion=-1&IdCategoria=0&NumeroJornada=$n&IdEquipo=0" \
    "$WORK/grouped.json" "grouped sport=$s round=$n" || rc=$?
  (( rc == 0 )) || return "$rc"
  if ! jq -c --argjson N "$n" --argjson now "$(now_ms)" --argjson run "$STARTED_MS" --slurpfile st "$f" "$JQ_DEFS"'
    def venue: if type == "object" then {Nombre, Direccion, CP, Municipio, Latitud, Longitud} else null end;
    def trimm: {Id, IdGrupo, IdJornada, NumeroJornada, FechaHora, Campo, CampoJuego: (.CampoJuego | venue), Latitud, Longitud,
      IdEstadoPartido, EstadoPartido, CambioAdministrador, MotivoCambioAdministrador,
      SolicitudesModificaciones: [(.SolicitudesModificaciones // [])[]? | select(type == "object") | {IdEstadoSolicitud}],
      IdEquipoLocal, EquipoLocal, URLEscudoEquipoLocal, IdEquipoVisitante, EquipoVisitante, URLEscudoEquipoVisitante};
    $st[0] as $st
    | if type != "array" then error("not a grouped calendar") else . end
    | [.[] | select(type == "object") | . as $c | (.Categorias // [])[]? | select(type == "object") | . as $k
        | (.Grupos // [])[]? | select(type == "object") | select((.IdGrupo | num) > 0)
        | {g: (.IdGrupo | num | tostring),
           info: {c: ($c.IdCompeticion | num), cn: ($c.Competicion // ""), k: ($k.IdCategoria | num), kn: ($k.Categoria // ""), gn: (.Grupo // "")},
           ms: [(.Enfrentamientos // [])[]? | select(type == "object" and (.Id | num) > 0) | trimm]}] as $groups
    | ($groups | map({key: .g, value: (.ms | length)}) | from_entries) as $incoming
    | ([$st.matches[] | select(.n == $N) | .g | tostring] | group_by(.) | map({key: .[0], value: length}) | from_entries) as $held
    | (($incoming | keys) + ($held | keys) | unique) as $gids
    | [$gids[] | . as $g | ($incoming[$g] // 0) as $in | ($held[$g] // 0) as $h | ($st.shrink["\($g):\($N)"] // {}) as $seen
        | {g: $g, in: $in, held: $h, ok: ($h == 0 or $in * 2 >= $h or (($seen.n // -1) == $in and ($seen.at // $run) < $run))}] as $dec
    | ([$dec[] | select(.ok) | {key: .g, value: true}] | from_entries) as $acc
    | $st
    | .matches = ((.matches | with_entries(select(.value.n != $N or ($acc[.value.g | tostring] | not))))
        + ([$groups[] | select($acc[.g]) | .g as $g | .ms[] | (.Id | num | tostring) as $id
            # "at" is when this match last CHANGED: an unchanged match keeps its stamp, so its group
            # files stay the same and the push carries only what changed.
            | {key: $id, value: {at: (if $st.matches[$id].m == . then $st.matches[$id].at else $now end), g: ($g | tonumber), n: $N, m: .}}] | from_entries))
    | .groups = (.groups + ([$groups[] | {key: .g, value: .info}] | from_entries))
    | .rounds[$N | tostring] = $now
    | .shrink = (reduce $dec[] as $d (.shrink;
        if $d.ok then del(.["\($d.g):\($N)"])
        elif (.["\($d.g):\($N)"].n // -1) == $d.in then .
        else .["\($d.g):\($N)"] = {n: $d.in, at: $run} end))
    | .last = {round: $N, groups: ($incoming | length), accepted: ($acc | length), refused: [$dec[] | select(.ok | not) | "\(.g): \(.in) of \(.held)"]}
  ' "$WORK/grouped.json" >"$WORK/sched.new" 2>"$WORK/jq.err"; then
    log "W grouped sport=$s round=$n: not a grouped calendar ($(head -c 100 "$WORK/jq.err")); nothing stored"
    REFUSED=$((REFUSED + 1))
    odd_answer || true
    return 1
  fi
  mv "$WORK/sched.new" "$f"
  ODD_IN_A_ROW=0
  STORED=$((STORED + 1))
  local summary
  summary=$(jq -r '.last | "groups=\(.groups) accepted=\(.accepted)" + (if (.refused | length) > 0 then " refused=\(.refused | join(", "))" else "" end)' "$f")
  [[ $summary == *refused=* ]] && REFUSED=$((REFUSED + 1))
  log "grouped sport=$s round=$n $summary"
}

# Round numbers of a sport to ask the grouped calendar for: "window" (matches from -3 to +6 days,
# and the previous number while one of its matches is still open), "t3" (the next three weeks,
# unless asked today) or "t4" (every number not asked for a week, oldest first, with its age).
# Dates come from the grouped matches and from the groups' own round lists.
plan_numbers() {
  local s=$1 what=$2 metas=("$D"/groups/*/meta.json)
  [[ -f ${metas[0]} ]] || metas=(/dev/null)
  jq -rn --argjson s "$s" --arg what "$what" --arg from "$FROM" --arg to "$TO" --arg today "$TODAY" --arg near "$NEAR_TO" \
    --argjson now "$(now_ms)" --argjson daystart "$DAY_START_MS" --slurpfile st "$D/state/sched-$s.json" "$JQ_DEFS"'
    $st[0] as $st
    | [inputs | select(.ref.sportId == $s)] as $metas
    | ([$metas[] | .finals[]?[]? | tostring] | map({key: ., value: true}) | from_entries) as $finals
    | ([$st.matches[] | {n, d: (.m.FechaHora | day)}] + [$metas[] | .rounds[]? | {n: .number, d: .date}]
        | map(select(.n != null and .n > 0))) as $dated
    | if $what == "window" then
        ([$dated[] | select(.d != null and .d >= $from and .d <= $to) | .n] | unique) as $win
        | if ($win | length) == 0 then empty else
            ([$dated[] | .n | select(. < ($win | min))] | max) as $prev
            | (if $prev != null and any($st.matches[]; .n == $prev and (.m | cancelled | not) and ((.m.IdEstadoPartido | num) != 11 or ($finals[.m.Id | num | tostring] | not)))
               then [$prev] else [] end) + $win | .[]
          end
      elif $what == "t3" then
        [$dated[] | select(.d != null and .d >= $today and .d <= $near) | .n] | unique | .[]
        | select(($st.rounds[tostring] // 0) < $daystart)
      else
        [$dated[] | .n] + [$st.rounds | keys[] | tonumber] | unique | .[] | . as $n
        | (($st.rounds[$n | tostring] // 0)) as $at
        | select($now - $at > 7 * 86400000)
        | "\($n) \($at)"
      end' "${metas[@]}"
}

# --- a group's own rounds: the scores --------------------------------------------------------------

# Groups whose grouped matches show one played (or due and not refreshed this run) without our final
# result: "group round", oldest match first. Matches from the last 14 days that started over an hour
# ago (untimed: their day has passed) and are not cancelled. A match the grouped calendar refreshed
# this run still calls pending has no result to fetch yet. A match «disputado con incidencias» has no
# score until the committee rules, which takes days: its group is asked at most once a day for it.
plan_results() {
  local metas=("$D"/groups/*/meta.json)
  [[ -f ${metas[0]} ]] || metas=(/dev/null)
  jq -rn --arg recent "$RECENT" --arg today "$TODAY" --arg before "$STARTED_BEFORE" --argjson run "$STARTED_MS" \
    --slurpfile s1 "$D/state/sched-1.json" --slurpfile s2 "$D/state/sched-2.json" --slurpfile s3 "$D/state/sched-3.json" "$JQ_DEFS"'
    [inputs] as $metas
    | ([$metas[] | .finals[]?[]? | tostring] | map({key: ., value: true}) | from_entries) as $finals
    | ([$metas[] | . as $mt | (.fetchedAt // {}) | to_entries[] | {key: "\($mt.groupId):\(.key)", value: .value}] | from_entries) as $fetched
    | [($s1[0], $s2[0], $s3[0]) | .matches[]
        | select(($finals[.m.Id | num | tostring] | not) and (.m | cancelled | not))
        | (.m.FechaHora // "") as $t | ($t | day) as $d
        | select($d != null and $d >= $recent)
        | select(if ($t[11:16]) == "00:00" or ($t | length) < 16 then $d < $today else $t[0:19] <= $before end)
        | select((.m | state) == "PLAYED" or .at < $run)
        | (((.m.IdEstadoPartido | num) == 10) or ((.m.EstadoPartido // "") | ascii_downcase | test("con incidencia"))) as $incid
        | select(($incid | not) or (($fetched["\(.g):\(.m.IdJornada | num)"] // 0) < ($run - 72000000)))
        | {g, r: (.m.IdJornada | num), t: $t}]
    | map(select(.r > 0)) | sort_by(.t) | .[] | "\(.g) \(.r)"'  "${metas[@]}"
}

# Groups listed by the grouped calendars and never read on their own: "group round-1-id" (or 0, the
# round the site calls current, when the grouped calendar has no round 1 for it).
plan_backfill() {
  local s
  for s in $SPORTS; do
    jq -r '([.matches[] | select(.n == 1) | {key: (.g | tostring), value: (.m.IdJornada)}] | from_entries) as $r1
      | .groups | keys[] | "\(.) \($r1[.] // 0)"' "$D/state/sched-$s.json"
  done | while read -r g r; do
    # No meta, or a meta without any round read (a first attempt that failed). jq -c writes "fetchedAt":{}.
    [[ -f $(gdir "$g")/meta.json ]] && ! grep -q '"fetchedAt":{}' "$(gdir "$g")/meta.json" && continue
    echo "$g $r"
  done
}

finals_count() {
  local f
  f="$(gdir "$1")/meta.json"
  [[ -f $f ]] && jq '[.finals[]?[]?] | length' "$f" || echo 0
}

# A group's meta.json: the site's round list, when each round was accepted, how many matches it
# holds, refused counts (shrinkSeen), the ids with a final result (finals), the last attempt and error.
init_meta() {
  local g=$1 dir s c k
  dir=$(gdir "$g")
  mkdir -p "$dir"
  [[ -f $dir/meta.json ]] && return 0
  read -r s c k <<<"${REF[$g]:-0 0 0}"
  jq -n --argjson g "$g" --argjson s "$s" --argjson c "$c" --argjson k "$k" '{v: 1, groupId: $g,
    ref: {groupId: $g, sportId: $s, competitionId: $c, categoryId: $k}, group: {}, rounds: [],
    fetchedAt: {}, count: {}, finals: {}, shrinkSeen: {}, currentRoundId: 0, lastAttempt: 0, lastError: null}' >"$dir/meta.json"
}

meta_set() {
  local g=$1; shift
  local f
  f="$(gdir "$g")/meta.json"
  jq -c "$@" "$f" >"$f.tmp" && mv "$f.tmp" "$f"
}

# Answered during this run already (a neighbour of an earlier answer, accepted or refused).
fresh_this_run() {
  local f
  f="$(gdir "$1")/meta.json"
  [[ -f $f ]] && jq -e --arg r "$2" --argjson t "$STARTED_MS" '(.fetchedAt[$r] // 0) >= $t or (.shrinkSeen[$r].at // 0) >= $t' "$f" >/dev/null
}

# One round of a group ([round] 0 = the one the site calls current), checked and stored.
fetch_round() {
  local g=$1 r=$2 dir q rc=0 s c k
  [[ -n ${REF[$g]:-} ]] || { log "W group=$g: not in any grouped calendar; skipped"; return 1; }
  read -r s c k <<<"${REF[$g]}"
  init_meta "$g"
  dir=$(gdir "$g")
  q="Calendarios/ObtenerDatosJornadaGrupo?IdDeporte=$s&IdCompeticion=$c&IdCategoria=$k&IdGrupo=$g&IdJornada=$r&IdEquipo=0"
  site_get "$q" "$WORK/answer.json" "group=$g round=$r" || rc=$?
  if (( rc != 0 )); then
    meta_set "$g" --arg e "${STOPPED:-odd answer}" --argjson t "$(now_ms)" '.lastError = $e | .lastAttempt = $t'
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
  if ! jq -c --argjson g "$g" --argjson asked "$asked" --argjson run "$STARTED_MS" --slurpfile meta "$dir/meta.json" "$JQ_DEFS"'
    def roundOf: {id: (.Id | num), number: (.NumeroJornada | num), date: (.Fecha | day)};
    $meta[0] as $m
    | if type != "object" or ((has("jornada") or has("jornadas")) | not) then {error: "not a round page"}
      elif $asked != 0 and ((.jornada.Id? // 0) | num) != $asked then {error: "asked round \($asked), got \((.jornada.Id? // 0) | num)"}
      else
        . as $a
        | [["asked", $a.jornada], ["previous", $a.previous], ["next", $a.next]]
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
               finals: [$ours[] | select(final) | .Id | num],
               ok: (($stranger | not) and ($held == 0 or $n * 2 >= $held or (($seen.n // -1) == $n and ($seen.at // $run) < $run))),
               shrink: ($stranger | not),
               why: (if $stranger then "an answer for another group" else "\($n) matches where \($held) are held" end)})
        | . as $rounds
        | {rounds: $rounds,
           list: ([$a.jornadas[]? | select(type == "object") | roundOf | select(.id > 0)] | sort_by(.number)),
           current: (if $asked == 0 then (($a.jornada.Id? // 0) | num) else 0 end),
           group: ([$rounds[] | .obj.Enfrentamientos[]] | first // {} | {name: (.Grupo // ""), competition: (.Competicion // ""), category: (.Categoria // ""), sport: (.Deporte // "")})}
      end' "$WORK/answer.json" >"$WORK/decision.json" 2>"$WORK/jq.err"; then
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
  ODD_IN_A_ROW=0
  local id json n why
  while IFS=$'\t' read -r id n json; do
    printf '%s\n' "$json" >"$dir/r$id.json"
    STORED=$((STORED + 1))
  done < <(jq -r '.rounds[] | select(.ok) | "\(.round.id)\t\(.n)\t\(.obj | tojson)"' "$WORK/decision.json")
  while IFS=$'\t' read -r id why; do
    log "W group=$g round=$id refused: $why; the previous copy stays"
    REFUSED=$((REFUSED + 1))
  done < <(jq -r '.rounds[] | select(.ok | not) | "\(.round.id)\t\(.why)"' "$WORK/decision.json")
  meta_set "$g" --argjson t "$(now_ms)" --argjson run "$STARTED_MS" --slurpfile d "$WORK/decision.json" '
    $d[0] as $d
    | reduce ($d.rounds[] | select(.ok)) as $r (.; .fetchedAt[$r.round.id | tostring] = $t | .count[$r.round.id | tostring] = $r.n
        | .finals[$r.round.id | tostring] = $r.finals | del(.shrinkSeen[$r.round.id | tostring]))
    | reduce ($d.rounds[] | select((.ok | not) and .shrink)) as $r (.; .shrinkSeen[$r.round.id | tostring] |= (if (.n // -1) == $r.n then . else {n: $r.n, at: $run} end))
    | ([$d.rounds[] | select(.ok) | .round.id]) as $got
    | (if ($d.list | length) > 0 and ($got - [$d.list[].id] | length) == 0 and ((.rounds | length) < 4 or ($d.list | length) * 2 >= (.rounds | length))
       then .rounds = $d.list else . end)
    | (if $d.current > 0 and ([$d.rounds[] | select(.ok and .kind == "asked") | .round.id] | index($d.current)) != null then .currentRoundId = $d.current else . end)
    | (if ($d.group.name // "") != "" then .group = $d.group else . end)
    | .lastAttempt = $t | .lastError = null'
  prune_rounds "$g"
  log "group=$g round=$asked stored=$(jq -c '[.rounds[] | select(.ok) | .round.id]' "$WORK/decision.json") finals=$(finals_count "$g")"
}

# Rounds the site no longer lists go, unless they hold a result (core: never drop a played round).
prune_rounds() {
  local g=$1 dir f id listed
  dir=$(gdir "$g")
  listed=" $(jq -r '[.rounds[].id] | join(" ")' "$dir/meta.json") "
  for f in "$dir"/r*.json; do
    [[ -f $f ]] || continue
    id=${f##*/r}; id=${id%.json}
    [[ $listed == *" $id "* ]] && continue
    if jq -e "$JQ_DEFS"'any(.Enfrentamientos[]?; final)' "$f" >/dev/null; then
      meta_set "$g" --argjson id "$id" --slurpfile r "$f" '.rounds = (.rounds + [{id: $id, number: ($r[0].NumeroJornada // 0), date: (($r[0].Fecha // "")[0:10])}] | unique_by(.id) | sort_by(.number))'
      continue
    fi
    log "group=$g round=$id no longer listed by the site: removed"
    rm -f "$f"
    meta_set "$g" --arg id "$id" 'del(.fetchedAt[$id], .count[$id], .finals[$id], .shrinkSeen[$id])'
  done
}

# --- what the web app reads ------------------------------------------------------------------------

# Per group all.json (its own rounds, its grouped matches, its names), per sport the catalogue of
# groups and teams, and a small index.json. One jq pass per sport splits the grouped matches by group.
write_views() {
  local s g line dir files sport_name
  local -A seen=()
  for s in $SPORTS; do
    case $s in 1) sport_name="Baloncesto" ;; 2) sport_name="Fútbol sala" ;; 3) sport_name="Voleibol" ;; esac
    jq -c --argjson s "$s" --arg sn "$sport_name" '
      ([.matches[]] | group_by(.g) | map({key: (.[0].g | tostring), value: map({at, m})}) | from_entries) as $by
      | .groups | to_entries[] | .key as $g
      | {g: $g, info: {groupId: ($g | tonumber), ref: {groupId: ($g | tonumber), sportId: $s, competitionId: .value.c, categoryId: .value.k},
          group: {name: .value.gn, competition: .value.cn, category: .value.kn, sport: $sn}}, sched: ($by[$g] // [])}' \
      "$D/state/sched-$s.json" >"$WORK/split-$s.jsonl"
    while IFS= read -r line; do
      g=${line#\{\"g\":\"}; g=${g%%\"*} # each line starts {"g":"<id>",
      seen[$g]=1
      dir=$(gdir "$g")
      mkdir -p "$dir"
      printf '%s\n' "$line" >"$WORK/split.json"
      files=("$dir"/r*.json)
      [[ -f ${files[0]} ]] || files=(/dev/null)
      local meta="$dir/meta.json"
      [[ -f $meta ]] || meta=/dev/null
      jq -cn --slurpfile x "$WORK/split.json" --slurpfile meta "$meta" '
        ($meta[0] // {}) as $m
        | {v: 1, info: $x[0].info, meta: $m, sched: $x[0].sched,
           rounds: (reduce inputs as $r ({}; if any(($m.rounds // [])[]; .id == $r.Id) then .[$r.Id | tostring] = $r else . end))}' \
        "${files[@]}" >"$dir/all.json"
    done <"$WORK/split-$s.jsonl"
    # The catalogue: every group of the sport with its teams.
    jq -c --argjson s "$s" '
      ([.matches[]] | group_by(.g) | map({key: (.[0].g | tostring), value: ([.[] | .m
          | ({id: .IdEquipoLocal, name: .EquipoLocal, crest: .URLEscudoEquipoLocal}, {id: .IdEquipoVisitante, name: .EquipoVisitante, crest: .URLEscudoEquipoVisitante})]
          | map(select((.id // 0) > 0)) | unique_by(.id))}) | from_entries) as $teams
      | {v: 1, sportId: $s, competitions: ([.groups | to_entries[] | .value + {g: (.key | tonumber)}] | group_by(.c)
          | map({id: .[0].c, name: .[0].cn, categories: (group_by(.k)
              | map({id: .[0].k, name: .[0].kn, groups: map({id: .g, name: .gn, teams: ($teams[.g | tostring] // [])})}))}))}' \
      "$D/state/sched-$s.json" >"$D/catalog/$s.json"
  done
  # Groups read on their own but gone from the grouped calendars keep their all.json from their pages.
  for dir in "$D"/groups/*/; do
    g=$(basename "$dir")
    [[ -n ${seen[$g]:-} || ! -f $dir/meta.json ]] && continue
    files=("$dir"r*.json)
    [[ -f ${files[0]} ]] || files=(/dev/null)
    jq -cn --slurpfile meta "${dir}meta.json" '$meta[0] as $m | {v: 1, info: {groupId: $m.groupId, ref: $m.ref, group: $m.group}, meta: $m, sched: [],
      rounds: (reduce inputs as $r ({}; if any($m.rounds[]; .id == $r.Id) then .[$r.Id | tostring] = $r else . end))}' "${files[@]}" >"${dir}all.json"
  done
  # index.json: small. Every group with its ids and when it last changed (newestFetch: the app skips
  # what it already has); the same shape the first app builds read.
  local metas=("$D"/groups/*/meta.json)
  [[ -f ${metas[0]} ]] || metas=(/dev/null)
  jq -cn --argjson ms "$(now_ms)" --arg at "$(date -u +%FT%TZ)" --arg next "$(next_run)" --argjson req "$REQ" \
    --argjson tiers "$(for t in boot t1 t2 t5 t3 t4; do printf '"%s":%s\n' "$t" "${TIER_REQ[$t]}"; done | paste -sd, | sed 's/^/{/; s/$/}/')" \
    --argjson stored "$STORED" --argjson refused "$REFUSED" --arg stopped "$STOPPED" \
    --slurpfile s1 "$D/state/sched-1.json" --slurpfile s2 "$D/state/sched-2.json" --slurpfile s3 "$D/state/sched-3.json" '
    ([inputs | {key: (.groupId | tostring), value: ([.fetchedAt[]?] | max // 0)}] | from_entries) as $pages
    | ([($s1[0], $s2[0], $s3[0]) | .matches[] | {g: (.g | tostring), at}] | group_by(.g) | map({key: .[0].g, value: (map(.at) | max)}) | from_entries) as $sched
    | {v: 1, generatedAt: $at, generatedMs: $ms, nextRun: $next, refreshHours: 3, refreshFromHour: 7, refreshToHour: 19,
       sports: {"1": ($s1[0].groups | length), "2": ($s2[0].groups | length), "3": ($s3[0].groups | length)},
       groups: ([[1, $s1[0]], [2, $s2[0]], [3, $s3[0]]] | map(.[0] as $s | .[1].groups | to_entries[]
         | {groupId: (.key | tonumber), sportId: $s, competitionId: .value.c, categoryId: .value.k,
            newestFetch: ([$pages[.key] // 0, $sched[.key] // 0] | max)}) | sort_by(.groupId)),
       lastRun: {requests: $req, tiers: $tiers, stored: $stored, refused: $refused, stopped: (if $stopped == "" then null else $stopped end)}}' \
    "${metas[@]}" >"$D/index.json"
  cat >"$WORK/data/README.md" <<'EOF'
# JDCM data

What the JDCM web app (https://makensi.es/jdcm) reads: the public JSON of
juegosdeportivos.ecmadrid.org for every group of the competition, read by a small device at home
(`pwa/pi/jdcm-fetch.sh` in https://github.com/olemoudi/jdcm) and force-pushed here as one commit
every run. Never edit it by hand.

- `v1/index.json`: when it was published, groups per sport, when each group last changed, the last run.
- `v1/state/sched-<sport>.json`: every group's matches from the grouped calendars (dates, venues,
  states; no scores), when each round number was read, refusals.
- `v1/groups/<id>/r<round>.json`: one round as the group's own page sent it (with scores).
- `v1/groups/<id>/meta.json`: that group's round list, when each round was read, its final results.
- `v1/groups/<id>/all.json`: all of the above for one group (what the app downloads).
- `v1/catalog/<sport>.json`: every competition, category, group and team of a sport.
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
  printf 'at=%s\nrequests=%s\ntiers=boot:%s now:%s results:%s backfill:%s daily:%s weekly:%s\nanswers=%s\nstored=%s\nrefused=%s\npublished=%s\nstopped=%s\n' \
    "$(date '+%F %T')" "$REQ" "${TIER_REQ[boot]}" "${TIER_REQ[t1]}" "${TIER_REQ[t2]}" "${TIER_REQ[t5]}" "${TIER_REQ[t3]}" "${TIER_REQ[t4]}" \
    "$OK_ANSWERS" "$STORED" "$REFUSED" "$1" "$STOPPED" >"$STATE_DIR/last-run"
}

# One commit with no parent, built in the clone of the previous one: the push sends only the files
# that changed, and the history of jdcm-data never grows.
publish_data() {
  local dir="$WORK/data" tree commit
  git -C "$dir" add -A
  tree=$(git -C "$dir" write-tree)
  commit=$(git -C "$dir" -c user.name="JDCM fetch" -c user.email="jdcm-fetch@users.noreply.github.com" \
    commit-tree "$tree" -m "data $(date -u +%FT%TZ): $REQ requests, $STORED stored")
  if git -C "$dir" push -q --force "$DATA_REMOTE" "$commit:refs/heads/main" 2>"$WORK/git.err"; then
    log "published to $DATA_REMOTE"
  else
    log "E push failed: $(tr '\n' ' ' < "$WORK/git.err" | head -c 300)"
    return 1
  fi
}

main "$@"
