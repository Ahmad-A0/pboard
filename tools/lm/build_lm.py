"""Estimate, mix, prune, quantise and export the n-gram LM of a locale (+ dialect overlays).

  chat component    <- conversational sources (train splits)
  general component <- general sources (train splits)
  mixture weight    <- EM on the chat dev splits
  base pack         <- entropy-pruned to the byte budget, quantised: app/src/main/assets/lm/<locale>.lm
  overlays          <- one <dialect>.lmo per configured dialect (spelling choice + lexical preferences)

Usage: tools/lm/run.sh build_lm.py <locale> [--force]
"""

import hashlib
import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import binfmt  # noqa: E402
import dialects  # noqa: E402
import langs  # noqa: E402
import lmcheck  # noqa: E402
import ngram  # noqa: E402
from encode import Vocab  # noqa: E402
from paths import ASSET_DIR, work  # noqa: E402
from textnorm import TextNorm  # noqa: E402

CUTOFFS = {"chat": {3: 2, 4: 2}, "general": {2: 2, 3: 2, 4: 3}, "synthetic": {3: 2, 4: 2}}
OOV_PENALTY = -12.0  # log P(specific unknown word | <unk>): ~1 / 160k distinct unknown types


def streams(L, enc, names, split):
    out = []
    for n in names:
        p = os.path.join(enc, f"{n}.{split}.ids.npy")
        if os.path.exists(p):
            out.append(p)
    return out


