#!/usr/bin/env python3
"""Build the swipe-model training set (docs/swipe-neural.md) in grid units.

Sources (never the held-out test data):
  * FUTO swipe train split (MIT; tools/data/raw/futo_swipe/train.jsonl, fetch with
    `curl -L -o tools/data/raw/futo_swipe/train.jsonl https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/train.jsonl`).
    Our swipe_futo tune/test are FUTO's dev/test, which are session-disjoint from train; sessions are checked anyway.
  * How We Swipe (MIT): only users in neither swipe_hws.tune nor swipe_hws.test, i.e. tune-split users left out of the
    core tune subset (taken from out/v1/swipe_hws_full.tune.jsonl.gz). Test-split users are never used.

Every stroke goes through exactly the projection the evaluation sets use (tools/data/human/common.py: source layout
-> key units -> Pixel-8 Gboard pixels, clamped into the keyboard window) and is then expressed in grid units with
swipefeat.fit_grid, which is what the engine does at run time with any geometry.

Output: tools/swipe/work/train.npz with
  uv int16 [P, 2] (grid units x 1000), t int32 [P] (ms from the first sample), off int64 [N + 1],
  lab uint8 [L] (collapsed letter classes), loff int64 [N + 1], word str [N], src uint8 [N] (0 futo, 1 hws),
  aspect float32 [N] (source key aspect = row pitch / key pitch), sess str [N]
and the same for the dev sets (core tune subsets) as dev_futo.npz / dev_hws.npz.

Usage: tools/swipe/py tools/swipe/prepare.py [--limit N] [--workers 8]
"""
import argparse
import gzip
import json
import multiprocessing as mp
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, "tools", "data", "human"))
import swipefeat as sf  # noqa: E402
from common import LETTERS, Layout, load_target, project_stroke, word_core  # noqa: E402

RAW = os.path.join(REPO, "tools", "data", "raw", "futo_swipe")
HUMAN = os.path.join(REPO, "tools", "data", "human")
WORK = os.path.join(HERE, "work")

_dst = None
_fit = None
_centres_n = None


def _init():
    global _dst, _fit, _centres_n
    _dst = load_target()
    _fit = sf.fit_grid(_dst.centres)
    with open(os.path.join(RAW, "swipe-5", "layouts", "qwerty.json"), encoding="utf-8") as f:
        _centres_n = {k["letter"]: (k["cx"], k["cy"]) for k in json.load(f)["keys"]}


def _grid(points):
    u, v, t = sf.to_grid(points, _fit)
    return u, v, t


def _futo_lines(lines):
    """Same filters as tools/data/human/src_futo.py (portrait, plain letters, >= 2 letters, >= 3 samples)."""
    out = []
    cache = {}
    for line in lines:
        d = json.loads(line)
        if not str(d.get("orientation", "")).startswith("portrait") or not isinstance(d["data"], list):
            continue
        target = word_core(d["word"])
        letters = [c for c in target.lower() if c in LETTERS]
        if len(letters) < 2 or len(letters) != sum(c.isalpha() for c in target):
            continue
        pts = d["data"]
        if len(pts) < 3 or pts[-1]["t"] - pts[0]["t"] < 30:
            continue
        W, H = float(d["canvas_width"]), float(d["canvas_height"])
        if (W, H) not in cache:
            cache[(W, H)] = Layout("futo", {c: (x * W, y * H) for c, (x, y) in _centres_n.items()}, bounds=(0, 0, W, H))
        proj, _ = project_stroke(cache[(W, H)], _dst, [(p["x"] * W, p["y"] * H, p["t"]) for p in pts])
        if len(proj) < 3:
            continue
        u, v, t = _grid(proj)
        out.append((u, v, t, target, 0, (H / 3) / (W / 10), d["session"]))
    return out


