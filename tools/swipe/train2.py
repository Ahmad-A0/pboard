#!/usr/bin/env python3
"""Train the layout-agnostic gesture model (format v2, docs/swipe-neural.md): touch frames -> per-frame intention
gate and spatial coefficients; key scores come from a fixed 2-D cosine basis evaluated at the key centres of the
layout in use, which is supplied at run time.

  P(blank) = 1 - sigmoid(g_t),   P(key k) = sigmoid(g_t) * softmax_k(c_t . phi(key_k)),
  phi(x, y)[a * N + b] = cos(pi a x) cos(pi b y),  (x, y) = key centre in the layout frame, [0, 1] per axis.

Training co-augments stroke and layout (flips, rotations, anisotropic scale and shear, per-key jitter) with
probability --coaug, so the network cannot tie positions to letters and has to read the geometry; the same
normalisation as on the device (swipefeat2) follows. Same data as train.py (tools/swipe/work/*.npz, grid units).

Usage: tools/swipe/py tools/swipe/train2.py <run-dir> [--coaug 1.0] [--nbasis 10] [--batch 768] [--lr 3e-3] ...
"""
import argparse
import json
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.nn.utils.rnn import pack_padded_sequence, pad_packed_sequence

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import swipefeat as sf  # noqa: E402
import swipefeat2 as f2  # noqa: E402
from train import BucketSampler, Strokes, augment, greedy  # noqa: E402

KEY_U = sf.KEY_U.astype(np.float64)
KEY_V = sf.KEY_V.astype(np.float64)


class SwipeNet2(nn.Module):
    def __init__(self, nfeat=f2.N_FEAT, conv=96, kernel=4, stride=2, hid=80, layers=2, nbasis=10, dropout=0.1):
        super().__init__()
        self.cfg = dict(nfeat=nfeat, conv=conv, kernel=kernel, stride=stride, hid=hid, layers=layers, nbasis=nbasis)
        self.kernel, self.stride, self.nbasis = kernel, stride, nbasis
        self.left = (kernel - stride) // 2
        self.right = kernel - 1 - self.left
        self.conv = nn.Conv1d(nfeat, conv, kernel, stride)
        self.rnn = nn.LSTM(conv, hid, layers, bidirectional=True, batch_first=True, dropout=dropout if layers > 1 else 0)
        self.drop = nn.Dropout(dropout)
        self.head = nn.Linear(2 * hid, nbasis * nbasis + 1)
        self.register_buffer("freq", torch.arange(nbasis, dtype=torch.float32) * math.pi)

    def out_lens(self, lens):
        return (lens + self.stride - 1) // self.stride

    def basis(self, keys):
        """keys [B, K, 2] in [0, 1] -> [B, K, N*N]."""
        cx = torch.cos(keys[..., 0:1] * self.freq)
        cy = torch.cos(keys[..., 1:2] * self.freq)
        return (cx[..., :, None] * cy[..., None, :]).flatten(-2)

    def forward(self, x, lens, keys):
        """x [B, T, F], lens [B], keys [B, K, 2] -> log-probs [B, T', 1 + K] (0 = blank), lens' [B]."""
        h = F.pad(x.transpose(1, 2), (self.left, self.right))
        h = F.relu(self.conv(h)).transpose(1, 2)
        olen = self.out_lens(lens)
        h = h[:, : int(olen.max())]
        packed = pack_padded_sequence(h, olen.cpu(), batch_first=True, enforce_sorted=False)
        y, _ = self.rnn(packed)
        y, _ = pad_packed_sequence(y, batch_first=True, total_length=h.shape[1])
        o = self.head(self.drop(y))
        c, g = o[..., :-1], o[..., -1:]
        z = torch.einsum("btc,bkc->btk", c, self.basis(keys))
        return torch.cat([F.logsigmoid(-g), F.logsigmoid(g) + z.log_softmax(-1)], -1), olen


