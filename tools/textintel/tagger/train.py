#!/usr/bin/env python3
"""Trains the grammar tagger: ELECTRA-small discriminator encoder + a case embedding (the word-piece vocabulary is
uncased, and "i" -> "I" needs case) + a tag head and an error-detection head on each word's first piece.

    python3 train.py --data <dir with train-*.npz, dev-000.npz> --encoder google/electra-small-discriminator \
        --out <dir> [--epochs 2] [--max-minutes 60] [--bench-steps 300]

Checkpoints (<out>/ckpt.pt) every --ckpt-minutes and at the end; resumes from it automatically.
"""

import argparse
import glob
import json
import math
import os
import random
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gectag import TAGS  # noqa: E402

KEEP = TAGS.index("KEEP")


class Tagger(nn.Module):
    def __init__(self, encoder, n_tags, pretrained=True):
        super().__init__()
        from transformers import ElectraConfig, ElectraModel
        if pretrained:
            self.enc = ElectraModel.from_pretrained(encoder)
        else:
            self.enc = ElectraModel(ElectraConfig.from_pretrained(encoder) if isinstance(encoder, str) else encoder)
        cfg = self.enc.config
        self.case_emb = nn.Embedding(3, cfg.embedding_size)
        nn.init.zeros_(self.case_emb.weight)
        self.drop = nn.Dropout(0.1)
        self.tag = nn.Linear(cfg.hidden_size, n_tags)
        self.det = nn.Linear(cfg.hidden_size, 2)

    def forward(self, ids, case, mask):
        emb = self.enc.embeddings.word_embeddings(ids) + self.case_emb(case)
        h = self.enc(inputs_embeds=emb, attention_mask=mask).last_hidden_state
        h = self.drop(h)
        return self.tag(h), self.det(h)


def load_split(paths):
    ids, case, lab, offs = [], [], [], [np.zeros(1, dtype=np.int64)]
    base = 0
    for p in sorted(paths):
        z = np.load(p)
        ids.append(z["ids"]); case.append(z["case"]); lab.append(z["lab"])
        offs.append(z["offs"][1:] + base)
        base += int(z["offs"][-1])
    return np.concatenate(ids), np.concatenate(case), np.concatenate(lab), np.concatenate(offs)


def make_batches(offs, max_tokens, rng, max_sents=1024):
    n = len(offs) - 1
    lens = offs[1:] - offs[:-1]
    order = np.arange(n)
    rng.shuffle(order)
    batches = []
    pool = 100 * 512
    for s in range(0, n, pool):
        chunk = order[s:s + pool]
        chunk = chunk[np.argsort(lens[chunk], kind="stable")]
        cur, cur_max = [], 0
        for i in chunk:
            L = int(lens[i])
            if cur and (max(cur_max, L) * (len(cur) + 1) > max_tokens or len(cur) >= max_sents):
                batches.append(np.asarray(cur))
                cur, cur_max = [], 0
            cur.append(i)
            cur_max = max(cur_max, L)
        if cur:
            batches.append(np.asarray(cur))
    rng.shuffle(batches)
    return batches


class BatchSet(torch.utils.data.Dataset):
    def __init__(self, data, batches):
        self.ids, self.case, self.lab, self.offs = data
        self.batches = batches

    def __len__(self):
        return len(self.batches)

    def __getitem__(self, k):
        b = self.batches[k]
        L = int(max(self.offs[i + 1] - self.offs[i] for i in b))
        ids = np.zeros((len(b), L), dtype=np.int64)
        case = np.zeros((len(b), L), dtype=np.int64)
        lab = np.full((len(b), L), -100, dtype=np.int64)
        mask = np.zeros((len(b), L), dtype=np.int64)
        for r, i in enumerate(b):
            s, e = self.offs[i], self.offs[i + 1]
            ids[r, :e - s] = self.ids[s:e]
            case[r, :e - s] = self.case[s:e]
            lab[r, :e - s] = self.lab[s:e]
            mask[r, :e - s] = 1
        return torch.from_numpy(ids), torch.from_numpy(case), torch.from_numpy(lab), torch.from_numpy(mask)


def loss_fn(tag_logits, det_logits, lab):
    m = lab != -100
    tl = tag_logits[m].float()
    dl = det_logits[m].float()
    y = lab[m]
    return F.cross_entropy(tl, y) + F.cross_entropy(dl, (y != KEEP).long()), tl, y


