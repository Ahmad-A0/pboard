#!/usr/bin/env bash
# Replay the human held-out (test) sets on one IME. Results: tools/bench/out/<label>_human_<set>/.
#   tools/bench/human.sh <serial> <ime-id> <geometry.json> <label> [swipe-limit] [vtap-limit]
# Each set starts from a clean IME state (see run.sh). Sets are re-projected from the Gboard geometry if needed.
# TSI has no hold times: taps are held 45 ms (fitted human median, Replay default).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERIAL="$1"; IME="$2"; GEO="$3"; LABEL="$4"; SWIPE_LIMIT="${5:-400}"; VTAP_LIMIT="${6:-0}"
H="$ROOT/tools/data/human/splits/v1"
B=${SKIP_BUILD:-0}
# Human sets are projected onto the Gboard Pixel 8 geometry; re-project them for other IMEs.
export PREPARE_ARGS="--source-geometry $ROOT/tools/bench/geometry/gboard_pixel8.json ${PREPARE_ARGS:-}"
run() {  # name file [extra key value ...]
  local name="$1" file="$2"; shift 2
  SKIP_BUILD=$B OUT="$ROOT/tools/bench/out/${LABEL}_human_${name}" \
    "$ROOT/tools/bench/run.sh" "$SERIAL" "$IME" "$GEO" "$file" "$@"
  B=1
}
run tap_tsi "$H/tap_tsi.test.jsonl.gz"
run swipe_futo "$H/swipe_futo.test.jsonl.gz" limit "$SWIPE_LIMIT"
run swipe_hws "$H/swipe_hws.test.jsonl.gz" limit "$SWIPE_LIMIT"
if [ "$VTAP_LIMIT" = 0 ]; then run tap_vtap "$H/tap_vtap.test.jsonl.gz"
else run tap_vtap "$H/tap_vtap.test.jsonl.gz" limit "$VTAP_LIMIT"; fi
