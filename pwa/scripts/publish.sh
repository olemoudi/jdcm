#!/usr/bin/env bash
# Builds the PWA for one channel and copies it into a checkout of the website repo
# (olemoudi/olemoudi.github.io, served at https://makensi.es/). It does not commit or push.
#
#   pwa/scripts/publish.sh <website-checkout> beta    →  <checkout>/jdcm/        https://makensi.es/jdcm/
#   pwa/scripts/publish.sh <website-checkout> alpha   →  <checkout>/jdcm/alpha/  https://makensi.es/jdcm/alpha/
#
# Beta is what families install; alpha is for testing. Publishing beta never touches jdcm/alpha/.
# The build is named by the last commit that touched what goes into it, so that must be committed
# first: the version a phone shows must map to exactly one tree (PWA_UPDATE_PROTOCOL §4).
set -euo pipefail

usage() { echo "usage: $0 <website-checkout> beta|alpha" >&2; exit 2; }
[ $# -eq 2 ] || usage
SITE=$(cd "$1" && pwd) || usage
CHANNEL=$2
case "$CHANNEL" in beta|alpha) ;; *) usage ;; esac
[ -f "$SITE/CNAME" ] && [ -d "$SITE/.git" ] || { echo "$SITE does not look like the website checkout" >&2; exit 1; }

PWA=$(cd "$(dirname "$0")/.." && pwd)
cd "$PWA"
# What goes into the bundle: the same list as BUILD_INPUTS in vite.config.ts.
INPUTS=(src public index.html vite.config.ts package.json package-lock.json tsconfig.app.json)
if [ -n "$(git status --porcelain -- "${INPUTS[@]}")" ]; then
  echo "the PWA has uncommitted changes: commit them first (the build is named by its commit)." >&2
  git status --short -- "${INPUTS[@]}" >&2
  exit 1
fi
COMMIT=$(git log -1 --format=%h -- "${INPUTS[@]}")
[ -n "$COMMIT" ] || { echo "the PWA has no commit yet" >&2; exit 1; }

echo "== installing (npm ci)"
npm ci --no-audit --no-fund --loglevel=error
echo "== unit tests"
npx vitest run --reporter=dot
echo "== building $CHANNEL ($COMMIT)"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
npx tsc -b
JDCM_CHANNEL=$CHANNEL npx vite build --outDir "$OUT" --emptyOutDir --logLevel warn

# What the phones depend on, checked before anything is copied.
[ "$(jq -r .commit "$OUT/version.json")" = "$COMMIT" ] || { echo "version.json does not name $COMMIT" >&2; exit 1; }
[ "$(jq -r .channel "$OUT/version.json")" = "$CHANNEL" ] || { echo "version.json is not the $CHANNEL channel" >&2; exit 1; }
BASE=/jdcm/; [ "$CHANNEL" = alpha ] && BASE=/jdcm/alpha/
[ "$(jq -r .scope "$OUT/manifest.webmanifest")" = "$BASE" ] || { echo "manifest scope is not $BASE" >&2; exit 1; }
[ -s "$OUT/sw.js" ] || { echo "no sw.js" >&2; exit 1; }
# GitHub Pages runs Jekyll on the website, which silently drops files named "_…" or ".…".
if find "$OUT" -name '_*' -o -name '.*' -not -path "$OUT" | grep -q .; then
  echo "a file Jekyll would drop:" >&2; find "$OUT" -name '_*' -o -name '.*' -not -path "$OUT" >&2; exit 1
fi

if [ "$CHANNEL" = beta ]; then
  DEST="$SITE/jdcm"
  mkdir -p "$DEST"
  # Everything of the previous beta goes, except the alpha's folder inside it.
  rsync -a --delete --exclude '/alpha/' "$OUT/" "$DEST/"
else
  DEST="$SITE/jdcm/alpha"
  mkdir -p "$DEST"
  rsync -a --delete "$OUT/" "$DEST/"
fi

echo
echo "== $CHANNEL $COMMIT copied to $DEST"
git -C "$SITE" status --short -- jdcm | head -20
cat <<EOF

Next (review, then):
  git -C "$SITE" add jdcm
  git -C "$SITE" commit -m "jdcm: $CHANNEL $COMMIT"
  ssk run olemoudi -- git -C "$SITE" push
Then check (GitHub Pages caches for up to 10 minutes):
  curl -s https://makensi.es${BASE}version.json
EOF
