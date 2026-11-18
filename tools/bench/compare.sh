#!/usr/bin/env bash
# Gboard vs pboard on identical input, identical harness and identical reset policy.
#   [APP_APK=<dev.pboard apk> APP_COMMIT=<sha>] [AVD=<avd>] tools/bench/compare.sh <serial> [step...]
# Steps (default all, in this order): tsi swipe vtap synthetic; "tsi-pboard" runs only pboard's TSI
# leg. APP_APK/APP_COMMIT select the dev.pboard build (IME + playground) for both keyboards. Results:
# tools/bench/out/{gboard,pboard}_<set>/ ; then tools/bench/report.py prints the side-by-side table.
# Both keyboards start every reset group (each participant for TSI/Vertanen, the whole set for the
# swipe and synthetic sets) from the emulator snapshot gboard_bench_base; pboard is additionally
# `pm clear`ed (Gboard never is: that would wipe its synced remote configuration).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERIAL="$1"; shift
STEPS="${*:-tsi swipe vtap synthetic}"
R="$ROOT/tools/bench/run.sh"
H="$ROOT/tools/data/human/splits/v1"
S="$ROOT/tools/bench/sets/gboard_pixel8"
GB=(com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME "$ROOT/tools/bench/geometry/gboard_pixel8.json")
PB=(dev.pboard/.ime.PboardIme "$ROOT/tools/bench/geometry/pboard_pixel8.json")
both() {  # name set [extra...]
  local name="$1" set="$2"; shift 2
  OUT="$ROOT/tools/bench/out/gboard_$name" "$R" "$SERIAL" "${GB[@]}" "$set" "$@" || echo "!! gboard $name failed"
  OUT="$ROOT/tools/bench/out/pboard_$name" "$R" "$SERIAL" "${PB[@]}" "$set" "$@" || echo "!! pboard $name failed"
}
export SKIP_BUILD="${SKIP_BUILD:-1}"
for step in $STEPS; do
  case $step in
    tsi) both human_tap_tsi "$H/tap_tsi.test.jsonl.gz" ;;
    tsi-pboard) OUT="$ROOT/tools/bench/out/pboard_human_tap_tsi" "$R" "$SERIAL" "${PB[@]}" "$H/tap_tsi.test.jsonl.gz" || echo "!! pboard tsi failed" ;;
    swipe) export MAX_ITEMS=400
           both human_swipe_futo "$H/swipe_futo.test.jsonl.gz"
           both human_swipe_hws "$H/swipe_hws.test.jsonl.gz"
           unset MAX_ITEMS ;;
    vtap) both human_tap_vtap "$H/tap_vtap.test.jsonl.gz" ;;
    synthetic) both synth_tap_v1 "$S/tap_v1.jsonl"
               both synth_swipe_v1 "$S/swipe_v1.jsonl"
               both synth_predict_v1 "$S/predict_v1.jsonl" variant incognito ;;
  esac
done
echo ALLDONE
