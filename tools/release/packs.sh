#!/usr/bin/env bash
# Release-signed pack APKs (voice, accurate voice, scan, translation) for the self-hosted update repo, so a phone with
# release pboard can install them from its browser over the repo's private URL (pboard itself never downloads anything).
#
#   tools/release/packs.sh [--translate "es fr de" | all | none] [--repo DIR=dist/repo] [--no-repo] [--out DIR=dist/packs]
#
# 1. inputs: fetches or verifies the pinned model files (tools/voice/fetch_voicepack.sh; tools/translate/build_pack.py
#    per language, cached in tools/translate/.packs); the scan pack's model is in the repository
# 2. builds every pack with -Ppboard.release=true (pboard's release key, root build.gradle.kts); a pack without its
#    model files fails the build
# 3. re-signs each APK with the key-rotation lineage exactly as tools/release.sh signs the app (v3, lineage from the
#    debug key), so the packs carry the same signing certificate history as release pboard
# 4. checks: apksigner verify; signer SHA-256 equals the app's release signer (newest release in the repo, or the
#    keystore); no permissions at all
# 5. copies them to <repo>/packs/ with .sha256 files, packs/index.json and packs/index.html, and rebuilds the repo index
#    (tools/release/repo.sh --index adds a "Packs" section)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! command -v adb >/dev/null 2>&1; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
cd "$ROOT"
TRANSLATE="es fr de"; REPO="$ROOT/dist/repo"; PUBLISH=1; OUT="$ROOT/dist/packs"
while [ $# -gt 0 ]; do
  case "$1" in
    --translate) TRANSLATE="$2"; shift ;;
    --repo) REPO="$2"; shift ;;
    --no-repo) PUBLISH=0 ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
[ "$TRANSLATE" = none ] && TRANSLATE=""
if [ "$TRANSLATE" = all ]; then
  TRANSLATE="$(sed -n 's/.*"dev\.pboard\.pack\.translate\.\([a-z]*\)".*/\1/p' app/src/main/java/dev/pboard/ime/translate/TranslatePacks.kt | tr '\n' ' ')"
fi
BT="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)"
APKSIGNER="${BT}apksigner"; AAPT2="${BT}aapt2"
PROPS="${PBOARD_SIGNING_PROPERTIES:-$HOME/.config/pboard/signing.properties}"
[ -f "$PROPS" ] || [ -n "${PBOARD_KEYSTORE:-}" ] || { echo "refusing: no release key ($PROPS, docs/release.md)" >&2; exit 4; }
prop() { sed -n "s/^$1=//p" "$PROPS" 2>/dev/null | tail -1; }

# --- the app's release signer
APP_SIGNER="$(python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1]))["releases"]; print(r[0]["signerSha256"] if r else "")' \
  "$REPO/index.json" 2>/dev/null || true)"
