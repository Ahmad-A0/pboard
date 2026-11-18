#!/usr/bin/env python3
"""Export a trained swipe model (train.py checkpoint) to the engine's `.pbsw` format, plus parity vectors.

  tools/swipe/py tools/swipe/export.py <best.pt> <out.pbsw> [--vectors out.json] [--meta key=value ...]

Format (little endian), read by dev.pboard.engine.swipe.SwipeModel:
  0   "PBSW"            4   u16 version (1)     6  u16 weight type (1 = fp16)
  8   i32 nFeat         12  i32 conv            16 i32 kernel        20 i32 stride
  24  i32 hid           28  i32 layers          32 i32 nClasses
  36  f32 stepS         40  f32 stepT           44 i32 maxFrames     48 f32 proxSigma
  52  i32 metadata length M (UTF-8 JSON at offset 64)
  64 + M, padded to 4: tensors, fp16, in this order
    conv.weight [conv, nFeat, kernel], conv.bias [conv]
    per layer, per direction (forward, reverse): w_ih [4 hid, in], w_hh [4 hid, hid], bias_ih + bias_hh [4 hid]
      (gate order i, f, g, o as in torch.nn.LSTM; in = conv for layer 0, else 2 hid)
    out.weight [nClasses, 2 hid], out.bias [nClasses]
"""
import argparse
import json
import os
import struct
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import swipefeat as sf  # noqa: E402
from train import SwipeNet, Strokes  # noqa: E402


def tensors(model):
    sd = {k: v.detach().cpu().float().numpy() for k, v in model.state_dict().items()}
    out = [sd["conv.weight"], sd["conv.bias"]]
    for l in range(model.cfg["layers"]):
        for sfx in ("", "_reverse"):
            out.append(sd["rnn.weight_ih_l%d%s" % (l, sfx)])
            out.append(sd["rnn.weight_hh_l%d%s" % (l, sfx)])
            out.append(sd["rnn.bias_ih_l%d%s" % (l, sfx)] + sd["rnn.bias_hh_l%d%s" % (l, sfx)])
    out += [sd["out.weight"], sd["out.bias"]]
    return out


def write(model, path, meta):
    c = model.cfg
    mj = json.dumps(meta, sort_keys=True).encode("utf-8")
    hdr = bytearray(64)
    hdr[0:4] = b"PBSW"
    struct.pack_into("<HH", hdr, 4, 1, 1)
    struct.pack_into("<7i", hdr, 8, c["nfeat"], c["conv"], c["kernel"], c["stride"], c["hid"], c["layers"], c["ncls"])
    struct.pack_into("<ffif", hdr, 36, sf.STEP_S, sf.STEP_T, sf.MAX_FRAMES, sf.PROX_SIGMA)
    struct.pack_into("<i", hdr, 52, len(mj))
    body = bytearray(hdr) + mj
    while len(body) % 4:
        body.append(0)
    for t in tensors(model):
        body += np.ascontiguousarray(t, dtype="<f2").tobytes()
    with open(path, "wb") as f:
        f.write(body)
    return len(body)


def fp16_model(model):
    """The model with fp16-rounded weights (what the engine computes with)."""
    with torch.no_grad():
        for p in model.parameters():
            p.copy_(p.half().float())
    return model


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("out")
    ap.add_argument("--vectors")
    ap.add_argument("--dev", default=os.path.join(HERE, "work", "dev_futo.npz"))
    ap.add_argument("--meta", nargs="*", default=[])
    a = ap.parse_args()
    c = torch.load(a.ckpt, map_location="cpu", weights_only=False)
    cfg = c["cfg"]
    model = SwipeNet(nfeat=cfg["nfeat"], conv=cfg["conv"], kernel=cfg["kernel"], stride=cfg["stride"], hid=cfg["hid"],
                     layers=cfg["layers"], ncls=cfg["ncls"])
    model.load_state_dict(c["model"])
    model.eval()
    fp16_model(model)
    meta = {"step": c.get("step"), "dev": c.get("dev"), "licence": "trained on swipe.futo.org (MIT, FUTO) and How We "
            "Swipe (MIT, Leiva et al. 2021) data; see NOTICE"}
    for kv in a.meta:
        k, v = kv.split("=", 1)
        meta[k] = v
    n = write(model, a.out, meta)
    print("wrote %s (%d bytes, %d params)" % (a.out, n, sum(p.numel() for p in model.parameters())))
    if a.vectors:
        s = Strokes(a.dev)
        cases = []
        for i in (0, 1, 7, 42):
            u, v, t = s.raw(i)
            x = sf.stroke_features(u, v, t)
            with torch.no_grad():
                lp, olen = model(torch.from_numpy(x)[None], torch.tensor([len(x)]))
            cases.append({"u": u.tolist(), "v": v.tolist(), "t": t.tolist(), "word": str(s.word[i]),
                          "x": x.reshape(-1).tolist(), "lp": lp[0, : int(olen[0])].reshape(-1).tolist()})
        with open(a.vectors, "w") as f:
            json.dump({"nFeat": sf.N_FEAT, "nClasses": cfg["ncls"], "cases": cases}, f)


if __name__ == "__main__":
    main()
