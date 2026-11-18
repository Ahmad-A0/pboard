"""Evaluation sets and perplexity.

1. Writes held-out evaluation sets to tools/data/work/<locale>/eval/<set>.txt (one tokenised sentence per
   line, original case) - these are what the Kotlin benchmark (engine :lmEval) reads for top-k accuracy and
   keystroke savings:
     chat       test splits of every conversational training source (<= 2500 sentences each)
     dailydialog, nussms_us   external conversational/SMS text never used for training
     web_gb, web_us           held-out British / American web text
     chat_gb    the chat set respelt with British spellings (VarCon A -> B), to test the en_GB overlay
2. Reports word perplexity of the shipped (quantised, pruned) model on each set, without overlay and with
   each dialect overlay.  Perplexity is over in-vocabulary word tokens (not ',', </s>, <num> or OOV words),
   each conditioned on its full n-gram history; the OOV rate is reported separately.

Usage: tools/lm/run.sh evaluate.py <locale>
"""

import json
import os
import random
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dialects  # noqa: E402
import langs  # noqa: E402
import lexicon as lexmod  # noqa: E402
import ngram  # noqa: E402
from encode import Vocab, BOS, EOS, COMMA, NUMID, UNK  # noqa: E402
from paths import work  # noqa: E402

PER_SOURCE = 2500
# LLM-generated training sources: never part of an evaluation set (quality is measured on human-written text only)
SYNTHETIC_SOURCES = {"soda"}


def _sample(path, n, seed):
    if not os.path.exists(path):
        return []
    lines = [l.rstrip("\n") for l in open(path, encoding="utf-8") if l.strip()]
    random.Random(seed).shuffle(lines)
    return lines[:n]


def build_sets(L):
    text = work(L.locale, "text")
    ev = work(L.locale, "eval")
    sets = {}
    chat = []
    for s in L.chat_sources:
        if s in SYNTHETIC_SOURCES:
            continue
        chat += _sample(os.path.join(text, f"{s}.test.txt"), PER_SOURCE, s)
    sets["chat"] = chat
    for s in L.eval_sources:
        sets[{"nussms": "nussms_us"}.get(s, s)] = _sample(os.path.join(text, f"{s}.test.txt"), 20000, s)
    for dl in L.dialects.values():
        tag = dl.locale.split("_")[1].lower()
        lines = []
        for s in dl.eval_sources:
            lines += _sample(os.path.join(text, f"{s}.test.txt"), 4000, s)
        sets[f"web_{tag}"] = lines
    if "en_GB" in L.dialects:
        a2b = {}
        for row in lexmod.load_varcon():
            a, b = lexmod.primary(row, "A"), lexmod.primary(row, "B")
            if a and b and a != b and not row["annotated"]:
                a2b[a] = b
                a2b[a.capitalize()] = b.capitalize()
        sets["chat_gb"] = [" ".join(a2b.get(t, t) for t in line.split()) for line in chat]
    for name, lines in sets.items():
        with open(os.path.join(ev, f"{name}.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    return sets


def encode_lines(vocab, lines):
    ids, lex = [], []
    for line in lines:
        a, b = vocab.encode_sentence(line.split())
        ids += a
        lex += b
    return np.array(ids, np.uint16), np.array(lex, np.int64)


def perplexity(model, vocab, inclass, adjust, log_z, ids, lex):
    lp = model.score_stream(ids)
    word = (ids >= ngram.NSPECIAL) | (ids == ngram.RARE)
    mask = word & (lex >= 0)
    total = lp[mask] + inclass[lex[mask]]
    if adjust is not None:
        total = total + adjust[lex[mask]] - log_z
    n_words = int(((ids >= ngram.NSPECIAL) | (ids == ngram.RARE) | (ids == UNK)).sum())
    oov = int((ids == UNK).sum())
    return float(np.exp(-total.mean())), oov / max(1, n_words), int(mask.sum())


def main(locale):
    L = langs.get(locale)
    d = work(locale)
    sets = build_sets(L)
    vocab = Vocab(locale)
    model = ngram.Model.load(os.path.join(d, "model_quant.npz"))
    inclass = dialects.base_inclass(vocab)
    overlays = {"base": (None, 0.0)}
    for dl in L.dialects.values():
        p = os.path.join(d, f"overlay_{dl.locale}.npy")
        if os.path.exists(p):
            adj = np.load(p)
            # logZ is stored in the .lmo header; recompute it the same way here
            g = vocab.ngram_id
            p_uni = np.exp(model.logp[1].astype(np.float64))
            contrib = np.exp(inclass + adj)
            z = p_uni[:ngram.NSPECIAL].sum() - p_uni[ngram.RARE]
            z += np.bincount(g, weights=contrib, minlength=vocab.n_ids)[ngram.NSPECIAL:] @ p_uni[ngram.NSPECIAL:]
            z += p_uni[ngram.RARE] * contrib[g == ngram.RARE].sum()
            overlays[dl.locale] = (adj, float(np.log(z)))
    results = {}
    print(f"{'set':<12} {'sents':>7} {'words':>8} {'OOV':>6} " + " ".join(f"{k:>9}" for k in overlays))
    for name, lines in sets.items():
        ids, lex = encode_lines(vocab, lines)
        row = {}
        for k, (adj, lz) in overlays.items():
            ppl, oov, n = perplexity(model, vocab, inclass, adj, lz, ids, lex)
            row[k] = ppl
        results[name] = {"sentences": len(lines), "words": n, "oov": oov, "ppl": row}
        print(f"{name:<12} {len(lines):>7} {n:>8} {oov:>6.2%} " + " ".join(f"{row[k]:>9.1f}" for k in overlays))
    # spelling choice: for words of a VarCon spelling class seen in held-out text, how often does each
    # dialect rank the spelling the writer actually used first within its class?
    g = vocab.ngram_id
    multi = np.bincount(g, minlength=vocab.n_ids) > 1
    print("\nspelling choice (share of spelling-class tokens whose written spelling the dialect ranks first)")
    print(f"{'set':<12} {'tokens':>7} " + " ".join(f"{k:>9}" for k in overlays))
    for name in [n for n in sets if n.startswith("web_")] + (["chat", "chat_gb"] if "chat_gb" in sets else []):
        _, lex = encode_lines(vocab, sets[name])
        lex = lex[lex >= 0]
        lex = lex[multi[g[lex]] & (g[lex] >= ngram.NSPECIAL)]
        row = {}
        for k, (adj, _) in overlays.items():
            score = inclass + (adj if adj is not None else 0.0)
            best = {}
            for i in np.flatnonzero(multi[g] & (g >= ngram.NSPECIAL)):
                if g[i] not in best or score[i] > score[best[g[i]]]:
                    best[g[i]] = i
            row[k] = float(np.mean([best[g[i]] == i for i in lex])) if len(lex) else float("nan")
        results.setdefault("spelling", {})[name] = {"tokens": int(len(lex)), **row}
        print(f"{name:<12} {len(lex):>7} " + " ".join(f"{row[k]:>8.1%} " for k in overlays))
    with open(os.path.join(d, "eval_ppl.json"), "w") as f:
        json.dump(results, f, indent=1)


if __name__ == "__main__":
    main(sys.argv[1])
