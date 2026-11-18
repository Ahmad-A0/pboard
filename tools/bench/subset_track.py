#!/usr/bin/env python3
"""How well does a verdict subset track the full set? Recomputes the headline metrics of full-run
results on the subset's ids only and prints full vs subset side by side.

Usage: subset_track.py <subset.ids> NAME=<full-run results.jsonl> [NAME=...]
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score  # noqa: E402


def metrics(recs):
    kind = recs[0]["kind"] if recs else "tap"
    rs = [r for r in recs if r["kind"] == kind]
    k = score.stats(rs, kind, None)
    out = {"items": k["items"], "acc": 100 * k["wordAccuracy"], "wer": 100 * k["wer"]}
    if "falseCorrectionRate" in k:
        out["fc"] = 100 * k["falseCorrectionRate"]
    return out


def main():
    ids = {l.strip() for l in open(sys.argv[1]) if l.strip()}
    print("%-24s %22s %22s" % ("run", "full: items acc% fc%", "subset: items acc% fc%"))
    for arg in sys.argv[2:]:
        name, path = arg.split("=", 1)
        recs = [json.loads(l) for l in open(path) if l.strip()]
        full, sub = metrics(recs), metrics([r for r in recs if r.get("id") in ids])
        f = lambda m: "%5d %6.2f %6s" % (m["items"], m["acc"], ("%.2f" % m["fc"]) if "fc" in m else "-")
        print("%-24s %22s %22s" % (name, f(full), f(sub)))


if __name__ == "__main__":
    main()
