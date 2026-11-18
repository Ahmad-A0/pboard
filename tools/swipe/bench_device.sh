#!/usr/bin/env bash
# On-device (ART) latency of the neural swipe decoder, without building an app: dexes the engine plus the swipe
# benchmark/eval classes, pushes models, the production language pack and human swipe traces, and runs them via
# app_process. Inside the devshell; on the dev phone always through the phone lock. It neither injects input nor
# reads the screen, so the guard's headless mode is enough (works while the phone is locked):
#
#   tools/phone/guard.sh --headless -- tools/swipe/bench_device.sh <phone-serial> encoder <model.pbsw>...
#   tools/phone/guard.sh --headless -- tools/swipe/bench_device.sh <phone-serial> decode <model.pbsw> [NeuralSwipeEval args]
#
#   encoder: SwipeBench (encoder only, synthetic input of 46/110/150 frames = median/p90/p99 human strokes)
#   decode:  NeuralSwipeEval on the human tune swipes (full suggestForSwipe: features, encoder, beam search, LM),
#            template matcher and neural model, with latency percentiles
# Everything is pushed to /data/local/tmp/pboard-swipebench and removed again, also on failure.
set -euo pipefail
SERIAL="$1"; MODE="$2"; shift 2
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
REPO="$PWD"
OUT="$REPO/engine/build/swipebench"
rm -rf "$OUT"; mkdir -p "$OUT"

./gradlew -q :engine:testClasses
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21 -name 'kotlin-stdlib-2.4.21.jar' | head -1)
D8="$ANDROID_HOME/build-tools/37.0.0/d8"
T=engine/build/classes/kotlin/test/dev/pboard/engine
"$D8" --release --min-api 29 --output "$OUT" "$STDLIB" \
  $(find engine/build/classes/kotlin/main -name '*.class' | sort) \
  $(find $T/swipe $T/eval $T/testutil $T/devlm -name '*.class' ! -name '*Test.class' ! -name '*Test$*.class' | sort)

DEV=/data/local/tmp/pboard-swipebench
trap 'adb -s "$SERIAL" shell "rm -rf $DEV" || true' EXIT
adb -s "$SERIAL" shell "rm -rf $DEV && mkdir -p $DEV/models $DEV/app/src/main/assets/lm $DEV/tools/data/human/splits/v1 $DEV/tools/data/human/geometry && touch $DEV/settings.gradle.kts"
adb -s "$SERIAL" push "$OUT/classes.dex" "$DEV/" >/dev/null
case "$MODE" in
  encoder)
    names=()
    for m in "$@"; do adb -s "$SERIAL" push "$m" "$DEV/models/" >/dev/null; names+=("models/$(basename "$m")"); done
    adb -s "$SERIAL" shell "cd $DEV && CLASSPATH=$DEV/classes.dex app_process /system/bin dev.pboard.engine.swipe.SwipeBenchKt ${names[*]}"
    ;;
  decode)
    MODEL="$1"; shift
    adb -s "$SERIAL" push "$MODEL" "$DEV/models/model.pbsw" >/dev/null
    A=app/src/main/assets/lm
    adb -s "$SERIAL" push $A/en.lm $A/en_GB.lmo $A/en_US.lmo $A/en.nnlm "$DEV/$A/" >/dev/null   # en.nnlm: --nnlm (final re-rank)
    adb -s "$SERIAL" push tools/data/human/geometry/gboard_pixel8.json "$DEV/tools/data/human/geometry/" >/dev/null
    adb -s "$SERIAL" push tools/data/human/splits/v1/swipe_futo.tune.jsonl.gz tools/data/human/splits/v1/swipe_hws.tune.jsonl.gz \
      "$DEV/tools/data/human/splits/v1/" >/dev/null
    adb -s "$SERIAL" shell "cd $DEV && CLASSPATH=$DEV/classes.dex app_process -Xmx512m /system/bin dev.pboard.engine.swipe.NeuralSwipeEvalKt --model models/model.pbsw $*"
    ;;
  *) echo "mode: encoder | decode" >&2; exit 2 ;;
esac
