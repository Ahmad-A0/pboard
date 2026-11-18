#!/usr/bin/env bash
# Validation gate for fast "done" detection (Replay -e settle fast) against the slow text-polling
# settle, per keyboard, on a 200-item TSI subset (4 participants x 50), gated, on the same devices:
#   Gboard: slow vs fast (item for item) and compare mode (false-early rate + time saved)
#   pboard: release slow vs bench slow (the bench build must decode exactly like release), bench
#           slow vs bench fast (item for item), and compare mode
#   tools/bench/settle_check.sh <dev1,dev2> <pboard-release.apk> <pboard-bench.apk> <commit> [label]
# Writes tools/bench/out/<label>settle_*/ and <label>settle_check.txt.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVS="$1"; REL="$2"; BEN="$3"; C="$4"; P="${5:+${5}_}"
R="$ROOT/tools/bench/run.sh"; O="$ROOT/tools/bench/out"; CMP="$ROOT/tools/bench/compare_results.py"
GB=(com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME "$ROOT/tools/bench/geometry/gboard_pixel8.json")
PB=(dev.pboard/.ime.PboardIme "$ROOT/tools/bench/geometry/pboard_pixel8.json")
SMALL="$O/shardtest_tsi200.jsonl"
mkdir -p "$O"
[ -s "$SMALL" ] || python3 -I - "$ROOT/tools/data/human/splits/v1/tap_tsi.test.jsonl.gz" "$SMALL" <<'EOF'
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
export SKIP_BUILD=1 AUTO_RESTART=1
run() { local name="$1"; shift; OUT="$O/${P}settle_$name" "$R" "$DEVS" "$@"; }
APP_APK="$REL" APP_COMMIT="$C" run gboard_slow "${GB[@]}" "$SMALL"
APP_APK="$REL" APP_COMMIT="$C" run gboard_fast "${GB[@]}" "$SMALL" settle fast
APP_APK="$REL" APP_COMMIT="$C" run gboard_compare "${GB[@]}" "$SMALL" settle compare
# the same groups on the devices in reverse order: every group lands on another device
APP_APK="$REL" APP_COMMIT="$C" OUT="$O/${P}settle_gboard_slow_swapped" "$R" "$(tr , "\n" <<<"$DEVS" | tac | paste -sd,)" "${GB[@]}" "$SMALL"
APP_APK="$REL" APP_COMMIT="$C" run pboard_release_slow "${PB[@]}" "$SMALL"
APP_APK="$BEN" APP_COMMIT="$C-bench" run pboard_bench_slow "${PB[@]}" "$SMALL"
APP_APK="$BEN" APP_COMMIT="$C-bench" run pboard_bench_fast "${PB[@]}" "$SMALL" settle fast
APP_APK="$BEN" APP_COMMIT="$C-bench" run pboard_bench_compare "${PB[@]}" "$SMALL" settle compare
{
  echo "== Gboard: slow vs fast";            python3 -I "$CMP" "$O/${P}settle_gboard_slow/results.jsonl" "$O/${P}settle_gboard_fast/results.jsonl"
  echo "== Gboard: across devices (slow, device order reversed)"; python3 -I "$CMP" "$O/${P}settle_gboard_slow/results.jsonl" "$O/${P}settle_gboard_slow_swapped/results.jsonl"
  echo "== pboard: release vs bench (slow)"; python3 -I "$CMP" "$O/${P}settle_pboard_release_slow/results.jsonl" "$O/${P}settle_pboard_bench_slow/results.jsonl"
  echo "== pboard: bench slow vs fast";      python3 -I "$CMP" "$O/${P}settle_pboard_bench_slow/results.jsonl" "$O/${P}settle_pboard_bench_fast/results.jsonl"
  for k in gboard pboard_bench; do
    echo "== $k compare mode (false-early = fast text != slow text)"
    python3 -I - "$O/${P}settle_${k}_compare/results.jsonl" <<'EOF'
import json, sys
rs = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
bad = [r for r in rs if r.get("fastAfter") is not None and r["fastAfter"] != r["after"]]
fm = sorted(r["fastMs"] for r in rs); sm = sorted(r["slowMs"] for r in rs)
med = lambda v: v[len(v) // 2] if v else float("nan")
print("items %d, false-early %d (%.1f %%), settle median fast %.0f ms vs slow %.0f ms" % (
    len(rs), len(bad), 100.0 * len(bad) / max(1, len(rs)), med(fm), med(sm)))
for r in bad[:5]:
    print("  %s: fast %r slow %r" % (r.get("id"), r["fastAfter"][-25:], r["after"][-25:]))
EOF
  done
  echo "== wall clock (active seconds per 100 items, from logs/timing.txt)"
  for d in "$O/${P}"settle_*/; do
    python3 -I - "$d" <<'EOF'
import re, sys, os
d = sys.argv[1]; s = n = 0
for line in open(os.path.join(d, "logs", "timing.txt")):
    kv = dict(re.findall(r"(\w+)=(\S+)", line)); s += int(kv["seconds"]); n += int(kv["items"])
print("%-28s %5d items  %6.0f s per 100 items (device-seconds)" % (os.path.basename(d.rstrip("/")), n, 100.0 * s / max(1, n)))
EOF
  done
} | tee "$O/${P}settle_check.txt"
