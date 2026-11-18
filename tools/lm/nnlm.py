"""Stretch experiment: does a small CPU-trained neural LM beat the n-gram for next-word prediction?

Model: word-level LSTM over the n-gram class ids (top NV classes; the rest map to <rare>), tied input/output
embeddings with an adaptive softmax.  ~6M parameters.  Trained for a fixed wall-clock budget on the same
training streams as the n-gram (chat and general sampled with the n-gram's mixture weights), evaluated on
the held-out sets of tools/lm/evaluate.py with the same targets as the Kotlin benchmark (word tokens; ','
<num> <unk> are context only), so top-k numbers are directly comparable.

Usage: tools/lm/run.sh nnlm.py <locale> [minutes]
(needs torch: tools/.venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu)
"""

import json
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import langs  # noqa: E402
from encode import Vocab, BOS, EOS, COMMA, NUMID, UNK, RARE, NSPECIAL  # noqa: E402
from paths import work  # noqa: E402

NV = 16384          # neural vocabulary: n-gram ids below this (most frequent classes)
EMB, HID = 192, 512
BPTT, BATCH = 32, 128


class LSTMLM(nn.Module):
    def __init__(self, nv=NV, emb=EMB, hid=HID, cutoffs=(2000, 8000), div=4.0):
        super().__init__()
        self.emb = nn.Embedding(nv, emb)
        self.rnn = nn.LSTM(emb, hid, num_layers=1, batch_first=True)
        self.proj = nn.Linear(hid, emb)
        self.out = nn.AdaptiveLogSoftmaxWithLoss(emb, nv, cutoffs=list(cutoffs), div_value=div)

    def forward(self, x, state=None):
        h, state = self.rnn(self.emb(x), state)
        return self.proj(h), state


def load_lstm(path):
    """An LSTMLM with the dimensions of the saved state dict at [path] (hidden size may differ from HID)."""
    sd = torch.load(path, map_location="cpu")
    nv, emb = sd["emb.weight"].shape
    model = LSTMLM(nv=nv, emb=emb, hid=sd["rnn.weight_hh_l0"].shape[1])
    model.load_state_dict(sd)
    return model


def widen(sd, hid, noise=1e-2, seed=1):
    """Net2Net-style widening of the LSTM to [hid] units: the old units keep their weights, the new units get a
    fresh initialisation and only small (noise-sized) connections into the old units and the projection, so the
    widened model starts out computing almost the same function and continues training from there."""
    g = torch.Generator().manual_seed(seed)
    h0 = sd["rnn.weight_hh_l0"].shape[1]
    assert hid > h0
    k = 1.0 / hid ** 0.5
    rnd = lambda *shape: (torch.rand(*shape, generator=g) * 2 - 1) * k          # PyTorch's LSTM init  # noqa: E731
    small = lambda *shape: torch.randn(*shape, generator=g) * noise             # noqa: E731
    out = dict(sd)
    w_ih, w_hh = sd["rnn.weight_ih_l0"], sd["rnn.weight_hh_l0"]
    n_ih, n_hh = rnd(4 * hid, w_ih.shape[1]), rnd(4 * hid, hid)
    for gate in range(4):
        old, new = slice(gate * h0, (gate + 1) * h0), slice(gate * hid, gate * hid + h0)
        n_ih[new] = w_ih[old]
        n_hh[new, :h0] = w_hh[old]
        n_hh[new, h0:] = small(h0, hid - h0)
    out["rnn.weight_ih_l0"], out["rnn.weight_hh_l0"] = n_ih, n_hh
    for b in ("rnn.bias_ih_l0", "rnn.bias_hh_l0"):
        nb = rnd(4 * hid)
        for gate in range(4):
            nb[gate * hid:gate * hid + h0] = sd[b][gate * h0:(gate + 1) * h0]
        out[b] = nb
    pw = sd["proj.weight"]
    out["proj.weight"] = torch.cat([pw, small(pw.shape[0], hid - h0)], dim=1)
    return out


