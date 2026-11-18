"""Dialect overlays: per-dialect spelling choice and lexical preferences on top of the shared base model.

For a word w of n-gram class g (a word, or a VarCon spelling class {color, colour}):

    log P_d(w | h) = log P_base(g | h) + inclass_base(w) + adjust_d(w) - log Z_d

* inclass_base(w): log share of w inside its class in the shared training data (stored in the base pack);
* adjust_d(w) = log share_d(w | g) - inclass_base(w) + bias_d(g)   (stored in <dialect>.lmo)
  - share_d: the dialect's spelling preference inside a spelling class: a VarCon prior (preferred / accepted
    variant / foreign spelling) updated with counts from that dialect's text that was NOT selected by spelling;
  - bias_d(g): lexical preference (mum vs mom, whilst, queue ...).  From genre-matched web text of each
    dialect we estimate the shrunk log-odds LO(g) = log p_GB(g) / p_US(g); the base model is a mixture
    s_GB p_GB + s_US p_US of the dialects, so bias_GB = -log(s_GB + s_US e^-LO), bias_US = -log(s_GB e^LO + s_US).
* Z_d renormalises the unigram marginal (a constant per dialect: rankings are unaffected).
The other dialect's spellings stay in the lexicon (known words, never typos) but rank lower.
"""

import os

import numpy as np

import lexicon as lexmod
from ngram import RARE, NSPECIAL
from paths import work

TAU = 10.0            # pseudo-occurrences shrinking log-odds towards 0
SPELL_PRIOR_N = 20.0  # pseudo-occurrences of the VarCon prior when estimating spelling shares
MIN_BIAS = 0.1


def base_inclass(vocab):
    """log P(word | n-gram class) in the shared data, per lexicon entry (vocab order)."""
    f = np.array(vocab.freq)
    g = vocab.ngram_id
    tot = np.bincount(g, weights=f, minlength=vocab.n_ids)
    return np.log(np.maximum(f, 1e-30) / np.maximum(tot[g], 1e-30))


_count_cache = {}
_tags = None
_shares_cache = {}


def _varcon_tags():
    global _tags
    if _tags is None:
        _tags = {}
        for row in lexmod.load_varcon():
            for w, t in row["words"].items():
                _tags.setdefault(w, set()).update(t)
    return _tags


def word_counts(locale, vocab, sources):
    """Occurrence counts per lexicon entry over the train splits of the given extract outputs."""
    key = (locale, tuple(sources))
    if key in _count_cache:
        return _count_cache[key]
    c = np.zeros(len(vocab.words))
    text_dir = work(locale, "text")
    for s in sources:
        path = os.path.join(text_dir, f"{s}.counts.tsv")
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            for line in f:
                tok, mid, init = line.rstrip("\n").split("\t")
                mid, init = int(mid), int(init)
                if mid:
                    i = vocab.lex_id(tok, False)
                    if i >= 0:
                        c[i] += mid
                if init:
                    i = vocab.lex_id(tok, True)
                    if i >= 0:
                        c[i] += init
    _count_cache[key] = c
    return c


def _units(vocab, c):
    """Aggregate per-word counts to units: n-gram classes (ids >= NSPECIAL), and single words for <rare>."""
    g = vocab.ngram_id
    cls = np.bincount(g, weights=c, minlength=vocab.n_ids)
    return cls


def base_dialect_shares(L, weights):
    """Share of each dialect in the base model's training mass (mixture weights x token shares)."""
    key = (L.locale, tuple(np.round(weights, 6)))
    if key not in _shares_cache:
        _shares_cache[key] = _base_dialect_shares(L, weights)
    return _shares_cache[key]


def _base_dialect_shares(L, weights):
    enc = work(L.locale, "enc")

    def ntok(names):
        t = 0
        for n in names:
            p = os.path.join(enc, f"{n}.train.ids.npy")
            if os.path.exists(p):
                t += len(np.load(p, mmap_mode="r"))
        return t

    comp = {"chat": (L.chat_sources, weights[0]), "general": (L.general_sources, weights[1])}
    if len(weights) > 2:
        comp["synthetic"] = (L.synthetic_sources, weights[2])
    shares = {}
    for dl in L.dialects.values():
        s = 0.0
        for names, w in comp.values():
            tot = ntok(names)
            mine = ntok([n for n in names if n in dl.text_sources])
            s += w * mine / max(1, tot)
        shares[dl.locale] = s
    unmarked = [dl.locale for dl in L.dialects.values() if dl.unmarked]
    if unmarked:
        shares[unmarked[0]] += max(0.0, 1.0 - sum(shares.values()))
    return shares