def log(msg):
    print(f"[build_lm {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def main(locale, force=False, max_order=None, out_dir=None):
    asset_dir = out_dir or ASSET_DIR
    tag = f"_o{max_order}" if max_order else ""
    L = langs.get(locale)
    tn = TextNorm(L)
    d = work(locale)
    enc = work(locale, "enc")
    vocab = Vocab(locale)
    n_ids = vocab.n_ids
    order = max_order or L.order

    # cached intermediate models are only valid if newer than every encoded stream (e.g. after a lexicon change)
    newest = max(os.path.getmtime(p) for p in streams(L, enc, L.chat_sources + L.general_sources
                                                          + L.synthetic_sources, "train"))

    def fresh(path):
        return os.path.exists(path) and os.path.getmtime(path) > newest and not force

    # mixture components, in weight order: chat, general[, synthetic]
    order_comps = [("chat", L.chat_sources), ("general", L.general_sources)]
    if L.synthetic_sources:
        order_comps.append(("synthetic", L.synthetic_sources))
    comps = {}
    for comp, names in order_comps:
        path = os.path.join(d, f"model_{comp}{tag}.npz")
        if fresh(path):
            comps[comp] = ngram.Model.load(path)
            continue
        files = streams(L, enc, names, "train")
        cdir = os.path.join(d, f"counts_{comp}")
        if not fresh(os.path.join(cdir, "DONE")):
            ngram.shutil.rmtree(cdir, ignore_errors=True)
        log(f"counting {comp}: {len(files)} streams")
        ngram.count(files, L.order, os.path.join(d, f"counts_{comp}"), n_ids=n_ids)
        n_tok = sum(len(np.load(f, mmap_mode="r")) for f in files)
        n_sent = sum(int((np.load(f, mmap_mode="r") == ngram.BOS).sum()) for f in files)
        log(f"estimating {comp} order {order} ({n_tok:,} tokens incl. markers)")
        m = ngram.estimate(os.path.join(d, f"counts_{comp}"), order, n_ids, CUTOFFS[comp],
                           bos_logprob=float(np.log(n_sent / n_tok)))
        m.save(path)
        comps[comp] = m
        log(f"{comp}: {m.size()}")

    # ---- mixture weights: leave-one-source-out, so the weight reflects conversational text the chat model
    # has NOT seen (tuning on in-domain dev data over-weights the chat component, ~0.94)
    wpath = os.path.join(d, "weights.json")
    if fresh(wpath):
        w = np.array(json.load(open(wpath))["weights"])
    else:
        w = loso_weights(L, d, enc, n_ids, [comps[c] for c, _ in order_comps[1:]])
        json.dump({"weights": list(w)}, open(wpath, "w"))
    log("mixture weights " + " ".join(f"{c}={x:.3f}" for (c, _), x in zip(order_comps, w)))
    mixed_path = os.path.join(d, f"model_mixed{tag}.npz")
    wtag = os.path.join(d, f"model_mixed{tag}.weights")
    if fresh(mixed_path) and os.path.exists(wtag) and open(wtag).read() == repr(list(w)):
        mixed = ngram.Model.load(mixed_path)
    else:
        mixed = ngram.interpolate([comps[c] for c, _ in order_comps], w)
        mixed.save(mixed_path)
        open(wtag, "w").write(repr(list(w)))
    del comps

    # ---- entropy pruning to the byte budget
    lex_bytes = binfmt.lexicon_bytes_estimate(vocab.words)
    size_fn = lambda counts: binfmt.size_estimate(mixed.order, counts, lex_bytes)  # noqa: E731
    log(f"mixed model {mixed.size()}; lexicon ~{lex_bytes / 1e6:.1f} MB; pruning to {L.target_bytes / 1e6:.1f} MB")
    scores = ngram.prune_scores(mixed)
    theta = ngram.prune_to(mixed, scores, size_fn, L.target_bytes)
    del scores
    log(f"pruned (theta={theta:.2e}): {mixed.size()}")
    assert not any(mixed.clamped.values()), f"pruned model: contexts without back-off mass {mixed.clamped}"
    pruned_path = os.path.join(d, f"model_pruned{tag}.npz")
    mixed.save(pruned_path)

    # ---- quantise + export
    quant = ngram.quantize_model(mixed)
    mixed.save(os.path.join(d, f"model_quant{tag}.npz"))
    inclass = dialects.base_inclass(vocab)
    build_id = hashlib.sha256(open(os.path.join(d, "lexicon.tsv"), "rb").read()
                              + json.dumps(mixed.size(), sort_keys=True).encode()).digest()[:8]
    os.makedirs(asset_dir, exist_ok=True)
    base_path = os.path.join(asset_dir, f"{locale}.lm")
    new_path = base_path + ".new"
    size, perm = binfmt.write_base(new_path, locale, L, tn, mixed, quant, vocab, inclass, OOV_PENALTY, build_id)
    np.save(os.path.join(d, f"pack_perm{tag}.npy"), perm)

    # ---- gates on the written binary: it matches the in-memory model on random n-grams, and no word class is
    # implausibly far below its unigram probability after <s> or a frequent context (tools/lm/lmcheck.py).
    # A failing pack never replaces the shipped one.
    verify(new_path, mixed)
    problems = lmcheck.check(new_path, words={int(g): w for w, g in reversed(list(zip(vocab.words, vocab.ngram_id)))})
    if problems:
        raise SystemExit("back-off gate failed (pack left at " + new_path + "):\n  " + "\n  ".join(problems))
    log("back-off gate passed (<s> and the 300 most frequent contexts)")
    os.replace(new_path, base_path)
    log(f"wrote {base_path}: {size / 1e6:.2f} MB")

    # ---- dialect overlays (lexical-bias hyper-parameters tuned on the dialect's dev text)
    for dl in L.dialects.values():
        dev = streams(L, enc, dl.text_sources + (L.chat_sources[:4] if dl.unmarked else []), "dev")
        (ppl, tau, dead, scale), ppl0 = dialects.tune(L, dl, vocab, mixed, inclass, w, dev)
        log(f"{dl.locale}: dev ppl {ppl0:.1f} -> {ppl:.1f} (tau={tau}, dead={dead}, scale={scale})")
        adjust, flags_or, log_z, stats = dialects.overlay(L, dl, vocab, mixed, inclass, w, tau, dead, scale)
        assert dialects.check_spelling_order(vocab, adjust, dl.varcon) == 0, f"{dl.locale}: dialect spelling not first"
        np.save(os.path.join(d, f"overlay_{dl.locale}{tag}.npy"), adjust)
        p = os.path.join(asset_dir, f"{dl.locale}.lmo")
        sz = binfmt.write_overlay(p, dl.locale, build_id, adjust, flags_or, log_z, perm)
        log(f"wrote {p}: {sz / 1e3:.0f} kB; {stats}")


LOSO_SOURCES = ("topicalchat", "empathetic", "bst", "taskmaster", "wildchat", "hhrlhf")


def loso_weights(L, d, enc, n_ids, others):
    """Average EM mixture weights over held-out (human) chat sources: the chat model is re-estimated without that
    source, and the weights of [chat, *others] are fitted on that source's dev split."""
    ws = []
    for s in [s for s in LOSO_SOURCES if s in L.chat_sources]:
        dev = os.path.join(enc, f"{s}.dev.ids.npy")
        if not os.path.exists(dev):
            continue
        cdir = os.path.join(d, "counts_loso")
        ngram.shutil.rmtree(cdir, ignore_errors=True)
        ngram.count(streams(L, enc, [n for n in L.chat_sources if n != s], "train"), L.order, cdir, n_ids=n_ids)
        m = ngram.estimate(cdir, L.order, n_ids, CUTOFFS["chat"])
        wi = ngram.em_weights([m] + list(others), np.load(dev))
        log(f"  held-out {s}: " + " ".join(f"{x:.3f}" for x in wi))
        ws.append(wi)
        ngram.shutil.rmtree(cdir, ignore_errors=True)
    return np.mean(ws, axis=0)


def verify(path, m, n=300):
    r = binfmt.Reader(path)
    rng = np.random.default_rng(0)
    worst = 0.0
    for k in range(2, m.order + 1):
        for key in rng.choice(m.keys[k], size=min(n, len(m.keys[k])), replace=False):
            seq = [int((key >> np.uint64(16 * (k - 1 - j))) & np.uint64(0xFFFF)) for j in range(k)]
            # query with a random extra context word to exercise back-off
            ctx, wd = seq[:-1], seq[-1]
            a = r.ngram_logprob(ctx, wd)
            kk = np.zeros(1, np.uint64)
            for t in seq:
                kk = (kk << np.uint64(16)) | np.uint64(t)
            b = float(m.lp(kk, k)[0])
            worst = max(worst, abs(a - b))
    log(f"binary verification: max |logp(binary) - logp(model)| = {worst:.4f}")
    assert worst < 0.01, "binary does not match the model"


if __name__ == "__main__":
    a = sys.argv[1:]
    opt = lambda name: a[a.index(name) + 1] if name in a else None  # noqa: E731
    main(a[0], "--force" in a, int(opt("--order")) if opt("--order") else None, opt("--out"))
