"""Back-off n-gram language models in numpy (orders <= 4, vocabulary <= 65536 ids).

* N-grams are packed into uint64 keys, 16 bits per word, oldest word in the highest bits, so sorting keys
  sorts lexicographically and an n-gram's children (its extensions) form one contiguous key range.
* Counting is external: each order is bucketed by its first word into contiguous key ranges, counted
  chunk by chunk and merged per bucket, so peak memory stays at ~1/NB of the full count table.
* Estimation is interpolated modified Kneser-Ney (Chen & Goodman 1998) with KenLM conventions (<s>-initial
  n-grams keep raw counts; three discounts per order from count-of-counts).  Count cutoffs are applied after
  the statistics are taken from the full counts, keeping the context/suffix closure, and back-off weights
  are then renormalised so every context's distribution sums to one.
* Models can be statically interpolated (union of n-grams, SRILM -mix-lm style), entropy-pruned
  (Stolcke 1998) to a size budget, and quantised.
"""

import glob
import os
import shutil

import numpy as np

UNK, BOS, EOS, COMMA, NUMID, RARE = 0, 1, 2, 3, 4, 5
NSPECIAL = 6
S16 = np.uint64(16)
LOG0 = -99.0


def kmask(k):
    return np.uint64((1 << (16 * k)) - 1)


def first_word(keys, k):
    return (keys >> np.uint64(16 * (k - 1))).astype(np.int64)


def ngram_keys(tok, k):
    """All valid order-k keys of a token stream (<s> only first, </s> only last)."""
    n = len(tok) - k + 1
    if n <= 0:
        return np.empty(0, np.uint64)
    key = tok[0:n].astype(np.uint64)
    for i in range(1, k):
        key = (key << S16) | tok[i:i + n].astype(np.uint64)
    bad = np.zeros(n, bool)
    for i in range(k - 1):
        bad |= tok[i:i + n] == EOS
    for i in range(1 if k > 1 else 0, k):
        bad |= tok[i:i + n] == BOS
    return key[~bad]


def lookup(sorted_keys, q):
    """(index, found) of q in sorted_keys."""
    if len(sorted_keys) == 0:
        return np.zeros(len(q), np.int64), np.zeros(len(q), bool)
    idx = np.searchsorted(sorted_keys, q)
    idx = np.minimum(idx, len(sorted_keys) - 1)
    return idx, sorted_keys[idx] == q


def _unique_counts(keys, counts=None):
    """Sort + aggregate. counts None means each key counts once."""
    if counts is None:
        keys = np.sort(keys)
        if len(keys) == 0:
            return keys, np.empty(0, np.uint32)
        flag = np.empty(len(keys), bool)
        flag[0] = True
        np.not_equal(keys[1:], keys[:-1], out=flag[1:])
        starts = np.flatnonzero(flag)
        c = np.diff(np.append(starts, len(keys))).astype(np.uint32)
        return keys[starts], c
    order = np.argsort(keys, kind="stable")
    keys, counts = keys[order], counts[order]
    del order
    if len(keys) == 0:
        return keys, counts.astype(np.uint32)
    flag = np.empty(len(keys), bool)
    flag[0] = True
    np.not_equal(keys[1:], keys[:-1], out=flag[1:])
    starts = np.flatnonzero(flag)
    return keys[starts], np.add.reduceat(counts.astype(np.uint64), starts).astype(np.uint32)


# ============================================================================ counting

class Buckets:
    """First-word ranges partitioning the key space of every order."""

    def __init__(self, unigram_counts, nb):
        cum = np.cumsum(unigram_counts.astype(np.float64))
        targets = cum[-1] * np.arange(1, nb) / nb
        cuts = np.unique(np.searchsorted(cum, targets) + 1)
        self.bounds = np.concatenate([[0], cuts[cuts < len(unigram_counts)]]).astype(np.int64)
        self.nb = len(self.bounds)

    def of(self, fw):
        return np.searchsorted(self.bounds, fw, side="right") - 1

    def key_cuts(self, k):
        """Key thresholds where buckets start for order k (for splitting a sorted key array)."""
        return (self.bounds.astype(np.uint64) << np.uint64(16 * (k - 1)))


