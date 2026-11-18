#!/usr/bin/env python3
"""Per-level summary of a tools/farm/ramp.sh run.

Usage: ramp_report.py <ramp-out-dir>

Host side (samples.tsv, 5 s samples): median and max of swap-in pages/s, PSI memory some/full avg10,
load1, MemAvailable. Workload side (res/*.jsonl, passes that started and ended on the same level):
Gboard decode latency (fastMs: injection end -> IME quiet), injection stall p95, timing-degraded
items, and the share of items whose output differs from the same item at the lowest level (an
overloaded Gboard commits literal words, so outputs drift before anything crashes).
"""
import collections
import csv
import glob
import json
import os
import statistics as st
import sys


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q * (len(v) - 1) + 0.5))] if v else float("nan")


def main():
    d = sys.argv[1]
    S = collections.defaultdict(list)
    with open(os.path.join(d, "samples.tsv")) as f:
        for r in csv.DictReader(f, delimiter="\t"):
            S[int(r["level"])].append(r)
    I = collections.defaultdict(list)
    for p in glob.glob(os.path.join(d, "res", "*.jsonl")):
        lv = p[:-len(".jsonl")] + ".lvl"
        if not os.path.exists(lv):
            continue
        a, b = open(lv).read().split()
        if a != b:
            continue
        dev = os.path.basename(p).split(".")[0]
        for line in open(p):
            if line.strip():
                r = json.loads(line)
                r["_dev"] = dev
                I[int(a)].append(r)
    if not S:
        print("no samples"); return
    base_lv = min(I) if I else None
    base = {}
    if base_lv is not None:
        for r in I[base_lv]:
            base.setdefault(r["i"], collections.Counter())[r["after"]] += 1
    print("| level | instances | swap-in/s med (max) | PSI some10 med (max) | PSI full10 max | load med (max) | MemAvail GB min "
          "| items | decode ms p50 / p95 | stall p95 ms | degraded | output drift vs level %s |" % base_lv)
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for lv in sorted(S):
        s = S[lv]
        f = lambda k: [float(r[k]) for r in s]
        items = I.get(lv, [])
        fast = [r["fastMs"] for r in items if r.get("fastMs") is not None]
        stall = [r.get("stallMs", 0) for r in items]
        deg = sum(1 for r in items if r.get("timingDegraded"))
        drift = sum(1 for r in items if r["i"] in base and base[r["i"]].most_common(1)[0][0] != r["after"])
        print("| %d | %s | %.0f (%.0f) | %.1f (%.1f) | %.1f | %.0f (%.0f) | %.1f | %d | %.0f / %.0f | %.0f | %d | %s |" % (
            lv, s[-1]["instances"], st.median(f("swapin_per_s")), max(f("swapin_per_s")),
            st.median(f("psi_some10")), max(f("psi_some10")), max(f("psi_full10")),
            st.median(f("load1")), max(f("load1")), min(f("memavail_gb")), len(items),
            pct(fast, 0.5), pct(fast, 0.95), pct(stall, 0.95), deg,
            ("%.1f%%" % (100.0 * drift / len(items))) if items else "-"))


if __name__ == "__main__":
    main()