def clip_ids(ids):
    ids = ids.astype(np.int32)
    ids[ids >= NV] = RARE
    return ids


def batches(stream, rng):
    """Random contiguous windows of the token stream."""
    n = len(stream) - BPTT - 1
    while True:
        starts = rng.integers(0, n, BATCH)
        idx = starts[:, None] + np.arange(BPTT + 1)[None, :]
        w = torch.from_numpy(stream[idx].astype(np.int64))
        yield w[:, :-1], w[:, 1:]


def topk_eval(model, vocab, lines, k=3):
    """Next-word top-1/top-3 with the Kotlin benchmark's targets; returns (words, top1, top3)."""
    model.eval()
    words = top1 = top3 = 0
    with torch.no_grad():
        for line in lines:
            ids, lex = vocab.encode_sentence(line.split())
            x = torch.from_numpy(clip_ids(np.array(ids[:-1])).astype(np.int64))[None, :]
            h, _ = model(x)
            lp = model.out.log_prob(h[0])                     # (T, NV)
            lp[:, [UNK, BOS, EOS, COMMA, NUMID, RARE]] = -1e9  # never predicted
            best = lp.topk(k, dim=1).indices.numpy()
            for t in range(1, len(ids) - 1):
                tgt = ids[t]
                if tgt in (COMMA, NUMID, UNK, EOS, BOS):
                    continue
                words += 1
                if tgt >= NV or tgt == RARE:
                    continue
                if best[t - 1, 0] == tgt:
                    top1 += 1
                if tgt in best[t - 1]:
                    top3 += 1
    model.train()
    return words, top1, top3


