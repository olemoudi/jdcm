#!/usr/bin/env bash
# Tests jdcm-fetch.sh end to end against a mock of the site (mock_site.py, built from the real
# fixtures) and a local bare repo standing in for olemoudi/jdcm-data. Never touches the site or GitHub.
# Usage: pwa/pi/test/test.sh   (needs bash, curl, jq, git, python3)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
T=$(mktemp -d)
PORT=${MOCK_PORT:-4331}
trap 'kill $MOCK 2>/dev/null || true; rm -rf "$T"' EXIT

python3 -I "$HERE/mock_site.py" "$ROOT/core/src/test/resources/fixtures" "$T/mode" "$T/requests.log" "$PORT" &
MOCK=$!
git init -q --bare "$T/data.git"
cat >"$T/groups.json" <<'JSON'
{ "groups": [
  { "groupId": 177, "sportId": 2, "competitionId": 113, "categoryId": 306, "label": "futsal" },
  { "groupId": 139, "sportId": 1, "competitionId": 107, "categoryId": 212, "label": "basket" } ] }
JSON
export JDCM_SITE="http://127.0.0.1:$PORT" JDCM_GROUPS_URL="file://$T/groups.json" JDCM_DATA_REMOTE="$T/data.git" \
  JDCM_PUMP_SSH=default JDCM_FETCH_HOME="$T/home" JDCM_FETCH_STATE="$T/state" JDCM_FETCH_CONFIG="$T/config" \
  JDCM_FETCH_GAP_MS=300 XDG_RUNTIME_DIR="$T"
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -s "http://127.0.0.1:$PORT/" >/dev/null 2>&1 && break; sleep 0.3; done

FAILS=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; FAILS=$((FAILS + 1)); fi; }
fetch() { : >"$T/requests.log"; "$HERE/../jdcm-fetch.sh" --now "$@" </dev/null; }
show() { git -C "$T/data.git" show "main:v1/$1"; }
commits() { git -C "$T/data.git" rev-list --count main 2>/dev/null || echo 0; }
requests() { wc -l <"$T/requests.log"; }

echo ok >"$T/mode"
fetch
check "first run publishes one commit" '[ "$(commits)" = 1 ]'
check "first run reads every round of both groups and the catalogue, within the budget" '[ "$(requests)" -ge 8 ] && [ "$(requests)" -le 20 ]'
check "requests are at least 300 ms apart (the test gap; 1.5 s for real)" \
  'awk "NR > 1 && \$1 - p < 0.299 { bad = 1 } { p = \$1 } END { exit bad }" "$T/requests.log"'
check "an honest User-Agent" 'grep -q "JDCM-PWA-fetch/1 (+https://github.com/olemoudi/jdcm)" "$T/requests.log"'
check "every round of the season is stored for both groups" \
  '[ "$(show groups/177/meta.json | jq ".rounds | length")" = 9 ] && [ "$(show groups/177/meta.json | jq ".fetchedAt | length")" = 9 ] && [ "$(show groups/139/meta.json | jq ".fetchedAt | length")" = 14 ]'
check "the real first-matchday result is in round 2596" '[ "$(show groups/177/r2596.json | jq "[.Enfrentamientos[] | select(.Id == 10379) | .ResultadoEquipoLocal] | first")" = 10 ]'
check "all.json carries the meta and every listed round" '[ "$(show groups/177/all.json | jq ".rounds | length")" = 9 ]'
check "the current round is the one the site calls current" '[ "$(show groups/177/meta.json | jq .currentRoundId)" = 2596 ]'
check "the futsal catalogue lists its 8 groups with their teams" '[ "$(show catalog/2.json | jq "[.competitions[].Categorias[].Grupos[]] | length")" = 8 ]'
check "index.json lists both groups and when it was generated" '[ "$(show index.json | jq -c "[.groups[].groupId]")" = "[177,139]" ] && show index.json | jq -e ".generatedMs > 0" >/dev/null'

fetch
check "a second run the same day asks only for the current rounds" '[ "$(requests)" = 2 ]'
check "and is still one commit (force-pushed)" '[ "$(commits)" = 1 ]'

echo shrink >"$T/mode"
fetch
check "a round answered with far fewer matches is refused, the stored one stays" \
  '[ "$(show groups/177/r2597.json | jq ".Enfrentamientos | length")" = 5 ] && grep -q "round=2597 refused" "$T/state/fetch.log"'
check "its smaller count is remembered, and it is not asked twice in one run" \
  '[ "$(show groups/177/meta.json | jq ".shrinkSeen[\"2597\"].n")" = 1 ] && [ "$(grep -c "IdJornada\": \"2597\"" "$T/requests.log")" -le 1 ]'
fetch
check "the same smaller answer in the next run is believed (a team withdrew)" '[ "$(show groups/177/r2597.json | jq ".Enfrentamientos | length")" = 1 ]'

echo ok >"$T/mode"; fetch --full >/dev/null
before=$(git -C "$T/data.git" rev-parse main)
echo down >"$T/mode"
fetch
check "a site that is down ends the run at the first request" '[ "$(requests)" = 1 ]'
check "and nothing is published over the data" '[ "$(git -C "$T/data.git" rev-parse main)" = "$before" ]'
check "the run says why" 'grep "last-run" -q <<<"last-run" && grep -q "stopped=site down (HTTP 503)" "$T/state/last-run"'

echo html >"$T/mode"
fetch
check "a login page is asked once more, then two odd answers stop the run" '[ "$(requests)" = 4 ] && grep -q "stopped=two odd answers in a row" "$T/state/last-run"'

echo ok >"$T/mode"
cp "$T/groups.json" "$T/groups.full.json"
jq '.groups |= map(select(.groupId == 177))' "$T/groups.full.json" >"$T/groups.json"
fetch
check "a group no longer listed is removed from the data" '! git -C "$T/data.git" cat-file -e main:v1/groups/139/meta.json 2>/dev/null'

exec 9>"$T/state/lock"; flock 9
fetch
check "a run while another holds the lock does nothing" '[ "$(requests)" = 0 ] && tail -1 "$T/state/fetch.log" | grep -q "another run"'
flock -u 9

echo
if (( FAILS )); then echo "$FAILS check(s) failed"; exit 1; fi
echo "all checks passed"
