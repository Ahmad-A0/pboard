#!/usr/bin/env python3
"""Summarise a verdict directory (conformance gate + verdict subsets of one commit) into PASS/FAIL + a markdown table.

Usage: verdict_summary.py <dir> --sha S [--wall SECONDS] [--cpu PERCENT] [--last last-main.json]

Gate (deliberately on the metrics a 200-300 word subset measures reliably):
  FAIL if the conformance gate failed, if a run lost > 10 % of its items to invalid groups, or if on
  any set pboard's word accuracy fell below the last main verdict's by more than
  max(1.0 pp, 2 x the standard error of the difference). False corrections are reported with a 95 %
  Wilson interval but not gated (too few clean words in a subset).
Writes <dir>/verdict.json and prints the markdown summary.
"""
import argparse
import json
import math
import os

SETS = ["tap_tsi", "tap_vtap", "swipe_futo", "swipe_hws"]


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (100 * max(0.0, c - h), 100 * min(1.0, c + h))


def load(d, kb, s):
    p = os.path.join(d, "%s_%s" % (kb, s), "score.json")
    if not os.path.exists(p):
        return None
    j = json.load(open(p))
    k = j.get("tap") or j.get("swipe")
    if not k:
        return None
    gpath = os.path.join(d, "%s_%s" % (kb, s), "groups.json")
    groups = json.load(open(gpath)) if os.path.exists(gpath) else []
    total = sum(g["items"] for g in groups) or k["items"]
    out = {"items": k["items"], "total": total, "acc": 100 * k["wordAccuracy"], "wer": 100 * k["wer"]}
    if "cleanItems" in k:
        out["fc"] = 100 * k["falseCorrectionRate"]
        out["fc_ci"] = wilson(k["falseCorrections"], k["cleanItems"])
        out["fc_n"] = "%d/%d" % (k["falseCorrections"], k["cleanItems"])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--sha", required=True)
    ap.add_argument("--wall", type=int, default=0)
    ap.add_argument("--cpu", default="?")
    ap.add_argument("--last")
    a = ap.parse_args()
    last = json.load(open(a.last)) if a.last and os.path.exists(a.last) else None
    rc_path = os.path.join(a.dir, "conformance.rc")
    conf = open(rc_path).read().strip() if os.path.exists(rc_path) else "skipped"
    res = {"sha": a.sha, "wall": a.wall, "cpu": a.cpu, "conformance": conf, "sets": {}}
    fails = []
    if conf not in ("0", "skipped"):
        fails.append("conformance gate failed (rc %s)" % conf)
    rows = []
    for s in SETS:
        g, p = load(a.dir, "gboard", s), load(a.dir, "pboard", s)
        res["sets"][s] = {"gboard": g, "pboard": p}
        for kb, m in (("gboard", g), ("pboard", p)):
            if m is None:
                fails.append("%s %s: no results" % (kb, s))
            elif m["items"] < 0.9 * m["total"]:
                fails.append("%s %s: only %d/%d items in valid groups" % (kb, s, m["items"], m["total"]))
        vs = ""
        if p and last and last["sets"].get(s, {}).get("pboard"):
            lp = last["sets"][s]["pboard"]
            d = p["acc"] - lp["acc"]
            se = math.sqrt(p["acc"] * (100 - p["acc"]) / max(1, p["items"]) + lp["acc"] * (100 - lp["acc"]) / max(1, lp["items"]))
            tol = max(1.0, 2 * se)
            vs = "%+.2f (tol %.1f)" % (d, tol)
            if d < -tol:
                fails.append("%s: pboard accuracy %.2f%% vs last main %.2f%% (%.2f pp drop > %.1f)" % (s, p["acc"], lp["acc"], -d, tol))
        fc = ("%.2f%% [%.1f, %.1f] (%s)" % (p["fc"], p["fc_ci"][0], p["fc_ci"][1], p["fc_n"])) if p and "fc" in p else "-"
        rows.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            s, ("%.2f%%" % g["acc"]) if g else "-", ("%.2f%%" % p["acc"]) if p else "-",
            ("%+.2f" % (p["acc"] - g["acc"])) if g and p else "-", ("%.2f%%" % p["wer"]) if p else "-", fc, vs or "-"))
    res["pass"] = not fails
    res["fails"] = fails
    with open(os.path.join(a.dir, "verdict.json"), "w") as f:
        json.dump(res, f, indent=1)
    print("VERDICT: %s  (%s, %d s wall, farm CPU busy %s%%, conformance %s)" % (
        "PASS" if not fails else "FAIL", a.sha, a.wall, a.cpu, {"0": "pass", "skipped": "skipped"}.get(conf, "FAIL")))
    print()
    print("| set | Gboard 18.6 acc | pboard acc | pboard - Gboard | pboard WER | pboard false corr. (95% CI) | pboard acc vs last main |")
    print("|---|---|---|---|---|---|---|")
    print("\n".join(rows))
    if last:
        print("\nlast main verdict: %s" % last.get("sha"))
    for f in fails:
        print("- FAIL: %s" % f)


if __name__ == "__main__":
    main()
