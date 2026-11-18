#!/usr/bin/env python3
"""Deletes a run's IME-area images that no divergence cites (analysis features stay in the cache).

  prune.py <runs-dir> [--keep 5]    runs-dir holds gboard/, pboard/ and divergences.json

Keeps the images of the first --keep examples of every cluster (both keyboards), so the report's evidence
can still be looked at; everything else is removed (the disk the harness runs on is shared and small).
"""
import argparse
import json
import os


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs")
    ap.add_argument("--keep", type=int, default=5)
    a = ap.parse_args()
    keep = set()
    p = os.path.join(a.runs, "divergences.json")
    if os.path.exists(p):
        for c in json.load(open(p))["clusters"]:
            for e in c["examples"][:a.keep]:
                for side in ("G", "P"):
                    if e.get(side) and e[side].get("ime"):
                        keep.add(e[side]["ime"])
    freed = n = 0
    for ime in ("gboard", "pboard"):
        d = os.path.join(a.runs, ime, "img")
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if f.endswith(".png") and f[:-4] not in keep:
                freed += os.path.getsize(os.path.join(d, f))
                os.remove(os.path.join(d, f))
                n += 1
    print("pruned %d images (%.1f MB), kept %d" % (n, freed / 1e6, len(keep)))


if __name__ == "__main__":
    main()