def dev_sentences(locale, n=600):
    """Fixed sample of chat *dev* sentences for monitoring (test sets are never used for model selection)."""
    L = langs.get(locale)
    out = []
    for s in L.chat_sources:
        p = os.path.join(work(locale, "text"), f"{s}.dev.txt")
        if os.path.exists(p):
            lines = [l.strip() for l in open(p, encoding="utf-8") if l.strip()]
            rng = np.random.default_rng(len(s))
            out += [lines[i] for i in rng.choice(len(lines), min(len(lines), n // len(L.chat_sources)), replace=False)]
    return out


def _atomic_save(obj, path):
    torch.save(obj, path + ".tmp")
    os.replace(path + ".tmp", path)


def train(locale, total_tokens=150e6, threads=6, ckpt_minutes=5.0, peak_lr=1.5e-3):
    """Resumable training: checkpoints every few minutes (model, optimiser, RNG, progress); rerunning the same
    command continues from the last checkpoint. Warm-starts from nnlm.pt (the earlier prototype) if present."""
    torch.set_num_threads(threads)
    torch.set_num_interop_threads(1)
    L = langs.get(locale)
    d = work(locale)
    enc = work(locale, "enc")
    vocab = Vocab(locale)
    w = json.load(open(os.path.join(d, "weights.json")))["weights"]
    comps = []
    for names in (L.chat_sources, L.general_sources):
        parts = [np.load(os.path.join(enc, f"{n}.train.ids.npy")) for n in names
                 if os.path.exists(os.path.join(enc, f"{n}.train.ids.npy"))]
        comps.append(clip_ids(np.concatenate(parts)))
    model = LSTMLM()
    opt = torch.optim.Adam(model.parameters(), lr=peak_lr)
    ckpt = os.path.join(d, "nnlm_ckpt.pt")
    rng = np.random.default_rng(1)
    seen, step = 0, 0
    if os.path.exists(ckpt):
        c = torch.load(ckpt, weights_only=False)
        model.load_state_dict(c["model"])
        opt.load_state_dict(c["opt"])
        rng.bit_generator.state = c["rng"]
        torch.set_rng_state(c["torch_rng"])
        seen, step = c["seen"], c["step"]
        print(f"[nnlm] resumed at step {step}, {seen / 1e6:.1f}M tokens", flush=True)
    elif os.path.exists(os.path.join(d, "nnlm.pt")):
        model.load_state_dict(torch.load(os.path.join(d, "nnlm.pt")))
        print("[nnlm] warm start from nnlm.pt", flush=True)
    print(f"[nnlm] {sum(p.numel() for p in model.parameters()) / 1e6:.2f}M parameters, chat {len(comps[0]):,} / "
          f"general {len(comps[1]):,} tokens, {threads} threads, target {total_tokens / 1e6:.0f}M tokens", flush=True)
    gens = [batches(c, rng) for c in comps]
    dev = dev_sentences(locale)
    t0 = time.time()
    t_ckpt = t0
    seen0, avg = seen, None
    while seen < total_tokens:
        x, y = next(gens[0] if rng.random() < w[0] else gens[1])
        h, _ = model(x)
        loss = model.out(h.reshape(-1, EMB), y.reshape(-1)).loss
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        for g in opt.param_groups:  # linear decay to 5% of peak over the token budget
            g["lr"] = peak_lr * (0.05 + 0.95 * max(0.0, 1 - seen / total_tokens))
        opt.step()
        step += 1
        seen += x.numel()
        avg = loss.item() if avg is None else 0.98 * avg + 0.02 * loss.item()
        if time.time() - t_ckpt > ckpt_minutes * 60 or seen >= total_tokens:
            _atomic_save({"model": model.state_dict(), "opt": opt.state_dict(), "rng": rng.bit_generator.state,
                          "torch_rng": torch.get_rng_state(), "seen": seen, "step": step}, ckpt)
            _atomic_save(model.state_dict(), os.path.join(d, "nnlm.pt"))
            t_ckpt = time.time()
            wds, t1, t3 = topk_eval(model, vocab, dev)
            rate = (seen - seen0) / (time.time() - t0)
            print(f"[nnlm] step {step} tokens {seen / 1e6:.1f}M ({rate:.0f}/s, eta {(total_tokens - seen) / rate / 3600:.1f} h) "
                  f"loss {avg:.3f} | chat-dev top1 {t1 / wds:.1%} top3 {t3 / wds:.1%} [checkpoint]", flush=True)
    print("[nnlm] training done", flush=True)


# ============================================================================ export (.nnlm) and int8 reference

NN_MAGIC = b"PBNN"
NN_HEADER = 512
KIND_I8, KIND_F32 = 1, 2
# section ids
S_EMB, S_WIH, S_WHH, S_B, S_PROJ_W, S_PROJ_B, S_HEAD = 1, 2, 3, 4, 5, 6, 7
S_TAIL = 10  # tail cluster i: S_TAIL + 2i (projection), S_TAIL + 2i + 1 (output)


def quant_rows(w):
    """Symmetric per-row int8: w ~ q * scale[row]."""
    w = np.asarray(w, np.float32)
    s = np.abs(w).max(axis=1) / 127.0
    s[s == 0] = 1e-12
    q = np.clip(np.round(w / s[:, None]), -127, 127).astype(np.int8)
    return q, s.astype(np.float32)


def model_tensors(model):
    sd = {k: v.detach().numpy() for k, v in model.state_dict().items()}
    t = [(S_EMB, sd["emb.weight"]), (S_WIH, sd["rnn.weight_ih_l0"]), (S_WHH, sd["rnn.weight_hh_l0"]),
         (S_B, sd["rnn.bias_ih_l0"] + sd["rnn.bias_hh_l0"]), (S_PROJ_W, sd["proj.weight"]), (S_PROJ_B, sd["proj.bias"]),
         (S_HEAD, sd["out.head.weight"])]
    i = 0
    while f"out.tail.{i}.0.weight" in sd:
        t += [(S_TAIL + 2 * i, sd[f"out.tail.{i}.0.weight"]), (S_TAIL + 2 * i + 1, sd[f"out.tail.{i}.1.weight"])]
        i += 1
    return t


def write_nnlm(path, model, base_build_id, alpha=0.5):
    """.nnlm layout (little-endian): 512-byte header, then sections. int8 sections hold rows*cols int8 values
    followed (4-aligned) by rows f32 scales; f32 sections hold cols f32 values. See docs/lm-format.md."""
    import struct
    out = model.out
    cutoffs = list(out.cutoffs)  # e.g. [2000, 8000, 16384]
    header = bytearray(NN_HEADER)
    header[0:4] = NN_MAGIC
    struct.pack_into("<HHI", header, 4, 1, 0, NN_HEADER)
    struct.pack_into("<IIII", header, 12, model.emb.num_embeddings, model.emb.embedding_dim, model.rnn.hidden_size,
                     len(cutoffs) - 1)
    for j, c in enumerate(cutoffs[:8]):
        struct.pack_into("<I", header, 28 + 4 * j, c)
    header[64:72] = base_build_id
    struct.pack_into("<f", header, 72, alpha)  # recommended interpolation weight of the neural model
    blobs, table = [], []
    off = NN_HEADER
    for sid, arr in model_tensors(model):
        if arr.ndim == 2:
            q, s = quant_rows(arr)
            data = q.tobytes()
            data += b"\0" * ((-len(data)) % 4) + s.tobytes()
            table.append((sid, KIND_I8, arr.shape[0], arr.shape[1], off))
        else:
            data = np.asarray(arr, np.float32).tobytes()
            table.append((sid, KIND_F32, 1, arr.shape[0], off))
        data += b"\0" * ((-len(data)) % 8)
        blobs.append(data)
        off += len(data)
    struct.pack_into("<I", header, 124, len(table))
    for j, (sid, kind, r, c, o) in enumerate(table):
        struct.pack_into("<IIIIQ", header, 128 + 24 * j, sid, kind, r, c, o)
    with open(path + ".tmp", "wb") as f:
        f.write(header)
        for b in blobs:
            f.write(b)
    os.replace(path + ".tmp", path)
    return off


def dequantized(model):
    """Copy of model whose matrices went through the int8 round trip (to measure quantisation loss)."""
    import copy
    m = copy.deepcopy(model)
    with torch.no_grad():
        for name, p in m.named_parameters():
            if p.ndim == 2:
                q, s = quant_rows(p.numpy())
                p.copy_(torch.from_numpy(q.astype(np.float32) * s[:, None]))
    return m


def reference_logprobs(model, tokens):
    """Int8 reference forward pass in numpy (exactly what the Kotlin implementation computes):
    returns (T, nv) log-probabilities after each input token."""
    t = {sid: arr for sid, arr in model_tensors(model)}

    def deq(sid):
        q, s = quant_rows(t[sid])
        return q.astype(np.float64) * s[:, None].astype(np.float64)
    E, Wih, Whh, Pw, Hw = deq(S_EMB), deq(S_WIH), deq(S_WHH), deq(S_PROJ_W), deq(S_HEAD)
    b, pb = t[S_B].astype(np.float64), t[S_PROJ_B].astype(np.float64)
    cut = list(model.out.cutoffs)
    tails = [(deq(S_TAIL + 2 * i), deq(S_TAIL + 2 * i + 1)) for i in range(len(cut) - 1)]
    H = Whh.shape[1]
    h, c = np.zeros(H), np.zeros(H)
    out = []

    def lsm(z):
        z = z - z.max()
        return z - np.log(np.exp(z).sum())
    for tok in tokens:
        g = Wih @ E[tok] + Whh @ h + b
        i_, f_, g_, o_ = g[:H], g[H:2 * H], g[2 * H:3 * H], g[3 * H:]
        sig = lambda v: 1 / (1 + np.exp(-v))  # noqa: E731
        c = sig(f_) * c + sig(i_) * np.tanh(g_)
        h = sig(o_) * np.tanh(c)
        e = Pw @ h + pb
        head = lsm(Hw @ e)
        lp = np.empty(cut[-1])
        lp[:cut[0]] = head[:cut[0]]
        for i, (tp, to) in enumerate(tails):
            lp[cut[i]:cut[i + 1]] = lsm(to @ (tp @ e)) + head[cut[0] + i]
        out.append(lp)
    return np.array(out)


def export(locale, model_file="nnlm.pt", out=None):
    """Exports work/<locale>/<model_file> to app/src/main/assets/lm/<locale>.nnlm (or [out]) as int8 and reports the
    quantisation loss. The interpolation weight comes from the matching alpha file (see tune_alpha)."""
    from paths import ASSET_DIR
    d = work(locale)
    vocab = Vocab(locale)
    model = load_lstm(os.path.join(d, model_file))
    model.eval()
    base = os.path.join(ASSET_DIR, f"{locale}.lm")
    build_id = open(base, "rb").read(104)[96:104]
    path = out or os.path.join(ASSET_DIR, f"{locale}.nnlm")
    apath = os.path.join(d, _alpha_file(model_file))
    alpha = json.load(open(apath))["alpha"] if os.path.exists(apath) else 0.5
    size = write_nnlm(path, model, build_id, alpha)
    print(f"[nnlm] interpolation weight alpha={alpha}", flush=True)
    dev = dev_sentences(locale)
    _, a1, a3 = topk_eval(model, vocab, dev)
    wds, b1, b3 = topk_eval(dequantized(model), vocab, dev)
    print(f"[nnlm] wrote {path}: {size / 1e6:.2f} MB; chat-dev top1/top3 fp32 {a1 / wds:.1%}/{a3 / wds:.1%}, "
          f"int8 {b1 / wds:.1%}/{b3 / wds:.1%}", flush=True)


def write_test_fixture(out_dir, n_ids, build_id, seed=7):
    """Small random LSTM over a tiny pack's ids, plus reference log-probs, for the Kotlin unit tests."""
    torch.manual_seed(seed)
    cut = (n_ids // 2, n_ids * 3 // 4)
    model = LSTMLM(nv=n_ids, emb=16, hid=24, cutoffs=cut, div=4.0)
    with torch.no_grad():
        for p in model.parameters():
            p.mul_(3.0)  # make the distribution peaky enough that rankings are meaningful
    model.eval()
    write_nnlm(os.path.join(out_dir, "tiny.nnlm"), model, build_id)
    toks = [BOS, NSPECIAL, NSPECIAL + 3, NSPECIAL + 1, n_ids - 1, NSPECIAL + 2]
    lp = reference_logprobs(model, toks)
    with open(os.path.join(out_dir, "tiny_nnlm_expect.tsv"), "w") as f:
        f.write(" ".join(map(str, toks)) + "\n")
        for row in lp:
            f.write(" ".join(f"{v:.6f}" for v in row) + "\n")


def main(locale, minutes=40.0):
    torch.set_num_threads(int(os.environ.get("PBOARD_NN_THREADS", os.cpu_count() or 4)))
    L = langs.get(locale)
    d = work(locale)
    enc = work(locale, "enc")
    vocab = Vocab(locale)
    w = json.load(open(os.path.join(d, "weights.json")))["weights"]
    comps = []
    for names in (L.chat_sources, L.general_sources):
        parts = [np.load(os.path.join(enc, f"{n}.train.ids.npy")) for n in names
                 if os.path.exists(os.path.join(enc, f"{n}.train.ids.npy"))]
        comps.append(clip_ids(np.concatenate(parts)))
    rng = np.random.default_rng(0)
    gens = [batches(c, rng) for c in comps]
    model = LSTMLM()
    n_params = sum(p.numel() for p in model.parameters())
    print(f"[nnlm] {n_params / 1e6:.2f}M parameters, chat {len(comps[0]):,} / general {len(comps[1]):,} tokens", flush=True)
    opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    dev_lines = [l.strip() for l in open(os.path.join(work(locale, "eval"), "dailydialog.txt")) if l.strip()][:300]
    t0 = time.time()
    step, seen, avg = 0, 0, None
    budget = minutes * 60
    while time.time() - t0 < budget:
        x, y = next(gens[0] if rng.random() < w[0] else gens[1])
        h, _ = model(x)
        loss = model.out(h.reshape(-1, EMB), y.reshape(-1)).loss
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        frac = (time.time() - t0) / budget
        for g in opt.param_groups:
            g["lr"] = 2e-3 * (0.1 + 0.9 * (1 - frac))
        opt.step()
        step += 1
        seen += x.numel()
        avg = loss.item() if avg is None else 0.98 * avg + 0.02 * loss.item()
        if step % 500 == 0:
            wds, t1, t3 = topk_eval(model, vocab, dev_lines)
            print(f"[nnlm] step {step} tokens {seen / 1e6:.1f}M ({seen / (time.time() - t0):.0f}/s) loss {avg:.3f} "
                  f"ppl~{math.exp(avg):.0f} | dailydialog[:300] top1 {t1 / wds:.1%} top3 {t3 / wds:.1%}", flush=True)
    torch.save(model.state_dict(), os.path.join(d, "nnlm.pt"))
    results = {}
    for name in ("chat", "dailydialog", "nussms_us", "web_gb", "web_us"):
        path = os.path.join(work(locale, "eval"), f"{name}.txt")
        lines = [l.strip() for l in open(path) if l.strip()][:1500]
        wds, t1, t3 = topk_eval(model, vocab, lines)
        results[name] = {"words": wds, "top1": t1 / wds, "top3": t3 / wds}
        print(f"[nnlm] {name:<12} words {wds:>6}  top1 {t1 / wds:.1%}  top3 {t3 / wds:.1%}", flush=True)
    json.dump({"params": n_params, "tokens_seen": seen, "minutes": minutes, "results": results},
              open(os.path.join(d, "nnlm_eval.json"), "w"), indent=1)


def _class_ids(path):
    """class key -> n-gram id from a vocab.tsv written by encode.py."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            w, _, _, gid, cls = line.rstrip("\n").split("\t")
            out[cls] = int(gid)
    return out


def remap(locale, old_vocab="vocab_nn_v1.tsv"):
    """Re-indexes a trained model after a lexicon change shifted the n-gram ids: embedding and output rows
    move to the new id of the same class. Rows that change adaptive-softmax cluster, and new classes, keep
    a fresh initialisation (a short fine-tune fills them in). Writes nnlm.pt; the old weights go to
    nnlm_<old_vocab>.pt and the old checkpoint is retired."""
    d = work(locale)
    old_ids, new_ids = _class_ids(os.path.join(d, old_vocab)), _class_ids(os.path.join(d, "vocab.tsv"))
    old = LSTMLM()
    old.load_state_dict(torch.load(os.path.join(d, "nnlm.pt")))
    new = LSTMLM()
    cut = list(old.out.cutoffs)

    def cluster(i):
        return next(k for k in range(len(cut)) if i < cut[k])  # 0 = head shortlist
    o_sd, n_sd = old.state_dict(), new.state_dict()
    for k in n_sd:
        if not (k.startswith("emb.") or k.startswith("out.")):
            n_sd[k] = o_sd[k].clone()  # LSTM and projection are id-independent
    n_sd["out.head.weight"][cut[0]:] = o_sd["out.head.weight"][cut[0]:]  # cluster logits
    for i in range(len(cut) - 1):
        n_sd[f"out.tail.{i}.0.weight"] = o_sd[f"out.tail.{i}.0.weight"].clone()
    pairs = [(n, o) for c, n in new_ids.items() if n < NV and (o := old_ids.get(c, -1)) >= 0 and o < NV]
    pairs += [(s, s) for s in range(NSPECIAL)]
    moved = same_cluster = 0
    for n, o in set(pairs):
        n_sd["emb.weight"][n] = o_sd["emb.weight"][o]
        moved += 1
        if cluster(n) != cluster(o):
            continue
        same_cluster += 1
        k = cluster(n)
        if k == 0:
            n_sd["out.head.weight"][n] = o_sd["out.head.weight"][o]
        else:
            w = f"out.tail.{k - 1}.1.weight"
            n_sd[w][n - cut[k - 1]] = o_sd[w][o - cut[k - 1]]
    new.load_state_dict(n_sd)
    os.replace(os.path.join(d, "nnlm.pt"), os.path.join(d, f"nnlm_{old_vocab.replace('.tsv', '')}.pt"))
    torch.save(new.state_dict(), os.path.join(d, "nnlm.pt"))
    ck = os.path.join(d, "nnlm_ckpt.pt")
    if os.path.exists(ck):
        os.replace(ck, os.path.join(d, f"nnlm_ckpt_{old_vocab.replace('.tsv', '')}.pt"))
    print(f"[nnlm] remapped {moved} of {NV} ids ({same_cluster} with output rows); "
          f"{NV - moved} ids newly initialised", flush=True)


def gpu_package(locale, out, tokens=600e6, lr=1e-3, hid=HID):
    """Writes everything tools/lm/gpu_train.py needs to continue training nnlm.pt on a rented GPU."""
    import shutil
    L = langs.get(locale)
    d, enc = work(locale), work(locale, "enc")
    os.makedirs(out, exist_ok=True)
    # one token stream per mixture component, in weights.json order: chat, general[, synthetic]
    comps = [("chat", L.chat_sources), ("general", L.general_sources)]
    if L.synthetic_sources:
        comps.append(("synthetic", L.synthetic_sources))
    for comp, names in comps:
        parts = [np.load(os.path.join(enc, f"{n}.train.ids.npy")) for n in names
                 if os.path.exists(os.path.join(enc, f"{n}.train.ids.npy"))]
        np.save(os.path.join(out, f"{comp}.u16.npy"), clip_ids(np.concatenate(parts)).astype(np.uint16))
    vocab = Vocab(locale)
    sents = [clip_ids(np.array(vocab.encode_sentence(l.split())[0])) for l in dev_sentences(locale)]
    np.savez(os.path.join(out, "dev.npz"), ids=np.concatenate(sents),
             offsets=np.cumsum([0] + [len(s) for s in sents]))
    shutil.copy(os.path.join(d, "nnlm.pt"), os.path.join(out, "init.pt"))
    shutil.copy(os.path.join(os.path.dirname(os.path.abspath(__file__)), "gpu_train.py"), out)
    w = json.load(open(os.path.join(d, "weights.json")))["weights"]
    assert len(w) == len(comps), "weights.json does not match the mixture components"
    json.dump({"weights": w, "streams": [c for c, _ in comps], "tokens": tokens, "lr": lr, "batch": 256, "bptt": BPTT,
               "eval_every": 20e6, "hid": hid}, open(os.path.join(out, "config.json"), "w"))
    print(f"[nnlm] GPU package in {out}", flush=True)


def _alpha_file(model_file):
    return "nnlm_alpha.json" if model_file == "nnlm.pt" else model_file.replace(".pt", "") + "_alpha.json"


def tune_alpha(locale, alphas=(0.3, 0.4, 0.5, 0.6, 0.7, 0.8), model_file="nnlm.pt"):
    """Chooses the interpolation weight on chat *dev* sentences (max top-1 + top-3); export() stores it in the
    .nnlm header as the recommended weight."""
    res = combo(locale, sets={"chat-dev": dev_sentences(locale)}, alphas=alphas, save=False,
                model_file=model_file)["chat-dev"]
    best = max(alphas, key=lambda a: res[a][0] + res[a][1])
    json.dump({"alpha": best, "dev": {str(a): v for a, v in res.items()}},
              open(os.path.join(work(locale), _alpha_file(model_file)), "w"), indent=1)
    print(f"[nnlm] tuned alpha = {best}", flush=True)
    return best


def combo(locale, n_lines=300, alphas=(0.0, 0.3, 0.5, 0.7, 1.0), sets=None, save=True, model_file="nnlm.pt"):
    """Class-level next-word top-1/top-3 of the n-gram, the LSTM and their linear interpolation
    alpha * P_lstm + (1 - alpha) * P_ngram, on identical positions (first n_lines of each eval set)."""
    import ngram as ng
    d = work(locale)
    vocab = Vocab(locale)
    model = load_lstm(os.path.join(d, model_file))
    model.eval()
    lm = ng.Model.load(os.path.join(d, "model_quant.npz"))
    cand = np.arange(NSPECIAL, NV, dtype=np.uint64)
    out = {}
    if sets is None:
        sets = {name: [l.strip() for l in open(os.path.join(work(locale, "eval"), f"{name}.txt")) if l.strip()][:n_lines]
                for name in ("chat", "dailydialog", "nussms_us")}
    for name, lines in sets.items():
        hits = {a: [0, 0] for a in alphas}
        words = 0
        for line in lines:
            ids, _ = vocab.encode_sentence(line.split())
            with torch.no_grad():
                h, _ = model(torch.from_numpy(clip_ids(np.array(ids[:-1])).astype(np.int64))[None, :])
                p_nn = model.out.log_prob(h[0]).exp().numpy()[:, NSPECIAL:]          # (T, NV-6)
            for t in range(1, len(ids) - 1):
                tgt = ids[t]
                if tgt in (COMMA, NUMID, UNK, EOS, BOS):
                    continue
                words += 1
                ctx = ids[max(0, t - 3):t]
                key = np.zeros(len(cand), np.uint64)
                for c in ctx:
                    key = (key << np.uint64(16)) | np.uint64(c)
                key = (key << np.uint64(16)) | cand
                p_ng = np.exp(lm.lp(key, len(ctx) + 1))
                for a in alphas:
                    p = a * p_nn[t - 1] + (1 - a) * p_ng
                    top = np.argpartition(-p, 3)[:3]
                    top = top[np.argsort(-p[top])] + NSPECIAL
                    hits[a][0] += int(top[0] == tgt)
                    hits[a][1] += int(tgt in top)
        out[name] = {a: (h1 / words, h3 / words) for a, (h1, h3) in hits.items()}
        print(f"[combo] {name:<12} words {words}: " + "  ".join(
            f"a={a}: {h1 / words:.1%}/{h3 / words:.1%}" for a, (h1, h3) in hits.items()), flush=True)
    if save:
        json.dump({k: {str(a): v for a, v in r.items()} for k, r in out.items()},
                  open(os.path.join(d, "nnlm_combo.json"), "w"), indent=1)
    return out


if __name__ == "__main__":
    a = sys.argv[1:]
    opt = lambda name, default: type(default)(a[a.index(name) + 1]) if name in a else default  # noqa: E731
    model_file = opt("--model", "nnlm.pt")
    if "--combo" in a:
        combo(a[0], model_file=model_file)
    elif "--tune" in a:
        tune_alpha(a[0], model_file=model_file)
    elif "--widen" in a:
        hid = int(a[a.index("--widen") + 1])
        src = os.path.join(work(a[0]), model_file)
        dst = os.path.join(work(a[0]), f"nnlm_h{hid}_init.pt")
        torch.save(widen(torch.load(src, map_location="cpu"), hid), dst)
        print(f"[nnlm] widened {src} to {hid} units: {dst}", flush=True)
    elif "--train" in a:
        train(a[0], total_tokens=opt("--tokens", 150e6), threads=opt("--threads", 6), peak_lr=opt("--lr", 1.5e-3))
    elif "--remap" in a:
        remap(a[0], opt("--old-vocab", "vocab_nn_v1.tsv"))
    elif "--gpu-package" in a:
        gpu_package(a[0], a[a.index("--gpu-package") + 1], tokens=opt("--tokens", 600e6), lr=opt("--lr", 1e-3),
                    hid=opt("--hid", HID))
    elif "--export" in a:
        export(a[0], model_file, opt("--out", "") or None)
    else:
        main(a[0], float(a[1]) if len(a) > 1 else 40.0)