def coaugment(X, Y, KX, KY, rng):
    """Same random affine map for stroke and layout about the layout centre, then per-key jitter."""
    cx, cy = KX.mean(), KY.mean()
    M = np.eye(2)
    if rng.random() < 0.5:
        M = np.diag([-1.0, 1.0]) @ M
    if rng.random() < 0.5:
        M = np.diag([1.0, -1.0]) @ M
    M = np.diag([rng.uniform(0.8, 1.25), rng.uniform(0.8, 1.25)]) @ M
    M = np.array([[1.0, rng.uniform(-0.15, 0.15)], [0.0, 1.0]]) @ M
    th = rng.uniform(0, 2 * math.pi) if rng.random() < 0.5 else rng.normal(0, 0.15)
    M = np.array([[math.cos(th), -math.sin(th)], [math.sin(th), math.cos(th)]]) @ M

    def ap(a, b):
        p = M @ np.stack([a - cx, b - cy])
        return p[0] + cx, p[1] + cy
    X, Y = ap(X, Y)
    KX, KY = ap(KX, KY)
    return X, Y, KX + rng.normal(0, 0.05, len(KX)), KY + rng.normal(0, 0.05, len(KY))


class TrainSet2(torch.utils.data.Dataset):
    def __init__(self, strokes, aug_p=0.8, coaug=1.0, seed=0):
        self.s, self.aug_p, self.coaug, self.seed, self.epoch = strokes, aug_p, coaug, seed, 0
        z = strokes
        self.aspect = z.aspect

    def __len__(self):
        return self.s.n

    def __getitem__(self, i):
        rng = np.random.default_rng((self.seed, self.epoch, i))
        u, v, t = self.s.raw(i)
        if rng.random() < self.aug_p:
            u, v, t = augment(u, v, t, rng)
        a = float(self.aspect[i]) * math.exp(rng.uniform(-0.1, 0.1))  # physical key aspect (row / key pitch)
        X, Y, KX, KY = u, v * a, KEY_U, KEY_V * a
        if rng.random() < self.coaug:
            X, Y, KX, KY = coaugment(X, Y, KX, KY, rng)
        f, keys = f2.features(X, Y, t, KX, KY)
        return torch.from_numpy(f), torch.from_numpy(keys), torch.from_numpy(self.s.labels(i))


PIXEL8_ASPECT = 154.75 / 107.0  # the evaluation replays every stroke on the Pixel 8 Gboard geometry


def canonical(s, i):
    u, v, t = s.raw(i)
    a = PIXEL8_ASPECT
    f, keys = f2.features(u, v * a, t, KEY_U, KEY_V * a)
    return torch.from_numpy(f), torch.from_numpy(keys), torch.from_numpy(s.labels(i))


def collate2(batch):
    xs, ks, ys = zip(*batch)
    lens = torch.tensor([len(x) for x in xs])
    X = torch.zeros(len(xs), int(lens.max()), xs[0].shape[1])
    for k, x in enumerate(xs):
        X[k, : len(x)] = x
    return X, lens, torch.stack(ks), torch.cat(ys), torch.tensor([len(y) for y in ys])


