#!/usr/bin/env python3
"""Runs the trained tagger over evaluation JSONL sets and writes candidate edits with confidences, then (with
--threshold) the gec_score.py prediction files.

    python3 predict.py cands --model tagger.pt --encoder <electra dir> --morph en_morph.txt --nouns nouns.txt \
        --sets a.jsonl b.jsonl ... --out cands.jsonl [--quant]
    python3 predict.py apply --cands cands.jsonl --threshold 0.9 [--score det|tag|min] --out pred.jsonl

A candidate is the most probable applicable non-KEEP tag of a word when its probability is >= 0.05:
{"cs", "ce", "cor", "rule": tag, "tag": p(tag), "det": p(error) from the detection head}.
"""

import argparse
import json
import os
import sys
import time

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gectag import TAGS, Morph, case_class, tag_to_edit, words  # noqa: E402

KEEP = TAGS.index("KEEP")


def load_model(path, encoder, quant=False, threads=None):
    from train import Tagger
    from transformers import ElectraConfig
    st = torch.load(path, map_location="cpu", weights_only=False)
    cfg = ElectraConfig.from_dict(st["encoder_config"])
    model = Tagger(cfg, len(st["tags"]), pretrained=False)
    model.load_state_dict(st["model"])
    model.eval()
    if quant:
        model = torch.ao.quantization.quantize_dynamic(model, {torch.nn.Linear}, dtype=torch.qint8)
    assert st["tags"] == TAGS, "tag vocabulary changed since training"
    return model


def encode(tok, ws, max_len=256):
    enc = tok(ws, is_split_into_words=True, add_special_tokens=True, truncation=True, max_length=max_len)
    wid = enc.word_ids()
    case = [0 if w is None else case_class(ws[w]) for w in wid]
    first = {}
    for p, w in enumerate(wid):
        if w is not None and w not in first:
            first[w] = p
    return enc["input_ids"], case, first


@torch.no_grad()
def run(model, tok, morph, rows, batch=64, min_p=0.05, dev="cpu"):
    out = {}
    items = []
    for r in rows:
        ws = words(r["text"])
        if not ws:
            out[r["id"]] = []
            continue
        ids, case, first = encode(tok, [w for w, _, _ in ws])
        items.append((r, ws, ids, case, first))
    items.sort(key=lambda x: len(x[2]))
    for b in range(0, len(items), batch):
        chunk = items[b:b + batch]
        L = max(len(x[2]) for x in chunk)
        ids = torch.zeros((len(chunk), L), dtype=torch.long)
        case = torch.zeros((len(chunk), L), dtype=torch.long)
        mask = torch.zeros((len(chunk), L), dtype=torch.long)
        for k, x in enumerate(chunk):
            n = len(x[2])
            ids[k, :n] = torch.tensor(x[2]); case[k, :n] = torch.tensor(x[3]); mask[k, :n] = 1
        tl, dl = model(ids.to(dev), case.to(dev), mask.to(dev))
        tp = tl.float().softmax(-1).cpu().numpy()
        dp = dl.float().softmax(-1)[..., 1].cpu().numpy()
        for k, (r, ws, _ids, _case, first) in enumerate(chunk):
            text = r["text"]
            cands = []
            for wi, p in first.items():
                probs = tp[k, p]
                order = np.argsort(-probs)
                for t in order[:4]:
                    if t == KEEP:
                        continue
                    if probs[t] < min_p:
                        break
                    e = tag_to_edit(text, ws, wi, TAGS[t], morph)
                    if e is None:
                        continue
                    e.update(rule=TAGS[t], tag=round(float(probs[t]), 5), det=round(float(dp[k, p]), 5),
                             keep=round(float(probs[KEEP]), 5))
                    cands.append(e)
                    break
            out[r["id"]] = cands
    return out


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("cands")
    c.add_argument("--model", required=True)
    c.add_argument("--encoder", required=True, help="dir with the word-piece tokenizer")
    c.add_argument("--morph", required=True)
    c.add_argument("--nouns", required=True)
    c.add_argument("--sets", nargs="+", required=True)
    c.add_argument("--out", required=True)
    c.add_argument("--quant", action="store_true")
    c.add_argument("--threads", type=int, default=8)
    p = sub.add_parser("apply")
    p.add_argument("--cands", required=True)
    p.add_argument("--threshold", type=float, required=True)
    p.add_argument("--score", default="tag", choices=["tag", "det", "min"])
    p.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.cmd == "cands":
        from transformers import AutoTokenizer
        torch.set_num_threads(a.threads)
        tok = AutoTokenizer.from_pretrained(a.encoder)
        morph = Morph(a.morph, a.nouns)
        model = load_model(a.model, a.encoder, a.quant)
        with open(a.out, "w", encoding="utf-8") as f:
            for s in a.sets:
                rows = [json.loads(l) for l in open(s, encoding="utf-8")]
                t = time.time()
                res = run(model, tok, morph, rows)
                print(f"{os.path.basename(s)}: {len(rows)} sentences in {time.time() - t:.1f}s", flush=True)
                for r in rows:
                    f.write(json.dumps({"id": r["id"], "cands": res.get(r["id"], [])}, ensure_ascii=False) + "\n")
    else:
        with open(a.out, "w", encoding="utf-8") as f:
            for line in open(a.cands, encoding="utf-8"):
                o = json.loads(line)
                edits = []
                for e in o["cands"]:
                    s = e["tag"] if a.score == "tag" else e["det"] if a.score == "det" else min(e["tag"], e["det"])
                    if s >= a.threshold:
                        edits.append({"cs": e["cs"], "ce": e["ce"], "cor": e["cor"], "rule": e["rule"],
                                      "conf": round(s, 4)})
                f.write(json.dumps({"id": o["id"], "edits": edits}, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
