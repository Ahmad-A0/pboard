#!/usr/bin/env bash
# Replay the four headline sets (docs/bench/gboard-baseline.md) on one keyboard, with the recipe
# the published table uses: the TSI verdict subset (303 words), all of VTAP (3818 words), and the
# first 400 FUTO and How We Swipe test swipes. Works for any installed IME that has a geometry file.
#
#   tools/bench/headline_sets.sh <device[,device...]> <ime-id> <geometry.json> <label> [set ...]
#
#   sets   any of tap_tsi tap_vtap swipe_futo swipe_hws (default: all four)
# Results: tools/bench/out/<label>_<set>/ (override the parent with OUT_BASE). Env is passed on to
# run.sh; SNAPSHOT defaults to "" here (reset = `pm clear` of the IME; right for keyboards whose
# clean state is a fresh install - not for Gboard, which needs its snapshot: set SNAPSHOT).
# Extra Replay arguments: REPLAY_ARGS="settle fast".
# A third-party keyboard must already be installed on every device (run.sh only installs pboard and
# the injector); its bundled data must survive `pm clear`.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 4 ] || { sed -n '2,14p' "$0"; exit 2; }
DEVICES="$1"; IME="$2"; GEO="$3"; LABEL="$4"; shift 4
SETS=("$@"); [ ${#SETS[@]} -gt 0 ] || SETS=(tap_tsi swipe_futo swipe_hws tap_vtap)
H="$ROOT/tools/data/human/splits/v1"
export SNAPSHOT="${SNAPSHOT-}"
# The human sets are projected onto the Gboard Pixel 8 geometry; re-project them for other IMEs.
export PREPARE_ARGS="--source-geometry $ROOT/tools/bench/geometry/gboard_pixel8.json ${PREPARE_ARGS:-}"
B="${SKIP_BUILD:-0}"
for s in "${SETS[@]}"; do
  case "$s" in
    tap_tsi)    f="$ROOT/tools/bench/sets/verdict/tap_tsi.jsonl"; max="" ;;
    tap_vtap)   f="$H/tap_vtap.test.jsonl.gz"; max="" ;;
    swipe_futo) f="$H/swipe_futo.test.jsonl.gz"; max=400 ;;
    swipe_hws)  f="$H/swipe_hws.test.jsonl.gz"; max=400 ;;
    *) echo "!! unknown set $s"; exit 2 ;;
  esac
  # shellcheck disable=SC2086
  SKIP_BUILD=$B MAX_ITEMS="$max" OUT="${OUT_BASE:-$ROOT/tools/bench/out}/${LABEL}_$s" \
    "$ROOT/tools/bench/run.sh" "$DEVICES" "$IME" "$GEO" "$f" ${REPLAY_ARGS:-} || echo "!! $s: run.sh failed"
  B=1
done
