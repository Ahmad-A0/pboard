#!/usr/bin/env python3
"""Builds the tagger's training data: licence-clean LM training text (tools/data/work/en/text/*.train.txt, sources
in docs/data-licenses.md) with synthetic errors from corrupt.py, tokenised for the ELECTRA word-piece vocabulary.

Output shards (npz): ids uint16, case uint8, lab int16 (tag id on a word's first piece, -100 elsewhere), offs int64.
Sentences equal to any evaluation/dev sentence are dropped (the eval sets come from the *.test/*.dev splits anyway).

    python3 -I make_data.py --text <dir with *.train.txt> --split train --n-chat 3200000 --n-web 1800000 \
        --morph en_morph.txt --nouns nouns.txt --tokenizer <electra dir> --eval-dir <textintel jsonl dir> --out <dir>
"""

import argparse
import collections
import glob
import json
import multiprocessing as mp
import os
import random
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gectag import TAG_ID, Morph, WORD_RE, case_class  # noqa: E402
from corrupt import Corruptor  # noqa: E402

CHAT = ["tatoeba", "topicalchat", "wildchat", "taskmaster", "sgd", "empathetic", "bst", "multiwoz", "wow", "oasst",
        "hhrlhf"]
WEB = ["fineweb", "fineweb_uk", "fineweb_us", "leipzig_uk", "wikipedia"]
QWORDS = {"what", "why", "how", "when", "where", "who", "which", "do", "does", "did", "is", "are", "can", "could",
          "would", "will", "should", "have", "has", "was", "were"}
DOUBLED = re.compile(r"\b([A-Za-z]+) \1\b", re.I)


def norm(t):
    return " ".join(WORD_RE.findall(t.lower()))


def render(line, rng):
    """LM text ("Yes , that's <num> dollars") -> typed text with numbers and, sometimes, end punctuation."""
    line = re.sub(r"<num>", lambda m: str(rng.choice([rng.randint(2, 12), rng.randint(10, 99), rng.randint(1990, 2025)])),
                  line)
    line = re.sub(r" ([,.!?;:%)])", r"\1", line)
    line = re.sub(r"([($]) ", r"\1", line)
    if line[-1:].isalnum():
        r = rng.random()
        first = line.split(" ", 1)[0].lower()
        if r < 0.3:
            line += "?" if first in QWORDS else "."
        elif r < 0.35:
            line += "!"
    return line


_tok = None
_morph = None


def init(tok_dir, morph, nouns):
    global _tok, _morph
    from transformers import AutoTokenizer
    _tok = AutoTokenizer.from_pretrained(tok_dir)
    _morph = Morph(morph, nouns)


def work(args):
    lines, seed, max_pieces = args
    rng = random.Random(seed)
    cor = Corruptor(_morph, seed)
    words_l, tags_l, fams = [], [], collections.Counter()
    samples = []
    for line in lines:
        text = render(line, rng)
        toks = [m.group(0) for m in WORD_RE.finditer(text)]
        r = rng.random()
        k = 0 if r < 0.3 else 1 if r < 0.72 else 2 if r < 0.9 else 3
        new, _sp, tags, f = cor.corrupt(toks, [True] * len(toks), text, k)
        fams.update(f)
        if not f:
            fams["(clean)"] += 1
        words_l.append(new)
        tags_l.append(tags)
        if len(samples) < 3 and f:
            samples.append((" ".join(new), [(w, t) for w, t in zip(new, tags) if t != "KEEP"]))
    enc = _tok(words_l, is_split_into_words=True, add_special_tokens=True, truncation=False)
    ids_all, case_all, lab_all, offs = [], [], [], [0]
    tagc = collections.Counter()
    for j, ws in enumerate(words_l):
        ids = enc["input_ids"][j]
        if len(ids) > max_pieces:
            continue
        wid = enc.word_ids(j)
        lab = np.full(len(ids), -100, dtype=np.int16)
        case = np.zeros(len(ids), dtype=np.uint8)
        prev = None
        for p, w in enumerate(wid):
            if w is None:
                continue
            case[p] = case_class(ws[w])
            if w != prev:
                lab[p] = TAG_ID[tags_l[j][w]]
                tagc[tags_l[j][w]] += 1
            prev = w
        ids_all.append(np.asarray(ids, dtype=np.uint16))
        case_all.append(case)
        lab_all.append(lab)
        offs.append(offs[-1] + len(ids))
    return (np.concatenate(ids_all), np.concatenate(case_all), np.concatenate(lab_all),
            np.asarray(offs, dtype=np.int64), fams, tagc, samples)


