"""Layout-agnostic gesture features (model format v2), shared by training and the engine
(dev.pboard.engine.swipe.SwipeFeatures2; SwipeFeatures2Test checks the port against `--vectors` output).

Inputs: the stroke (x, y, t) and the centres of the layout's keys, both in the same pixel space (any layout).
  1. Key units: everything is divided by the median nearest-neighbour distance between key centres (one key
     pitch on a phone keyboard), so screen size and density drop out; aspect ratio is kept (physical shape).
  2. Frames: a frame each time progress = arc length / STEP_S + elapsed time / STEP_T passes an integer (the
     finger moving or resting both advance it), linearly interpolated, plus the final sample. Long strokes are
     capped at MAX_FRAMES by scaling both steps.
  3. Per-frame features (N_FEAT): position in the layout frame (key-centre bounding box mapped to [-1, 1]), motion
     since the previous frame (in steps), elapsed time (in steps), first/last flags and the log size of the layout
     frame in key units (so the network knows the frame's aspect).
  4. Key positions for the model's spatial head: key centres in the frame, [0, 1] per axis.
No feature depends on which letter a key carries, so the same network serves any layout supplied at run time.
"""
import json
import math
import sys

import numpy as np

STEP_S = 0.5        # key units of arc length per progress unit
STEP_T = 70.0       # ms per progress unit
MAX_FRAMES = 160
N_FEAT = 9


def key_unit(kx, ky):
    """Median nearest-neighbour distance between key centres (pixels)."""
    k = np.stack([kx, ky], 1).astype(np.float64)
    d = np.sqrt(((k[:, None, :] - k[None, :, :]) ** 2).sum(-1))
    d[np.arange(len(k)), np.arange(len(k))] = np.inf
    return float(np.median(d.min(1)))


def frame_of(kx, ky):
    """(x0, sx, y0, sy): key-centre bounding box in key units (sizes floored at 1 key)."""
    x0, x1, y0, y1 = float(kx.min()), float(kx.max()), float(ky.min()), float(ky.max())
    return x0, max(1.0, x1 - x0), y0, max(1.0, y1 - y0)


def resample(x, y, t, step_s=None, step_t=None, max_frames=None):
    """Frames at integer progress values (see module doc). x, y key units; t ms (non-decreasing)."""
    step_s = STEP_S if step_s is None else step_s
    step_t = STEP_T if step_t is None else step_t
    max_frames = MAX_FRAMES if max_frames is None else max_frames
    x = np.asarray(x, np.float64); y = np.asarray(y, np.float64); t = np.asarray(t, np.float64)
    seg = np.hypot(np.diff(x), np.diff(y))
    dt = np.maximum(0.0, np.diff(t))
    total_s = float(seg.sum()); total_t = float(dt.sum())
    est = total_s / step_s + total_t / step_t
    if est > max_frames - 2:
        k = est / (max_frames - 2)
        step_s *= k; step_t *= k
    prog = np.concatenate([[0.0], np.cumsum(seg / step_s + dt / step_t)])
    keep = np.concatenate([[True], np.diff(prog) > 1e-9])  # drop samples that add no progress
    prog, xs, ys, ts = prog[keep], x[keep], y[keep], np.maximum.accumulate(t)[keep]
    end = prog[-1]
    targets = np.arange(0.0, end, 1.0)
    if len(targets) == 0 or end - targets[-1] > 1e-6:
        targets = np.concatenate([targets, [end]])
    if len(prog) == 1:
        return np.array([xs[0]]), np.array([ys[0]]), np.array([ts[0]]), step_s, step_t
    return np.interp(targets, prog, xs), np.interp(targets, prog, ys), np.interp(targets, prog, ts), step_s, step_t


def features(px, py, pt, kx, ky):
    """Stroke and keys in pixels -> (features [T, N_FEAT] float32, key frame coords [K, 2] float32)."""
    u = key_unit(kx, ky)
    kx = np.asarray(kx, np.float64) / u; ky = np.asarray(ky, np.float64) / u
    x = np.asarray(px, np.float64) / u; y = np.asarray(py, np.float64) / u
    x0, sx, y0, sy = frame_of(kx, ky)
    fx, fy, ft, step_s, step_t = resample(x, y, np.asarray(pt, np.float64) - pt[0])
    T = len(fx)
    f = np.zeros((T, N_FEAT), np.float32)
    f[:, 0] = (fx - x0) / sx * 2 - 1
    f[:, 1] = (fy - y0) / sy * 2 - 1
    f[1:, 2] = np.diff(fx) / step_s
    f[1:, 3] = np.diff(fy) / step_s
    f[1:, 4] = np.minimum(np.diff(ft), 4 * step_t) / step_t
    f[0, 5] = 1
    f[T - 1, 6] = 1
    f[:, 7] = math.log(sx)
    f[:, 8] = math.log(sy)
    keys = np.stack([(kx - x0) / sx, (ky - y0) / sy], 1).astype(np.float32)
    return f, keys


def write_vectors(path):
    rng = np.random.default_rng(11)
    # QWERTY-like key centres in pixels (Pixel 8 pitches) plus a jittered, rotated layout.
    rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
    shift = [0, 0.5, 1.5]
    kx = np.array([58.5 + 107 * (i + shift[r]) for r, row in enumerate(rows) for i in range(len(row))])
    ky = np.array([1715.5 + 154.75 * r for r, row in enumerate(rows) for i in range(len(row))])
    th = 0.7
    rx = 540 + (kx - 540) * math.cos(th) - (ky - 1870) * math.sin(th) + rng.normal(0, 5, len(kx))
    ry = 1870 + (kx - 540) * math.sin(th) + (ky - 1870) * math.cos(th) + rng.normal(0, 5, len(kx))
    cases = []
    for (lx, ly) in [(kx, ky), (rx, ry)]:
        for n, hz in [(2, 60), (6, 45), (50, 60), (120, 120), (500, 90)]:
            t = np.round(np.cumsum(rng.uniform(0.5, 1.5, n) * 1000.0 / hz)); t[0] = 0  # whole ms, as on the device
            x = np.cumsum(rng.normal(0, 30, n)) + 500
            y = np.cumsum(rng.normal(0, 20, n)) + 1850
            if n == 6:
                t[3] = t[2]; x[4] = x[3]; y[4] = y[3]
            f, keys = features(x, y, t, lx, ly)
            cases.append({"x": x.tolist(), "y": y.tolist(), "t": t.tolist(), "kx": lx.tolist(), "ky": ly.tolist(),
                          "f": f.reshape(-1).tolist(), "keys": keys.reshape(-1).tolist()})
    with open(path, "w") as fh:
        json.dump({"nFeat": N_FEAT, "cases": cases}, fh)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--vectors":
        write_vectors(sys.argv[2])