if [ -z "$APP_SIGNER" ]; then
  KS="$(prop storeFile)"; [[ "$KS" = /* ]] || KS="$(dirname "$PROPS")/$KS"
  APP_SIGNER="$(PW="$(prop storePassword)" keytool -exportcert -keystore "$KS" -storepass:env PW -alias "$(prop keyAlias)" \
    | sha256sum | cut -d' ' -f1)"
fi
echo "== app release signer: $APP_SIGNER"

# --- 1. inputs
echo "== model inputs"
tools/voice/fetch_voicepack.sh >/dev/null
TR=tools/translate/.packs
for l in $TRANSLATE; do
  [ -f "$TR/$l/translate/manifest.json" ] || python3 -I tools/translate/build_pack.py "$l" "$TR" --models "$TR/models" >/dev/null
done

# --- 2. build
G=(--console=plain -q --no-configuration-cache -Ppboard.release=true)
echo "== building voice, accurate voice and scan packs"
ANDROID_SERIAL=none ./gradlew "${G[@]}" :voicepack:assembleRelease :voicepackaccurate:assembleRelease :scanpack:assembleRelease
declare -A BUILT=(
  [voice-en]=voicepack/build/outputs/apk/release/voicepack-release.apk
  [voice-en-accurate]=voicepackaccurate/build/outputs/apk/release/voicepackaccurate-release.apk
  [scan]=scanpack/build/outputs/apk/release/scanpack-release.apk
)
mkdir -p "$OUT"
for l in $TRANSLATE; do
  echo "== building translation pack $l"
  ANDROID_SERIAL=none ./gradlew "${G[@]}" :translatepack:assembleRelease -PtranslatePack="$ROOT/$TR/$l" -PtranslatePackId="$l"
  cp translatepack/build/outputs/apk/release/translatepack-release.apk "$OUT/.translate-$l.apk"
  BUILT[translate-$l]="$OUT/.translate-$l.apk"
done

# --- 3 + 4. lineage, checks
LINEAGE=""
if [ -z "${PBOARD_KEYSTORE:-}" ] && [ -n "$(prop lineage)" ]; then
  LINEAGE="$(prop lineage)"; [[ "$LINEAGE" = /* ]] || LINEAGE="$(dirname "$PROPS")/$LINEAGE"
  KS="$(prop storeFile)"; [[ "$KS" = /* ]] || KS="$(dirname "$PROPS")/$KS"
fi
: > "$OUT/packs.tsv"
for name in "${!BUILT[@]}"; do
  src="${BUILT[$name]}"
  badging="$("$AAPT2" dump badging "$src")"
  pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
  vcode="$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
  apk="$OUT/pboard-$name-v$vcode.apk"
  if [ -n "$LINEAGE" ]; then
    PBOARD_KS_PW="$(prop storePassword)" PBOARD_KEY_PW="$(prop keyPassword)" "$APKSIGNER" sign \
      --ks "$KS" --ks-key-alias "$(prop keyAlias)" --ks-pass env:PBOARD_KS_PW --key-pass env:PBOARD_KEY_PW \
      --lineage "$LINEAGE" --rotation-min-sdk-version 28 \
      --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
      --out "$apk" "$src"
    rm -f "$apk.idsig"
  else
    cp "$src" "$apk"
  fi
  "$APKSIGNER" verify --min-sdk-version 29 "$apk"
  signer="$("$APKSIGNER" verify --print-certs "$apk" | grep -m1 -E 'Signer( #1)?: certificate SHA-256 digest' | awk '{print $NF}')"
  if [ "$signer" != "$APP_SIGNER" ]; then echo "FATAL: $apk signer $signer != app release signer" >&2; rm -f "$apk"; exit 5; fi
  case "$name" in   # the model must really be inside
    voice-en) need=assets/voice/encoder.int8.ort ;;
    voice-en-accurate) need=assets/voice/accurate/encoder.int8.ort ;;
    scan) need=assets/ocr/eng.traineddata ;;
    translate-*) need=assets/translate/manifest.json ;;
  esac
  if ! unzip -l "$apk" | grep -q " $need\$"; then echo "FATAL: $apk lacks $need" >&2; rm -f "$apk"; exit 5; fi
  perms="$("$AAPT2" dump permissions "$apk" | grep -c '^uses-permission' || true)"
  if [ "$perms" != 0 ]; then echo "FATAL: $apk requests permissions" >&2; rm -f "$apk"; exit 5; fi
  (cd "$OUT" && sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256")
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$pkg" "$vcode" "$(basename "$apk")" "$(stat -c %s "$apk")" >> "$OUT/packs.tsv"
  echo "   $(basename "$apk")  $pkg  $(numfmt --to=iec-i --suffix=B "$(stat -c %s "$apk")")"
done
rm -f "$OUT"/.translate-*.apk

# --- 5. repo
if [ $PUBLISH = 1 ]; then
  mkdir -p "$REPO/packs"
  python3 -I - "$OUT" "$REPO/packs" "$APP_SIGNER" <<'EOF'
import html, json, os, shutil, sys
out, dest, signer = sys.argv[1], sys.argv[2], sys.argv[3]
about = {
    "voice-en": "Voice typing: on-device speech recogniser (X-ASR streaming, English) - needed for the mic key",
    "voice-en-accurate": "Optional: more accurate final text for voice typing (parakeet-110m, CC-BY-4.0)",
    "scan": "Scan text: on-device OCR (Tesseract, English)",
}
packs = []
for line in open(os.path.join(out, "packs.tsv")):
    name, pkg, vcode, apk, size = line.rstrip("\n").split("\t")
    for f in (apk, apk + ".sha256"):
        shutil.copyfile(os.path.join(out, f), os.path.join(dest, f))
    sha = open(os.path.join(out, apk + ".sha256")).read().split()[0]
    lang = name[len("translate-"):] if name.startswith("translate-") else None
    packs.append({"name": name, "package": pkg, "versionCode": int(vcode), "file": apk, "sizeBytes": int(size),
                  "sha256": sha, "signerSha256": signer,
                  "about": about.get(name) or "Translation pack: English <-> %s (Firefox Translations models, MPL-2.0)" % lang})
order = {"voice-en": 0, "voice-en-accurate": 1, "scan": 2}
packs.sort(key=lambda p: (order.get(p["name"], 3), p["name"]))
json.dump({"packs": packs}, open(os.path.join(dest, "index.json"), "w"), indent=2)
rows = "\n".join('<li><a href="%s">%s</a> <span>%s, %.1f MB, v%d</span><br>%s<br><code>SHA-256 %s</code></li>' % (
    html.escape(p["file"]), html.escape(p["file"]), html.escape(p["package"]), p["sizeBytes"] / 1e6, p["versionCode"],
    html.escape(p["about"]), p["sha256"]) for p in packs)
open(os.path.join(dest, "index.html"), "w").write("""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>pboard packs</title>
<style>body{font:15px/1.5 system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem}
li{margin:.8rem 0}code{font-size:12px;word-break:break-all}span{color:#666}</style></head><body>
<h1>pboard packs</h1>
<p>Optional add-ons for pboard, installed like any APK (open the link on the phone and install). pboard only uses packs
signed by its own release key, certificate SHA-256<br><code>%s</code></p>
<ol>
%s
</ol>
<p><a href="../">pboard releases</a> &middot; <a href="index.json">index.json</a></p>
</body></html>
""" % (signer, rows))
print("== %s: %d pack(s)" % (dest, len(packs)))
EOF
  tools/release/repo.sh --index "$REPO"
fi
echo "== packs in $OUT$([ $PUBLISH = 1 ] && echo " and $REPO/packs")"
