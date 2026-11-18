#!/usr/bin/env python3
"""Scores grammar-check predictions against the sets built by gec_data.py.

Predictions: JSONL, one line per sentence id: {"id", "edits": [{"cs", "ce", "cor", "rule"}]} with character
offsets into the set's "text" (an insertion has cs == ce). Sentences without a line have no edits.

Metrics (the ERRANT/M2 conventions, simplified to exact edit matching):
  * correction: an edit is right when its token span and its correction (whitespace removed) equal a gold edit
    of the chosen annotator;
  * detection: an edit is right when its token span overlaps a gold edit's span (the problem was found, the
    suggestion may differ);
  * per sentence the annotator that gives the best running F0.5 is chosen (as the M2 scorer does);
  * clean sets (no gold edits): every edit is a false alarm; reported per 1,000 sentences.
Per-rule precision uses the predictions' "rule" field.

    python3 -I tools/textintel/gec_score.py --gold tools/data/work/textintel/bea_dev.jsonl --pred pred.jsonl [--show 20]
"""

import argparse
import collections
import json


def f_beta(tp, fp, fn, beta=0.5):
    p = tp / (tp + fp) if tp + fp else 1.0
    r = tp / (tp + fn) if tp + fn else 1.0
    f = (1 + beta * beta) * p * r / (beta * beta * p + r) if p + r else 0.0
    return p, r, f


def to_tokens(row, e):
    """Projects a character edit onto the sentence's token span; returns (ts, te, corrected token text)."""
    spans = row["spans"]
    text = row["text"]
    cs, ce = e["cs"], e["ce"]
    if cs == ce:
        ts = next((i for i, (a, b) in enumerate(spans) if a >= cs), len(spans))
        return ts, ts, e["cor"]
    ts = next((i for i, (a, b) in enumerate(spans) if b > cs), len(spans))
    te = ts
    while te < len(spans) and spans[te][0] < ce:
        te += 1
    if te == ts:
        return ts, ts, e["cor"]
    full = text[spans[ts][0]:cs] + e["cor"] + text[ce:spans[te - 1][1]]
    return ts, te, full


def norm(s):
    return "".join(s.split()).replace("’", "'")


def overlap(a, b):
    a0, a1 = a[0], max(a[1], a[0] + 1)
    b0, b1 = b[0], max(b[1], b[0] + 1)
    return max(a0, b0) < min(a1, b1)


def score(gold_rows, preds, show=0, show_ok=0):
    tot = collections.Counter()
    rules = collections.defaultdict(collections.Counter)
    types_gold = collections.Counter()
    types_found = collections.Counter()
    fps = []
    oks = []
    n = 0
    for row in gold_rows:
        n += 1
        hyp = [(*to_tokens(row, e), e.get("rule", "?"), e) for e in preds.get(row["id"], [])]
        best = None
        for ann in row["annotators"]:
            g = [(x["ts"], x["te"], norm(x["cor"]), x["type"]) for x in ann]
            gset = {(a, b, c) for a, b, c, _ in g}
            ctp = sum(1 for (ts, te, cor, _r, _e) in hyp if (ts, te, norm(cor)) in gset)
            dtp = sum(1 for (ts, te, _c, _r, _e) in hyp if any(overlap((ts, te), (a, b)) for a, b, _, _ in g))
            dfound = sum(1 for (a, b, _, _) in g if any(overlap((ts, te), (a, b)) for ts, te, _c, _r, _e in hyp))
            cur = (ctp, len(hyp) - ctp, len(g) - ctp)
            p, r, f = f_beta(tot["ctp"] + cur[0], tot["cfp"] + cur[1], tot["cfn"] + cur[2])
            key = (f, ctp, -cur[1] - cur[2])
            if best is None or key > best[0]:
                best = (key, g, gset, cur, dtp, dfound)
        _, g, gset, cur, dtp, dfound = best
        tot["ctp"] += cur[0]; tot["cfp"] += cur[1]; tot["cfn"] += cur[2]
        tot["dtp"] += dtp; tot["dfp"] += len(hyp) - dtp; tot["dfn"] += len(g) - dfound
        tot["edits"] += len(hyp)
        for a, b, c, t in g:
            types_gold[t] += 1
            if any(overlap((ts, te), (a, b)) for ts, te, _c, _r, _e in hyp):
                types_found[t] += 1
        for (ts, te, cor, rule, e) in hyp:
            ok = (ts, te, norm(cor)) in gset
            det = any(overlap((ts, te), (a, b)) for a, b, _, _ in g)
            rules[rule]["n"] += 1
            rules[rule]["c"] += ok
            rules[rule]["d"] += det
            if not det:
                fps.append((row["text"], row["text"][e["cs"]:e["ce"]], e["cor"], rule))
            elif ok:
                oks.append((row["text"], row["text"][e["cs"]:e["ce"]], e["cor"], rule))
    cp, cr, cf = f_beta(tot["ctp"], tot["cfp"], tot["cfn"])
    dp, dr, df = f_beta(tot["dtp"], tot["dfp"], tot["dfn"])
    out = {
        "sentences": n, "edits": tot["edits"], "per_1k_sentences": round(1000 * tot["edits"] / max(n, 1), 2),
        "correction": {"P": round(cp, 4), "R": round(cr, 4), "F0.5": round(cf, 4), "tp": tot["ctp"], "fp": tot["cfp"], "fn": tot["cfn"]},
        "detection": {"P": round(dp, 4), "R": round(dr, 4), "F0.5": round(df, 4), "tp": tot["dtp"], "fp": tot["dfp"], "fn": tot["dfn"]},
        "rules": {r: {"n": c["n"], "correct": c["c"], "detected": c["d"]} for r, c in sorted(rules.items(), key=lambda x: -x[1]["n"])},
    }
    return out, fps[:show], oks[:show_ok], types_gold, types_found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", required=True, nargs="+")
    ap.add_argument("--pred", required=True)
    ap.add_argument("--show", type=int, default=0, help="print this many false alarms")
    ap.add_argument("--show-ok", type=int, default=0, help="print this many correct edits")
    ap.add_argument("--types", action="store_true", help="recall per gold error type")
    ap.add_argument("--json", help="write the metrics here")
    a = ap.parse_args()
    preds = {}
    for line in open(a.pred, encoding="utf-8"):
        o = json.loads(line)
        preds[o["id"]] = o["edits"]
    results = {}
    for gpath in a.gold:
        rows = [json.loads(l) for l in open(gpath, encoding="utf-8")]
        name = rows[0]["set"] if rows else gpath
        res, fps, oks, tg, tf = score(rows, preds, a.show, a.show_ok)
        results[name] = res
        c, d = res["correction"], res["detection"]
        print(f"{name:20s} n={res['sentences']:5d} edits={res['edits']:5d} ({res['per_1k_sentences']}/1k)  "
              f"corr P={c['P']:.3f} R={c['R']:.3f} F0.5={c['F0.5']:.3f}  det P={d['P']:.3f} R={d['R']:.3f} F0.5={d['F0.5']:.3f}")
        for r, v in res["rules"].items():
            print(f"    {r:28s} n={v['n']:4d} correct={v['correct']:4d} detected={v['detected']:4d}")
        for t, s, cor, rule in fps:
            print(f"    FA [{rule}] {s!r} -> {cor!r} :: {t}")
        for t, s, cor, rule in oks:
            print(f"    OK [{rule}] {s!r} -> {cor!r} :: {t}")
        if a.types:
            for t, k in tg.most_common(40):
                print(f"    type {t:16s} gold={k:5d} found={tf[t]:4d}")
    if a.json:
        json.dump(results, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
