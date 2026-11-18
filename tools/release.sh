#!/usr/bin/env bash
# One-command release: a versioned, release-signed APK with its checksum, changelog and release notes.
#
#   tools/release.sh [--tag vX.Y.Z] [--allow-debug-key] [--allow-dirty] [--no-repo] [--out DIR]
#
#   --tag vX.Y.Z      create that annotated tag on HEAD first (local only, never pushed); versionName becomes X.Y.Z
#   --allow-debug-key CI fallback when no release key exists: sign with the debug key, labelled "+debugsigned" /
#                     "-DEBUGSIGNED" everywhere. Such an APK can NOT update a release-signed install.
#   --allow-dirty     build with uncommitted changes (versionName gets "-dirty"); otherwise refused
#   --no-repo         do not add the release to the update repo (dist/repo, see tools/release/repo.sh)
#   --out DIR         output directory (default dist/<versionName>)
#
# Output (dist/ is gitignored): pboard-<versionName>.apk, .apk.sha256, RELEASE_NOTES.md (merges since the previous
# tag), CHANGELOG.md (all releases), release.json (version, commit, signer, size, permissions), mapping.txt (R8, keep
# for crash traces; not published). Signing: docs/release.md. Nothing is uploaded anywhere.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v adb >/dev/null 2>&1; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
cd "$ROOT"
TAG=""; DEBUGKEY=0; DIRTY=0; REPO=1; OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="$2"; shift ;;
    --allow-debug-key) DEBUGKEY=1 ;;
    --allow-dirty) DIRTY=1 ;;
    --no-repo) REPO=0 ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
BT="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)"
APKSIGNER="${BT}apksigner"; AAPT2="${BT}aapt2"
PROPS="${PBOARD_SIGNING_PROPERTIES:-$HOME/.config/pboard/signing.properties}"

# --- preconditions
if [ $DIRTY = 0 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "refusing: uncommitted changes (commit them, or --allow-dirty for a test build)" >&2; exit 3; fi
branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = main ] || echo "note: releasing from '$branch', not main (versionCode/changelog follow this branch)" >&2
if [ -n "$TAG" ]; then
  [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]] || { echo "tag must look like v1.2.3" >&2; exit 2; }
  git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && { echo "tag $TAG already exists" >&2; exit 2; }
  git tag -a "$TAG" -m "pboard ${TAG#v}"
  echo "== tagged HEAD as $TAG (local; push it yourself if you publish)"
fi
HAVE_KEY=0
{ [ -n "${PBOARD_KEYSTORE:-}" ] || [ -f "$PROPS" ]; } && HAVE_KEY=1
if [ $HAVE_KEY = 0 ] && [ $DEBUGKEY = 0 ]; then
  echo "refusing: no release key ($PROPS); see docs/release.md, or --allow-debug-key for a labelled CI build" >&2; exit 4; fi

# --- build (no configuration cache: the signing passwords must not be cached; ANDROID_SERIAL: no device tasks run)
GRADLE_ARGS=(--console=plain -q --no-configuration-cache -Ppboard.release=true)
[ $HAVE_KEY = 0 ] && GRADLE_ARGS+=(-Ppboard.allowDebugSigning=true)
echo "== building release APK"
ANDROID_SERIAL=none ./gradlew "${GRADLE_ARGS[@]}" :app:assembleRelease
BUILT=app/build/outputs/apk/release/app-release.apk
badging="$("$AAPT2" dump badging "$BUILT")"
VNAME="$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
VCODE="$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
SUFFIX=""; [ $HAVE_KEY = 0 ] && SUFFIX="-DEBUGSIGNED"
OUT="${OUT:-dist/$VNAME}"
mkdir -p "$OUT"
APK="$OUT/pboard-$VNAME$SUFFIX.apk"

