"""Self-contained GPU training of the neural LM (same model and parameter names as nnlm.py), for a rented GPU.

Input directory (made by `nnlm.py <locale> --gpu-package <dir>`):
  chat.u16.npy, general.u16.npy   training token streams (n-gram ids clipped to the neural vocabulary)
  dev.npz                          encoded chat *dev* sentences (monitoring / best-checkpoint selection only)
  init.pt                          starting weights
  config.json                      {weights, tokens, lr, batch, bptt, eval_every, hid}, optional:
                                   streams: names of the <name>.u16.npy streams, one per weight (default chat, general),
                                   amp: "bf16" (mixed precision), warmup: tokens of linear lr warm-up,
                                   eval_init: score init.pt first so later checkpoints must beat it,
                                   bench_seconds: measure throughput for that long, then exit (no saving)
Writes ckpt.pt (resumable: rerun the same command), best.pt (best chat-dev top-1+top-3) and log.txt.

A run directory other than the data directory (with its own config.json and optionally init.pt) lets several
model sizes train side by side on one GPU. If init.pt has fewer hidden units than config "hid", it is widened
(Net2Net style: the trained units are kept, see nnlm.widen).

Usage: python gpu_train.py <data dir> [<run dir>]
"""

import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn

NV, EMB = 16384, 192
UNK, BOS, EOS, COMMA, NUMID, RARE = 0, 1, 2, 3, 4, 5


class LSTMLM(nn.Module):
    def __init__(self, nv=NV, emb=EMB, hid=512, cutoffs=(2000, 8000), div=4.0):
        super().__init__()
        self.emb = nn.Embedding(nv, emb)
        self.rnn = nn.LSTM(emb, hid, num_layers=1, batch_first=True)
        self.proj = nn.Linear(hid, emb)
        self.out = nn.AdaptiveLogSoftmaxWithLoss(emb, nv, cutoffs=list(cutoffs), div_value=div)

    def forward(self, x, state=None):
        h, state = self.rnn(self.emb(x), state)
        return self.proj(h), state


def widen(sd, hid, noise=1e-2, seed=1):
    """Same as nnlm.widen (kept here so this file stays self-contained on the GPU host)."""
    g = torch.Generator().manual_seed(seed)
    h0 = sd["rnn.weight_hh_l0"].shape[1]
    k = 1.0 / hid ** 0.5
    rnd = lambda *shape: (torch.rand(*shape, generator=g) * 2 - 1) * k  # noqa: E731
    small = lambda *shape: torch.randn(*shape, generator=g) * noise     # noqa: E731
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


def dev_topk(model, sents, dev):
    model.eval()
    words = t1 = t3 = 0
    with torch.no_grad():
        for ids in sents:
            x = torch.from_numpy(ids[:-1].astype(np.int64))[None].to(dev)
            h, _ = model(x)
            lp = model.out.log_prob(h[0])
            lp[:, [UNK, BOS, EOS, COMMA, NUMID, RARE]] = -1e9
            best = lp.topk(3, dim=1).indices.cpu().numpy()
            for t in range(1, len(ids) - 1):
                tgt = int(ids[t])
                if tgt in (COMMA, NUMID, UNK, EOS, BOS):
                    continue
                words += 1
                if tgt == RARE:
                    continue
                t1 += int(best[t - 1, 0] == tgt)
                t3 += int(tgt in best[t - 1])
    model.train()
    return t1 / words, t3 / words


