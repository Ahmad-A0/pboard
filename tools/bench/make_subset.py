#!/usr/bin/env python3
"""Deterministic stratified subset of a human set, for quick verdicts.

Usage: make_subset.py <set.jsonl[.gz]> <out.jsonl> <n-items> [--seed 1]

Every participant gets an equal share (round robin). Tap sets keep whole sentences (items whose id
shares the prefix before the last "/w<k>"), so typing context and chaining stay as in the full run;
swipe sets take single items. Order in the output = the full set's order (grouped by participant).
Also writes <out>.ids (the selected ids) so subset metrics can be recomputed from full-run results
(subset_track.py).
"""
import argparse
import collections
import gzip
import json
import random


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inp")
    ap.add_argument("out")
    ap.add_argument("n", type=int)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    opener = gzip.open if a.inp.endswith(".gz") else open
    lines = [l for l in opener(a.inp, "rt", encoding="utf-8") if l.strip()]
    items = [json.loads(l) for l in lines]
    tap = items[0].get("kind") == "tap"
    # units: sentences (tap) or single items (swipe), per participant, in file order
    units = collections.OrderedDict()
    for i, it in enumerate(items):
        p = it.get("participant", "?")
        key = it["id"].rsplit("/", 1)[0] if tap and "/" in it.get("id", "") else it.get("id", str(i))
        units.setdefault(p, collections.OrderedDict()).setdefault(key, []).append(i)
    rng = random.Random(a.seed)
    queues = {}
    for p, us in units.items():
        ks = list(us)
        rng.shuffle(ks)
        queues[p] = [us[k] for k in ks]
    chosen = set()
    parts = list(queues)
    while len(chosen) < a.n and any(queues.values()):
        for p in parts:
            if queues[p] and len(chosen) < a.n:
                chosen.update(queues[p].pop(0))
    sel = sorted(chosen)
    with open(a.out, "w", encoding="utf-8") as f:
        for i in sel:
            f.write(lines[i] if lines[i].endswith("\n") else lines[i] + "\n")
    with open(a.out + ".ids", "w") as f:
        f.write("\n".join(items[i]["id"] for i in sel) + "\n")
    print("%s: %d of %d items, %d participants" % (a.out, len(sel), len(items), len({items[i].get("participant") for i in sel})))


if __name__ == "__main__":
    main()