# --- signing-key rotation lineage (optional, tools/release/make_rotation.sh): re-sign so the APK carries the proof.
# v3 only (minSdk 29 >= 28, so every device verifies v3): a v2 block would have to be signed by the OLDEST key.
prop() { sed -n "s/^$1=//p" "$PROPS" 2>/dev/null | tail -1; }
LINEAGE=""
if [ $HAVE_KEY = 1 ] && [ -z "${PBOARD_KEYSTORE:-}" ] && [ -n "$(prop lineage)" ]; then
  LINEAGE="$(prop lineage)"; [[ "$LINEAGE" = /* ]] || LINEAGE="$(dirname "$PROPS")/$LINEAGE"
  KS="$(prop storeFile)"; [[ "$KS" = /* ]] || KS="$(dirname "$PROPS")/$KS"
  echo "== re-signing with the key-rotation lineage $(basename "$LINEAGE")"
  PBOARD_KS_PW="$(prop storePassword)" PBOARD_KEY_PW="$(prop keyPassword)" "$APKSIGNER" sign \
    --ks "$KS" --ks-key-alias "$(prop keyAlias)" --ks-pass env:PBOARD_KS_PW --key-pass env:PBOARD_KEY_PW \
    --lineage "$LINEAGE" --rotation-min-sdk-version 28 \
    --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
    --out "$APK.part" "$BUILT"
  mv "$APK.part" "$APK"
  rm -f "$APK.idsig" "$APK.part.idsig"
else
  cp "$BUILT" "$APK"
fi

# --- checks on the final APK
"$APKSIGNER" verify --min-sdk-version 29 "$APK"
certs="$("$APKSIGNER" verify --print-certs -v "$APK")"
SIGNER="$(grep -m1 -E 'Signer( #1)?: certificate SHA-256 digest|Signer #1 certificate SHA-256 digest' <<<"$certs" | awk '{print $NF}')"
SIGNER_DN="$(grep -m1 -E 'certificate DN' <<<"$certs" | sed 's/.*certificate DN: //')"
PERMS="$("$AAPT2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort -u)"
if grep -qiE 'INTERNET|NETWORK|WIFI|BLUETOOTH|NFC' <<<"$PERMS"; then
  echo "FATAL: network permission in the APK: $PERMS" >&2; rm -f "$APK"; exit 5; fi
if [ $HAVE_KEY = 1 ] && grep -q 'CN=Android Debug' <<<"$SIGNER_DN"; then
  echo "FATAL: release APK is signed with the debug key" >&2; rm -f "$APK"; exit 5; fi
SIZE="$(stat -c %s "$APK")"

# --- checksum, notes, changelog, metadata
(cd "$OUT" && sha256sum "$(basename "$APK")" > "$(basename "$APK").sha256")
python3 -I tools/release/changelog.py --title "pboard $VNAME" > "$OUT/RELEASE_NOTES.md"
python3 -I tools/release/changelog.py --all > "$OUT/CHANGELOG.md"
cp -f app/build/outputs/mapping/release/mapping.txt "$OUT/mapping.txt" 2>/dev/null || true
python3 -I - "$OUT/release.json" <<EOF
import json, sys
json.dump({
  "versionName": "$VNAME", "versionCode": int("$VCODE"), "commit": "$(git rev-parse HEAD)",
  "apk": "$(basename "$APK")", "sha256": "$(cut -d' ' -f1 "$APK.sha256")", "sizeBytes": $SIZE,
  "signerSha256": "$SIGNER", "signerDN": "$SIGNER_DN", "debugSigned": $([ $HAVE_KEY = 0 ] && echo True || echo False),
  "rotationLineage": $([ -n "$LINEAGE" ] && echo True || echo False),
  "permissions": [p for p in """$PERMS""".split() if p],
}, open(sys.argv[1], "w"), indent=2)
EOF
[ $REPO = 1 ] && [ $HAVE_KEY = 1 ] && tools/release/repo.sh "$OUT"

echo "== pboard $VNAME (versionCode $VCODE)$([ $HAVE_KEY = 0 ] && echo ' DEBUG-SIGNED')"
echo "   apk:     $APK ($(numfmt --to=iec-i --suffix=B "$SIZE"))"
echo "   sha256:  $(cut -d' ' -f1 "$APK.sha256")"
echo "   signer:  $SIGNER_DN  sha256 $SIGNER$([ -n "$LINEAGE" ] && echo ' (+ rotation lineage from the debug key)')"
echo "   perms:   ${PERMS//$'\n'/ }"
echo "   notes:   $OUT/RELEASE_NOTES.md"