def main(d, run=None):
    run = run or d
    cfg = json.load(open(os.path.join(run, "config.json")))
    hid = cfg.get("hid", 512)
    dev = torch.device("cuda")
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    names = cfg.get("streams", ["chat", "general"])
    streams = [torch.from_numpy(np.load(os.path.join(d, f"{n}.u16.npy")).astype(np.int64)).to(dev) for n in names]
    cum = np.cumsum(cfg["weights"]) / np.sum(cfg["weights"])  # stream sampling by mixture weight
    z = np.load(os.path.join(d, "dev.npz"))
    sents = np.split(z["ids"], z["offsets"][1:-1])
    model = LSTMLM(hid=hid).to(dev)
    opt = torch.optim.Adam(model.parameters(), lr=cfg["lr"])
    ckpt, best_path, logf = (os.path.join(run, n) for n in ("ckpt.pt", "best.pt", "log.txt"))
    seen, best = 0, -1.0
    gen = torch.Generator(device=dev).manual_seed(1)
    rng = np.random.default_rng(1)
    if os.path.exists(ckpt):
        c = torch.load(ckpt, map_location=dev, weights_only=False)
        model.load_state_dict(c["model"]); opt.load_state_dict(c["opt"])
        seen, best = c["seen"], c["best"]
        rng.bit_generator.state = c["rng"]
        gen.set_state(c["gen"].cpu() if hasattr(c["gen"], "cpu") else c["gen"])
    else:
        init = os.path.join(run, "init.pt") if os.path.exists(os.path.join(run, "init.pt")) else os.path.join(d, "init.pt")
        sd = torch.load(init, map_location="cpu")
        if sd["rnn.weight_hh_l0"].shape[1] < hid:
            sd = widen(sd, hid)
        model.load_state_dict(sd)
    B, T = cfg["batch"], cfg["bptt"]
    total, every = cfg["tokens"], cfg["eval_every"]
    bf16 = cfg.get("amp") == "bf16"
    warmup = cfg.get("warmup", 0)
    bench = cfg.get("bench_seconds")
    arange = torch.arange(T + 1, device=dev)
    next_eval = (seen // every + 1) * every

    def log(msg):
        print(msg, flush=True)
        with open(logf, "a") as f:
            f.write(msg + "\n")
    log(f"start at {seen / 1e6:.0f}M tokens of {total / 1e6:.0f}M, hid {hid}, bf16 {bf16}, best {best:.4f}")
    if seen == 0 and cfg.get("eval_init") and not bench:
        a1, a3 = dev_topk(model, sents, dev)
        best = a1 + a3
        torch.save({k: v.cpu() for k, v in model.state_dict().items()}, best_path)
        log(f"init chat-dev top1 {a1:.2%} top3 {a3:.2%} (later checkpoints must beat it)")
    t0, s0, steps = time.time(), seen, 0
    while seen < total:
        s = streams[min(int(np.searchsorted(cum, rng.random(), side="right")), len(streams) - 1)]
        starts = torch.randint(0, len(s) - T - 1, (B,), device=dev, generator=gen)
        w = s[starts[:, None] + arange]
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=bf16):
            h, _ = model(w[:, :-1])
            loss = model.out(h.reshape(-1, EMB).float(), w[:, 1:].reshape(-1)).loss
        opt.zero_grad(set_to_none=True)
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        warm = min(1.0, (seen + B * T) / warmup) if warmup else 1.0
        for g in opt.param_groups:
            g["lr"] = cfg["lr"] * warm * (0.05 + 0.95 * max(0.0, 1 - seen / total))
        opt.step()
        seen += B * T
        steps += 1
        if bench:
            if steps == 20:  # exclude cuDNN autotuning / warm-up from the measurement
                torch.cuda.synchronize()
                t0, s0 = time.time(), seen
            elif steps > 20 and time.time() - t0 >= bench:
                torch.cuda.synchronize()
                rate = (seen - s0) / (time.time() - t0)
                log(f"bench: {rate / 1e3:.0f}k tokens/s (hid {hid}, batch {B}, bf16 {bf16}, loss {loss.item():.3f})")
                return
            continue
        if seen >= next_eval or seen >= total:
            next_eval += every
            a1, a3 = dev_topk(model, sents, dev)
            score = a1 + a3
            if score > best:
                best = score
                torch.save({k: v.cpu() for k, v in model.state_dict().items()}, best_path + ".tmp")
                os.replace(best_path + ".tmp", best_path)
            torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "seen": seen, "best": best,
                        "rng": rng.bit_generator.state, "gen": gen.get_state()}, ckpt + ".tmp")
            os.replace(ckpt + ".tmp", ckpt)
            rate = (seen - s0) / (time.time() - t0)
            log(f"{seen / 1e6:.0f}M tokens ({rate / 1e3:.0f}k/s, eta {(total - seen) / rate / 60:.0f} min) "
                f"loss {loss.item():.3f} chat-dev top1 {a1:.2%} top3 {a3:.2%}{' *best*' if score >= best else ''}")
    log("done")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
