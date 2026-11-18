"""Gesture features shared by training (Python) and inference (Kotlin, dev.pboard.engine.swipe.SwipeFeatures).

Keep the two in sync: SwipeFeaturesTest (engine) checks the Kotlin port against vectors written by
`python3 tools/swipe/swipefeat.py --vectors <out.json>`.

Pipeline for one stroke (points in keyboard pixels of some QWERTY geometry, t in ms):
  1. Grid units. The 26 letter centres are fitted (least squares, per axis) to the canonical staggered QWERTY grid
     (q = (0.5, 0.5), a = (1.0, 1.5), z = (2.0, 2.5)), so u is measured in key pitches and v in row pitches.
     This is the same key-relative geometry the evaluation projects every human stroke onto, and it removes
     screen size, keyboard height and key aspect ratio.
  2. Frames. The stroke is resampled along its length: a frame every STEP_S grid units of arc length, or every
     STEP_T ms if the finger moves less than that (dwell on a key still produces frames). Positions and times are
     linearly interpolated, so the result does not depend on the touch sampling rate.
  3. Per-frame features (N_FEAT): position, motion since the previous frame, elapsed time, first/last flags and a
     Gaussian proximity to each of the 26 letter keys.
"""
import json
import math
import sys

import numpy as np

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_SHIFT = [0.0, 0.5, 1.5]
LETTERS = "abcdefghijklmnopqrstuvwxyz"
GRID = {c: (i + 0.5 + ROW_SHIFT[r], r + 0.5) for r, row in enumerate(ROWS) for i, c in enumerate(row)}
KEY_U = np.array([GRID[c][0] for c in LETTERS], dtype=np.float32)
KEY_V = np.array([GRID[c][1] for c in LETTERS], dtype=np.float32)

STEP_S = 0.30        # grid units of arc length per frame
STEP_T = 40.0        # ms per frame while (nearly) stationary
MAX_FRAMES = 192     # longer strokes are resampled with a proportionally larger step
PROX_SIGMA = 0.55    # grid units
N_BASE = 7
N_FEAT = N_BASE + 26
N_CLASSES = 27       # 0 = CTC blank, 1 + i = LETTERS[i]


def fit_grid(centres):
    """centres: {letter: (cx, cy)} in pixels -> (ox, px, oy, py) with u = (x - ox) / px, v = (y - oy) / py."""
    def line(pairs):
        n = len(pairs)
        mx = sum(p[0] for p in pairs) / n
        my = sum(p[1] for p in pairs) / n
        sxx = sum((p[0] - mx) ** 2 for p in pairs)
        sxy = sum((p[0] - mx) * (p[1] - my) for p in pairs)
        b = sxy / sxx
        return my - b * mx, b
    ox, px = line([(GRID[c][0], xy[0]) for c, xy in centres.items()])
    oy, py = line([(GRID[c][1], xy[1]) for c, xy in centres.items()])
    return ox, px, oy, py


def to_grid(points, fit):
    ox, px, oy, py = fit
    p = np.asarray(points, dtype=np.float64)
    return (p[:, 0] - ox) / px, (p[:, 1] - oy) / py, p[:, 2]


