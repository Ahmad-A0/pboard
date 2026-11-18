#!/usr/bin/env bash
# Gated runs used for the current report (see docs/bench/gboard-baseline.md):
#   1. determinism / sharding check: 200 TSI items (4 participants x 50), one device vs two shards
#      (skipped with SKIP_DETERMINISM=1)
#   2. TSI test split, per-participant resets, sharded over the devices: Gboard, then each pboard APK
#   [LABEL=gb18] tools/bench/gated_tsi.sh <dev-single> <dev1,dev2> <apk:commit> [<apk:commit> ...]
# Results: tools/bench/out/${LABEL:+$LABEL_}{gboard,pboard-<commit>}_human_tap_tsi/
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SINGLE="$1"; SHARDS="$2"; shift 2
R="$ROOT/tools/bench/run.sh"; O="$ROOT/tools/bench/out"; P="${LABEL:+${LABEL}_}"
GB=(com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME "$ROOT/tools/bench/geometry/gboard_pixel8.json")
PB=(dev.pboard/.ime.PboardIme "$ROOT/tools/bench/geometry/pboard_pixel8.json")
TSI="$ROOT/tools/data/human/splits/v1/tap_tsi.test.jsonl.gz"
export SKIP_BUILD=1 AUTO_RESTART=1
FIRST="${1%%:*}"; export APP_APK="$FIRST" APP_COMMIT="${1##*:}"   # playground host for the Gboard runs
if [ "${SKIP_DETERMINISM:-0}" != 1 ]; then
  SMALL="$O/shardtest_tsi200.jsonl"
  python3 -I - "$TSI" "$SMALL" <<'EOF'
import gzip, json, sys, collections
per = collections.OrderedDict()
for line in gzip.open(sys.argv[1], "rt"):
    p = json.loads(line)["participant"]
    per.setdefault(p, [])
    if len(per[p]) < 50:
        per[p].append(line)
with open(sys.argv[2], "w") as f:
    for p in list(per)[:4]:
        f.writelines(per[p])
EOF
  OUT="$O/${P}determinism_single" "$R" "$SINGLE" "${GB[@]}" "$SMALL"
  OUT="$O/${P}determinism_sharded" "$R" "$SHARDS" "${GB[@]}" "$SMALL"
  python3 -I "$ROOT/tools/bench/compare_results.py" "$O/${P}determinism_single/results.jsonl" "$O/${P}determinism_sharded/results.jsonl" | tee "$O/${P}determinism.txt"
fi
OUT="$O/${P}gboard_human_tap_tsi" "$R" "$SHARDS" "${GB[@]}" "$TSI"
for spec in "$@"; do
  apk="${spec%%:*}"; c="${spec##*:}"
  APP_APK="$apk" APP_COMMIT="$c" OUT="$O/${P}pboard-${c}_human_tap_tsi" "$R" "$SHARDS" "${PB[@]}" "$TSI"
done
echo ALLDONE
