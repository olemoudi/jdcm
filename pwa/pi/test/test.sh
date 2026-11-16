#!/usr/bin/env bash
# Tests jdcm-fetch.sh end to end against a mock of the site (mock_site.py: the real futsal grouped
# calendar of round 1, and round numbers 2-9 made from it) and a local bare repo standing in for
# olemoudi/jdcm-data. Never touches the site or GitHub. Usage: pwa/pi/test/test.sh
# (needs bash, curl, jq, git, python3)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
T=$(mktemp -d)
PORT=${MOCK_PORT:-4331}
trap 'kill $MOCK 2>/dev/null || true; [ -n "${KEEP:-}" ] || rm -rf "$T"' EXIT

python3 -I "$HERE/mock_site.py" "$ROOT/core/src/test/resources/fixtures" "$T/mode" "$T/requests.log" "$PORT" &
MOCK=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -s "http://127.0.0.1:$PORT/" >/dev/null 2>&1 && break; sleep 0.3; done

FAILS=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; FAILS=$((FAILS + 1)); fi; }
fresh() { # a new data repo and state
  rm -rf "$T/data.git" "$T/state" "$T/home"
  git init -q --bare "$T/data.git"
  export JDCM_SITE="http://127.0.0.1:$PORT" JDCM_DATA_REMOTE="$T/data.git" JDCM_PUMP_SSH=default \
    JDCM_FETCH_HOME="$T/home" JDCM_FETCH_STATE="$T/state" JDCM_FETCH_CONFIG="$T/config" JDCM_FETCH_GAP_MS=200 XDG_RUNTIME_DIR="$T"
}
fetch() { : >"$T/requests.log"; "$HERE/../jdcm-fetch.sh" --now "$@" </dev/null; }
mode() { echo "$*" >"$T/mode"; }
show() { git -C "$T/data.git" show "main:v1/$1"; }
requests() { wc -l <"$T/requests.log"; }
grouped_requests() { grep -c ObtenerCalendarioAgrupado "$T/requests.log" || true; }
group_requests() { grep -c ObtenerDatosJornadaGrupo "$T/requests.log" || true; }
tier() { sed -n 's/^tiers=//p' "$T/state/last-run" | tr ' ' '\n' | grep "^$1:" | cut -d: -f2; }

echo "== a first run: every group, from the grouped calendars"
fresh; mode played
fetch
check "it publishes one commit with no parent" '[ "$(git -C "$T/data.git" rev-list --count main)" = 1 ] && [ "$(git -C "$T/data.git" rev-list --parents -n1 main | wc -w)" = 1 ]'
check "bootstrap: round 1 of each sport (3)" '[ "$(tier boot)" = 3 ]'
check "results: one page per group with a played match (8)" '[ "$(tier results)" = 8 ] && [ "$(group_requests)" = 8 ]'
check "daily: the next three weeks (rounds 2-4)" '[ "$(tier daily)" = 3 ]'
check "weekly: every other round number (5-9)" '[ "$(tier weekly)" = 5 ]'
check "within the budget: $(requests) requests" '[ "$(requests)" -le 150 ] && [ "$(requests)" = 19 ]'
check "requests at least 200 ms apart (the test gap; 1.5 s for real), honest User-Agent" \
  'awk "NR > 1 && \$1 - p < 0.199 { bad = 1 } { p = \$1 } END { exit bad }" "$T/requests.log" && grep -q "JDCM-PWA-fetch/2 (+https://github.com/olemoudi/jdcm)" "$T/requests.log"'
check "the schedule layer holds every match of the 8 groups, 9 rounds" '[ "$(show state/sched-2.json | jq ".matches | length")" = 360 ]'
check "the catalogue lists the 8 groups with their teams" '[ "$(show catalog/2.json | jq "[.competitions[].categories[].groups[] | select((.teams | length) >= 9)] | length")" = 8 ]'
check "index.json lists every group (small)" '[ "$(show index.json | jq ".groups | length")" = 8 ] && [ "$(show index.json | wc -c)" -lt 4000 ]'
check "a group's all.json: its grouped matches, its own rounds with scores, its names" \
  '[ "$(show groups/177/all.json | jq "(.sched | length), (.rounds | length), .info.ref.competitionId, ([.rounds[].Enfrentamientos[] | select(.ResultadoEquipoLocal != null)] | length)" | paste -sd" ")" = "45 2 113 5" ]'