SPELL_MARGIN = float(np.log(4.0))  # the dialect's spelling is at least 4x as likely as any variant


def variant_groups(vocab):
    groups = {}
    for i, c in enumerate(vocab.cls):
        groups.setdefault(c, []).append(i)
    return [m for m in groups.values() if len(m) > 1]


def preferred_members(vocab, mem, tags, X):
    """Members that are dialect X's spelling: VarCon tag X (preferred) or X. (equally accepted, e.g. British
    'among' and 'amongst'). Variants (Xv, XV, X-, Xx) and the other dialect's spellings are not preferred."""
    return [i for i in mem if X in tags.get(vocab.words[i], ()) or (X + ".") in tags.get(vocab.words[i], ())]


def enforce_spelling_order(vocab, adjust, tags, X):
    """Lowers non-preferred spellings so that, in each variant group, the best of the dialect's spellings has
    the highest prior and every other spelling is at least SPELL_MARGIN below it. Returns (groups whose top
    member was not a dialect spelling before, groups checked, examples). Priors compared: log freq + adjust,
    which orders both the lexicon prior and every in-context probability of the group's members."""
    bad, checked, examples = 0, 0, []
    logf = np.log(np.maximum(np.array(vocab.freq), 1e-30))
    for mem in variant_groups(vocab):
        pref = preferred_members(vocab, mem, tags, X)
        others = [i for i in mem if i not in pref]
        if not pref or not others:
            continue
        checked += 1
        prior = {i: logf[i] + adjust[i] for i in mem}
        p = max(pref, key=lambda i: prior[i])
        worst = max(others, key=lambda i: prior[i])
        if prior[p] <= prior[worst]:
            bad += 1
            examples.append(f"{vocab.words[p]}<{vocab.words[worst]}")
        for i in others:
            excess = prior[i] - (prior[p] - SPELL_MARGIN)
            if excess > 0:
                adjust[i] -= excess
    return bad, checked, examples


def check_spelling_order(vocab, adjust, X):
    """Number of variant groups whose top-ranked member is not one of dialect X's spellings (must be 0)."""
    tags = _varcon_tags()
    logf = np.log(np.maximum(np.array(vocab.freq), 1e-30))
    bad = 0
    for mem in variant_groups(vocab):
        pref = preferred_members(vocab, mem, tags, X)
        others = [i for i in mem if i not in pref]
        if pref and others and max(logf[i] + adjust[i] for i in others) >= max(logf[i] + adjust[i] for i in pref):
            bad += 1
    return bad


def tune(L, dl, vocab, model, inclass, weights, dev_streams):
    """Grid-search the lexical-bias hyper-parameters on held-out dialect text (dev splits)."""
    lp_parts, lex_parts = [], []
    for ids_path in dev_streams:
        ids = np.load(ids_path)
        lex = np.load(ids_path.replace(".ids.npy", ".lex.npy")).astype(np.int64)
        lp = model.score_stream(ids)
        mask = ((ids >= NSPECIAL) | (ids == RARE)) & (lex >= 0)
        lp_parts.append(lp[mask])
        lex_parts.append(lex[mask])
    lp, lex = np.concatenate(lp_parts), np.concatenate(lex_parts)
    base = lp + inclass[lex]
    best = None
    for tau in (10.0, 30.0, 100.0):
        for dead in (0.0, 0.4, 0.8):
            for scale in (0.5, 0.75, 1.0):
                adj, _, log_z, _ = overlay(L, dl, vocab, model, inclass, weights, tau, dead, scale, quiet=True)
                ppl = float(np.exp(-(base + adj[lex] - log_z).mean()))
                if best is None or ppl < best[0]:
                    best = (ppl, tau, dead, scale)
    ppl0 = float(np.exp(-base.mean()))
    return best, ppl0


