"""Build a tiny language pack (+ en_GB/en_US overlays) from an inline corpus for the Kotlin unit tests.

Runs the real pipeline code (counting, Kneser-Ney, renormalisation, quantisation, pack writer) on ~40
sentences so engine/src/test can check the reader against exact expectations.  Writes
engine/src/test/resources/lm/tiny.lm, tiny_GB.lmo, tiny_US.lmo and tiny_expect.tsv (reference log
probabilities computed by the Python model).

Usage: tools/lm/run.sh make_test_pack.py
"""

import os
import shutil
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import binfmt  # noqa: E402
import langs  # noqa: E402
import ngram  # noqa: E402
from paths import REPO  # noqa: E402
from textnorm import TextNorm  # noqa: E402

CORPUS = """\
I am going to the store.
I am going to the park with my mum.
I am going to the park with my mom.
I'm going to London tomorrow.
I'm going home now.
I don't know what you mean.
I don't think so.
I don't know.
Do you want to go to the park?
Do you want to come with me?
Do you know what time it is?
What time is it?
What colour is the car?
What color is the car?
The car is red.
The colour of the sky is blue.
The color of the sky is blue.
See you tomorrow!
See you soon.
See you later.
Thank you so much.
Thank you very much.
Thanks a lot.
How are you doing?
How are you?
Are you okay?
I love you.
I love this song.
This is so good.
This is my favourite song.
This is my favorite song.
Let's go to the park.
Let's meet at the station.
My iPhone is broken.
I bought a new iPhone.
Oh shit, I forgot.
Holy shit, that is great.
We are going to the cinema.
They are going to the cinema.
She is going to London.
"""

GB = {"colour", "favourite", "mum"}
US = {"color", "favorite", "mom"}


class TinyVocab:
    """Duck-typed encode.Vocab for a hand-made lexicon."""

    def __init__(self, words, freq, flags, cls):
        self.words, self.freq, self.flags, self.cls = words, freq, flags, cls
        ranked = sorted(dict.fromkeys(cls), key=lambda c: -sum(f for f, k in zip(freq, cls) if k == c))
        self.class_id = {c: ngram.NSPECIAL + i for i, c in enumerate(ranked)}
        self.ngram_id = np.array([self.class_id[c] for c in cls], np.int32)
        self.n_ids = ngram.NSPECIAL + len(ranked)
        self.exact = {w: i for i, w in enumerate(words)}

    def encode(self, toks):
        ids = [ngram.BOS]
        for t in toks:
            if t == ",":
                ids.append(ngram.COMMA)
            elif t in self.exact:
                ids.append(int(self.ngram_id[self.exact[t]]))
            elif t.lower() in self.exact:
                ids.append(int(self.ngram_id[self.exact[t.lower()]]))
            else:
                ids.append(ngram.UNK)
        return ids + [ngram.EOS]


def main():
    L = langs.get("en")
    tn = TextNorm(L)
    sents = list(tn.sentences(CORPUS))
    counts = {}
    for s in sents:
        for t in s:
            if t != ",":
                key = t if t in ("I", "I'm", "London", "iPhone", "Let's") else t.lower()
                counts[key] = counts.get(key, 0) + 1
    counts["dog"] = 1  # a word that occurs nowhere in the corpus -> rare / unigram only
    words = sorted(counts, key=lambda w: (-counts[w], w))
    freq = [counts[w] / sum(counts.values()) for w in words]
    flags = [1 if w == "shit" else 0 for w in words]
    cls = []
    pairs = {"colour": "color", "favourite": "favorite"}
    for w in words:
        cls.append(pairs.get(w, w))
    vocab = TinyVocab(words, freq, flags, cls)

    ids = np.array([i for s in sents for i in vocab.encode(s)], np.uint16)
    tmp = tempfile.mkdtemp()
    np.save(os.path.join(tmp, "s.npy"), ids)
    ngram.count([os.path.join(tmp, "s.npy")], 3, os.path.join(tmp, "c"), nb=2, n_ids=vocab.n_ids)
    m = ngram.estimate(os.path.join(tmp, "c"), 3, vocab.n_ids, {}, bos_logprob=float(np.log(len(sents) / len(ids))))
    quant = ngram.quantize_model(m)
    f = np.array(freq)
    g = vocab.ngram_id
    inclass = np.log(f / np.bincount(g, weights=f, minlength=vocab.n_ids)[g])

    out = os.path.join(REPO, "engine", "src", "test", "resources", "lm")
    os.makedirs(out, exist_ok=True)
    build_id = b"tinytest"
    _, perm = binfmt.write_base(os.path.join(out, "tiny.lm"), "en", L, tn, m, quant, vocab, inclass, -12.0, build_id)
    for tag, prefer, avoid in (("GB", GB, US), ("US", US, GB)):
        adj = np.zeros(len(words))
        for i, w in enumerate(words):
            if w in prefer:
                adj[i] = np.log(0.95) - inclass[i] if w in pairs or w in pairs.values() else 0.7
            elif w in avoid:
                adj[i] = np.log(0.05) - inclass[i] if w in pairs or w in pairs.values() else -2.0
        binfmt.write_overlay(os.path.join(out, f"tiny_{tag}.lmo"), f"en_{tag}", build_id, adj,
                             np.zeros(len(words), np.uint8), 0.0, perm)
    # reference values: log P(class | ctx) from the in-memory quantised model, for a few contexts
    with open(os.path.join(out, "tiny_expect.tsv"), "w") as fo:
        for ctx, w in [((), "I"), (("I",), "am"), (("I", "am"), "going"), (("going", "to"), "the"),
                       (("to", "the"), "park"), (("the",), "colour"), (("you",), "dog"), (("see", "you"), "tomorrow")]:
            seq = [ngram.BOS] if len(ctx) < m.order - 1 else []  # short context = start of the field
            seq += [int(g[vocab.exact[t]]) for t in ctx]
            seq.append(int(g[vocab.exact[w]]))
            k = min(len(seq), m.order)
            seq = seq[-k:]
            key = np.zeros(1, np.uint64)
            for t in seq:
                key = (key << np.uint64(16)) | np.uint64(t)
            lp = float(m.lp(key, k)[0])
            fo.write(f"{' '.join(ctx)}\t{w}\t{lp:.5f}\n")
    shutil.rmtree(tmp)
    try:  # neural rescorer fixture (needs torch; optional)
        import nnlm
        nnlm.write_test_fixture(out, vocab.n_ids, build_id)
    except ImportError:
        print("torch not installed: tiny.nnlm not regenerated")
    print("wrote", out, os.listdir(out))


if __name__ == "__main__":
    main()
