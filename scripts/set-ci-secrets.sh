#!/usr/bin/env bash
# Uploads the release-signing secrets the Release workflow needs, from ~/.jdcm-signing/.
# Values are piped to `gh secret set` on stdin: never on the command line, never printed.
# Run once (and again only if the key is ever rotated): ssk run olemoudi -- scripts/set-ci-secrets.sh
set -euo pipefail
DIR="$HOME/.jdcm-signing"
PROPS="$DIR/keystore.properties"
REPO="olemoudi/jdcm"
[ -f "$PROPS" ] && [ -f "$DIR/release.jks" ] || { echo "missing $DIR/release.jks or keystore.properties" >&2; exit 1; }
val() { grep "^$1=" "$PROPS" | cut -d= -f2- | tr -d '\n'; }

base64 -w0 "$DIR/release.jks" | gh secret set RELEASE_KEYSTORE_B64 -R "$REPO"
val storePassword | gh secret set RELEASE_STORE_PASSWORD -R "$REPO"
val keyAlias | gh secret set RELEASE_KEY_ALIAS -R "$REPO"
val keyPassword | gh secret set RELEASE_KEY_PASSWORD -R "$REPO"

# The certificate digest CI compares every release against (public information, not a secret).
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
KEYTOOL="${JAVA_HOME:-$HOME/.jdks/jdk-17.0.19+10}/bin/keytool"
"$KEYTOOL" -exportcert -keystore "$DIR/release.jks" -alias "$(val keyAlias)" -storepass:file <(val storePassword) -file "$TMP/cert.der" >/dev/null 2>&1
sha256sum "$TMP/cert.der" | cut -d' ' -f1 | tr -d '\n' | gh secret set RELEASE_CERT_SHA256 -R "$REPO"

gh secret list -R "$REPO"
