"""Encode tokenised text into n-gram token-id streams.

Vocabulary (written to tools/data/work/<locale>/vocab.tsv):
  * lexicon entries keep their lexicon.tsv order (lexId = line number, most frequent first);
  * n-gram ids: 0 <unk>, 1 <s>, 2 </s>, 3 ',', 4 <num>, 5 <rare>, then one id per word class (a word, or a
    VarCon spelling class such as {color, colour}) in decreasing class frequency, up to 65536 ids in total;
  * words of classes beyond the n-gram vocabulary map to <rare> and are scored as <rare> x P(word | rare).

Truecasing of a token: contraction/misspelling repair ("dont" -> "don't"), then the exact spelling if it is a
lexicon word (sentence-initial capitalised words prefer their lowercase form when that is more frequent),
otherwise the most frequent case variant ("london" -> "London", "IPHONE" -> "iPhone"), otherwise <unk>.

Outputs per source/split: <source>.<split>.ids.npy (uint16 stream: <s> w1 .. wn </s> per sentence) and, for
dev/test, <source>.<split>.lex.npy (int32 lexIds aligned with the ids; -1 for specials/<unk>).

Usage: tools/lm/run.sh encode.py <locale>
"""

import multiprocessing as mp
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import langs  # noqa: E402
from paths import work  # noqa: E402
from textnorm import TextNorm, NUM, UNK as UNK_TOK, COMMA as COMMA_TOK  # noqa: E402

UNK, BOS, EOS, COMMA, NUMID, RARE = 0, 1, 2, 3, 4, 5
NSPECIAL = 6
MAX_IDS = 65536


class Vocab:
    def __init__(self, locale):
        self.L = langs.get(locale)
        self.tn = TextNorm(self.L)
        d = work(locale)
        self.words, self.freq, self.flags, self.cls = [], [], [], []
        with open(os.path.join(d, "lexicon.tsv"), encoding="utf-8") as f:
            for line in f:
                w, fr, fl, c = line.rstrip("\n").split("\t")
                self.words.append(w)
                self.freq.append(float(fr))
                self.flags.append(int(fl))
                self.cls.append(c or w)
        self.repair = {}
        with open(os.path.join(d, "repair.tsv"), encoding="utf-8") as f:
            for line in f:
                k, v = line.rstrip("\n").split("\t")
                self.repair[k] = v
        self.exact = {w: i for i, w in enumerate(self.words)}
        self.by_lower = {}
        for i, w in enumerate(self.words):  # words are in frequency order: first variant = most frequent
            self.by_lower.setdefault(self.tn.lower(w), []).append(i)
        # classes -> n-gram ids by class frequency
        cfreq = {}
        for c, fr in zip(self.cls, self.freq):
            cfreq[c] = cfreq.get(c, 0.0) + fr
        ranked = sorted(cfreq, key=lambda c: -cfreq[c])
        self.class_id = {c: NSPECIAL + i for i, c in enumerate(ranked[: MAX_IDS - NSPECIAL])}
        self.ngram_id = np.array([self.class_id.get(c, RARE) for c in self.cls], dtype=np.int32)
        self.n_ids = NSPECIAL + min(len(ranked), MAX_IDS - NSPECIAL)

    def lex_id(self, tok, initial):
        tn = self.tn
        low = tn.lower(tok)
        if low in self.repair and (tok == low or tok[:1].isupper()):
            tok = self.repair[low]
            low = tn.lower(tok)
        i = self.exact.get(tok)
        if i is not None:
            if initial and tok[:1].isupper():
                j = self.exact.get(low)
                if j is not None and j < i:
                    return j
            return i
        cand = self.by_lower.get(low)
        return cand[0] if cand else -1

    def encode_sentence(self, toks):
        ids, lex = [BOS], [-1]
        for p, t in enumerate(toks):
            if t == NUM:
                ids.append(NUMID); lex.append(-1)
            elif t == COMMA_TOK:
                ids.append(COMMA); lex.append(-1)
            elif t == UNK_TOK:
                ids.append(UNK); lex.append(-1)
            else:
                i = self.lex_id(t, p == 0)
                ids.append(UNK if i < 0 else int(self.ngram_id[i])); lex.append(i)
        ids.append(EOS); lex.append(-1)
        return ids, lex

    def write_vocab(self, path):
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"# n_ids={self.n_ids}\n")
            for i, w in enumerate(self.words):
                f.write(f"{w}\t{self.freq[i]:.6e}\t{self.flags[i]}\t{self.ngram_id[i]}\t{self.cls[i]}\n")


V = None


def _init(locale):
    global V
    V = Vocab(locale)


def _encode_lines(lines):
    ids, lex = [], []
    for line in lines:
        toks = line.split()
        if toks:
            a, b = V.encode_sentence(toks)
            ids.extend(a)
            lex.extend(b)
    return np.array(ids, dtype=np.uint16), np.array(lex, dtype=np.int32)


def _chunks(path, n=50000):
    buf = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            buf.append(line)
            if len(buf) >= n:
                yield buf
                buf = []
    if buf:
        yield buf


def main(locale):
    L = langs.get(locale)
    text_dir, enc_dir = work(locale, "text"), work(locale, "enc")
    vocab = Vocab(locale)
    vocab.write_vocab(os.path.join(work(locale), "vocab.tsv"))
    print(f"[encode] {len(vocab.words):,} lexicon entries, {vocab.n_ids:,} n-gram ids", flush=True)
    names = L.chat_sources + L.general_sources + L.synthetic_sources + L.eval_sources
    with mp.Pool(max(1, (os.cpu_count() or 2) - 2), initializer=_init, initargs=(locale,)) as pool:
        for name in names:
            for split in ("train", "dev", "test"):
                src = os.path.join(text_dir, f"{name}.{split}.txt")
                if not os.path.exists(src) or os.path.getsize(src) == 0:
                    continue
                out = os.path.join(enc_dir, f"{name}.{split}.ids.npy")
                if os.path.exists(out) and os.path.getmtime(out) > os.path.getmtime(os.path.join(work(locale), "lexicon.tsv")):
                    continue
                parts = pool.map(_encode_lines, _chunks(src), chunksize=1)
                ids = np.concatenate([p[0] for p in parts])
                np.save(out, ids)
                if split != "train":
                    np.save(os.path.join(enc_dir, f"{name}.{split}.lex.npy"), np.concatenate([p[1] for p in parts]))
                n_unk = int((ids == UNK).sum())
                n_tok = int(len(ids) - 2 * (ids == BOS).sum())
                print(f"[encode] {name}.{split}: {n_tok:,} tokens, OOV {n_unk / max(1, n_tok):.2%}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1])
