#!/usr/bin/env python3
"""Grammar-check thresholds: sweep on dev candidates, apply to test candidates, score.

Input: candidate dumps from `./gradlew :engine:gecEval` (every candidate fix with its LM score "ng" = n-gram,
"nn" = n-gram + LSTM) and the gold sets they were run on.

  sweep:  python3 -I tools/textintel/gec_tune.py sweep --cands cands_dev.jsonl --pos synth_chat_dev.jsonl \
              synthconf_chat_dev.jsonl --neg clean_chat_dev.jsonl --model nn --budget 0.1 --out thresholds.json
          For each rule group (a family, or a confusion pair "conf:then>than"), picks the lowest threshold whose
          false alarms on the clean (negative) sentences stay within --budget per 1,000 sentences, and reports
          the recall it keeps on the synthetic (positive) errors.
  apply:  python3 -I tools/textintel/gec_tune.py apply --cands cands_test.jsonl --thresholds thresholds.json \
              --model nn --gold bea_dev.jsonl clean_chat.jsonl ... [--show 20]
"""

import argparse
import collections
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gec_score  # noqa: E402

GRID = [-math.inf] + [x / 2 for x in range(-8, 41)] + [math.inf]


def load_cands(paths):
    out = {}
    for p in paths:
        for line in open(p, encoding="utf-8"):
            o = json.loads(line)
            out[o["id"]] = o["cands"]
    return out


def load_gold(paths):
    rows = []
    for p in paths:
        rows += [json.loads(l) for l in open(p, encoding="utf-8")]
    return rows


def group_of(c):
    return c["rule"] if c["family"] == "confusion" else c["family"]


def threshold_for(th, c):
    return th.get(c["rule"], th.get(c["family"], math.inf))


def predict(cands, th, model, screen=math.inf):
    preds = {}
    for sid, lst in cands.items():
        acc = []
        for c in lst:
            t = threshold_for(th, c)
            d = c.get(model)
            if t == math.inf:
                continue
            if t != -math.inf and (d is None or d < t):
                continue
            # GrammarConfig.screenMargin: the n-gram alone rejects what it puts far below the threshold
            if t != -math.inf and model == "nn" and c.get("ng") is not None and c["ng"] < t - screen:
                continue
            acc.append(c)
        # overlapping fixes: keep the surest
        acc.sort(key=lambda c: -(c.get(model) if c.get(model) is not None else 99))
        kept = []
        for c in acc:
            if all(not (k["cs"] < max(c["ce"], c["cs"] + 1) and c["cs"] < max(k["ce"], k["cs"] + 1)) for k in kept):
                kept.append(c)
        preds[sid] = [{"cs": c["cs"], "ce": c["ce"], "cor": c["cor"], "rule": c["rule"]} for c in kept]
    return preds


def gold_match(row, c):
    ts, te, cor = gec_score.to_tokens(row, c)
    for ann in row["annotators"]:
        for e in ann:
            if (e["ts"], e["te"], gec_score.norm(e["cor"])) == (ts, te, gec_score.norm(cor)):
                return True
    return False


def sweep(a):
    cands = load_cands(a.cands)
    pos_rows = load_gold(a.pos)
    neg_rows = load_gold(a.neg)
    n_neg = len(neg_rows)
    # positives: candidates that are the gold fix; for recall, the number of gold edits per group
    pos_scores = collections.defaultdict(list)
    neg_scores = collections.defaultdict(list)
    n_gold = collections.Counter()
    for row in pos_rows:
        fam = row.get("family", "?")
        n_gold[fam] += 1
        for c in cands.get(row["id"], []):
            if gold_match(row, c):
                pos_scores[group_of(c)].append(c.get(a.model))
                break
    for row in neg_rows:
        for c in cands.get(row["id"], []):
            neg_scores[group_of(c)].append(c.get(a.model))
    th = {}
    report = []
    groups = sorted(set(pos_scores) | set(neg_scores))
    for g in groups:
        ps = [x for x in pos_scores[g] if x is not None]
        ns = [x for x in neg_scores[g] if x is not None]
        gold = n_gold.get(g, 0) or n_gold.get(g.split(":")[0], 0)
        best = None
        for t in GRID:
            fa = sum(1 for x in ns if x >= t)
            if 1000.0 * fa / n_neg <= a.budget:
                best = t
                break
        th[g] = best
        tp = sum(1 for x in ps if best is not None and x >= best)
        report.append((g, best, tp, len(ps), gold, sum(1 for x in ns if best is not None and x >= best), len(ns)))
    for g, t, tp, np_, gold, fa, nn in report:
        print(f"{g:28s} th={t!s:6s} recall={tp:4d}/{gold:4d} (cands {np_:4d})  FA={fa:3d}/{nn:5d} cands on {n_neg} clean")
    if a.out:
        json.dump({k: (v if v is not None and math.isfinite(v) else ("inf" if v is None or v > 0 else "-inf"))
                   for k, v in th.items()}, open(a.out, "w"), indent=1, sort_keys=True)


