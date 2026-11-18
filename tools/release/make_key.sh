#!/usr/bin/env bash
# One-time: create pboard's release signing key OUTSIDE the repo and the properties file Gradle reads.
#   tools/release/make_key.sh [DIR=~/.config/pboard]
# Creates DIR/pboard-release.p12 (PKCS12, RSA 4096, valid 50 years, alias "pboard", random password) and
# DIR/signing.properties, both mode 600 in a mode-700 directory. Refuses to overwrite an existing key: losing or
# replacing it means installed copies can never be updated again (see docs/release.md "Back up the key").
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! command -v keytool >/dev/null 2>&1; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
DIR="${1:-$HOME/.config/pboard}"
KS="$DIR/pboard-release.p12"; PROPS="$DIR/signing.properties"
umask 077
mkdir -p "$DIR"; chmod 700 "$DIR"
[ ! -e "$KS" ] && [ ! -e "$PROPS" ] || { echo "refusing: $KS or $PROPS already exists (never replace a release key)" >&2; exit 1; }
PW="$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 40)"
# keytool reads the passwords from the environment, so they never appear in the process list
PBOARD_KS_PW="$PW" keytool -genkeypair -v -storetype PKCS12 -keystore "$KS" -alias pboard \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 18262 \
  -dname "CN=pboard release, O=pboard" \
  -storepass:env PBOARD_KS_PW -keypass:env PBOARD_KS_PW >/dev/null
cat >"$PROPS" <<EOF
# pboard release signing (read by app/build.gradle.kts when building with -Ppboard.release=true / tools/release.sh).
# SECRET. Never commit. Back up together with pboard-release.p12 (docs/release.md).
storeFile=pboard-release.p12
storePassword=$PW
keyAlias=pboard
keyPassword=$PW
EOF
chmod 600 "$KS" "$PROPS"
echo "created $KS and $PROPS"
PBOARD_KS_PW="$PW" keytool -list -v -keystore "$KS" -storepass:env PBOARD_KS_PW | grep -E 'Alias|Owner|Valid|SHA256:|Signature algorithm|Subject Public Key'
