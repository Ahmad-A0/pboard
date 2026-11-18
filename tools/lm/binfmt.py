"""Writer (and a small reader for verification) of the pboard language-model pack format.

See docs/lm-format.md for the specification.  Base pack: <locale>.lm ("PBLM"); dialect overlay: <dialect>.lmo
("PBLO").  Everything is little-endian; sections are 8-byte aligned and padded with 8 zero bytes so a reader
can always load a 64-bit word at any record's byte offset.
"""

import hashlib
import os
import struct

import numpy as np

from ngram import S16, kmask, lookup

MAGIC_BASE, MAGIC_OVERLAY = b"PBLM", b"PBLO"
VERSION = (1, 1)  # 1.1 adds LEX_LOGFREQ and VARIANTS (readers treat them as optional)
HEADER_SIZE = 1024

# section ids
SEC_STRINGS, SEC_STR_OFFSETS, SEC_LEX_NGRAM, SEC_LEX_INCLASS, SEC_LEX_FLAGS = 1, 2, 3, 4, 5
SEC_HASH_LOWER, SEC_CLASS_START, SEC_CLASS_MEMBERS = 7, 8, 9
SEC_CASE_TABLE, SEC_FOLD_TABLE, SEC_UNIGRAM, SEC_LOCALE_RULES = 11, 12, 13, 14
SEC_LEX_LOGFREQ, SEC_VARIANTS = 15, 16
SEC_ORDER_BASE, SEC_QUANT_BASE = 20, 40       # + order (2..8)
SEC_OVL_ADJUST, SEC_OVL_FLAGS = 60, 61

FLAG_COMPOUNDING = 1
FLAG_CLITICS = 2
MILLI = 1000.0  # fixed-point scale of 16-bit log values (milli-nats)


def fnv1a_utf16(s: str) -> int:
    h = 0x811C9DC5
    data = s.encode("utf-16-le", "surrogatepass")
    for i in range(0, len(data), 2):
        h ^= data[i] | (data[i + 1] << 8)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def hash_table(keys, n_slots_min):
    """Open addressing (linear probing) table of value+1 for each key (first occurrence wins)."""
    size = 1
    while size < n_slots_min:
        size <<= 1
    table = np.zeros(size, np.uint32)
    seen = set()
    for value, key in keys:
        if key in seen:
            continue
        seen.add(key)
        i = fnv1a_utf16(key) & (size - 1)
        while table[i]:
            i = (i + 1) & (size - 1)
        table[i] = value + 1
    return table


def ptr_bits(n):
    return max(1, int(n).bit_length())


def record_bits(order, k, counts, word_bits=16, prob_bits=8, bow_bits=8):
    if k == order:
        return word_bits + prob_bits
    return word_bits + prob_bits + bow_bits + ptr_bits(counts[k + 1])


