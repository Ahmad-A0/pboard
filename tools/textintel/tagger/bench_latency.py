#!/usr/bin/env python3
"""Size, FLOPs and single-thread CPU latency of the trained tagger (batch 1), fp32 and int8 dynamic quantisation.

    python3 bench_latency.py --model tagger.pt --encoder <electra dir> [--pieces 20] [--runs 300]
"""

import argparse
import io
import os
import statistics
import sys
import time

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from predict import load_model  # noqa: E402


def size_of(model):
    buf = io.BytesIO()
    torch.save(model.state_dict(), buf)
    return buf.tell()


def latency(model, ids, case, mask, runs):
    with torch.no_grad():
        for _ in range(20):
            model(ids, case, mask)
        ts = []
        for _ in range(runs):
            t = time.perf_counter()
            model(ids, case, mask)
            ts.append((time.perf_counter() - t) * 1000)
    ts.sort()
    return statistics.median(ts), ts[int(0.95 * len(ts))], ts[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--encoder", required=True)
    ap.add_argument("--pieces", type=int, nargs="+", default=[12, 20, 32])
    ap.add_argument("--runs", type=int, default=300)
    a = ap.parse_args()
    torch.set_num_threads(1)
    m32 = load_model(a.model, a.encoder)
    n = sum(p.numel() for p in m32.parameters())
    emb = m32.enc.embeddings.word_embeddings.weight.numel()
    print(f"params {n:,} (word-piece embeddings {emb:,}; encoder+heads {n - emb:,})")
    print(f"fp32 state_dict {size_of(m32) / 1e6:.1f} MB; all-int8 weights would be ~{n / 1e6:.1f} MB + scales")
    m8 = load_model(a.model, a.encoder, quant=True)
    print(f"torch dynamic int8 state_dict {size_of(m8) / 1e6:.1f} MB (Linear weights int8, embeddings stay fp32)")
    from torch.utils.flop_counter import FlopCounterMode
    for L in a.pieces:
        ids = torch.randint(1000, 20000, (1, L)); ids[0, 0] = 101; ids[0, -1] = 102
        case = torch.zeros((1, L), dtype=torch.long)
        mask = torch.ones((1, L), dtype=torch.long)
        with FlopCounterMode(display=False) as fc, torch.no_grad():
            m32(ids, case, mask)
        flops = fc.get_total_flops()
        f32 = latency(m32, ids, case, mask, a.runs)
        q8 = latency(m8, ids, case, mask, a.runs)
        print(f"{L:3d} pieces: {flops / 1e6:.0f} MFLOPs ({flops / 2e6:.0f} M MACs); 1 thread fp32 median {f32[0]:.2f} ms "
              f"(min {f32[2]:.2f}, p95 {f32[1]:.2f}); int8 median {q8[0]:.2f} ms (min {q8[2]:.2f}, p95 {q8[1]:.2f})")


if __name__ == "__main__":
    main()