def _chunks(path, size, limit):
    buf, n = [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            buf.append(line)
            n += 1
            if len(buf) >= size:
                yield buf
                buf = []
            if limit and n >= limit:
                break
    if buf:
        yield buf


def _hws_items(path, exclude):
    for line in gzip.open(path, "rt", encoding="utf-8"):
        d = json.loads(line)
        if d["participant"] in exclude:
            continue
        u, v, t = _grid(d["points"])
        yield (u, v, t, d["target"], 1, d["srcKeyAspect"], d["participant"])


def _trace_items(path, src):
    for line in gzip.open(path, "rt", encoding="utf-8"):
        d = json.loads(line)
        u, v, t = _grid(d["points"])
        yield (u, v, t, d["target"], src, d.get("srcKeyAspect", 1.45), d["participant"], d["context"])


def save(path, items, with_context=False):
    n = len(items)
    off = np.zeros(n + 1, np.int64)
    loff = np.zeros(n + 1, np.int64)
    for i, it in enumerate(items):
        off[i + 1] = off[i] + len(it[0])
        loff[i + 1] = loff[i] + len(sf.labels(it[3]))
    uv = np.zeros((off[-1], 2), np.int16)
    t = np.zeros(off[-1], np.int32)
    lab = np.zeros(loff[-1], np.uint8)
    for i, it in enumerate(items):
        a, b = off[i], off[i + 1]
        uv[a:b, 0] = np.clip(np.round(np.asarray(it[0]) * 1000), -32000, 32000)
        uv[a:b, 1] = np.clip(np.round(np.asarray(it[1]) * 1000), -32000, 32000)
        tt = np.asarray(it[2], np.float64)
        t[a:b] = np.round(tt - tt[0])
        lab[loff[i]:loff[i + 1]] = sf.labels(it[3])
    extra = {}
    if with_context:
        extra["context"] = np.array([it[7] for it in items])
    np.savez(path, uv=uv, t=t, off=off, lab=lab, loff=loff, word=np.array([it[3] for it in items]),
             src=np.array([it[4] for it in items], np.uint8), aspect=np.array([it[5] for it in items], np.float32),
             sess=np.array([it[6] for it in items]), **extra)
    print("wrote %s: %d strokes, %d points" % (path, n, off[-1]), file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--workers", type=int, default=8)
    a = ap.parse_args()
    os.makedirs(WORK, exist_ok=True)
    _init()
    v1 = os.path.join(HUMAN, "splits", "v1")
    core_tune = {json.loads(l)["participant"] for l in gzip.open(os.path.join(v1, "swipe_hws.tune.jsonl.gz"), "rt")}
    core_test = {json.loads(l)["participant"] for l in gzip.open(os.path.join(v1, "swipe_hws.test.jsonl.gz"), "rt")}
    # Dev sets for checkpoint selection: the core tune subsets.
    save(os.path.join(WORK, "dev_futo.npz"), list(_trace_items(os.path.join(v1, "swipe_futo.tune.jsonl.gz"), 0)), True)
    save(os.path.join(WORK, "dev_hws.npz"), list(_trace_items(os.path.join(v1, "swipe_hws.tune.jsonl.gz"), 1)), True)
    futo_eval_sessions = set()
    for name in ("swipe_futo_full.tune.jsonl.gz", "swipe_futo_full.test.jsonl.gz"):
        p = os.path.join(HUMAN, "out", "v1", name)
        if os.path.exists(p):
            futo_eval_sessions |= {json.loads(l)["participant"] for l in gzip.open(p, "rt")}
        else:
            print("warning: %s missing, session overlap not checked" % name, file=sys.stderr)

    items = []
    with mp.Pool(a.workers, initializer=_init) as pool:
        for res in pool.imap(_futo_lines, _chunks(os.path.join(RAW, "train.jsonl"), 4000, a.limit)):
            items.extend(res)
            if len(items) % 100000 < 4000:
                print("futo %d" % len(items), file=sys.stderr)
    overlap = sum(1 for it in items if it[6] in futo_eval_sessions)
    print("futo train strokes %d, in tune/test sessions %d (dropped)" % (len(items), overlap), file=sys.stderr)
    items = [it for it in items if it[6] not in futo_eval_sessions]
    hws_full = os.path.join(HUMAN, "out", "v1", "swipe_hws_full.tune.jsonl.gz")
    hws = list(_hws_items(hws_full, core_tune | core_test))
    print("hws strokes from %d users outside the core tune/test subsets: %d" % (len({h[6] for h in hws}), len(hws)),
          file=sys.stderr)
    items.extend(hws)
    save(os.path.join(WORK, "train.npz"), items)


if __name__ == "__main__":
    main()