def pack_records(fields):
    """fields: list of (values uint64 array, nbits). Returns (bytes, record_bits)."""
    n = len(fields[0][0])
    rb = sum(b for _, b in fields)
    assert rb <= 57, f"record of {rb} bits exceeds 57"
    total_bits = n * rb
    words = np.zeros((total_bits + 63) // 64 + 2, np.uint64)
    pos = np.arange(n, dtype=np.uint64) * np.uint64(rb)
    shift = 0
    for vals, b in fields:
        v = vals.astype(np.uint64) & np.uint64((1 << b) - 1)
        bp = pos + np.uint64(shift)
        wi = (bp >> np.uint64(6)).astype(np.int64)
        off = bp & np.uint64(63)
        np.bitwise_or.at(words, wi, v << off)
        spill = off + np.uint64(b) > np.uint64(64)
        if spill.any():
            np.bitwise_or.at(words, wi[spill] + 1, v[spill] >> (np.uint64(64) - off[spill]))
        shift += b
    nbytes = (total_bits + 7) // 8
    return words.view(np.uint8)[:nbytes].tobytes(), rb


def size_estimate(order, counts, lexicon_bytes):
    total = lexicon_bytes + (counts[1] + 1) * 8 + 4096
    for k in range(2, order + 1):
        total += (counts[k] + (1 if k < order else 0)) * record_bits(order, k, counts) / 8 + 2048
    return int(total)


class SectionWriter:
    def __init__(self):
        self.sections = []  # (id, bytes)

    def add(self, sid, data):
        if isinstance(data, np.ndarray):
            data = data.astype(data.dtype.newbyteorder("<"), copy=False).tobytes()
        self.sections.append((sid, data))

    def write(self, path, header_fields):
        table_off = 128
        assert table_off + len(self.sections) * 24 <= HEADER_SIZE
        offset = HEADER_SIZE
        layout = []
        for sid, data in self.sections:
            layout.append((sid, offset, len(data)))
            offset += len(data) + 8
            offset = (offset + 7) & ~7
        header = bytearray(HEADER_SIZE)
        header[: len(header_fields)] = header_fields
        struct.pack_into("<I", header, 124, len(self.sections))
        for i, (sid, off, ln) in enumerate(layout):
            struct.pack_into("<IIQQ", header, table_off + 24 * i, sid, 0, off, ln)
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(header)
            for (sid, data), (_, off, ln) in zip(self.sections, layout):
                f.seek(off)
                f.write(data)
                f.write(b"\0" * 8)
            f.truncate(offset)
        os.replace(tmp, path)
        return offset


def pack_order(words, tn):
    """Pack lexicon order: sorted by (fold key, lower key) in UTF-16 order, then frequency (input order).
    Returns perm with perm[pack_id] = input index.  Prefix completions are then contiguous pack-id ranges and
    the case variants of a word are adjacent."""
    from textnorm import utf16_key
    keyed = [(utf16_key(tn.fold(w)), utf16_key(tn.lower(w)), i) for i, w in enumerate(words)]
    keyed.sort()
    return np.array([i for _, _, i in keyed], np.int64)


def lexicon_bytes_estimate(words):
    n = len(words)
    slots = 1
    while slots < n * 0.95 / 0.82:  # ~0.95 lowercase groups per entry, load factor <= 0.82
        slots <<= 1
    return sum(len(w.encode("utf-8")) for w in words) + n * (4 + 2 + 2 + 1 + 4) + slots * 4 + 65537 * 4


def write_base(path, locale, L, tn, m, quant, vocab, inclass, oov_penalty, build_id):
    """m: quantised Model; quant: tables from ngram.quantize_model; vocab: encode.Vocab;
    inclass: float array (log P(word | its n-gram id)) per lexicon entry (vocab order).
    Returns (file size, perm) where perm maps pack lexicon ids to vocab indices."""
    order, n_ids = m.order, m.n_ids
    perm = pack_order(vocab.words, tn)
    words = [vocab.words[i] for i in perm]
    n_lex = len(words)
    sw = SectionWriter()

    # ---- lexicon (pack order)
    enc = [w.encode("utf-8") for w in words]
    offs = np.zeros(n_lex + 1, np.uint32)
    offs[1:] = np.cumsum([len(e) for e in enc])
    ngram_of = vocab.ngram_id[perm].astype(np.uint16)
    sw.add(SEC_STRINGS, b"".join(enc))
    sw.add(SEC_STR_OFFSETS, offs)
    sw.add(SEC_LEX_NGRAM, ngram_of)
    sw.add(SEC_LEX_INCLASS, np.clip(np.round(inclass[perm] * MILLI), -32767, 0).astype(np.int16))
    sw.add(SEC_LEX_FLAGS, np.array(vocab.flags, np.uint8)[perm])
    # context-free prior: log relative frequency in the training mix (the unigram of a Kneser-Ney model is a
    # continuation probability, a poor prior for words that follow few contexts, e.g. "Francisco")
    logf = np.log(np.maximum(np.array(vocab.freq, np.float64)[perm], 1e-14))
    sw.add(SEC_LEX_LOGFREQ, np.clip(np.round(logf * MILLI), -32767, 0).astype(np.int16))
    # dialect spelling variants (VarCon classes), including pairs outside the n-gram vocabulary:
    # (lexId, lexId of the class representative) pairs sorted by lexId
    pack_of = np.empty(n_lex, np.int64)
    pack_of[perm] = np.arange(n_lex)
    cls_count = {}
    for c in vocab.cls:
        cls_count[c] = cls_count.get(c, 0) + 1
    pairs = sorted((int(pack_of[i]), int(pack_of[vocab.exact[c]])) for i, c in enumerate(vocab.cls)
                   if cls_count[c] > 1 and c in vocab.exact)
    sw.add(SEC_VARIANTS, np.array(pairs, np.uint32).reshape(-1))
    lower = [tn.lower(w) for w in words]
    groups = [(i, lk) for i, lk in enumerate(lower) if i == 0 or lower[i - 1] != lk]  # first id of each group
    sw.add(SEC_HASH_LOWER, hash_table(groups, int(len(groups) / 0.82)))  # linear probing, load <= 0.82
    # n-gram id -> member lexicon ids (most frequent first); <rare> and specials have no members listed
    members = [[] for _ in range(n_ids)]
    for pid in range(n_lex):
        g = int(ngram_of[pid])
        if g >= 6:
            members[g].append(pid)
    for g in range(n_ids):
        members[g].sort(key=lambda pid: perm[pid])  # vocab order = frequency order
    mstart = np.zeros(n_ids + 1, np.uint32)
    mstart[1:] = np.cumsum([len(x) for x in members])
    sw.add(SEC_CLASS_START, mstart)
    sw.add(SEC_CLASS_MEMBERS, np.array([p for x in members for p in x], np.uint32))

    # ---- locale rules: case overrides, fold table for every character of the lexicon (and its upper case)
    chars = set("".join(words))
    chars |= {c.upper() for c in chars if len(c.upper()) == 1}
    case_lines = [f"{k}\t{v}" for k, v in sorted(L.case_overrides.items())]
    fold_lines = []
    for c in sorted(chars):
        fc = tn.fold(c)
        if fc != c:
            fold_lines.append(f"{c}\t{fc}")
    sw.add(SEC_CASE_TABLE, "\n".join(case_lines).encode("utf-8"))
    sw.add(SEC_FOLD_TABLE, "\n".join(fold_lines).encode("utf-8"))
    rules = {"clitics": list(L.clitics), "linking": list(L.linking_morphemes)}
    sw.add(SEC_LOCALE_RULES, "\n".join(f"{k}\t{' '.join(v)}" for k, v in rules.items()).encode("utf-8"))

    # ---- unigram table: i16 logp, i16 bow (milli-nats), u32 child start; plus sentinel
    child_start = np.zeros(n_ids + 1, np.uint32)
    if order >= 2:
        fw = (m.keys[2] >> np.uint64(16)).astype(np.int64)
        child_start[:] = np.searchsorted(fw, np.arange(n_ids + 1))
    uni = np.zeros(n_ids + 1, dtype=[("lp", "<i2"), ("bow", "<i2"), ("child", "<u4")])
    uni["lp"][:n_ids] = np.clip(np.round(m.logp[1] * MILLI), -32767, 0)
    uni["bow"][:n_ids] = np.clip(np.round(m.bow[1] * MILLI), -32767, 32767) if order > 1 else 0
    uni["child"] = child_start
    sw.add(SEC_UNIGRAM, uni.tobytes())

    # ---- higher orders
    counts = {k: len(m.keys[k]) for k in range(1, order + 1)}
    rec_bits = {}
    for k in range(2, order + 1):
        keys = m.keys[k]
        word = (keys & np.uint64(0xFFFF)).astype(np.uint64)
        pcodes = quant[("p", k)][0].astype(np.uint64)
        fields = [(word, 16), (pcodes, 8)]
        if k < order:
            bcodes = quant[("b", k)][0].astype(np.uint64)
            nxt_keys = m.keys[k + 1] >> S16
            child = np.searchsorted(nxt_keys, keys).astype(np.uint64)
            # sentinel record: child start = count of next order
            word = np.append(word, 0).astype(np.uint64)
            pcodes = np.append(pcodes, 0).astype(np.uint64)
            bcodes = np.append(bcodes, 0).astype(np.uint64)
            child = np.append(child, counts[k + 1]).astype(np.uint64)
            fields = [(word, 16), (pcodes, 8), (bcodes, 8), (child, ptr_bits(counts[k + 1]))]
        data, rb = pack_records(fields)
        rec_bits[k] = rb
        sw.add(SEC_ORDER_BASE + k, data)
        q = [quant[("p", k)][1]] + ([quant[("b", k)][1]] if k < order else [np.zeros(256, np.float32)])
        sw.add(SEC_QUANT_BASE + k, np.concatenate(q).astype(np.float32))

    flags = (FLAG_COMPOUNDING if L.compounding else 0) | (FLAG_CLITICS if L.clitics else 0)
    hdr = bytearray(128)
    hdr[0:4] = MAGIC_BASE
    struct.pack_into("<HHII", hdr, 4, VERSION[0], VERSION[1], HEADER_SIZE, flags)
    hdr[16:32] = locale.encode("ascii").ljust(16, b"\0")
    struct.pack_into("<III", hdr, 32, order, n_ids, n_lex)
    for k in range(1, 9):
        struct.pack_into("<I", hdr, 44 + 4 * (k - 1), counts.get(k, 0))
    for k in range(2, 9):
        struct.pack_into("<B", hdr, 76 + k, rec_bits.get(k, 0))
    struct.pack_into("<f", hdr, 88, oov_penalty)
    struct.pack_into("<f", hdr, 92, m.bos_logprob)
    hdr[96:104] = build_id
    return sw.write(path, bytes(hdr)), perm


def write_overlay(path, dialect, base_build_id, adjust, flags_or, log_z, perm):
    """adjust/flags_or are in vocab order; perm maps pack ids to vocab indices (from write_base)."""
    adjust, flags_or = adjust[perm], flags_or[perm]
    sw = SectionWriter()
    sw.add(SEC_OVL_ADJUST, np.clip(np.round(adjust * MILLI), -32767, 32767).astype(np.int16))
    sw.add(SEC_OVL_FLAGS, flags_or.astype(np.uint8))
    hdr = bytearray(128)
    hdr[0:4] = MAGIC_OVERLAY
    struct.pack_into("<HHII", hdr, 4, VERSION[0], VERSION[1], HEADER_SIZE, 0)
    hdr[16:32] = dialect.encode("ascii").ljust(16, b"\0")
    struct.pack_into("<I", hdr, 40, len(adjust))
    struct.pack_into("<f", hdr, 88, log_z)
    hdr[96:104] = base_build_id
    return sw.write(path, bytes(hdr))


def build_id_of(*paths):
    h = hashlib.sha256()
    for p in paths:
        with open(p, "rb") as f:
            h.update(f.read())
    return h.digest()[:8]


# ============================================================================ reader (verification only)

class Reader:
    def __init__(self, path):
        self.buf = np.fromfile(path, np.uint8)
        b = self.buf.tobytes()
        assert b[:4] in (MAGIC_BASE, MAGIC_OVERLAY)
        self.order, self.n_ids, self.n_lex = struct.unpack_from("<III", b, 32)
        self.counts = {k: struct.unpack_from("<I", b, 44 + 4 * (k - 1))[0] for k in range(1, 9)}
        self.rec_bits = {k: b[76 + k] for k in range(2, 9)}
        n = struct.unpack_from("<I", b, 124)[0]
        self.sec = {}
        for i in range(n):
            sid, _, off, ln = struct.unpack_from("<IIQQ", b, 128 + 24 * i)
            self.sec[sid] = (off, ln)

    def raw(self, sid):
        off, ln = self.sec[sid]
        return self.buf[off:off + ln]

    def field(self, k, idx, shift, nbits):
        rb = self.rec_bits[k]
        off, _ = self.sec[SEC_ORDER_BASE + k]
        bitpos = idx.astype(np.uint64) * np.uint64(rb) + np.uint64(shift)
        byte = (bitpos >> np.uint64(3)).astype(np.int64) + off
        w = np.zeros(len(idx), np.uint64)
        for j in range(8):
            w |= self.buf[byte + j].astype(np.uint64) << np.uint64(8 * j)
        return (w >> (bitpos & np.uint64(7))) & np.uint64((1 << nbits) - 1)

    def unigram_logprobs(self):
        """log P(g) for every n-gram id g, as stored."""
        uni = np.frombuffer(self.raw(SEC_UNIGRAM).tobytes(), dtype=[("lp", "<i2"), ("bow", "<i2"), ("child", "<u4")])
        return uni["lp"][: self.n_ids].astype(np.float64) / MILLI

    def successor_logprobs(self, c):
        """log P(g | c) for every n-gram id g after the one-word context c, as the runtime computes it: the
        stored bigram if there is one, else the back-off weight of c plus the unigram."""
        uni = np.frombuffer(self.raw(SEC_UNIGRAM).tobytes(), dtype=[("lp", "<i2"), ("bow", "<i2"), ("child", "<u4")])
        out = uni["lp"][: self.n_ids].astype(np.float64) / MILLI + uni["bow"][c] / MILLI
        lo, hi = int(uni["child"][c]), int(uni["child"][c + 1])
        if hi > lo:
            idx = np.arange(lo, hi)
            words = self.field(2, idx, 0, 16).astype(np.int64)
            quant = np.frombuffer(self.raw(SEC_QUANT_BASE + 2).tobytes(), "<f4")
            out[words] = quant[self.field(2, idx, 16, 8).astype(np.int64)]
        return out

    def ngram_logprob(self, ctx, w):
        """Reference scalar implementation of the back-off query on the binary (for tests)."""
        uni = np.frombuffer(self.raw(SEC_UNIGRAM).tobytes(), dtype=[("lp", "<i2"), ("bow", "<i2"), ("child", "<u4")])
        quant = {k: np.frombuffer(self.raw(SEC_QUANT_BASE + k).tobytes(), "<f4") for k in range(2, self.order + 1)}

        def find(seq):  # returns (order, index) of the n-gram seq, or None
            lo, hi = int(uni["child"][seq[0]]), int(uni["child"][seq[0] + 1])
            idx = seq[0]
            for k in range(2, len(seq) + 1):
                words = self.field(k, np.arange(lo, hi), 0, 16).astype(np.int64)
                j = np.searchsorted(words, seq[k - 1])
                if j >= len(words) or words[j] != seq[k - 1]:
                    return None
                idx = lo + j
                if k < self.order:
                    pb = ptr_bits(self.counts[k + 1])
                    lo = int(self.field(k, np.array([idx]), 32, pb)[0])
                    hi = int(self.field(k, np.array([idx + 1]), 32, pb)[0])
            return idx

        def prob(k, idx):
            if k == 1:
                return uni["lp"][idx] / MILLI
            return float(quant[k][int(self.field(k, np.array([idx]), 16, 8)[0])])

        def bow(k, idx):
            if k == 1:
                return uni["bow"][idx] / MILLI
            return float(quant[k][256 + int(self.field(k, np.array([idx]), 24, 8)[0])])

        ctx = list(ctx)[-(self.order - 1):] if self.order > 1 else []
        total = 0.0
        for start in range(len(ctx) + 1):
            seq = ctx[start:] + [w]
            idx = find(seq)
            if idx is not None:
                return total + prob(len(seq), idx)
            cidx = find(ctx[start:]) if ctx[start:] else None
            if cidx is not None:
                total += bow(len(ctx) - start, cidx)
        raise AssertionError("unigram must exist")