def resample(u, v, t, step_s=STEP_S, step_t=STEP_T, max_frames=MAX_FRAMES):
    """Frames along the stroke (see module doc). u, v grid units, t ms (non-decreasing). Returns (fu, fv, ft)."""
    n = len(u)
    # Drop exact repeats of position and time; a non-increasing time keeps the segment but gives it no duration.
    total = 0.0
    for i in range(1, n):
        total += math.hypot(u[i] - u[i - 1], v[i] - v[i - 1])
    dur = float(t[-1] - t[0]) if n > 1 else 0.0
    # Cap the frame count: scale both steps up for very long or very slow strokes.
    est = total / step_s + dur / step_t
    if est > max_frames - 2:
        k = est / (max_frames - 2)
        step_s *= k
        step_t *= k
    fu, fv, ft = [float(u[0])], [float(v[0])], [float(t[0])]
    s_since = 0.0
    t_last = float(t[0])
    for i in range(1, n):
        au, av, at = float(u[i - 1]), float(v[i - 1]), float(t[i - 1])
        bu, bv, bt = float(u[i]), float(v[i]), float(t[i])
        L = math.hypot(bu - au, bv - av)
        D = max(0.0, bt - at)
        pos = 0.0
        while True:
            f_s = pos + (step_s - s_since) / L if L > 1e-9 else math.inf
            tc = at + pos * D
            f_t = pos + (step_t - (tc - t_last)) / D if D > 1e-9 else math.inf
            f = min(f_s, f_t)
            if f > 1.0:
                s_since += (1.0 - pos) * L
                break
            fu.append(au + f * (bu - au))
            fv.append(av + f * (bv - av))
            ft.append(at + f * D)
            s_since = 0.0
            t_last = at + f * D
            pos = f
    lu, lv, lt = float(u[-1]), float(v[-1]), float(max(t[-1], ft[-1]))
    if s_since > 1e-6 or lt - t_last > 1e-6 or len(fu) == 1:
        fu.append(lu)
        fv.append(lv)
        ft.append(lt)
    return np.array(fu, np.float32), np.array(fv, np.float32), np.array(ft, np.float32)


def features(fu, fv, ft, step_s=STEP_S, step_t=STEP_T):
    """[T, N_FEAT] float32 features of resampled frames."""
    T = len(fu)
    x = np.zeros((T, N_FEAT), np.float32)
    x[:, 0] = (fu - 5.0) / 3.0
    x[:, 1] = (fv - 1.5) / 1.5
    du = np.diff(fu, prepend=fu[0])
    dv = np.diff(fv, prepend=fv[0])
    dt = np.diff(ft, prepend=ft[0])
    x[:, 2] = du / step_s
    x[:, 3] = dv / step_s
    x[:, 4] = np.minimum(dt, 4 * step_t) / step_t
    x[0, 5] = 1.0
    x[T - 1, 6] = 1.0
    d2 = (fu[:, None] - KEY_U[None, :]) ** 2 + (fv[:, None] - KEY_V[None, :]) ** 2
    x[:, N_BASE:] = np.exp(-d2 / (2 * PROX_SIGMA * PROX_SIGMA))
    return x


def stroke_features(u, v, t):
    fu, fv, ft = resample(u, v, t)
    return features(fu, fv, ft)


def labels(word):
    """Collapsed letter-key sequence of a word (1-based class ids): non-letters dropped, repeats merged."""
    out = []
    for c in word.lower():
        if c in GRID:
            k = 1 + LETTERS.index(c)
            if not out or out[-1] != k:
                out.append(k)
    return out


def write_vectors(path):
    """Reference vectors for the Kotlin port (a few deterministic strokes)."""
    rng = np.random.default_rng(7)
    cases = []
    for n, hz in [(2, 60), (5, 45), (40, 60), (90, 120), (400, 90)]:
        t = np.cumsum(rng.uniform(0.5, 1.5, n) * 1000.0 / hz) - 0
        t[0] = 0
        u = np.cumsum(rng.normal(0, 0.25, n)) + 4.0
        v = np.cumsum(rng.normal(0, 0.15, n)) + 1.5
        if n == 5:
            t[2] = t[1]  # duplicate time
        fu, fv, ft = resample(u, v, t)
        x = features(fu, fv, ft)
        cases.append({"u": u.tolist(), "v": v.tolist(), "t": t.tolist(), "fu": fu.tolist(), "fv": fv.tolist(),
                      "ft": ft.tolist(), "x": x.reshape(-1).tolist()})
    with open(path, "w") as f:
        json.dump({"nFeat": N_FEAT, "cases": cases}, f)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--vectors":
        write_vectors(sys.argv[2])
