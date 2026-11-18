#!/usr/bin/env python3
"""Builds the grammar-check evaluation sets (JSONL) from public GEC benchmarks and held-out clean text.

Evaluation only. BEA-2019 W&I+LOCNESS (non-commercial research licence) and CoNLL-2014 (no licence stated) may
not be trained on or shipped; the outputs go to tools/data/work/textintel/ (gitignored) and are never committed.

Each output line: {"id", "set", "text", "tokens", "spans": [[start, end] per token], "annotators": [[edit...]...]}
where an edit is {"ts", "te", "cs", "ce", "type", "cor"}: token span [ts, te), the same span in characters of
"text" (an insertion has cs == ce), the error type and the tokenised correction. "text" is the M2 sentence
detokenised the way a person would type it ("do n't" -> "don't", no space before punctuation).

Clean-text sets ("annotators": [[]]) measure false alarms on native text: held-out chat and web sentences.

    python3 -I tools/textintel/gec_data.py --raw tools/data/raw/gec --heldout <dir with chat.txt ...> \
        --out tools/data/work/textintel
"""

import argparse
import json
import os
import random
import re

CLITICS = {"n't", "'s", "'m", "'re", "'ve", "'ll", "'d", "’s", "n’t", "’m", "’re", "’ve", "’ll", "’d", "'"}
NO_SPACE_BEFORE = set(".,!?;:)]}%") | {"...", "''", "”", "’"}
NO_SPACE_AFTER = set("([{$#") | {"``", "“"}


def detokenize(tokens):
    """Joins tokens as typed text; returns (text, [(start, end)] per token)."""
    out = []
    spans = []
    pos = 0
    quote_open = False
    prev = None
    for i, t in enumerate(tokens):
        space = i > 0
        if t in CLITICS or t.lower() in CLITICS or t in NO_SPACE_BEFORE or (len(t) > 1 and all(c in ".!?" for c in t)):
            space = False
        if prev is not None and (prev in NO_SPACE_AFTER or (prev == '"' and quote_open)):
            space = False
        if t == '"':
            if quote_open:
                space = False
            quote_open = not quote_open
        # hyphenated compounds were split by the tokeniser ("well - known"): join them again
        if t == "-" and prev and prev[-1:].isalnum() and i + 1 < len(tokens) and tokens[i + 1][:1].isalnum():
            space = False
        if prev == "-" and i > 1 and tokens[i - 2][-1:].isalnum() and t[:1].isalnum():
            space = False
        if space:
            out.append(" ")
            pos += 1
        spans.append((pos, pos + len(t)))
        out.append(t)
        pos += len(t)
        prev = t
    return "".join(out), spans


def read_m2(path):
    sent = None
    edits = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("S "):
            sent = line[2:].split(" ") if line[2:] else []
            edits = {}
        elif line.startswith("A "):
            span, typ, cor, _req, _none, ann = line[2:].split("|||")
            s, e = map(int, span.split())
            edits.setdefault(int(ann), [])
            if typ in ("noop", "UNK", "Um") or s < 0:
                continue
            edits[int(ann)].append((s, e, typ, cor))
        elif not line.strip() and sent is not None:
            yield sent, edits
            sent = None
    if sent is not None:
        yield sent, edits


def m2_set(path, name):
    rows = []
    for k, (tokens, edits) in enumerate(read_m2(path)):
        if not tokens:
            continue
        text, spans = detokenize(tokens)
        anns = []
        for a in sorted(edits) or [0]:
            lst = []
            for (s, e, typ, cor) in edits.get(a, []):
                if s < len(spans):
                    cs = spans[s][0]
                else:
                    cs = len(text)
                ce = spans[e - 1][1] if e > s else cs
                lst.append({"ts": s, "te": e, "cs": cs, "ce": ce, "type": typ, "cor": cor})
            anns.append(lst)
        rows.append({"id": f"{name}-{k}", "set": name, "text": text, "tokens": tokens, "spans": spans, "annotators": anns})
    return rows


TOKEN = re.compile(r"[A-Za-z0-9]+(?:['’][A-Za-z]+)*|[^\sA-Za-z0-9]")


def clean_set(path, name, n, seed=7):
    # the LM pipeline wrote numbers as <num>: put a digit back so the text reads as typed
    lines = [l.strip().replace("<num>", "3") for l in open(path, encoding="utf-8") if l.strip()]
    lines = [l for l in lines if 3 <= len(l.split()) <= 40]
    random.Random(seed).shuffle(lines)
    rows = []
    for k, text in enumerate(lines[:n]):
        toks = [m.group(0) for m in TOKEN.finditer(text)]
        spans = [(m.start(), m.end()) for m in TOKEN.finditer(text)]
        rows.append({"id": f"{name}-{k}", "set": name, "text": text, "tokens": toks, "spans": spans, "annotators": [[]]})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--heldout", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--clean-n", type=int, default=3000)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    wi = os.path.join(a.raw, "wi", "wi+locness", "m2")
    sets = {
        "bea_dev": m2_set(os.path.join(wi, "ABCN.dev.gold.bea19.m2"), "bea_dev"),
        "bea_dev_native": m2_set(os.path.join(wi, "N.dev.gold.bea19.m2"), "bea_dev_native"),
        "conll14": m2_set(os.path.join(a.raw, "conll14", "conll14st-test-data", "noalt", "official-2014.combined.m2"), "conll14"),
    }
    for name in ("chat", "dailydialog", "nussms_us", "web_gb", "web_us"):
        p = os.path.join(a.heldout, name + ".txt")
        if os.path.exists(p):
            sets["clean_" + name] = clean_set(p, "clean_" + name, a.clean_n)
    for name, rows in sets.items():
        with open(os.path.join(a.out, name + ".jsonl"), "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        n_edits = sum(len(r["annotators"][0]) for r in rows)
        print(f"{name}: {len(rows)} sentences, {n_edits} edits (annotator 0)")


if __name__ == "__main__":
    main()
