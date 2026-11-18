#!/usr/bin/env bash
# On-device (ART) latency of grammar check and Smart Compose, without building an app: dexes the engine and the
# evaluation mains, pushes the language packs, the morphology table and small held-out subsets, and runs GecEval /
# ComposeEval via app_process. Prints check() latency (n-gram and n-gram + LSTM) and completion latency.
#
#   tools/textintel/bench_device.sh <adb-serial> [sentences]
#
# Run inside the devshell. On the dev phone, through the phone lock in headless mode (pushes files and runs
# app_process only: no input, no screen reads):
#   tools/phone/guard.sh --headless -- tools/textintel/bench_device.sh <phone-serial> 300
# Everything is pushed to /data/local/tmp/pboard-tibench and removed again, also on failure. Only held-out text the
# project may redistribute is pushed (chat test split and its synthetic-error variant; no BEA/CoNLL data).
set -euo pipefail
SERIAL="$1"; N="${2:-300}"
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
W=tools/data/work/textintel
OUT="$PWD/engine/build/tibench"
rm -rf "$OUT"; mkdir -p "$OUT/sets"

[ "${SKIP_BUILD:-0}" = 1 ] || ./gradlew -q :engine:testClasses
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib -name 'kotlin-stdlib-2.*.jar' | sort | tail -1)
D8="$ANDROID_HOME/build-tools/37.0.0/d8"
"$D8" --release --min-api 29 --output "$OUT" "$STDLIB" \
  $(find engine/build/classes/kotlin/main/dev/pboard/engine -name '*.class' | sort -u) \
  $(find engine/build/classes/kotlin/test/dev/pboard/engine/textintel -name 'GecEval*.class' -o -name 'ComposeEval*.class')

head -n "$N" "$W/clean_chat.jsonl" > "$OUT/sets/clean_chat.jsonl"
head -n "$N" "$W/synth_chat_test.jsonl" > "$OUT/sets/synth_chat_test.jsonl"
H="${HELDOUT:-$W/heldout}"
head -n "$((N / 3))" "$H/chat.txt" > "$OUT/sets/chat.txt"
A=app/src/main/assets
DEV=/data/local/tmp/pboard-tibench
trap 'adb -s "$SERIAL" shell "rm -rf $DEV" || true' EXIT
adb -s "$SERIAL" shell "rm -rf $DEV && mkdir -p $DEV/lm $DEV/textintel $DEV/sets"
adb -s "$SERIAL" push "$OUT/classes.dex" "$DEV/" >/dev/null
adb -s "$SERIAL" push $A/lm/en.lm $A/lm/en_GB.lmo $A/lm/en_US.lmo $A/lm/en.nnlm "$DEV/lm/" >/dev/null
adb -s "$SERIAL" push $A/textintel/en_morph.txt "$DEV/textintel/" >/dev/null
adb -s "$SERIAL" push "$OUT/sets/." "$DEV/sets/" >/dev/null
echo "== grammar check (en-GB), $N + $N sentences"
adb -s "$SERIAL" shell "cd $DEV && GEC_LM_DIR=$DEV/lm CLASSPATH=$DEV/classes.dex app_process /system/bin dev.pboard.engine.textintel.GecEvalKt $DEV/cands.jsonl en-GB $DEV/sets/clean_chat.jsonl $DEV/sets/synth_chat_test.jsonl"
echo "== Smart Compose (en-US), $((N / 3)) sentences"
adb -s "$SERIAL" shell "cd $DEV && COMPOSE_LM_DIR=$DEV/lm COMPOSE_SENTENCES=$((N / 3)) CLASSPATH=$DEV/classes.dex app_process /system/bin dev.pboard.engine.textintel.ComposeEvalKt en-US $DEV/sets/chat.txt" | grep -E "^==|^  2    0\.(45|50|60)"
