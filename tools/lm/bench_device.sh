#!/usr/bin/env bash
# On-device (ART) latency + accuracy benchmark of the language packs, without building an app:
# dexes the engine + benchmark classes, pushes the packs and an eval subset, runs LmEval via app_process.
#
#   tools/lm/bench_device.sh <adb-serial> [sentences-per-set]      e.g. tools/lm/bench_device.sh emulator-5554 100
#
# Run inside the devshell. Only use an emulator/device you are allowed to load (emulator timing benchmarks
# of other work are skewed by concurrent CPU use). On the dev phone, run it through the phone lock in headless
# mode (it only pushes files and runs app_process: no input, no screen reads, works while the phone is locked):
#   tools/phone/guard.sh --headless -- tools/lm/bench_device.sh <phone-serial> 100
# Everything is pushed to /data/local/tmp/pboard-lmbench and removed again, also on failure.
# Options (environment): BENCH_NNLM=<file.nnlm> benchmarks another neural model than the shipped asset;
# BENCH_RUNS="ngram nn nn2" picks the configurations; BENCH_LATENCY_ONLY=1 skips the accuracy rows.
set -euo pipefail
SERIAL="$1"; N="${2:-100}"
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
REPO="$PWD"
OUT="$REPO/engine/build/lmbench"
rm -rf "$OUT"; mkdir -p "$OUT/eval"

./gradlew -q :engine:testClasses
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21 -name 'kotlin-stdlib-2.4.21.jar' | head -1)
D8="$ANDROID_HOME/build-tools/37.0.0/d8"
"$D8" --release --min-api 29 --output "$OUT" "$STDLIB" \
  $(find engine/build/classes/kotlin/main/dev/pboard/engine/lm \
         engine/build/classes/kotlin/main/dev/pboard/engine -maxdepth 1 -name '*.class' | sort -u) \
  $(find engine/build/classes/kotlin/test/dev/pboard/engine/lm -name 'LmEval*.class' -o -name 'EvalResult*.class')
if [ "$SERIAL" = "--dex-only" ]; then ls -l "$OUT"; exit 0; fi

for s in chat dailydialog nussms_us; do
  head -n "$N" "tools/data/work/en/eval/$s.txt" > "$OUT/eval/$s.txt"
done
A=app/src/main/assets/lm
NNLM="${BENCH_NNLM:-$A/en.nnlm}"
RUNS="${BENCH_RUNS:-ngram nn nn2}"
SENT=$N; [ "${BENCH_LATENCY_ONLY:-0}" = 1 ] && SENT=0
DEV=/data/local/tmp/pboard-lmbench
trap 'adb -s "$SERIAL" shell "rm -rf $DEV" || true' EXIT
adb -s "$SERIAL" shell "rm -rf $DEV && mkdir -p $DEV/eval"
adb -s "$SERIAL" push "$OUT/classes.dex" $A/en.lm $A/en_US.lmo "$DEV/" >/dev/null
adb -s "$SERIAL" push "$NNLM" "$DEV/en.nnlm" >/dev/null
adb -s "$SERIAL" push "$OUT/eval/." "$DEV/eval/" >/dev/null
run() {  # <title> <env> <LmEval args>
  echo "== $1"
  adb -s "$SERIAL" shell "cd $DEV && $2 LM_EVAL_SENTENCES=$SENT CLASSPATH=$DEV/classes.dex app_process /system/bin dev.pboard.engine.lm.LmEvalKt $3"
}
for r in $RUNS; do
  case $r in
    ngram) run "n-gram only" "" "en.lm eval en_US.lmo" ;;
    nn)    run "n-gram + neural rescoring ($(basename "$NNLM"))" "" "en.lm eval en_US.lmo en.nnlm" ;;
    nn2)   run "n-gram + neural rescoring ($(basename "$NNLM")), 2 threads" "LM_EVAL_NN_THREADS=2" "en.lm eval en_US.lmo en.nnlm" ;;
  esac
done
