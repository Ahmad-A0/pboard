#!/usr/bin/env python3
"""Regression gate: compares a run's divergence clusters with a committed baseline.

  gate.py <divergences.json> <baseline-clusters.json> [--update]

Exit 1 when a cluster of a behavioural kind (text, cursor, keyboard, focus) that is not allowlisted appears
and is not in the baseline. Clusters that disappeared are reported as fixed. --update rewrites the baseline.
Signatures are compared with digits normalised (counts vary between runs). Baseline keys starting with "_" are notes
(e.g. why a cluster was accepted): ignored by the comparison and kept by --update.
"""
import argparse
import json
import os
import re

GATED = ("text", "cursor", "keyboard", "focus", "editor-events")


def key(c):
    return c["kind"] + "|" + re.sub(r"\d+", "#", re.sub(r"\s*\[real-time\]$", "", c["signature"]))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("divergences")
    ap.add_argument("baseline")
    ap.add_argument("--update", action="store_true")
    ap.add_argument("--subset", help="scenarios.jsonl: only divergences inside these scenarios' steps count")
    a = ap.parse_args()
    res = json.load(open(a.divergences))
    if a.subset:
        # baseline for a smaller mode (e.g. quick) from a bigger run: quick scenarios are prefixes of full ones
        steps = {}
        for l in open(a.subset):
            if l.strip():
                r = json.loads(l)
                steps[r["id"]] = len(r["steps"])
        now = {}
        for d in res["divergences"]:
            if d.get("allowed") or d["sc"] not in steps or d["step"] >= steps[d["sc"]]:
                continue
            now.setdefault(key(d), set()).add(d["sc"])
        now = {k: len(v) for k, v in now.items()}
    else:
        cl = [c for c in res["clusters"] if not c.get("allowed")]
        now = {key(c): len(c["scenarios"]) for c in cl}
    if a.update or not os.path.exists(a.baseline):
        os.makedirs(os.path.dirname(os.path.abspath(a.baseline)), exist_ok=True)
        notes = {k: v for k, v in (json.load(open(a.baseline)) if os.path.exists(a.baseline) else {}).items() if k.startswith("_")}
        json.dump(dict(sorted({**notes, **now}.items())), open(a.baseline, "w"), indent=1, ensure_ascii=False)
        print("== gate: baseline written (%d clusters) -> %s" % (len(now), a.baseline))
        return
    base = {k: v for k, v in json.load(open(a.baseline)).items() if not k.startswith("_")}
    new = [k for k in now if k not in base]
    gone = [k for k in base if k not in now]
    bad = [k for k in new if k.split("|")[0] in GATED]
    for k in sorted(new):
        print("NEW   %s (%d scenarios)%s" % (k, now[k], "  <- gated" if k in bad else ""))
    for k in sorted(gone):
        print("GONE  %s" % k)
    print("== gate: %d clusters, %d new (%d gated), %d gone" % (len(now), len(new), len(bad), len(gone)))
    raise SystemExit(1 if bad else 0)


if __name__ == "__main__":
    main()
