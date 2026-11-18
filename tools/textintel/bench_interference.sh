#!/usr/bin/env bash
# Keystroke and commit latency of the keyboard engine on a device with text intelligence off, with grammar checks,
# and with grammar checks + Smart Compose running on a background-priority thread (InterferenceBench.kt). Dexes the
# engine and the bench, pushes the packs, the morphology table and the human TUNE tap traces, runs via app_process.
#
#   tools/textintel/bench_interference.sh <adb-serial> [words] [rounds]
#
# Run inside the devshell. On the dev phone through the phone lock in headless mode (no input, no screen reads):
#   tools/phone/guard.sh --headless -- tools/textintel/bench_interference.sh <phone-serial> 300 2
# Everything is pushed to /data/local/tmp/pboard-tiinterf and removed again, also on failure.
set -euo pipefail
SERIAL="$1"; WORDS="${2:-300}"; ROUNDS="${3:-2}"
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
OUT="$PWD/engine/build/tiinterf"
rm -rf "$OUT"; mkdir -p "$OUT"
[ "${SKIP_BUILD:-0}" = 1 ] || ./gradlew -q :engine:testClasses
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib -name 'kotlin-stdlib-2.*.jar' | sort | tail -1)
D8="$ANDROID_HOME/build-tools/37.0.0/d8"
"$D8" --release --min-api 29 --output "$OUT" "$STDLIB" \
  $(find engine/build/classes/kotlin/main -name '*.class') \
  $(find engine/build/classes/kotlin/test/dev/pboard/engine/eval engine/build/classes/kotlin/test/dev/pboard/engine/devlm -name '*.class') \
  $(find engine/build/classes/kotlin/test/dev/pboard/engine/textintel -name 'InterferenceBench*.class')
A=app/src/main/assets
H=tools/data/human
DEV=/data/local/tmp/pboard-tiinterf
trap 'adb -s "$SERIAL" shell "rm -rf $DEV" || true' EXIT
adb -s "$SERIAL" shell "rm -rf $DEV && mkdir -p $DEV"
adb -s "$SERIAL" push "$OUT/classes.dex" $A/lm/en.lm $A/lm/en_GB.lmo $A/lm/en.nnlm $A/textintel/en_morph.txt \
  $H/splits/v1/tap_tsi.tune.jsonl.gz $H/geometry/gboard_pixel8.json "$DEV/" >/dev/null
adb -s "$SERIAL" shell "cd $DEV && CLASSPATH=$DEV/classes.dex app_process -Xmx1g /system/bin dev.pboard.engine.textintel.InterferenceBenchKt $DEV $WORDS $ROUNDS"
