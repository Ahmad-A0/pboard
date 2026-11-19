#!/usr/bin/env python3
"""Gboard vs pboard side by side with per-participant (cluster) bootstrap 95 % CIs.

Usage: headline.py --gboard G/results_valid.jsonl --pboard P/results_valid.jsonl [--set G/set.jsonl
        --geometry geo.json] [--noise A/results.jsonl B/results.jsonl] [--reps 2000] [--label NAME]
        [--names A B]   (any two keyboards: --gboard is A, --pboard is B)

Metrics per keyboard: word accuracy, WER, false-correction rate (clean words), noisy recovery and,
for tap sets with terminators, accuracy on words whose terminator hit the space bar. CIs resample
participants with replacement (items of one person are correlated). The delta (pboard - Gboard) is
bootstrapped on the same participant draw (paired). --noise gives two Gboard runs of the same items
(e.g. slow vs slow with the device order reversed): their accuracy difference and per-item
disagreement are the noise floor. A delta is called real only if its CI excludes 0 AND its size
exceeds the noise floor.
"""
import argparse
import collections
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score  # noqa: E402


def load(p):
    return [json.loads(l) for l in open(p) if l.strip()]


def per_item(recs, lits):
    """id -> (participant, ok, edits, words, clean(True/False/None), term_ok)"""
    out = {}
    for r in recs:
        hyp, _ = score.produced(r)
        ref = score.norm_tokens(r["target"])
        lit = lits.get(r.get("id")) if lits else None
        clean = score.is_clean(r, lit) if r["kind"] == "tap" else None
        term = r.get("terminatorKeyOnTarget", "space") in ("space", ".")
        out[r.get("id", r["i"])] = (r.get("participant", "?"), hyp == ref, score.edit_distance(ref, hyp), len(ref), clean, term)
    return out


def metrics(items, ids):
    n = ok = ed = w = cn = cb = nn = nok = tn = tok = 0
    for i in ids:
        p, k, e, words, c, t = items[i]
        n += 1; ok += k; ed += e; w += words
        if c is True:
            cn += 1; cb += not k
        elif c is False:
            nn += 1; nok += k
        if t:
            tn += 1; tok += k
    m = {"acc": 100.0 * ok / max(1, n), "wer": 100.0 * ed / max(1, w)}
    if cn + nn:
        m["fc"] = 100.0 * cb / max(1, cn)
        m["rec"] = 100.0 * nok / max(1, nn)
    if tn and tn != n:
        m["acc_term"] = 100.0 * tok / tn
    return m


def ci(vals):
    v = sorted(vals)
    return (v[int(0.025 * (len(v) - 1))], v[int(0.975 * (len(v) - 1))])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gboard", required=True)
    ap.add_argument("--pboard", required=True)
    ap.add_argument("--set")
    ap.add_argument("--geometry")
    ap.add_argument("--noise", nargs=2)
    ap.add_argument("--reps", type=int, default=2000)
    ap.add_argument("--label", default="")
    ap.add_argument("--json")
    ap.add_argument("--names", nargs=2, default=["Gboard", "pboard"], metavar=("A", "B"),
                    help="column names when --gboard / --pboard hold two other keyboards (B - A is reported)")
    a = ap.parse_args()
    na, nb = a.names
    lits = score.literals_on_geometry(a.set, a.geometry) if a.set and a.geometry else None
    G, P = per_item(load(a.gboard), lits), per_item(load(a.pboard), lits)
    ids = sorted(set(G) & set(P))
    by_part = collections.defaultdict(list)
    for i in ids:
        by_part[G[i][0]].append(i)
    parts = sorted(by_part)
    mg, mp = metrics(G, ids), metrics(P, ids)
    keys = [k for k in ("acc", "wer", "fc", "rec", "acc_term") if k in mg and k in mp]
    rng = random.Random(1)
    boot = {k: ([], [], []) for k in keys}
    for _ in range(a.reps):
        draw = [i for p in (rng.choice(parts) for _ in parts) for i in by_part[p]]
        bg, bp = metrics(G, draw), metrics(P, draw)
        for k in keys:
            if k in bg and k in bp:
                boot[k][0].append(bg[k]); boot[k][1].append(bp[k]); boot[k][2].append(bp[k] - bg[k])
    noise = None
    if a.noise:
        A, B = per_item(load(a.noise[0]), lits), per_item(load(a.noise[1]), lits)
        nid = sorted(set(A) & set(B))
        dis = sum(A[i][1] != B[i][1] for i in nid)
        noise = {"items": len(nid), "acc_diff": abs(metrics(A, nid)["acc"] - metrics(B, nid)["acc"]),
                 "disagree": 100.0 * dis / max(1, len(nid))}
    names = {"acc": "word accuracy", "wer": "WER", "fc": "false corrections", "rec": "noisy recovery",
             "acc_term": "accuracy, terminator on bar"}
    print("%s: %d items, %d participants (paired, both keyboards on every item)" % (a.label, len(ids), len(parts)))
    print("| metric | %s | %s | %s - %s | real? |" % (na, nb, nb, na))
    print("|---|---|---|---|---|")
    res = {"label": a.label, "items": len(ids), "participants": len(parts), "metrics": {}, "noise": noise}
    for k in keys:
        cg, cp, cd = ci(boot[k][0]), ci(boot[k][1]), ci(boot[k][2])
        d = mp[k] - mg[k]
        floor = noise["acc_diff"] if noise and k in ("acc", "acc_term") else 0.0
        real = (cd[0] > 0 or cd[1] < 0) and abs(d) > floor
        print("| %s | %.2f%% [%.2f, %.2f] | %.2f%% [%.2f, %.2f] | %+.2f [%+.2f, %+.2f] | %s |" % (
            names[k], mg[k], cg[0], cg[1], mp[k], cp[0], cp[1], d, cd[0], cd[1], "yes" if real else "no"))
        res["metrics"][k] = {"gboard": mg[k], "gboard_ci": cg, "pboard": mp[k], "pboard_ci": cp, "delta": d, "delta_ci": cd, "real": real}
    if noise:
        print("\nnoise floor (two Gboard runs of the same %d items): accuracy differs by %.2f pp, %.2f %% of items disagree"
              % (noise["items"], noise["acc_diff"], noise["disagree"]))
    if a.json:
        json.dump(res, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