def load_lines(paths, banned, rng, n):
    seen = set()
    out = []
    for p in paths:
        for line in open(p, encoding="utf-8"):
            line = line.strip()
            if not line or "<unk>" in line:
                continue
            nw = line.count(" ") + 1
            if nw < 3 or nw > 60:
                continue
            if DOUBLED.search(line):
                continue  # typed text with a doubled word would teach that doubles are fine
            key = line.lower()
            if key in seen:
                continue
            seen.add(key)
            if norm(line) in banned:
                continue
            out.append(line)
    rng.shuffle(out)
    return out[:n], len(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", required=True)
    ap.add_argument("--split", default="train")
    ap.add_argument("--n-chat", type=int, required=True)
    ap.add_argument("--n-web", type=int, required=True)
    ap.add_argument("--morph", required=True)
    ap.add_argument("--nouns", required=True)
    ap.add_argument("--tokenizer", required=True)
    ap.add_argument("--eval-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--shard", type=int, default=500000)
    ap.add_argument("--max-pieces", type=int, default=96)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--workers", type=int, default=14)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    banned = set()
    for p in glob.glob(os.path.join(a.eval_dir, "*.jsonl")):
        for l in open(p, encoding="utf-8"):
            try:
                banned.add(norm(json.loads(l)["text"]))
            except (ValueError, KeyError):
                pass
    chat, nc = load_lines([os.path.join(a.text, f"{s}.{a.split}.txt") for s in CHAT], banned, rng, a.n_chat)
    web, nw = load_lines([os.path.join(a.text, f"{s}.{a.split}.txt") for s in WEB], banned, rng, a.n_web)
    print(f"chat {len(chat)} of {nc}, web {len(web)} of {nw}", flush=True)
    lines = chat + web
    rng.shuffle(lines)
    os.makedirs(a.out, exist_ok=True)
    chunk = 5000
    jobs = [(lines[i:i + chunk], a.seed * 1000003 + i, a.max_pieces) for i in range(0, len(lines), chunk)]
    fams, tagc = collections.Counter(), collections.Counter()
    buf = []
    shard = 0
    nsent = 0
    samples = []

    def flush():
        nonlocal buf, shard
        if not buf:
            return
        ids = np.concatenate([b[0] for b in buf])
        case = np.concatenate([b[1] for b in buf])
        lab = np.concatenate([b[2] for b in buf])
        offs = [np.zeros(1, dtype=np.int64)]
        base = 0
        for b in buf:
            offs.append(b[3][1:] + base)
            base += int(b[3][-1])
        np.savez_compressed(os.path.join(a.out, f"{a.split}-{shard:03d}.npz"), ids=ids, case=case, lab=lab,
                 offs=np.concatenate(offs))
        shard += 1
        buf = []

    with mp.Pool(a.workers, initializer=init, initargs=(a.tokenizer, a.morph, a.nouns)) as pool:
        cur = 0
        for res in pool.imap(work, jobs, chunksize=1):
            buf.append(res[:4])
            fams.update(res[4])
            tagc.update(res[5])
            samples += res[6][:1]
            n = len(res[3]) - 1
            nsent += n
            cur += n
            if cur >= a.shard:
                flush()
                cur = 0
                print(f"  {nsent} sentences", flush=True)
        flush()
    stats = {"sentences": nsent, "families": dict(fams.most_common()), "tags": dict(tagc.most_common()),
             "samples": samples[:60]}
    json.dump(stats, open(os.path.join(a.out, f"{a.split}-stats.json"), "w"), indent=1)
    print(json.dumps({"sentences": nsent, "families": dict(fams.most_common())}))


if __name__ == "__main__":
    main()
