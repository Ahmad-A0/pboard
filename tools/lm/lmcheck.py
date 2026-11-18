"""Back-off sanity gate for a built base pack (.lm), run by build_lm.py on every build.

For the sentence start <s> and for the most frequent one-word contexts, every n-gram id (every word class, including
<rare>) must keep a probability within a sane distance of its unigram probability:

    log P(g | c) >= log P(g) - bound

A Kneser-Ney back-off model guarantees log P(g | c) >= log gamma(c) + log P(g) for words never seen after c, where
gamma(c) is the context's back-off weight. For contexts followed by a wide variety of words, such as <s>, "the" or
"and", gamma is large (-1 to -5 nats), so a deficit far beyond that means a corrupted back-off weight or
probability. That is what happened in b80e983: after quantisation the back-off weight of <s> collapsed to
-14.7 and words such as "dashing" lost ~11 nats at the start of a sentence.

Usage: tools/lm/run.sh lmcheck.py <pack.lm> [--report]
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import binfmt  # noqa: E402

BOS, COMMA, RARE, NSPECIAL = 1, 3, 5, 6
BOUND_BOS = 6.0     # <s>: correct packs measure 3.7-4.6 nats (= -log gamma(<s>))
BOUND_CTX = 8.0     # frequent mid-sentence contexts: correct packs measure up to 5.6 (after "I": small gamma)
N_CONTEXTS = 300    # most frequent word contexts checked (by unigram probability)


def context_ids(r, n=N_CONTEXTS):
    uni = r.unigram_logprobs()
    words = np.argsort(-uni[NSPECIAL:])[:n] + NSPECIAL
    return [BOS, COMMA] + [int(w) for w in words]


def gaps(r, c):
    """log P(g | c) - log P(g) for g in <rare> and every word id."""
    uni = r.unigram_logprobs()
    ids = np.concatenate([[RARE], np.arange(NSPECIAL, r.n_ids)])
    return ids, (r.successor_logprobs(c) - uni)[ids]


def check(path, report=False, words=None):
    """Returns a list of violation strings (empty = pass). [words] maps n-gram id -> a word, for messages."""
    r = binfmt.Reader(path)
    bad = []
    for c in context_ids(r):
        ids, g = gaps(r, c)
        bound = BOUND_BOS if c == BOS else BOUND_CTX
        j = int(np.argmin(g))
        name = "<s>" if c == BOS else "," if c == COMMA else (words or {}).get(c, f"id {c}")
        if report and (c in (BOS, COMMA) or g[j] < -3):
            print(f"  context {name!r:>14}: min gap {g[j]:7.2f} at id {int(ids[j])} "
                  f"({(words or {}).get(int(ids[j]), '?')}); 1st pct {np.percentile(g, 1):6.2f}", flush=True)
        if g[j] < -bound:
            n = int((g < -bound).sum())
            bad.append(f"context {name!r}: {n} ids more than {bound} nats below their unigram probability "
                       f"(worst {g[j]:.2f} at id {int(ids[j])} {(words or {}).get(int(ids[j]), '')})")
    return bad


def class_names(locale):
    """n-gram id -> its most frequent word (for readable messages), from the build's vocabulary."""
    from encode import Vocab
    v = Vocab(locale)
    names = {}
    for w, g in zip(v.words, v.ngram_id):
        names.setdefault(int(g), w)
    return names


if __name__ == "__main__":
    a = sys.argv[1:]
    try:
        names = class_names("en")
    except Exception:  # noqa: BLE001 - names are cosmetic
        names = None
    problems = check(a[0], report="--report" in a, words=names)
    for p in problems:
        print("FAIL", p)
    print("PASS" if not problems else f"{len(problems)} context(s) fail", flush=True)
    sys.exit(1 if problems else 0)