def count(streams, order, out_dir, nb=12, chunk=12_000_000, n_ids=65536):
    """Count all orders 1..order of the given token streams into out_dir/k{k}/b{b}.{keys,cnt}.npy."""
    if os.path.exists(os.path.join(out_dir, "DONE")):
        return
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    uni = np.zeros(n_ids, np.int64)
    for s in streams:
        uni += np.bincount(np.load(s, mmap_mode="r"), minlength=n_ids)[:n_ids]
    bk = Buckets(uni, nb)
    np.save(os.path.join(out_dir, "bounds.npy"), bk.bounds)
    np.save(os.path.join(out_dir, "unigram_raw.npy"), uni)
    part = 0
    for s in streams:
        tok_all = np.load(s, mmap_mode="r")
        pos = 0
        while pos < len(tok_all):
            end = min(len(tok_all), pos + chunk)
            if end < len(tok_all):  # cut after a sentence end
                end = pos + int(np.flatnonzero(tok_all[pos:end] == EOS)[-1]) + 1
            tok = np.asarray(tok_all[pos:end])
            pos = end
            for k in range(1, order + 1):
                keys, cnt = _unique_counts(ngram_keys(tok, k))
                cuts = np.searchsorted(keys, bk.key_cuts(k))
                cuts = np.append(cuts, len(keys))
                d = os.path.join(out_dir, f"k{k}")
                os.makedirs(d, exist_ok=True)
                for b in range(bk.nb):
                    lo, hi = cuts[b], cuts[b + 1]
                    if hi > lo:
                        np.save(os.path.join(d, f"b{b}.p{part}.keys.npy"), keys[lo:hi])
                        np.save(os.path.join(d, f"b{b}.p{part}.cnt.npy"), cnt[lo:hi])
            part += 1
    for k in range(1, order + 1):
        d = os.path.join(out_dir, f"k{k}")
        for b in range(bk.nb):
            _merge_parts(d, f"b{b}")
    open(os.path.join(out_dir, "DONE"), "w").close()


def _merge_parts(d, prefix):
    kp = sorted(glob.glob(os.path.join(d, f"{prefix}.p*.keys.npy")))
    if not kp:
        np.save(os.path.join(d, f"{prefix}.keys.npy"), np.empty(0, np.uint64))
        np.save(os.path.join(d, f"{prefix}.cnt.npy"), np.empty(0, np.uint32))
        return
    keys = np.concatenate([np.load(p) for p in kp])
    cnt = np.concatenate([np.load(p.replace(".keys.", ".cnt.")) for p in kp])
    keys, cnt = _unique_counts(keys, cnt)
    np.save(os.path.join(d, f"{prefix}.keys.npy"), keys)
    np.save(os.path.join(d, f"{prefix}.cnt.npy"), cnt)
    for p in kp:
        os.remove(p)
        os.remove(p.replace(".keys.", ".cnt."))


# ============================================================================ model

