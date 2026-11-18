#!/usr/bin/env python3
"""Train the gesture-typing model (docs/swipe-neural.md): touch frames -> per-frame letter posteriors, CTC loss.

Model: strided 1-D convolution over the frame features (halves the frame rate), a stack of bidirectional LSTMs,
and a linear layer to 27 classes (CTC blank + a..z). Inputs come from swipefeat.py (grid units, so geometry-free).

Usage:
  tools/swipe/py tools/swipe/train.py <run-dir> [--data tools/swipe/work] [--hid 96] [--conv 96] [--layers 2]
         [--epochs 8] [--batch 256] [--lr 2e-3] [--workers 8] [--device cuda] [--max-steps N]
Writes <run-dir>/ckpt.pt (resumable: rerun the same command), best.pt (lowest dev loss), log.txt, and on
completion <run-dir>/model.pbsw (export.py format).
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


class SwipeNet(nn.Module):
    def __init__(self, nfeat=sf.N_FEAT, conv=96, kernel=4, stride=2, hid=96, layers=2, ncls=sf.N_CLASSES, dropout=0.1):
        super().__init__()
        self.cfg = dict(nfeat=nfeat, conv=conv, kernel=kernel, stride=stride, hid=hid, layers=layers, ncls=ncls)
        self.kernel, self.stride = kernel, stride
        self.left = (kernel - stride) // 2
        self.right = kernel - 1 - self.left
        self.conv = nn.Conv1d(nfeat, conv, kernel, stride)
        self.rnn = nn.LSTM(conv, hid, layers, bidirectional=True, batch_first=True, dropout=dropout if layers > 1 else 0)
        self.drop = nn.Dropout(dropout)
        self.out = nn.Linear(2 * hid, ncls)

    def out_lens(self, lens):
        return (lens + self.stride - 1) // self.stride

    def forward(self, x, lens):
        """x [B, T, F] (zero padded), lens [B] -> log-probs [B, T', C], lens' [B]."""
        h = F.pad(x.transpose(1, 2), (self.left, self.right))
        h = F.relu(self.conv(h)).transpose(1, 2)
        olen = self.out_lens(lens)
        h = h[:, : int(olen.max())]
        packed = pack_padded_sequence(h, olen.cpu(), batch_first=True, enforce_sorted=False)
        y, _ = self.rnn(packed)
        y, _ = pad_packed_sequence(y, batch_first=True, total_length=h.shape[1])
        return self.out(self.drop(y)).log_softmax(-1), olen


# ------------------------------------------------------------------------------------------------ data

class Strokes:
    def __init__(self, path):
        z = np.load(path)
        self.uv = z["uv"]
        self.t = z["t"]
        self.off = z["off"]
        self.lab = z["lab"]
        self.loff = z["loff"]
        self.word = z["word"]
        self.src = z["src"]
        self.n = len(self.off) - 1
        self.npts = np.diff(self.off)

    def raw(self, i):
        a, b = self.off[i], self.off[i + 1]
        uv = self.uv[a:b].astype(np.float64) / 1000.0
        return uv[:, 0], uv[:, 1], self.t[a:b].astype(np.float64)

    def labels(self, i):
        return self.lab[self.loff[i]:self.loff[i + 1]].astype(np.int64)


def augment(u, v, t, rng, geo=1.0):
    """Geometry and timing variation (grid units): see docs/swipe-neural.md, 'Augmentation'. [geo] scales the
    geometric ranges (scale, aspect, rotation, offset)."""
    n = len(u)
    if n > 3 and rng.random() < 0.5:  # lower touch sampling rate: drop interior samples
        keep = rng.random(n) < rng.uniform(0.5, 1.0)
        keep[0] = keep[-1] = True
        u, v, t = u[keep], v[keep], t[keep]
    cu, cv = u.mean(), v.mean()
    # Key aspect (row pitch / key pitch) differs between phones and size sets (1.16-1.70 in the data, Gboard 1.30 for
    # 350-399 dp and 1.45 for >= 400 dp): people's strokes are similar in physical space, so a different aspect shows
    # up in grid units as an anisotropic scale of the stroke's shape; plus overall scale, rotation and offset.
    sx = rng.uniform(1 - 0.1 * geo, 1 + 0.1 * geo)
    sy = sx * math.exp(rng.uniform(-0.15 * geo, 0.15 * geo))
    a = 1.4  # rotate in (approximately) physical space
    th = rng.normal(0, math.radians(3 * geo))
    du, dv = (u - cu) * sx, (v - cv) * sy * a
    ru = du * math.cos(th) - dv * math.sin(th)
    rv = (du * math.sin(th) + dv * math.cos(th)) / a
    u = cu + ru + rng.normal(0, 0.12 * geo)
    v = cv + rv + rng.normal(0, 0.08 * geo)
    u = u + rng.normal(0, 0.02, len(u))
    v = v + rng.normal(0, 0.02, len(v))
    t = (t - t[0]) * math.exp(rng.uniform(-0.3, 0.3))
    return u, v, t


class TrainSet(torch.utils.data.Dataset):
    def __init__(self, strokes, aug_p=0.8, seed=0, reverse=0.0, geo=1.0):
        self.s = strokes
        self.aug_p = aug_p
        self.reverse = reverse
        self.geo = geo
        self.seed = seed
        self.epoch = 0

    def __len__(self):
        return self.s.n

    def __getitem__(self, i):
        rng = np.random.default_rng((self.seed, self.epoch, i))
        u, v, t = self.s.raw(i)
        y = self.s.labels(i)
        if rng.random() < self.aug_p:
            u, v, t = augment(u, v, t, rng, self.geo)
        if self.reverse and rng.random() < self.reverse:
            # Time reversal with the target reversed (FUTO's technical report): the gesture still matches its keys,
            # but the letter sequence is no longer a word, which weakens the network's learned spelling prior.
            u, v, t, y = u[::-1].copy(), v[::-1].copy(), (t[-1] - t)[::-1].copy(), y[::-1].copy()
        x = sf.stroke_features(u, v, t)
        return torch.from_numpy(x), torch.from_numpy(y)


def collate(batch):
    xs, ys = zip(*batch)
    lens = torch.tensor([len(x) for x in xs])
    X = torch.zeros(len(xs), int(lens.max()), xs[0].shape[1])
    for k, x in enumerate(xs):
        X[k, : len(x)] = x
    ylen = torch.tensor([len(y) for y in ys])
    return X, lens, torch.cat(ys), ylen


class BucketSampler(torch.utils.data.Sampler):
    """Batches of similar stroke length (less padding), shuffled each epoch."""

    def __init__(self, npts, batch, seed=0):
        self.npts, self.batch, self.seed, self.epoch = npts, batch, seed, 0

    def __iter__(self):
        rng = np.random.default_rng((self.seed, self.epoch))
        idx = rng.permutation(len(self.npts))
        chunk = self.batch * 64
        batches = []
        for a in range(0, len(idx), chunk):
            c = idx[a:a + chunk]
            c = c[np.argsort(self.npts[c], kind="stable")]
            batches.extend(c[b:b + self.batch] for b in range(0, len(c), self.batch))
        for k in rng.permutation(len(batches)):
            yield batches[k].tolist()

    def __len__(self):
        return (len(self.npts) + self.batch - 1) // self.batch


def greedy(lp, olen):
    best = lp.argmax(-1).cpu().numpy()
    out = []
    for k in range(len(best)):
        seq, prev = [], 0
        for c in best[k, : int(olen[k])]:
            if c != prev and c != 0:
                seq.append(int(c))
            prev = c
        out.append(seq)
    return out


@torch.no_grad()
def evaluate(model, strokes, dev, batch=256):
    model.eval()
    ctc = nn.CTCLoss(blank=0, reduction="sum", zero_infinity=True)
    tot, exact, n = 0.0, 0, 0
    for a in range(0, strokes.n, batch):
        items = []
        for i in range(a, min(strokes.n, a + batch)):
            u, v, t = strokes.raw(i)
            items.append((torch.from_numpy(sf.stroke_features(u, v, t)), torch.from_numpy(strokes.labels(i))))
        X, lens, Y, ylen = collate(items)
        lp, olen = model(X.to(dev), lens.to(dev))
        tot += float(ctc(lp.transpose(0, 1), Y.to(dev), olen, ylen.to(dev)))
        g = greedy(lp, olen)
        ys = torch.split(Y, ylen.tolist())
        exact += sum(int(g[k] == ys[k].tolist()) for k in range(len(g)))
        n += len(items)
    model.train()
    return tot / n, exact / n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--data", default=os.path.join(HERE, "work"))
    ap.add_argument("--hid", type=int, default=96)
    ap.add_argument("--conv", type=int, default=96)
    ap.add_argument("--kernel", type=int, default=4)
    ap.add_argument("--stride", type=int, default=2)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--dropout", type=float, default=0.1)
    ap.add_argument("--epochs", type=float, default=8)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--aug", type=float, default=0.8)
    ap.add_argument("--reverse", type=float, default=0.0, help="probability of a time-reversed stroke + reversed target")
    ap.add_argument("--geo", type=float, default=1.0, help="multiplier of the geometric augmentation ranges")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--max-steps", type=int, default=0)
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--threads", type=int, default=0)
    ap.add_argument("--init", help="start from these weights (a best.pt of the same shape)")
    ap.add_argument("--teacher", help="best.pt of a larger model to distil from (same kernel/stride)")
    ap.add_argument("--distill", type=float, default=1.0, help="weight of the per-frame KL(teacher || student) term")
    ap.add_argument("--dev-limit", type=int, default=0, help="evaluate on the first N dev strokes only (smoke tests)")
    a = ap.parse_args()
    os.makedirs(a.run, exist_ok=True)
    if a.threads:
        torch.set_num_threads(a.threads)
    dev = torch.device(a.device)
    torch.manual_seed(1)
    train = Strokes(os.path.join(a.data, "train.npz"))
    devsets = {k: Strokes(os.path.join(a.data, "dev_%s.npz" % k)) for k in ("futo", "hws")}
    if a.dev_limit:
        for s in devsets.values():
            s.n = min(s.n, a.dev_limit)
    model = SwipeNet(conv=a.conv, kernel=a.kernel, stride=a.stride, hid=a.hid, layers=a.layers, dropout=a.dropout).to(dev)
    nparam = sum(p.numel() for p in model.parameters())
    if a.init:
        model.load_state_dict(torch.load(a.init, map_location=dev, weights_only=False)["model"])
    teacher = None
    if a.teacher:
        tc = torch.load(a.teacher, map_location=dev, weights_only=False)
        teacher = SwipeNet(**{k: tc["cfg"][k] for k in ("nfeat", "conv", "kernel", "stride", "hid", "layers", "ncls")}).to(dev)
        teacher.load_state_dict(tc["model"])
        teacher.eval()
        assert (teacher.kernel, teacher.stride) == (model.kernel, model.stride)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-5)
    ds = TrainSet(train, a.aug, reverse=a.reverse, geo=a.geo)
    sampler = BucketSampler(train.npts, a.batch)
    steps_per_epoch = len(sampler)
    total = a.max_steps or int(a.epochs * steps_per_epoch)
    warm = min(1000, total // 20)

    def lr_at(step):
        if step < warm:
            return a.lr * (step + 1) / warm
        p = (step - warm) / max(1, total - warm)
        return a.lr * (0.02 + 0.98 * 0.5 * (1 + math.cos(math.pi * min(1.0, p))))

    ckpt, best_path, logf = (os.path.join(a.run, n) for n in ("ckpt.pt", "best.pt", "log.txt"))
    step, best = 0, float("inf")
    if os.path.exists(ckpt):
        c = torch.load(ckpt, map_location=dev, weights_only=False)
        model.load_state_dict(c["model"])
        opt.load_state_dict(c["opt"])
        step, best = c["step"], c["best"]
    with open(os.path.join(a.run, "config.json"), "w") as f:
        json.dump({**vars(a), "model": model.cfg, "params": nparam, "steps": total}, f, indent=1)

    def log(s):
        print(s, flush=True)
        with open(logf, "a") as f:
            f.write(s + "\n")

    log("params %d, %d strokes, %d steps/epoch, %d steps total, device %s" % (nparam, train.n, steps_per_epoch, total, dev))
    ctc = nn.CTCLoss(blank=0, reduction="mean", zero_infinity=True)
    t0 = time.time()
    run_loss, run_n = 0.0, 0
    while step < total:
        epoch = step // steps_per_epoch
        ds.epoch = sampler.epoch = epoch
        # fork: workers share the arrays (Python >= 3.14 defaults to forkserver, which would pickle them per worker)
        loader = torch.utils.data.DataLoader(ds, batch_sampler=sampler, collate_fn=collate, num_workers=a.workers,
                                             persistent_workers=False, prefetch_factor=4 if a.workers else None,
                                             multiprocessing_context="fork" if a.workers else None)
        skip = step % steps_per_epoch
        for bi, (X, lens, Y, ylen) in enumerate(loader):
            if bi < skip:
                continue
            for g in opt.param_groups:
                g["lr"] = lr_at(step)
            lp, olen = model(X.to(dev, non_blocking=True), lens.to(dev))
            loss = ctc(lp.transpose(0, 1), Y.to(dev), olen, ylen.to(dev))
            if teacher is not None:
                with torch.no_grad():
                    tlp, _ = teacher(X.to(dev, non_blocking=True), lens.to(dev))
                mask = (torch.arange(lp.shape[1], device=dev)[None, :] < olen[:, None]).float()
                kl = (tlp.exp() * (tlp - lp)).sum(-1)
                loss = loss + a.distill * (kl * mask).sum() / mask.sum()
            opt.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            step += 1
            run_loss += float(loss)
            run_n += 1
            if step % 200 == 0:
                log("step %d ep %.2f loss %.4f lr %.2e %.1f steps/s" % (step, step / steps_per_epoch, run_loss / run_n,
                                                                         lr_at(step), 200 / (time.time() - t0)))
                run_loss, run_n, t0 = 0.0, 0, time.time()
            if step % a.eval_every == 0 or step >= total:
                res = {k: evaluate(model, s, dev) for k, s in devsets.items()}
                dl = sum(r[0] for r in res.values()) / len(res)
                log("eval step %d " % step + " ".join("%s loss %.3f greedy %.4f" % (k, r[0], r[1]) for k, r in res.items()))
                if dl < best:
                    best = dl
                    torch.save({"model": model.state_dict(), "cfg": model.cfg, "step": step, "dev": res}, best_path + ".tmp")
                    os.replace(best_path + ".tmp", best_path)
                torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "step": step, "best": best}, ckpt + ".tmp")
                os.replace(ckpt + ".tmp", ckpt)
                t0 = time.time()
            if step >= total:
                break
    log("done step %d best dev loss %.4f" % (step, best))
    with open(os.path.join(a.run, "DONE"), "w") as f:
        f.write("ok\n")


if __name__ == "__main__":
    main()