check "final results are remembered per group (the untimed match was rained off: no result)" \
  '[ "$(show groups/177/meta.json | jq "[.finals[][]] | length")" = 5 ] && [ "$(show groups/184/meta.json | jq "[.finals[][]] | length")" = 4 ]'

echo "== groups never read on their own (nothing played yet)"
fresh; mode ok
fetch
check "backfill: round 1 of each of the 8 groups, no results asked" '[ "$(tier backfill)" = 8 ] && [ "$(tier results)" = 0 ]'
fresh; mode played
fetch >/dev/null

echo "== the next run the same day"
fetch
check "asks only the grouped calendars being played (rounds 1 and 2)" '[ "$(requests)" = 2 ] && [ "$(tier now)" = 2 ]'
check "still one commit" '[ "$(git -C "$T/data.git" rev-list --count main)" = 1 ]'

echo "== results entered late"
fresh; mode played late
fetch
check "a group whose page brings no new result is asked once" '[ "$(group_requests)" = 8 ] && [ "$(grep -o "IdGrupo\": \"[0-9]*" "$T/requests.log" | sort | uniq -d | wc -l)" = 0 ]'
mode played
fetch
check "once the results are in, each group is asked again (2 grouped + 8 pages)" '[ "$(tier now)" = 2 ] && [ "$(tier results)" = 8 ]'
fetch
check "and then not any more" '[ "$(tier results)" = 0 ]'

echo "== merging: a rain-off seen only in the grouped calendar"
mode played rain
fetch
check "the grouped state is newer than the group's own page for that match" \
  '[ "$(show groups/177/all.json | jq "[.sched[] | select(.m.Id == 11379)] | first | (.m.IdEstadoPartido == 6 and .at > 0)")" = true ]'

echo "== defensive: a grouped answer missing seven groups"
mode played gshrink
fetch
check "is refused for them (their matches stay)" '[ "$(show state/sched-2.json | jq "[.matches[] | select(.n == 2)] | length")" = 40 ] && grep -q "refused=178: 0 of 5" "$T/state/fetch.log"'
fetch
check "and believed when a later run says the same" '[ "$(show state/sched-2.json | jq "[.matches[] | select(.n == 2)] | length")" = 5 ]'

echo "== the budget"
fresh; mode played
JDCM_FETCH_MAX_REQUESTS=5 fetch
check "never more than the budget, and the run says why" '[ "$(requests)" = 5 ] && grep -q "stopped=budget of 5 requests" "$T/state/last-run"'

echo "== the site down, a login page, the lock"
before=$(git -C "$T/data.git" rev-parse main)
mode down
fetch
check "a site that is down ends the run at the first request, nothing is published" \
  '[ "$(requests)" = 1 ] && [ "$(git -C "$T/data.git" rev-parse main)" = "$before" ] && grep -q "stopped=site down (HTTP 503)" "$T/state/last-run"'
mode html
fetch
check "a login page is asked once more, then two odd answers stop the run" '[ "$(requests)" = 4 ] && grep -q "stopped=two odd answers in a row" "$T/state/last-run"'
fresh; mode played wrong
fetch
check "a JSON answer for another round is odd too: two in a row stop the run" \
  '[ "$(group_requests)" = 2 ] && grep -q "stopped=two odd answers in a row" "$T/state/last-run"'
mode played
exec 9>"$T/state/lock"; flock 9
fetch
check "a run while another holds the lock does nothing" '[ "$(requests)" = 0 ] && tail -1 "$T/state/fetch.log" | grep -q "another run"'
flock -u 9

check "no jq error anywhere in the log" '! grep -q "jq: error" "$T/state/fetch.log"'

echo
if (( FAILS )); then echo "$FAILS check(s) failed"; exit 1; fi
echo "all checks passed"