class Model:
    """keys[k], logp[k] for k=1..N (keys[1] = arange(V)); bow[k] for k<N (log back-off of order-k contexts)."""

    def __init__(self, order, n_ids):
        self.order, self.n_ids = order, n_ids
        self.keys, self.logp, self.bow = {}, {}, {}
        self.bos_logprob = -2.5  # log marginal probability of being at a sentence start (for pruning)

    def size(self):
        return {k: len(self.keys[k]) for k in self.keys}

    def save(self, path):
        arrs = {"meta": np.array([self.order, self.n_ids], np.int64), "bos": np.array([self.bos_logprob])}
        for k in self.keys:
            arrs[f"keys{k}"], arrs[f"logp{k}"] = self.keys[k], self.logp[k]
            if k in self.bow:
                arrs[f"bow{k}"] = self.bow[k]
        np.savez(path, **arrs)

    @staticmethod
    def load(path):
        z = np.load(path)
        m = Model(int(z["meta"][0]), int(z["meta"][1]))
        m.bos_logprob = float(z["bos"][0])
        for k in range(1, m.order + 1):
            m.keys[k], m.logp[k] = z[f"keys{k}"], z[f"logp{k}"]
            if f"bow{k}" in z:
                m.bow[k] = z[f"bow{k}"]
        return m

    # ---------------------------------------------------------------- queries
    def lp(self, keys, k):
        """Natural-log p(w | h) for order-k keys, backing off as needed."""
        if k == 1:
            return self.logp[1][keys.astype(np.int64)].astype(np.float64)
        out = np.empty(len(keys), np.float64)
        idx, found = lookup(self.keys[k], keys)
        out[found] = self.logp[k][idx[found]]
        nf = ~found
        if nf.any():
            sub = keys[nf]
            cidx, cfound = lookup(self.keys[k - 1], sub >> S16)
            b = np.where(cfound, self.bow[k - 1][cidx], 0.0)
            out[nf] = b + self.lp(sub & kmask(k - 1), k - 1)
        return out

    def score_stream(self, ids):
        """Log prob of every non-<s> position of a token stream using the longest available context."""
        n = len(ids)
        bos_pos = np.flatnonzero(ids == BOS)
        sent_start = np.zeros(n, np.int64)
        sent_start[bos_pos] = bos_pos
        sent_start = np.maximum.accumulate(sent_start)
        hist = np.arange(n) - sent_start  # number of tokens before position within the sentence (incl. <s>)
        out = np.full(n, np.nan)
        ids64 = ids.astype(np.uint64)
        for k in range(1, self.order + 1):
            sel = np.flatnonzero((np.minimum(hist, self.order - 1) == k - 1) & (ids != BOS))
            if len(sel) == 0:
                continue
            key = np.zeros(len(sel), np.uint64)
            for j in range(k - 1, -1, -1):
                key = (key << S16) | ids64[sel - j]
            out[sel] = self.lp(key, k)
        return out

    # ---------------------------------------------------------------- normalisation
    def renormalize(self):
        """Recompute back-off weights so that every context distribution sums to one. Contexts whose explicit
        successors leave (numerically) no back-off mass are clamped; their number per order is kept in
        self.clamped and reported, because a clamped weight is arbitrary (see quantize_model)."""
        self.clamped = {}
        for k in range(2, self.order + 1):
            keys = self.keys[k]
            bow = np.zeros(len(self.keys[k - 1]), np.float32)
            if len(keys):
                ctx = keys >> S16
                starts = np.flatnonzero(np.concatenate([[True], ctx[1:] != ctx[:-1]]))
                p = np.exp(self.logp[k].astype(np.float64))
                q = np.exp(self.lp(keys & kmask(k - 1), k - 1))
                num = 1.0 - np.add.reduceat(p, starts)
                den = 1.0 - np.add.reduceat(q, starts)
                self.clamped[k - 1] = int((num <= 1e-7).sum())
                if self.clamped[k - 1]:
                    print(f"[ngram] warning: {self.clamped[k - 1]} order-{k - 1} contexts have no back-off mass "
                          f"left (min 1-sum p = {num.min():.2e}); their back-off weights are clamped", flush=True)
                num = np.maximum(num, 1e-7)
                den = np.maximum(den, 1e-7)
                cidx, cfound = lookup(self.keys[k - 1], ctx[starts])
                assert cfound.all(), f"order {k}: context missing"
                bow[cidx] = np.log(num / den)
            self.bow[k - 1] = bow


# ============================================================================ Kneser-Ney estimation

def _discounts(a):
    n = [np.count_nonzero(a == r) for r in (1, 2, 3, 4)]
    n = [max(x, 1) for x in n]
    y = n[0] / (n[0] + 2 * n[1])
    d = [0.0, 1 - 2 * y * n[1] / n[0], 2 - 3 * y * n[2] / n[1], 3 - 4 * y * n[3] / n[2]]
    d = [0.0] + [min(max(d[i], 0.05), float(i)) for i in (1, 2, 3)]
    return np.array(d)


