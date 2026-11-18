#!/usr/bin/env python3
"""Per-frame log-scores of v2 checkpoints (train2.py) on human swipe sets, for NeuralSwipeEval --emissions (the
same binary format as futo_bench.py): lets a candidate be judged through the engine's own decoder before it is
ported. Keys are the letter keys of the evaluation geometry (Pixel 8 Gboard), in a..z order = our class order.

  tools/swipe/py tools/swipe/emit2.py <out.bin> <run>=<best.pt> [...] -- <set.jsonl.gz> [...]
"""
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


def main():
    args = sys.argv[1:]
    out = args[0]
    sep = args.index("--")
    runs = [a.split("=", 1) for a in args[1:sep]]
    sets = args[sep + 1:]
    torch.set_num_threads(4)
    models = []
    for name, path in runs:
        c = torch.load(path, map_location="cpu", weights_only=False)
        m = SwipeNet2(**c["cfg"])
        m.load_state_dict(c["model"])
        m.eval()
        models.append((name, m))
    g = json.load(open(os.path.join(REPO, "tools", "data", "human", "geometry", "gboard_pixel8.json")))
    letters = sorted((k for k in g["keys"] if len(k["label"]) == 1 and k["label"].isalpha()), key=lambda k: k["label"])
    kx = np.array([k["cx"] for k in letters]); ky = np.array([k["cy"] for k in letters])
    recs = []
    for path in sets:
        for line in gzip.open(path, "rt", encoding="utf-8"):
            d = json.loads(line)
            p = np.asarray(d["points"], np.float64)
            f, keys = f2.features(p[:, 0], p[:, 1], p[:, 2], kx, ky)
            arrs = []
            with torch.no_grad():
                for _, m in models:
                    lp, olen = m(torch.from_numpy(f)[None], torch.tensor([len(f)]), torch.from_numpy(keys)[None])
                    arrs.append(lp[0, : int(olen[0])].numpy().astype("<f4"))
            recs.append((d["id"], arrs))
    with open(out, "wb") as fh:
        fh.write(b"PBEM" + struct.pack("<II", len(models), len(recs)))
        for name, _ in models:
            b = name.encode(); fh.write(struct.pack("<H", len(b)) + b)
        for rid, arrs in recs:
            b = rid.encode()
            fh.write(struct.pack("<H", len(b)) + b + struct.pack("<H", arrs[0].shape[0]))
            for a in arrs:
                fh.write(a.tobytes())
    print("wrote %s: %d records x %d models" % (out, len(recs), len(models)))


if __name__ == "__main__":
    main()
