#!/usr/bin/env python3
"""External reference: FUTO Swipe's released models (futo-org/futo-swipe on Hugging Face, FUTO Model Weights
License 1.0, which permits evaluation) run on our human swipe sets, so their encoder can be compared with ours
through the same decoder (our trie beam search, language model and scoring in NeuralSwipeEval).

Evaluation only. Nothing from these models is shipped, distilled or trained on (their licence would bind any model
derived from their weights or outputs). The weights are downloaded by the user into a directory outside the repo:

  for f in honorable_sturgeon/model_fp32.pte magic_macaw/model_fp32.pte; do
    curl -L --create-dirs -o "$DIR/$f" "https://huggingface.co/futo-org/futo-swipe/resolve/main/$f"; done
  tools/.venv/bin/pip install --no-deps executorch==1.6.0 nvidia-cuda-runtime==13.2.86   # CPU use; libcudart is a link dep
  tools/swipe/py tools/swipe/futo_bench.py <DIR> <out.bin> <set.jsonl.gz>...

Input preparation follows the model card's description (our own code): the stroke in the encoder's normalized
keyboard frame (three letter rows; x = u / 10, y = v / 3 in our grid units, clipped to [0, 1]), resampled to a
uniform 60 Hz and then linearly to 64 points; the 26 letter centres at their QWERTY positions in the same frame.

Output (read by NeuralSwipeEval --emissions): per record "id\\tT\\tvariant" header lines are avoided; instead a
little-endian binary: magic "PBEM", u32 variants V, u32 records N; then per variant a UTF-8 name (u16 length +
bytes); then per record: u16 id length + id bytes, u16 T, and for each variant T x 27 float32 log-scores in our
class order (0 = blank, 1 + i = letter 'a' + i).
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
import swipefeat as sf  # noqa: E402

LETTERS = sf.LETTERS


def resample64(x, y, t, T=64):
    """Uniform 60 Hz, then T points evenly spaced in time (as the model card describes)."""
    t = t - t[0]
    if t[-1] > 1e-3:
        n60 = max(2, int(round(t[-1] / (1000.0 / 60.0))) + 1)
        tt = np.linspace(0.0, t[-1], n60)
        x, y = np.interp(tt, t, x), np.interp(tt, t, y)
    idx = np.linspace(0, len(x) - 1, T)
    return np.stack([np.interp(idx, np.arange(len(x)), x), np.interp(idx, np.arange(len(y)), y)]).astype(np.float32)


def main():
    from executorch.runtime import Runtime
    mdir, out, sets = sys.argv[1], sys.argv[2], sys.argv[3:]
    rt = Runtime.get()
    enc = rt.load_program(os.path.join(mdir, "honorable_sturgeon", "model_fp32.pte")).load_method("forward")
    dec = rt.load_program(os.path.join(mdir, "magic_macaw", "model_fp32.pte")).load_method("forward")
    g = json.load(open(os.path.join(REPO, "tools", "data", "human", "geometry", "gboard_pixel8.json")))
    fit = sf.fit_grid({k["label"]: (k["cx"], k["cy"]) for k in g["keys"] if len(k["label"]) == 1 and k["label"].isalpha()})
    keys = torch.zeros(1, 64, 2)
    mask = torch.zeros(1, 64, dtype=torch.bool)
    for i, c in enumerate(LETTERS):
        keys[0, i, 0] = sf.GRID[c][0] / 10.0
        keys[0, i, 1] = sf.GRID[c][1] / 3.0
        mask[0, i] = True
    variants = ["futo-enc", "futo-enc+dec"]
    recs = []
    for path in sets:
        for line in gzip.open(path, "rt", encoding="utf-8"):
            d = json.loads(line)
            u, v, t = sf.to_grid(d["points"], fit)
            feats = resample64(np.clip(u / 10.0, 0, 1), np.clip(v / 3.0, 0, 1), np.asarray(t, np.float64))
            le, coeff, lam = enc.execute((torch.from_numpy(feats[None]), keys, mask))
            le = le[0]  # [32, 65]: keys 0..63, blank 64
            e27 = torch.cat([le[:, :26], le[:, 64:65]], dim=1)  # letters a..z, blank (decoder's order)
            dec_out = dec.execute((torch.cat([e27, coeff[0], lam[0]], dim=1)[None],))[0][0]  # [32, 27]
            dec_lp = torch.log_softmax(dec_out, dim=-1)
            ours = []
            for m in (e27, dec_lp):
                ours.append(torch.cat([m[:, 26:27], m[:, :26]], dim=1).numpy().astype("<f4"))  # blank first
            recs.append((d["id"], ours))
    with open(out, "wb") as f:
        f.write(b"PBEM" + struct.pack("<II", len(variants), len(recs)))
        for v in variants:
            b = v.encode()
            f.write(struct.pack("<H", len(b)) + b)
        for rid, arrs in recs:
            b = rid.encode()
            f.write(struct.pack("<H", len(b)) + b + struct.pack("<H", arrs[0].shape[0]))
            for a in arrs:
                f.write(a.tobytes())
    print("wrote %s: %d records x %d variants" % (out, len(recs), len(variants)))


if __name__ == "__main__":
    main()
