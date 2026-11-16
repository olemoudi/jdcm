#!/usr/bin/env bash
# Renders the PWA's icons for both channels from the app's launcher icon (tools/icon/jdcm_icon.svg),
# into public/<channel>/. Needs rsvg-convert. The PNGs are committed: run this only when the icon changes.
#  - favicon.svg, pwa-192.png, pwa-512.png: the round icon (as on Android), transparent corners;
#  - maskable-512.png, apple-touch-icon.png (180): full bleed, since the OS cuts its own shape
#    (iOS would fill transparent corners with black);
#  - alpha: violet instead of orange and an «α» badge, so the testing build is never mistaken for beta.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=../tools/icon/jdcm_icon.svg
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

ORANGE='#F2643B'
VIOLET='#7C4DFF'
BADGE='<circle cx="74" cy="38" r="11" fill="#1A1B1E" stroke="#FFFFFF" stroke-width="2"/><text x="74" y="43.5" text-anchor="middle" font-family="DejaVu Sans, Arial, sans-serif" font-weight="bold" font-size="16" fill="#FFFFFF">α</text>'

for channel in beta alpha; do
  out=public/$channel
  mkdir -p "$out"
  round="$TMP/$channel-round.svg"
  square="$TMP/$channel-square.svg"
  cp "$SRC" "$round"
  if [ "$channel" = alpha ]; then
    sed -i "s/$ORANGE/$VIOLET/g; s|</g></svg>|$BADGE</g></svg>|" "$round"
  fi
  # Full bleed: the round clip becomes the whole square.
  sed 's#<circle cx="54" cy="54" r="54"/>#<rect width="108" height="108"/>#' "$round" > "$square"
  cp "$round" "$out/favicon.svg"
  rsvg-convert -w 192 -h 192 "$round" -o "$out/pwa-192.png"
  rsvg-convert -w 512 -h 512 "$round" -o "$out/pwa-512.png"
  rsvg-convert -w 512 -h 512 "$square" -o "$out/maskable-512.png"
  rsvg-convert -w 180 -h 180 "$square" -o "$out/apple-touch-icon.png"
done
ls -la public/beta public/alpha