def estimate(count_dir, order, n_ids, cutoffs, bos_logprob=None):
    """Interpolated modified Kneser-Ney from bucketed counts, applying count cutoffs (dict order->min count)."""
    bounds = np.load(os.path.join(count_dir, "bounds.npy"))
    nb = len(bounds)
    tmp = os.path.join(count_dir, "cont")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)

    def load(k, b, what):
        return np.load(os.path.join(count_dir, f"k{k}", f"b{b}.{what}.npy"))

    bk = Buckets.__new__(Buckets)
    bk.bounds, bk.nb = bounds, nb
    kept = {}
    required = None
    disc = {}
    for k in range(order, 0, -1):
        # ---- adjusted counts for order k (raw counts at the top order and for <s>-initial n-grams)
        def adjusted(b, keys, raw):
            if k == order:
                return raw.astype(np.int64)
            ck = np.load(os.path.join(tmp, f"k{k}.b{b}.keys.npy"))
            cc = np.load(os.path.join(tmp, f"k{k}.b{b}.cnt.npy"))
            idx, found = lookup(ck, keys)
            a = np.where(found, cc[idx], 0).astype(np.int64)
            bos = first_word(keys, k) == BOS
            a[bos] = raw[bos]
            return a

        # ---- pass 1: count-of-counts, and continuation counts for order k-1 from this order's suffixes
        allc = []
        for b in range(nb):
            keys, raw = load(k, b, "keys"), load(k, b, "cnt")
            a = adjusted(b, keys, raw)
            allc.append(np.minimum(a, 5).astype(np.int8))
            if k > 1:
                suf = keys & kmask(k - 1)
                sk, sc = _unique_counts(suf)
                cuts = np.append(np.searchsorted(sk, bk.key_cuts(k - 1)), len(sk))
                for bb in range(nb):
                    if cuts[bb + 1] > cuts[bb]:
                        np.save(os.path.join(tmp, f"k{k - 1}.b{bb}.p{b}.keys.npy"), sk[cuts[bb]:cuts[bb + 1]])
                        np.save(os.path.join(tmp, f"k{k - 1}.b{bb}.p{b}.cnt.npy"), sc[cuts[bb]:cuts[bb + 1]])
        if k > 1:
            for bb in range(nb):
                _merge_parts(tmp, f"k{k - 1}.b{bb}")
        disc[k] = _discounts(np.concatenate(allc))
        del allc

        # ---- pass 2: interpolation weights per context, cutoffs
        if k == 1:
            keys = np.concatenate([load(1, b, "keys") for b in range(nb)])
            raw = np.concatenate([load(1, b, "cnt") for b in range(nb)])
            a = np.concatenate([adjusted(b, load(1, b, "keys"), load(1, b, "cnt")) for b in range(nb)])
            dense = np.zeros(n_ids, np.float64)
            dense[keys.astype(np.int64)] = a
            dense[BOS] = 0
            d = disc[1][np.minimum(dense, 3).astype(np.int64)]
            S = dense.sum()
            u = np.maximum(dense - d, 0) / S
            gamma = (disc[1][1] * (dense == 1).sum() + disc[1][2] * (dense == 2).sum() + disc[1][3] * (dense >= 3).sum()) / S
            kept[1] = (np.arange(n_ids, dtype=np.uint64), u, np.full(n_ids, gamma))
            break
        ks, us, gs = [], [], []
        for b in range(nb):
            keys, raw = load(k, b, "keys"), load(k, b, "cnt")
            if len(keys) == 0:
                continue
            a = adjusted(b, keys, raw).astype(np.float64)
            ctx = keys >> S16
            starts = np.flatnonzero(np.concatenate([[True], ctx[1:] != ctx[:-1]]))
            grp = np.repeat(np.arange(len(starts)), np.diff(np.append(starts, len(keys))))
            S = np.add.reduceat(a, starts)
            n1 = np.add.reduceat((a == 1).astype(np.float64), starts)
            n2 = np.add.reduceat((a == 2).astype(np.float64), starts)
            n3 = np.add.reduceat((a >= 3).astype(np.float64), starts)
            D = disc[k]
            gamma = (D[1] * n1 + D[2] * n2 + D[3] * n3) / S
            u = (a - D[np.minimum(a, 3).astype(np.int64)]) / S[grp]
            keep = raw >= cutoffs.get(k, 1)
            if required is not None:
                _, found = lookup(required, keys)
                keep |= found
            ks.append(keys[keep]); us.append(u[keep].astype(np.float32)); gs.append(gamma[grp][keep].astype(np.float32))
        kk = np.concatenate(ks)
        kept[k] = (kk, np.concatenate(us), np.concatenate(gs))
        required = np.unique(np.concatenate([kk >> S16, kk & kmask(k - 1)])) if k > 1 else None
        print(f"    order {k}: kept {len(kk):,}  D={np.round(disc[k][1:], 3)}", flush=True)
    shutil.rmtree(tmp, ignore_errors=True)

    # ---- bottom-up interpolation
    m = Model(order, n_ids)
    _, u1, g1 = kept[1]
    p1 = u1 + g1 / (n_ids - 1)
    p1[BOS] = 0.0
    m.keys[1] = kept[1][0]
    m.logp[1] = np.where(p1 > 0, np.log(np.maximum(p1, 1e-300)), LOG0).astype(np.float32)
    plin = {1: p1}
    for k in range(2, order + 1):
        keys, u, g = kept[k]
        idx, found = lookup(m.keys[k - 1], keys & kmask(k - 1))
        assert found.all(), f"order {k}: suffix closure violated"
        p = u.astype(np.float64) + g.astype(np.float64) * plin[k - 1][idx]
        plin[k] = p
        m.keys[k] = keys
        m.logp[k] = np.log(p).astype(np.float32)
    if bos_logprob is not None:
        m.bos_logprob = bos_logprob
    m.renormalize()
    return m


