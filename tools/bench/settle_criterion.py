#!/usr/bin/env python3
"""Fast-settle acceptance for a nondeterministic IME (Gboard): fast-vs-slow per-item disagreement must
not exceed slow-vs-slow disagreement (two slow runs of the same items, e.g. with the device order
reversed), judged with a per-participant bootstrap 95 % CI on the difference, on >= 400 items; and
compare mode must show no false-early item.

Usage: settle_criterion.py SLOW_A/results.jsonl SLOW_B/results.jsonl FAST/results.jsonl
                           [--compare COMPARE/results.jsonl] [--min-items 400] [--reps 2000]
Exit 0 = fast settle allowed.
"""
import argparse
import collections
import json
import random


def load(p):
    return {r.get("id"): r for r in (json.loads(l) for l in open(p) if l.strip())}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("slow_a")
    ap.add_argument("slow_b")
    ap.add_argument("fast")
    ap.add_argument("--compare")
    ap.add_argument("--min-items", type=int, default=400)
    ap.add_argument("--reps", type=int, default=2000)
    a = ap.parse_args()
    A, B, F = load(a.slow_a), load(a.slow_b), load(a.fast)
    ids = sorted(set(A) & set(B) & set(F))
    parts = collections.defaultdict(list)
    for i in ids:
        parts[A[i].get("participant", "?")].append(i)
    pk = sorted(parts)

    def rates(sel):
        n = len(sel) or 1
        ss = sum(A[i]["after"] != B[i]["after"] for i in sel) / n
        fs = sum(A[i]["after"] != F[i]["after"] for i in sel) / n
        return 100 * ss, 100 * fs

    ss, fs = rates(ids)
    rng = random.Random(1)
    diffs = []
    for _ in range(a.reps):
        sel = [i for p in (rng.choice(pk) for _ in pk) for i in parts[p]]
        s, f = rates(sel)
        diffs.append(f - s)
    diffs.sort()
    lo, hi = diffs[int(0.025 * (len(diffs) - 1))], diffs[int(0.975 * (len(diffs) - 1))]
    early = None
    if a.compare:
        C = [json.loads(l) for l in open(a.compare) if l.strip()]
        early = sum(1 for r in C if r.get("fastAfter") is not None and r["fastAfter"] != r["after"])
    ok = len(ids) >= a.min_items and lo <= 0 and (early in (None, 0))
    print("items %d (%d participants); slow-vs-slow disagreement %.2f %%, fast-vs-slow %.2f %%, "
          "difference %+.2f pp [95%% CI %+.2f, %+.2f]; false-early %s -> fast settle %s"
          % (len(ids), len(pk), ss, fs, fs - ss, lo, hi, "n/a" if early is None else early, "ALLOWED" if ok else "NOT allowed"))
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