@torch.no_grad()
def evaluate(model, strokes, dev, batch=512):
    model.eval()
    ctc = nn.CTCLoss(blank=0, reduction="sum", zero_infinity=True)
    tot, exact, n = 0.0, 0, 0
    for a in range(0, strokes.n, batch):
        X, lens, K, Y, ylen = collate2([canonical(strokes, i) for i in range(a, min(strokes.n, a + batch))])
        lp, olen = model(X.to(dev), lens.to(dev), K.to(dev))
        tot += float(ctc(lp.transpose(0, 1), Y.to(dev), olen, ylen.to(dev)))
        g = greedy(lp, olen)
        ys = torch.split(Y, ylen.tolist())
        exact += sum(int(g[k] == ys[k].tolist()) for k in range(len(g)))
        n += len(g)
    model.train()
    return tot / n, exact / n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--data", default=os.path.join(HERE, "work"))
    ap.add_argument("--hid", type=int, default=80)
    ap.add_argument("--conv", type=int, default=96)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--nbasis", type=int, default=10)
    ap.add_argument("--dropout", type=float, default=0.1)
    ap.add_argument("--coaug", type=float, default=1.0)
    ap.add_argument("--aug", type=float, default=0.8)
    ap.add_argument("--epochs", type=float, default=20)
    ap.add_argument("--batch", type=int, default=768)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--max-steps", type=int, default=0)
    ap.add_argument("--eval-every", type=int, default=1000)
    ap.add_argument("--threads", type=int, default=0)
    ap.add_argument("--dev-limit", type=int, default=0)
    a = ap.parse_args()
    os.makedirs(a.run, exist_ok=True)
    if a.threads:
        torch.set_num_threads(a.threads)
    dev = torch.device(a.device)
    torch.manual_seed(1)
    train = Strokes(os.path.join(a.data, "train.npz"))
    train.aspect = np.load(os.path.join(a.data, "train.npz"))["aspect"]
    devsets = {}
    for k in ("futo", "hws"):
        s = Strokes(os.path.join(a.data, "dev_%s.npz" % k))
        s.aspect = np.load(os.path.join(a.data, "dev_%s.npz" % k))["aspect"]
        if a.dev_limit:
            s.n = min(s.n, a.dev_limit)
        devsets[k] = s
    model = SwipeNet2(conv=a.conv, hid=a.hid, layers=a.layers, nbasis=a.nbasis, dropout=a.dropout).to(dev)
    nparam = sum(p.numel() for p in model.parameters())
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-5)
    ds = TrainSet2(train, a.aug, a.coaug)
    sampler = BucketSampler(train.npts, a.batch)
    spe = len(sampler)
    total = a.max_steps or int(a.epochs * spe)
    warm = min(500, total // 20)

    def lr_at(step):
        if step < warm:
            return a.lr * (step + 1) / warm
        p = (step - warm) / max(1, total - warm)
        return a.lr * (0.02 + 0.98 * 0.5 * (1 + math.cos(math.pi * min(1.0, p))))

    ckpt, best_path, logf = (os.path.join(a.run, n) for n in ("ckpt.pt", "best.pt", "log.txt"))
    step, best = 0, float("inf")
    if os.path.exists(ckpt):
        c = torch.load(ckpt, map_location=dev, weights_only=False)
        model.load_state_dict(c["model"]); opt.load_state_dict(c["opt"])
        step, best = c["step"], c["best"]
    with open(os.path.join(a.run, "config.json"), "w") as f:
        json.dump({**vars(a), "model": model.cfg, "params": nparam, "steps": total, "format": 2}, f, indent=1)

    def log(s):
        print(s, flush=True)
        with open(logf, "a") as f:
            f.write(s + "\n")

    log("params %d, %d strokes, %d steps/epoch, %d steps, device %s" % (nparam, train.n, spe, total, dev))
    ctc = nn.CTCLoss(blank=0, reduction="mean", zero_infinity=True)
    t0, run_loss, run_n = time.time(), 0.0, 0
    while step < total:
        ds.epoch = sampler.epoch = step // spe
        loader = torch.utils.data.DataLoader(ds, batch_sampler=sampler, collate_fn=collate2, num_workers=a.workers,
                                             prefetch_factor=4 if a.workers else None, pin_memory=dev.type == "cuda",
                                             multiprocessing_context="fork" if a.workers else None)
        skip = step % spe
        for bi, (X, lens, K, Y, ylen) in enumerate(loader):
            if bi < skip:
                continue
            for g in opt.param_groups:
                g["lr"] = lr_at(step)
            lp, olen = model(X.to(dev, non_blocking=True), lens.to(dev), K.to(dev, non_blocking=True))
            loss = ctc(lp.transpose(0, 1), Y.to(dev), olen, ylen.to(dev))
            opt.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            step += 1
            run_loss += loss.item(); run_n += 1
            if step % 100 == 0:
                log("step %d ep %.2f loss %.4f lr %.2e %.1f steps/s" % (step, step / spe, run_loss / run_n, lr_at(step),
                                                                      100 / (time.time() - t0)))
                run_loss, run_n, t0 = 0.0, 0, time.time()
            if step % a.eval_every == 0 or step >= total:
                res = {k: evaluate(model, s, dev) for k, s in devsets.items()}
                dl = sum(r[0] for r in res.values()) / len(res)
                log("eval step %d " % step + " ".join("%s loss %.3f greedy %.4f" % (k, r[0], r[1]) for k, r in res.items()))
                if dl < best:
                    best = dl
                    torch.save({"model": model.state_dict(), "cfg": model.cfg, "step": step, "dev": res, "format": 2},
                               best_path + ".tmp")
                    os.replace(best_path + ".tmp", best_path)
                torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "step": step, "best": best}, ckpt + ".tmp")
                os.replace(ckpt + ".tmp", ckpt)
                t0 = time.time()
            if step >= total:
                break
    log("done step %d best dev loss %.4f" % (step, best))
    open(os.path.join(a.run, "DONE"), "w").write("ok\n")


if __name__ == "__main__":
    main()
