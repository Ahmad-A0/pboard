#!/usr/bin/env bash
# Full synthetic baseline for one IME: tap, swipe and next-word prediction sets, each from a clean
# IME state (snapshot restore for Gboard, see run.sh). Results land in tools/bench/out/<label>_{tap,swipe,predict}_v1/.
#   tools/bench/baseline.sh <serial> <ime-id> <geometry.json> <label>
# Prediction probes run in the "incognito" field variant (IME_FLAG_NO_PERSONALIZED_LEARNING) so
# that tapping one suggestion slot does not teach the IME and reorder the strip for the next probe.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERIAL="$1"; IME="$2"; GEO="$3"; LABEL="$4"
NAME="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$GEO")"
B=${SKIP_BUILD:-0}
for s in tap_v1 swipe_v1 predict_v1; do
  extra=()
  [ $s = predict_v1 ] && extra=(variant incognito)
  SKIP_BUILD=$B OUT="$ROOT/tools/bench/out/${LABEL}_${s}" \
    "$ROOT/tools/bench/run.sh" "$SERIAL" "$IME" "$GEO" "$ROOT/tools/bench/sets/$NAME/$s.jsonl" "${extra[@]}"
  B=1
done
