#!/usr/bin/env bash
# On-device (ART) engine latency without building an app: dexes the engine and the DeviceBench harness, pushes the
# packs and a few human TUNE traces, runs dev.pboard.engine.bench.DeviceBenchKt via app_process.
#
#   tools/bench/bench_engine_device.sh <adb-serial> [words] [gestures] [configs]
#       configs: comma list of ngram, rerank<N>, rerank<N>bg (LSTM step on a background thread), full
#       (default: ngram,rerank8,rerank16,rerank32,full); suffixes: c<N> (context predictions), -cold (no neural state
#       for each word or glide's context), -prep (cold, then prepareContext)
#
# Run inside the devshell. On the dev phone go through the phone lock (no UI is touched, so --headless works even
# while the phone is locked):
#   tools/phone/guard.sh --headless -- tools/bench/bench_engine_device.sh <phone-serial> 300 150
# Everything is pushed to /data/local/tmp/pboard-enginebench and removed again, also on failure.
set -euo pipefail
SERIAL="$1"; WORDS="${2:-300}"; GESTURES="${3:-150}"; CONFIGS="${4:-ngram,rerank8,rerank16,rerank32,full}"
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
OUT="$PWD/engine/build/enginebench"
rm -rf "$OUT"; mkdir -p "$OUT"

./gradlew -q :engine:testClasses
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21 -name 'kotlin-stdlib-2.4.21.jar' | head -1)
D8="$ANDROID_HOME/build-tools/37.0.0/d8"
# Engine main classes + the eval/bench helpers (no JUnit test classes).
"$D8" --release --min-api 29 --output "$OUT" "$STDLIB" \
  $(find engine/build/classes/kotlin/main -name '*.class') \
  $(find engine/build/classes/kotlin/test/dev/pboard/engine/eval engine/build/classes/kotlin/test/dev/pboard/engine/bench \
         engine/build/classes/kotlin/test/dev/pboard/engine/devlm -name '*.class')

A=app/src/main/assets/lm
H=tools/data/human
DEV=/data/local/tmp/pboard-enginebench
trap 'adb -s "$SERIAL" shell "rm -rf $DEV" || true' EXIT
adb -s "$SERIAL" shell "rm -rf $DEV && mkdir -p $DEV"
adb -s "$SERIAL" push "$OUT/classes.dex" $A/en.lm $A/en_GB.lmo $A/en.nnlm engine/src/main/resources/dev/pboard/engine/swipe/qwerty.pbsw \
  $H/splits/v1/tap_tsi.tune.jsonl.gz $H/splits/v1/swipe_futo.tune.jsonl.gz $H/geometry/gboard_pixel8.json "$DEV/" >/dev/null
adb -s "$SERIAL" shell "cd $DEV && CLASSPATH=$DEV/classes.dex app_process -Xmx1g /system/bin dev.pboard.engine.bench.DeviceBenchKt $DEV $WORDS $GESTURES $CONFIGS"