def read_thresholds(path):
    raw = json.load(open(path))
    return {k: (math.inf if v == "inf" else -math.inf if v == "-inf" else float(v)) for k, v in raw.items()}


def apply(a):
    cands = load_cands(a.cands)
    th = read_thresholds(a.thresholds)
    preds = predict(cands, th, a.model, a.screen)
    results = {}
    for gpath in a.gold:
        rows = [json.loads(l) for l in open(gpath, encoding="utf-8")]
        rows = [r for r in rows if r["id"] in cands]
        if not rows:
            continue
        res, fps, oks, tg, tf = gec_score.score(rows, preds, a.show, a.show_ok)
        name = rows[0]["set"]
        results[name] = res
        c, d = res["correction"], res["detection"]
        print(f"{name:22s} n={res['sentences']:5d} flags={res['edits']:4d} ({res['per_1k_sentences']:6.1f}/1k)  "
              f"corr P={c['P']:.3f} R={c['R']:.3f} F0.5={c['F0.5']:.3f}  det P={d['P']:.3f} R={d['R']:.3f} F0.5={d['F0.5']:.3f}")
        if a.rules:
            for r, v in res["rules"].items():
                print(f"    {r:26s} n={v['n']:4d} correct={v['correct']:4d} detected={v['detected']:4d}")
        if a.families and rows[0].get("family"):
            fam_n = collections.Counter(r["family"] for r in rows)
            fam_hit = collections.Counter()
            for r in rows:
                for e in preds.get(r["id"], []):
                    if gold_match(r, e):
                        fam_hit[r["family"]] += 1
                        break
            for f in sorted(fam_n):
                print(f"    family {f:24s} recall {fam_hit[f]:4d}/{fam_n[f]:4d} = {fam_hit[f] / fam_n[f]:.3f}")
        for t, s, cor, rule in fps:
            print(f"    FA [{rule}] {s!r} -> {cor!r} :: {t}")
        for t, s, cor, rule in oks:
            print(f"    OK [{rule}] {s!r} -> {cor!r} :: {t}")
    if a.json:
        json.dump(results, open(a.json, "w"), indent=1)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sweep")
    s.add_argument("--cands", nargs="+", required=True)
    s.add_argument("--pos", nargs="+", required=True)
    s.add_argument("--neg", nargs="+", required=True)
    s.add_argument("--model", default="nn")
    s.add_argument("--budget", type=float, default=0.1, help="false alarms per 1,000 clean sentences, per group")
    s.add_argument("--out")
    p = sub.add_parser("apply")
    p.add_argument("--cands", nargs="+", required=True)
    p.add_argument("--thresholds", required=True)
    p.add_argument("--model", default="nn")
    p.add_argument("--gold", nargs="+", required=True)
    p.add_argument("--show", type=int, default=0)
    p.add_argument("--show-ok", type=int, default=0)
    p.add_argument("--rules", action="store_true")
    p.add_argument("--families", action="store_true")
    p.add_argument("--json")
    p.add_argument("--screen", type=float, default=math.inf, help="n-gram screening margin (nats), as in the engine")
    a = ap.parse_args()
    sweep(a) if a.cmd == "sweep" else apply(a)


if __name__ == "__main__":
    main()
