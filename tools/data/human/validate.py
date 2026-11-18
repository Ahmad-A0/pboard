#!/usr/bin/env python3
"""Sanity checks for the built splits: projection fidelity and split hygiene.

Usage (repo root):  python3 -I tools/data/human/validate.py
  * tap traces: the key under each projected point on the target layout must equal the key the participant
    touched on their own keyboard (key-relative projection keeps which key was hit) -> agreement rate;
  * swipe traces: first/last projected samples land nearest the first/last letter -> rate;
  * splits: no participant / sender appears in both tune and test of a set; ids are unique.
"""
import collections
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
from common import LETTERS, OUT, SPLITS, VERSION, load_target, read_jsonl


def main():
    dst = load_target()
    ok = True
    files = sorted(glob.glob(os.path.join(SPLITS, VERSION, "*.jsonl.gz")) + glob.glob(os.path.join(OUT, VERSION, "*.jsonl.gz")))
    parts = collections.defaultdict(lambda: collections.defaultdict(set))
    for path in files:
        base = os.path.basename(path)
        setname, split = base.split(".")[0], base.split(".")[1]
        n = agree = tot = se_ok = 0
        ids = set()
        for it in read_jsonl(path):
            n += 1
            if it["id"] in ids:
                print("DUPLICATE id", it["id"], base)
                ok = False
            ids.add(it["id"])
            parts[setname][split].add(it.get("participant", ""))
            if it.get("kind") == "tap":
                for k, (x, y, _) in enumerate(it["points"][:-1]):
                    if it.get("offsetsKu") and it["offsetsKu"][k] is None:
                        continue
                    lit = it["literal"][k]
                    got = dst.literal(x, y)
                    got = " " if got == "space" else got
                    if got not in LETTERS and got not in (" ", "."):
                        got = "?"
                    tot += 1
                    agree += got == lit
            elif it.get("kind") == "swipe":
                ks = [c for c in it["target"].lower() if c in LETTERS]
                p0, p1 = it["points"][0], it["points"][-1]
                se_ok += dst.nearest_letter(p0[0], p0[1]) == ks[0] and dst.nearest_letter(p1[0], p1[1]) == ks[-1]
        extra = ""
        if tot:
            extra = "tap literal agreement on target %.4f" % (agree / tot)
        elif se_ok:
            extra = "swipe start+end on intended keys %.3f" % (se_ok / n)
        print("%-40s %6d items  %s" % (base, n, extra))
    for setname, sp in sorted(parts.items()):
        clash = sp["tune"] & sp["test"]
        clash.discard("")
        if clash:
            print("LEAK: %s has %d participants in both splits" % (setname, len(clash)))
            ok = False
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