@torch.no_grad()
def evaluate(model, loader, dev):
    model.eval()
    tot_loss, nb = 0.0, 0
    tp = fp = fn = 0
    tp9 = fp9 = 0
    for ids, case, lab, mask in loader:
        ids, case, lab, mask = (x.to(dev, non_blocking=True) for x in (ids, case, lab, mask))
        with torch.autocast(device_type=dev.type, dtype=torch.bfloat16, enabled=dev.type == "cuda"):
            tl, dl = model(ids, case, mask)
        loss, logits, y = loss_fn(tl, dl, lab)
        tot_loss += loss.item(); nb += 1
        pr = logits.softmax(-1)
        pred = pr.argmax(-1)
        err = y != KEEP
        tp += ((pred == y) & err).sum().item()
        fp += ((pred != y) & (pred != KEEP)).sum().item()
        fn += ((pred != y) & err).sum().item()
        p2 = pr.clone(); p2[:, KEEP] = 0
        conf, alt = p2.max(-1)
        hi = conf >= 0.9
        tp9 += (hi & (alt == y)).sum().item()
        fp9 += (hi & (alt != y)).sum().item()
    model.train()
    P = tp / max(tp + fp, 1); R = tp / max(tp + fn, 1)
    return {"loss": tot_loss / max(nb, 1), "P": P, "R": R, "P@0.9": tp9 / max(tp9 + fp9, 1),
            "R@0.9": tp9 / max(tp + fn, 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--encoder", default="google/electra-small-discriminator")
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--max-minutes", type=float, default=1e9)
    ap.add_argument("--bench-steps", type=int, default=0)
    ap.add_argument("--max-tokens", type=int, default=16384)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--warmup", type=float, default=0.03)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--ckpt-minutes", type=float, default=5.0)
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--compile", action="store_true")
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(a.seed)
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    log = open(os.path.join(a.out, "train.log.jsonl"), "a")

    def emit(d):
        d["t"] = round(time.time() - t0, 1)
        print(json.dumps(d), flush=True)
        log.write(json.dumps(d) + "\n"); log.flush()

    t0 = time.time()
    train = load_split(glob.glob(os.path.join(a.data, "train-*.npz")))
    val = load_split(glob.glob(os.path.join(a.data, "dev-*.npz")))
    n_train = len(train[3]) - 1
    emit({"event": "data", "train_sents": n_train, "train_pieces": int(train[3][-1]), "val_sents": len(val[3]) - 1})

    model = Tagger(a.encoder, len(TAGS)).to(dev)
    decay = [p for n, p in model.named_parameters() if p.ndim >= 2 and "LayerNorm" not in n]
    no_decay = [p for n, p in model.named_parameters() if not (p.ndim >= 2 and "LayerNorm" not in n)]
    opt = torch.optim.AdamW([{"params": decay, "weight_decay": 0.01}, {"params": no_decay, "weight_decay": 0.0}],
                            lr=a.lr, betas=(0.9, 0.98), eps=1e-6, fused=dev.type == "cuda")
    rng = np.random.default_rng(a.seed)
    probe = make_batches(train[3], a.max_tokens, np.random.default_rng(0))
    steps_per_epoch = len(probe)
    total = int(steps_per_epoch * a.epochs) if not a.bench_steps else a.bench_steps
    warm = max(1, int(total * a.warmup))

    def lr_at(step):
        if step < warm:
            return a.lr * step / warm
        return a.lr * max(0.0, (total - step) / max(1, total - warm))

    step, epoch, done_in_epoch = 0, 0, 0
    ck = os.path.join(a.out, "ckpt.pt")
    if os.path.exists(ck) and not a.bench_steps:
        st = torch.load(ck, map_location="cpu", weights_only=False)
        model.load_state_dict(st["model"]); opt.load_state_dict(st["opt"])
        step, epoch, done_in_epoch = st["step"], st["epoch"], st["done_in_epoch"]
        emit({"event": "resume", "step": step, "epoch": epoch})
    fwd = torch.compile(model) if a.compile else model
    val_loader = torch.utils.data.DataLoader(BatchSet(val, make_batches(val[3], a.max_tokens, np.random.default_rng(0))),
                                             batch_size=None, num_workers=2, pin_memory=True)
    emit({"event": "start", "steps_per_epoch": steps_per_epoch, "total_steps": total,
          "params": sum(p.numel() for p in model.parameters()), "device": str(dev),
          "gpu": torch.cuda.get_device_name(0) if dev.type == "cuda" else "cpu"})
    model.train()
    last_ck = time.time()
    t_start = time.time()
    seen_sents = 0
    seen_tokens = 0
    t_win = time.time()
    while step < total:
        batches = make_batches(train[3], a.max_tokens, np.random.default_rng(a.seed * 100 + epoch))
        loader = torch.utils.data.DataLoader(BatchSet(train, batches[done_in_epoch:]), batch_size=None,
                                             num_workers=a.workers, pin_memory=True, persistent_workers=False,
                                             prefetch_factor=8)
        for ids, case, lab, mask in loader:
            ids, case, lab, mask = (x.to(dev, non_blocking=True) for x in (ids, case, lab, mask))
            for g in opt.param_groups:
                g["lr"] = lr_at(step)
            with torch.autocast(device_type=dev.type, dtype=torch.bfloat16, enabled=dev.type == "cuda"):
                tl, dl = fwd(ids, case, mask)
            loss, _, _ = loss_fn(tl, dl, lab)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            step += 1
            done_in_epoch += 1
            seen_sents += ids.shape[0]
            seen_tokens += int(mask.sum())
            if step % 100 == 0:
                if dev.type == "cuda":
                    torch.cuda.synchronize()
                dt = time.time() - t_win
                emit({"event": "train", "step": step, "epoch": epoch, "loss": round(loss.item(), 4),
                      "lr": lr_at(step), "sents_per_s": round(seen_sents / dt, 1),
                      "tokens_per_s": round(seen_tokens / dt, 1)})
                seen_sents = seen_tokens = 0
                t_win = time.time()
            if a.eval_every and step % a.eval_every == 0 and not a.bench_steps:
                emit({"event": "eval", "step": step, **evaluate(model, val_loader, dev)})
            stop = step >= total or (time.time() - t_start) / 60 > a.max_minutes
            if not a.bench_steps and (time.time() - last_ck > a.ckpt_minutes * 60 or stop):
                torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "step": step, "epoch": epoch,
                            "done_in_epoch": done_in_epoch}, ck + ".tmp")
                os.replace(ck + ".tmp", ck)
                last_ck = time.time()
            if stop:
                break
        else:
            epoch += 1
            done_in_epoch = 0
            continue
        break
    if not a.bench_steps:
        emit({"event": "final_eval", "step": step, **evaluate(model, val_loader, dev)})
        torch.save({"model": model.state_dict(), "tags": TAGS, "encoder_config": model.enc.config.to_dict(),
                    "step": step}, os.path.join(a.out, "tagger.pt"))
    emit({"event": "done", "step": step, "minutes": round((time.time() - t_start) / 60, 2)})


if __name__ == "__main__":
    main()
