#!/usr/bin/env bash
# On-device translation benchmark (headless): dexes :translate + PhoneBench, pushes a model and a sentence file to
# the phone, runs app_process. Run from the repo root inside the devshell, through the phone guard:
#
#   ./gradlew :translate:phoneBenchBundle
#   tools/phone/guard.sh --headless -- tools/translate/phone_bench.sh <model> <sentences.txt> [cpu-mask]
#
# <model>: a Firefox Translations model directory (model.*.bin, vocab.*.spm, lex.*.bin, uncompressed), or
#          <pack.zip>:<from>-<to> to load from a deflated pack file (what a pack APK's assets look like).
# cpu-mask (hex, optional): pin with taskset, e.g. c0 = the two Cortex-A76 cores of a Snapdragon 732G,
#          3f = the six Cortex-A55 cores.
# Leaves /data/local/tmp/pbtr/ on the phone (delete with: adb shell rm -r /data/local/tmp/pbtr).
set -euo pipefail
model="$1"; sentences="$2"; mask="${3:-}"
serial="${PHONE:-${ANDROID_SERIAL:?set PHONE or ANDROID_SERIAL}}"
bundle=translate/build/phone-bench
out="$(mktemp -d)"
"$ANDROID_HOME/build-tools/37.0.0/d8" --min-api 29 --release --output "$out" "$bundle"/*.jar
dst=/data/local/tmp/pbtr
adb -s "$serial" shell mkdir -p "$dst"
adb -s "$serial" push -q "$out/classes.dex" "$dst/bench.dex"
if [[ "$model" == *.zip:* ]]; then
  zip="${model%%:*}"; pair="${model##*:}"
  adb -s "$serial" push -q --sync "$zip" "$dst/$(basename "$zip")"
  arg="$dst/$(basename "$zip"):$pair"
else
  adb -s "$serial" shell mkdir -p "$dst/$(basename "$model")"
  for f in "$model"/model.*.bin "$model"/*vocab.*.spm "$model"/lex.*.bin; do
    adb -s "$serial" push -q --sync "$f" "$dst/$(basename "$model")/"
  done
  arg="$dst/$(basename "$model")"
fi
adb -s "$serial" push -q "$sentences" "$dst/sentences.txt"
rm -rf "$out"
pin=""; [[ -n "$mask" ]] && pin="taskset $mask"
adb -s "$serial" shell "$pin app_process -Djava.class.path=$dst/bench.dex /system/bin dev.pboard.translate.PhoneBench $arg $dst/sentences.txt"
