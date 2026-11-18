#!/usr/bin/env python3
"""Export a v2 swipe model (train2.py checkpoint) to `.pbsw` version 2, plus parity vectors for the engine.

  tools/swipe/py tools/swipe/export2.py <best.pt> <out.pbsw> [--vectors out.json] [--meta key=value ...]

Format: as export.py (version 1) except
  4   u16 version = 2
  8   i32 nFeat (9, swipefeat2)      32  i32 nBasis (instead of nClasses)
  36  f32 stepS  40 f32 stepT  44 i32 maxFrames  48 i32 byte offset of an embedded auxiliary model (0 = none)
  tensors: conv, LSTM layers as in version 1, then head.weight [nBasis^2 + 1, 2 hid], head.bias [nBasis^2 + 1]
  (rows 0 .. nBasis^2 - 1: basis coefficients, a * nBasis + b; last row: the intention gate).
"""
import argparse
import gzip
import json
import os
import struct
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import swipefeat2 as f2  # noqa: E402
from train2 import SwipeNet2  # noqa: E402


def tensors(model):
    sd = {k: v.detach().cpu().float().numpy() for k, v in model.state_dict().items()}
    out = [sd["conv.weight"], sd["conv.bias"]]
    for l in range(model.cfg["layers"]):
        for sfx in ("", "_reverse"):
            out.append(sd["rnn.weight_ih_l%d%s" % (l, sfx)])
            out.append(sd["rnn.weight_hh_l%d%s" % (l, sfx)])
            out.append(sd["rnn.bias_ih_l%d%s" % (l, sfx)] + sd["rnn.bias_hh_l%d%s" % (l, sfx)])
    return out + [sd["head.weight"], sd["head.bias"]]


def load(path):
    c = torch.load(path, map_location="cpu", weights_only=False)
    m = SwipeNet2(**c["cfg"])
    m.load_state_dict(c["model"])
    m.eval()
    with torch.no_grad():
        for p in m.parameters():
            p.copy_(p.half().float())
    return m, c


def image(model, meta):
    """One version 2 model image (header, metadata, fp16 tensors), 4-byte aligned."""
    cfg = model.cfg
    mj = json.dumps(meta, sort_keys=True).encode("utf-8")
    hdr = bytearray(64)
    hdr[0:4] = b"PBSW"
    struct.pack_into("<HH", hdr, 4, 2, 1)
    struct.pack_into("<7i", hdr, 8, cfg["nfeat"], cfg["conv"], cfg["kernel"], cfg["stride"], cfg["hid"], cfg["layers"], cfg["nbasis"])
    struct.pack_into("<ffii", hdr, 36, f2.STEP_S, f2.STEP_T, f2.MAX_FRAMES, 0)
    struct.pack_into("<i", hdr, 52, len(mj))
    body = bytearray(hdr) + mj
    while len(body) % 4:
        body.append(0)
    for t in tensors(model):
        body += np.ascontiguousarray(t, dtype="<f2").tobytes()
    while len(body) % 4:
        body.append(0)
    return body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("out")
    ap.add_argument("--vectors")
    ap.add_argument("--aux", help="best.pt of a co-augmented v2 model to embed (scores very short words)")
    ap.add_argument("--meta", nargs="*", default=[])
    a = ap.parse_args()
    model, c = load(a.ckpt)
    meta = {"step": c.get("step"), "dev": c.get("dev"), "format": 2, "licence": "trained on swipe.futo.org (MIT, FUTO) "
            "and How We Swipe (MIT, Leiva et al. 2021) data; see NOTICE"}
    for kv in a.meta:
        k, v = kv.split("=", 1)
        meta[k] = v
    body = image(model, meta)
    if a.aux:
        aux, ac = load(a.aux)
        meta["aux"] = {"step": ac.get("step"), "dev": ac.get("dev")}
        body = image(model, meta)
        struct.pack_into("<i", body, 48, len(body))
        body += image(aux, {"role": "aux", "step": ac.get("step")})
    open(a.out, "wb").write(body)
    print("wrote %s (%d bytes, %d params)" % (a.out, len(body), sum(p.numel() for p in model.parameters())))
    if a.vectors:
        g = json.load(open(os.path.join(REPO, "tools", "data", "human", "geometry", "gboard_pixel8.json")))
        letters = sorted((k for k in g["keys"] if len(k["label"]) == 1 and k["label"].isalpha()), key=lambda k: k["label"])
        kx = np.array([k["cx"] for k in letters]); ky = np.array([k["cy"] for k in letters])
        cases = []
        for i, line in enumerate(gzip.open(os.path.join(REPO, "tools/data/human/splits/v1/swipe_futo.tune.jsonl.gz"), "rt")):
            if i not in (0, 1, 7, 42):
                continue
            d = json.loads(line)
            p = np.asarray(d["points"], np.float64)
            f, keys = f2.features(p[:, 0], p[:, 1], p[:, 2], kx, ky)
            with torch.no_grad():
                lp, olen = model(torch.from_numpy(f)[None], torch.tensor([len(f)]), torch.from_numpy(keys)[None])
            cases.append({"word": d["target"], "points": d["points"], "x": f.reshape(-1).tolist(),
                          "keys": keys.reshape(-1).tolist(), "lp": lp[0, : int(olen[0])].reshape(-1).tolist()})
        json.dump({"nFeat": f2.N_FEAT, "format": 2, "kx": kx.tolist(), "ky": ky.tolist(),
                   "keyX": keys[:, 0].tolist(), "keyY": keys[:, 1].tolist(), "cases": cases}, open(a.vectors, "w"))


if __name__ == "__main__":
    main()
