#!/usr/bin/env python3
"""Threshold sweep on the dev sets and score tables on every set, with tools/textintel/gec_score.py.

    python3 evaluate.py sweep --cands cands_dev.jsonl --dir <textintel jsonl dir> --out sweep.json
    python3 evaluate.py table --cands cands_all.jsonl --dir <textintel jsonl dir> --thresholds 0.5 0.9 0.97 \
        --score tag --pred-dir <dir> --out table.md

Run from the repository root (gec_score.py is imported from tools/textintel).
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.getcwd(), "tools", "textintel"))
from gec_score import score  # noqa: E402

DEV = ["synth_chat_dev", "synthconf_chat_dev", "synth_web_dev", "clean_chat_dev", "clean_web_dev", "clean_tatoeba_dev",
       "clean_wow_dev"]
TEST = ["bea_dev", "bea_dev_native", "conll14", "synth_chat_test", "clean_chat", "clean_dailydialog",
        "clean_nussms_us", "clean_web_gb", "clean_web_us"]


def load_cands(path):
    c = {}
    for line in open(path, encoding="utf-8"):
        o = json.loads(line)
        c[o["id"]] = o["cands"]
    return c


def conf(e, how):
    return e["tag"] if how == "tag" else e["det"] if how == "det" else min(e["tag"], e["det"])


def preds_at(cands, thr, how):
    return {k: [dict(cs=e["cs"], ce=e["ce"], cor=e["cor"], rule=e["rule"], conf=round(conf(e, how), 4))
                for e in v if conf(e, how) >= thr] for k, v in cands.items()}


def run(sets, d, cands, thr, how):
    out = {}
    for s in sets:
        rows = [json.loads(l) for l in open(os.path.join(d, s + ".jsonl"), encoding="utf-8")]
        res, *_ = score(rows, preds_at(cands, thr, how))
        out[s] = res
    return out


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sweep")
    s.add_argument("--cands", required=True)
    s.add_argument("--dir", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--scores", nargs="+", default=["tag"], help="confidence: tag, det or min (see predict.py)")
    t = sub.add_parser("table")
    t.add_argument("--cands", required=True)
    t.add_argument("--dir", required=True)
    t.add_argument("--thresholds", type=float, nargs="+", required=True)
    t.add_argument("--score", default="tag")
    t.add_argument("--sets", nargs="+", default=TEST)
    t.add_argument("--pred-dir")
    t.add_argument("--out", required=True)
    a = ap.parse_args()
    cands = load_cands(a.cands)
    if a.cmd == "sweep":
        res = []
        for how in a.scores:
            for thr in (0.5, 0.7, 0.8, 0.9, 0.95, 0.97, 0.98, 0.99, 0.995, 0.998, 0.999, 0.9995, 0.9998, 0.9999):
                r = run(DEV, a.dir, cands, thr, how)
                row = {"score": how, "thr": thr}
                for k, v in r.items():
                    row[k] = {"per1k": v["per_1k_sentences"], "cP": v["correction"]["P"], "cR": v["correction"]["R"],
                              "dP": v["detection"]["P"], "dR": v["detection"]["R"]}
                res.append(row)
                print(f"{how:3s} {thr:.3f}  " + "  ".join(
                    f"{k}: " + (f"{v['per1k']:.1f}/1k" if k.startswith("clean") else
                                f"cP={v['cP']:.3f} cR={v['cR']:.3f} dP={v['dP']:.3f}") for k, v in row.items()
                    if isinstance(v, dict)), flush=True)
        json.dump(res, open(a.out, "w"), indent=1)
    else:
        lines = ["| set | thr | n | flags/1k | corr P | corr R | corr F0.5 | det P | det R | det F0.5 |",
                 "|---|---|---|---|---|---|---|---|---|---|"]
        allres = {}
        for thr in a.thresholds:
            r = run(a.sets, a.dir, cands, thr, a.score)
            allres[str(thr)] = r
            for k in a.sets:
                v = r[k]
                c, d = v["correction"], v["detection"]
                if k.startswith("clean"):
                    lines.append(f"| {k} | {thr} | {v['sentences']} | {v['per_1k_sentences']:.2f} | - | - | - | - | - | - |")
                else:
                    lines.append(f"| {k} | {thr} | {v['sentences']} | {v['per_1k_sentences']:.1f} | {c['P']:.3f} | "
                                 f"{c['R']:.3f} | {c['F0.5']:.3f} | {d['P']:.3f} | {d['R']:.3f} | {d['F0.5']:.3f} |")
            if a.pred_dir:
                os.makedirs(a.pred_dir, exist_ok=True)
                p = preds_at(cands, thr, a.score)
                with open(os.path.join(a.pred_dir, f"pred_{a.score}_{thr}.jsonl"), "w", encoding="utf-8") as f:
                    for k, v in p.items():
                        f.write(json.dumps({"id": k, "edits": v}, ensure_ascii=False) + "\n")
        open(a.out, "w").write("\n".join(lines) + "\n")
        json.dump(allres, open(a.out + ".json", "w"), indent=1)
        print("\n".join(lines))


if __name__ == "__main__":
    main()