# ============================================================================ mixing

def interpolate(models, weights):
    """Static linear interpolation of models into one back-off model over the union of their n-grams."""
    order = max(m.order for m in models)
    out = Model(order, models[0].n_ids)
    out.bos_logprob = float(np.log(sum(w * np.exp(m.bos_logprob) for m, w in zip(models, weights))))
    for k in range(1, order + 1):
        keys = models[0].keys[1] if k == 1 else np.unique(np.concatenate([m.keys[k] for m in models if k in m.keys]))
        p = np.zeros(len(keys))
        for m, w in zip(models, weights):
            kk = min(k, m.order)
            q = keys if kk == k else keys & kmask(kk)
            p += w * np.exp(m.lp(q, kk))
        out.keys[k] = keys
        out.logp[k] = np.log(np.maximum(p, 1e-300)).astype(np.float32)
        if k == 1:
            out.logp[1][BOS] = LOG0
        print(f"    mixed order {k}: {len(keys):,}", flush=True)
    out.renormalize()
    return out


def em_weights(models, ids, iters=20):
    """Interpolation weights maximising likelihood of a dev token stream."""
    P = np.stack([np.exp(m.score_stream(ids)) for m in models])
    P = P[:, ~np.isnan(P[0])]
    w = np.full(len(models), 1.0 / len(models))
    for _ in range(iters):
        post = w[:, None] * P
        post /= post.sum(0, keepdims=True)
        w = post.mean(1)
    return w


# ============================================================================ entropy pruning

def prune_scores(m):
    """Stolcke's relative-entropy increase for removing each n-gram of order >= 2 (computed independently)."""
    # log marginal probability of every n-gram (as a history)
    logpm = {1: m.logp[1].astype(np.float64).copy()}
    logpm[1][BOS] = m.bos_logprob
    for k in range(2, m.order):
        idx, found = lookup(m.keys[k - 1], m.keys[k] >> S16)
        logpm[k] = logpm[k - 1][idx] + m.logp[k]
    scores = {}
    for k in range(2, m.order + 1):
        keys = m.keys[k]
        ctx = keys >> S16
        starts = np.flatnonzero(np.concatenate([[True], ctx[1:] != ctx[:-1]]))
        grp = np.repeat(np.arange(len(starts)), np.diff(np.append(starts, len(keys))))
        lp_ = m.logp[k].astype(np.float64)
        p = np.exp(lp_)
        lq = m.lp(keys & kmask(k - 1), k - 1)
        q = np.exp(lq)
        num = np.maximum(1.0 - np.add.reduceat(p, starts), 1e-9)[grp]
        den = np.maximum(1.0 - np.add.reduceat(q, starts), 1e-9)[grp]
        lb = np.log(num / den)
        lb2 = np.log((num + p) / (den + q))
        cidx, _ = lookup(m.keys[k - 1], ctx)
        ph = np.exp(logpm[k - 1][cidx])
        scores[k] = -ph * (p * (lq + lb2 - lp_) + num * (lb2 - lb))
    return scores


