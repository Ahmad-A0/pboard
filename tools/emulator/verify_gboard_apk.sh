#!/usr/bin/env bash
# Verify that an APK is a genuine Gboard build for x86_64 (or the ABI given), signed with Gboard's key.
#   tools/emulator/verify_gboard_apk.sh <apk> [abi=x86_64]
# Exit 0 only if: apksigner verifies the APK, the package is com.google.android.inputmethod.latin,
# its signer set is exactly Gboard's (v3.0 original cert f0fd6c5b..., rotated by v3.1 to 7ce83c1b...;
# the Play builds, the 15.1 emulator preload and the dev phone's 18.6 all carry this set), and it
# contains native code for <abi>. Prints the version name/code on success.
# Downloads are untrusted: run this before installing anything.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -z "${ANDROID_HOME:-}" ]; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
APK="$1"; ABI="${2:-x86_64}"
BT="$ANDROID_HOME/build-tools/37.0.0"
ORIG=f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83
ROT=7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053

out="$("$BT/apksigner" verify -v --print-certs "$APK" 2>&1 | grep -v '^WARNING' || true)"
if ! grep -q '^Verifies$' <<<"$out"; then echo "!! $APK: signature does not verify"; exit 1; fi
certs="$(grep 'certificate SHA-256 digest' <<<"$out" | grep -v 'Source Stamp' | awk '{print $NF}' | sort -u | tr '\n' ' ')"
if [ "$certs" != "$(printf '%s\n' "$ROT" "$ORIG" | sort | tr '\n' ' ')" ]; then
  echo "!! $APK: signer set is not Gboard's: $certs"; exit 1
fi
badging="$("$BT/aapt" dump badging "$APK" 2>/dev/null)"
pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
ver="$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
code="$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
abis="$(sed -n "s/^native-code: //p" <<<"$badging")"
[ "$pkg" = com.google.android.inputmethod.latin ] || { echo "!! $APK: package is '$pkg'"; exit 1; }
grep -q "'$ABI'" <<<"$abis" || { echo "!! $APK: no $ABI native code (has: $abis)"; exit 1; }
echo "ok: $APK = Gboard $ver (versionCode $code), native $abis, signers $ORIG -> $ROT"
