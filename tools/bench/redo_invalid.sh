#!/usr/bin/env bash
# Re-run the invalid groups of a finished run.sh run (same OUT, same IME/geometry/set/settle; the
# valid groups are kept through RESUME=1). The invalid results are kept beside as res/<g>.jsonl.invalid-<time>.
#
#   tools/bench/redo_invalid.sh <out-dir> <devices|pool[:N]> <ime-id> <geometry.json> <set> [run.sh args...]
#
# APP_APK / APP_COMMIT / AUTO_RESTART etc. as for run.sh. Exits 0 when nothing is invalid.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTD="${1:?out dir}"; shift
[ -f "$OUTD/groups.json" ] || { echo "!! $OUTD has no groups.json (run not finished?)"; exit 2; }
mapfile -t BAD < <(python3 -I -c 'import json,sys; [print(g["group"]) for g in json.load(open(sys.argv[1])) if not g["valid"]]' "$OUTD/groups.json")
[ ${#BAD[@]} -gt 0 ] || { echo "== $OUTD: all groups valid"; exit 0; }
ts=$(date +%H%M%S)
for g in "${BAD[@]}"; do
  [ -e "$OUTD/res/$g.jsonl" ] && mv "$OUTD/res/$g.jsonl" "$OUTD/res/$g.jsonl.invalid-$ts"
done
LOG="$OUTD.log"; [ -e "$LOG" ] && cp "$LOG" "$LOG.before-redo-$ts"
echo "== $OUTD: re-running ${#BAD[@]} invalid group(s): ${BAD[*]}"
RESUME=1 SKIP_BUILD=1 OUT="$OUTD" "$ROOT/tools/bench/run.sh" "$@" > "$LOG" 2>&1
grep -h "== groups\|invalid group" "$LOG" | cut -c1-200