def prune_to(m, scores, size_fn, target_bytes):
    """Remove the n-grams with the smallest scores (keeping every context of a kept n-gram) until the model
    fits target_bytes according to size_fn(counts dict). Binary search on a global threshold."""

    def keep_sets(theta):
        keeps = {}
        child_ctx = None
        for k in range(m.order, 1, -1):
            keep = scores[k] >= theta
            if child_ctx is not None:
                _, found = lookup(child_ctx, m.keys[k])
                keep |= found
            keeps[k] = keep
            child_ctx = np.unique(m.keys[k][keep] >> S16)
        return keeps

    def nbytes(theta):
        keeps = keep_sets(theta)
        counts = {1: len(m.keys[1])}
        counts.update({k: int(v.sum()) for k, v in keeps.items()})
        return size_fn(counts), counts

    lo, hi = -30.0, 0.0  # log10 thresholds
    for _ in range(30):
        mid = (lo + hi) / 2
        b, counts = nbytes(10 ** mid)
        if b > target_bytes:
            lo = mid
        else:
            hi = mid
    theta = 10 ** hi
    keeps = keep_sets(theta)
    for k, keep in keeps.items():
        m.keys[k] = m.keys[k][keep]
        m.logp[k] = m.logp[k][keep]
    m.renormalize()
    return theta


# ============================================================================ quantisation

def quantize_values(x, bits):
    """Equal-population binning (KenLM style). Returns (codes uint, centers float32)."""
    n = 1 << bits
    if len(x) == 0:
        return np.zeros(0, np.uint32), np.zeros(n, np.float32)
    xs = np.sort(x)
    edges = xs[np.minimum((np.arange(1, n) * len(xs)) // n, len(xs) - 1)]
    edges = np.unique(edges)
    codes = np.searchsorted(edges, x, side="right")
    sums = np.bincount(codes, weights=x, minlength=len(edges) + 1)
    cnts = np.bincount(codes, minlength=len(edges) + 1)
    centers = np.where(cnts > 0, sums / np.maximum(cnts, 1), 0.0)
    out = np.zeros(n, np.float32)
    out[: len(centers)] = centers
    return codes.astype(np.uint32), out


def quantize_model(m, prob_bits=8, bow_bits=8):
    """Quantise probabilities (orders >= 2) and back-off weights (orders 2..N-1). Returns tables
    {('p'|'b', k): (codes, centers)} and updates m in place to the quantised values.

    The back-off weights are those of the unquantised model (as in KenLM); they are NOT recomputed from the
    quantised probabilities. Renormalising after quantisation charges a context's whole rounding error to its
    back-off mass. For a context followed by almost every word, such as <s>, that mass is tiny (0.6%), and the
    upward rounding of its frequent successors exceeded it: the quantised successors summed to 1.0012, the
    back-off weight of <s> collapsed from -3.7 to -14.7, and every word never seen sentence-initially lost
    ~11 nats at the start of a sentence (en pack 1.1 as built in b80e983)."""
    tables = {}
    for k in range(2, m.order + 1):
        codes, centers = quantize_values(m.logp[k].astype(np.float64), prob_bits)
        m.logp[k] = centers[codes]
        tables[("p", k)] = (codes, centers)
    for k in range(1, m.order):
        if k == 1:
            continue  # unigram back-offs are stored at 16-bit precision
        codes, centers = quantize_values(m.bow[k].astype(np.float64), bow_bits)
        m.bow[k] = centers[codes]
        tables[("b", k)] = (codes, centers)
    return tables
