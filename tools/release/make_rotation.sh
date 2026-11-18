#!/usr/bin/env bash
# One-time, OPTIONAL: a signing-key rotation proof (APK Signature Scheme v3 lineage) from the debug key that signed the
# builds already installed on the dev phone to the release key, so the phone can move to release-signed builds as an
# ordinary update (learned data kept) instead of uninstall + reinstall (learned data wiped). See docs/release.md.
#   tools/release/make_rotation.sh [OLD_KEYSTORE=~/.android/debug.keystore]
# Writes ~/.config/pboard/rotation-from-debug.lineage (not secret: certificates and signatures only) and records it as
# `lineage=` in signing.properties, after which tools/release.sh signs every release with it. The old (debug) key keeps
# the "installed data" and "permission" capabilities, both required for the update itself (pboard declares a
# signature permission, androidx core's DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION; without "permission" the update fails
# with INSTALL_FAILED_DUPLICATE_PERMISSION). No rollback (an APK signed only with the debug key can never update a
# release-signed install), no shared UID, no auth.
# PBOARD_LINEAGE_OUT overrides the output (for tests; then signing.properties is not touched).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! command -v keytool >/dev/null 2>&1; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner"
OLD="${1:-$HOME/.android/debug.keystore}"
DIR="$HOME/.config/pboard"; PROPS="${PBOARD_SIGNING_PROPERTIES:-$DIR/signing.properties}"
OUT="${PBOARD_LINEAGE_OUT:-$DIR/rotation-from-debug.lineage}"
[ -f "$PROPS" ] || { echo "no $PROPS: run tools/release/make_key.sh first" >&2; exit 1; }
[ ! -e "$OUT" ] || { echo "refusing: $OUT exists" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$PROPS" | tail -1; }
KS="$(prop storeFile)"; [[ "$KS" = /* ]] || KS="$(dirname "$PROPS")/$KS"
export PBOARD_KS_PW="$(prop storePassword)" PBOARD_KEY_PW="$(prop keyPassword)"
umask 077
"$APKSIGNER" rotate --out "$OUT" \
  --old-signer --ks "$OLD" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --set-installed-data true --set-permission true --set-shared-uid false --set-rollback false --set-auth false \
  --new-signer --ks "$KS" --ks-key-alias "$(prop keyAlias)" --ks-pass env:PBOARD_KS_PW --key-pass env:PBOARD_KEY_PW
chmod 600 "$OUT"
[ -n "${PBOARD_LINEAGE_OUT:-}" ] || grep -q "^lineage=" "$PROPS" || printf 'lineage=%s\n' "$(basename "$OUT")" >> "$PROPS"
"$APKSIGNER" lineage --in "$OUT" --print-certs -v | grep -E 'Signer #|certificate DN|SHA-256|Has '
