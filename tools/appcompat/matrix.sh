#!/usr/bin/env bash
# Both keyboards through the app matrix on one leased pool device, field by field: Gboard, then pboard (pm clear'ed
# before each of its fields), so the comparison grows as the run goes. The comparison is rewritten after each field.
#   tools/appcompat/matrix.sh <serial> <out-dir> [fields a,b (default: all)] [scenarios x,y]
# Typically on the emulator farm inside a lease:
#   tools/farm/pool.sh lease 1 -j appcompat -- tools/appcompat/matrix.sh @DEVICES@ ~/appcompat-out/run1
# PBOARD_ONLY=1 reruns only pboard (e.g. a fixed build) against an existing Gboard run in <out-dir>/gboard.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
S="$1"; OUT="$2"; F="${3:-}"; SC="${4:-}"
mkdir -p "$OUT"
[ -n "$F" ] || F="$(python3 -I -c 'import sys; sys.path.insert(0, sys.argv[1]); import apps; print(",".join(f["id"] for f in apps.FIELDS))' "$HERE")"
for f in ${F//,/ }; do
  [ "${PBOARD_ONLY:-0}" = 1 ] || python3 -I "$HERE/run.py" --serial "$S" --ime gboard --out "$OUT/gboard" --fields "$f" --scenarios "$SC" --append
  python3 -I "$HERE/run.py" --serial "$S" --ime pboard --out "$OUT/pboard" --fields "$f" --scenarios "$SC" --append
  python3 -I "$HERE/compare.py" "$OUT/gboard" "$OUT/pboard" --md "$OUT/compare.md" --json "$OUT/compare.json" > /dev/null
done
cat "$OUT/compare.md"