def overlay(L, dl, vocab, model, inclass, weights, tau=TAU, dead=0.0, scale=1.0, quiet=False):
    n_lex = len(vocab.words)
    g = vocab.ngram_id
    others = [o for o in L.dialects.values() if o.locale != dl.locale]
    assert len(others) == 1, "overlay estimation assumes two dialects"
    other = others[0]
    shares = base_dialect_shares(L, weights)
    s_me, s_other = shares[dl.locale], shares[other.locale]

    # ---- lexical log-odds per unit (class, or word for <rare>)
    c_me, c_ot = word_counts(L.locale, vocab, dl.text_sources), word_counts(L.locale, vocab, other.text_sources)
    n_me, n_ot = c_me.sum(), c_ot.sum()
    r_me, r_ot = n_me / (n_me + n_ot), n_ot / (n_me + n_ot)

    def log_odds(a, b):
        lo_ = np.log((a + tau * r_me) / (b + tau * r_ot)) - np.log(n_me / n_ot)
        lo_ = np.sign(lo_) * np.maximum(np.abs(lo_) - dead, 0.0)  # dead zone: ignore weak evidence
        return lo_ * scale

    lo_cls = log_odds(_units(vocab, c_me), _units(vocab, c_ot))
    lo_word = log_odds(c_me, c_ot)
    # n-gram ids shared by several words are exactly the VarCon spelling classes
    multi = np.bincount(g, minlength=vocab.n_ids) > 1
    spelling_class = multi & (np.arange(vocab.n_ids) >= NSPECIAL)
    lo_cls[spelling_class] = 0.0  # spelling is handled by shares; routing used spelling, so avoid circularity
    lo = np.where(g == RARE, lo_word, lo_cls[g])

    def bias_from(lo_):
        b = -np.log(s_me + s_other * np.exp(-lo_))
        b = np.clip(b, -4.0, 2.5)
        b[np.abs(b) < MIN_BIAS] = 0.0
        return b

    bias = bias_from(lo)
    bias[g < NSPECIAL] = 0.0
    bias[(g == RARE) & ((c_me + c_ot) < 20)] = 0.0

    # ---- spelling shares inside multi-member classes
    tags = _varcon_tags()
    c_sp = word_counts(L.locale, vocab, dl.spelling_sources)
    adjust = bias.copy()
    X = dl.varcon
    members = {}
    for i in np.flatnonzero(multi[g] & (g >= NSPECIAL)):
        members.setdefault(int(g[i]), []).append(i)
    n_spell = 0
    for cl, mem in members.items():
        if not any(vocab.cls[i] != vocab.words[i] for i in mem):
            continue  # not a spelling class (e.g. case variants never share ids, but be safe)
        prior = np.array([0.95 if X in tags.get(vocab.words[i], ()) else
                          0.15 if (X + "v") in tags.get(vocab.words[i], ()) or (X + ".") in tags.get(vocab.words[i], ())
                          else 0.03 for i in mem])
        prior /= prior.sum()
        cnt = c_sp[mem]
        share = (cnt + SPELL_PRIOR_N * prior) / (cnt.sum() + SPELL_PRIOR_N)
        pref = int(np.argmax(prior))
        share[pref] = max(share[pref], 0.6)
        share = np.maximum(share, 0.01)
        share /= share.sum()
        for j, i in enumerate(mem):
            adjust[i] = np.log(share[j]) - inclass[i] + bias[i]
        n_spell += 1

    # ---- dialect spelling order is a hard rule: in every variant group (words sharing a variantKey, including
    # groups outside the n-gram vocabulary) the dialect's VarCon spelling must outrank every other spelling by
    # at least SPELL_MARGIN, whatever mixed-dialect corpus counts say
    misordered = enforce_spelling_order(vocab, adjust, tags, X)

    # ---- normaliser of the unigram marginal
    p_uni = np.exp(model.logp[1].astype(np.float64))
    z = p_uni[:NSPECIAL].sum() - p_uni[RARE]
    contrib = np.exp(inclass + adjust)
    z += np.bincount(g, weights=contrib, minlength=vocab.n_ids)[NSPECIAL:] @ p_uni[NSPECIAL:] / 1.0
    z += p_uni[RARE] * contrib[g == RARE].sum()
    log_z = float(np.log(z))

    if quiet:
        return adjust, None, log_z, None
    order = np.argsort(-bias)
    top = [vocab.words[i] for i in order[:30] if bias[i] > 0][:20]
    low = [vocab.words[i] for i in order[::-1][:200] if bias[i] < 0 and vocab.freq[i] > 1e-6][:15]
    stats = (f"shares {s_me:.2f}/{s_other:.2f}, {int((adjust != 0).sum()):,} adjusted words, {n_spell:,} spelling "
             f"classes, {misordered[0]}/{misordered[1]} variant groups had the wrong spelling first before the "
             f"spelling-order rule ({', '.join(misordered[2][:8])}), logZ={log_z:.4f}\n"
             f"    boosted: {' '.join(top)}\n    lowered: {' '.join(low)}")
    return adjust, np.zeros(n_lex, np.uint8), log_z, stats
