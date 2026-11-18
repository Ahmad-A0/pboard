"""Shared helpers for the human-data converters: keyboard layouts, key-unit projection, splits, JSONL I/O.

Key units
---------
Every QWERTY layout used by the adopted datasets has the standard stagger (row 2 shifted by half a key,
row 3 by one and a half). We therefore describe positions on a canonical grid measured in *key pitches*
(centre-to-centre distance of adjacent letter keys, i.e. the hit-box width including the gap) horizontally and
*row pitches* vertically:

    u = (x - cx_q) / pitch_x + 0.5        q -> (0.5, 0.5), a -> (1.0, 1.5), z -> (2.0, 2.5)
    v = (y - cy_q) / pitch_y + 0.5

Taps are projected key-relatively: the offset from the *intended* key's centre in source pitches is re-applied
around the same key on the target layout. Swipes have no single intended key per sample, so the whole stroke is
mapped through the per-axis affine transform that takes the source letter grid onto the target letter grid
(identical to the key-relative map at every key centre). See docs/eval-data.md for the caveats.
"""
import gzip
import hashlib
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
RAW = os.path.join(REPO, "tools", "data", "raw")
SPLITS = os.path.join(HERE, "splits")
OUT = os.path.join(HERE, "out")  # gitignored: full-size and licence-unclear local builds

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_SHIFT = [0.0, 0.5, 1.5]
LETTERS = set("abcdefghijklmnopqrstuvwxyz")
GRID = {c: (i + 0.5 + ROW_SHIFT[r], r + 0.5) for r, row in enumerate(ROWS) for i, c in enumerate(row)}

VERSION = "v1"


def lstsq_line(pairs):
    """Least-squares fit y = a + b x over (x, y) pairs."""
    n = len(pairs)
    mx = sum(p[0] for p in pairs) / n
    my = sum(p[1] for p in pairs) / n
    sxx = sum((p[0] - mx) ** 2 for p in pairs)
    sxy = sum((p[0] - mx) * (p[1] - my) for p in pairs)
    b = sxy / sxx
    return my - b * mx, b


class Layout:
    """A QWERTY layout in some pixel space: letter centres plus optional special keys.

    centres: {letter: (cx, cy)}; specials: {name: (cx, cy, width, height)} with width/height in the
    hit-box (pitch) sense. Exposes the affine grid fit and key-relative helpers.
    """

    def __init__(self, name, centres, specials=None, bounds=None, meta=None):
        self.name = name
        self.centres = dict(centres)
        self.specials = dict(specials or {})
        self.bounds = bounds  # (x0, y0, x1, y1) of the touchable keyboard area, or None
        self.meta = meta or {}
        ox, self.px = lstsq_line([(GRID[c][0], xy[0]) for c, xy in self.centres.items()])
        oy, self.py = lstsq_line([(GRID[c][1], xy[1]) for c, xy in self.centres.items()])
        self.ox, self.oy = ox, oy  # pixel position of grid (0, 0)
        self.fit_rms = math.sqrt(sum((self.from_units(*GRID[c])[0] - xy[0]) ** 2 + (self.from_units(*GRID[c])[1] - xy[1]) ** 2
                                     for c, xy in self.centres.items()) / len(self.centres))

    # ---- grid (affine) mapping
    def to_units(self, x, y):
        return (x - self.ox) / self.px, (y - self.oy) / self.py

    def from_units(self, u, v):
        return self.ox + u * self.px, self.oy + v * self.py

    # ---- key-relative mapping
    def key_rect(self, key):
        """(cx, cy, w, h) of a letter (pitch-sized) or special key."""
        if key in self.centres:
            cx, cy = self.centres[key]
            return cx, cy, self.px, self.py
        return self.specials[key]

    def offset_of(self, key, x, y):
        """Offset of (x, y) from the key centre: (dx in key widths of that key, dy in row pitches)."""
        cx, cy, w, h = self.key_rect(key)
        return (x - cx) / w, (y - cy) / self.py

    def place(self, key, du, dv):
        cx, cy, w, h = self.key_rect(key)
        return cx + du * w, cy + dv * self.py

    def nearest_letter(self, x, y):
        """Literal key for a tap: nearest letter centre in key units (ties impossible in practice)."""
        u, v = self.to_units(x, y)
        best, bd = None, 1e18
        for c, (cx, cy) in self.centres.items():
            cu, cv = self.to_units(cx, cy)
            d = (cu - u) ** 2 + (cv - v) ** 2
            if d < bd:
                best, bd = c, d
        return best

    def literal(self, x, y):
        """Key under (x, y) incl. specials (their hit boxes win), else nearest letter."""
        for name, (cx, cy, w, h) in self.specials.items():
            if abs(x - cx) <= w / 2 and abs(y - cy) <= self.py / 2:
                return name
        return self.nearest_letter(x, y)


def load_target(path=None):
    """The Pixel-8 Gboard geometry. Prefers the benchmark's measured file if present in this checkout,
    else the snapshot vendored next to this module (same content, sha256 recorded in the manifest)."""
    cands = [path] if path else [os.path.join(REPO, "tools", "bench", "geometry", "gboard_pixel8.json"),
                                 os.path.join(HERE, "geometry", "gboard_pixel8.json")]
    for p in cands:
        if p and os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                raw = json.load(f)
            break
    else:
        raise FileNotFoundError("no target geometry found")
    centres, specials = {}, {}
    for k in raw["keys"]:
        if k["label"] in LETTERS:
            centres[k["label"]] = (k["cx"], k["cy"])
        else:
            specials[k["name"]] = (k["cx"], k["cy"], k["width"], k["height"])
    kb = raw["keyboard"]
    lay = Layout(raw["name"], centres, specials, bounds=(kb["x"], kb["y"], kb["x"] + kb["width"], kb["y"] + kb["height"]),
                 meta={"path": os.path.relpath(p, REPO), "sha256": sha256_file(p)})
    lay.raw = raw
    return lay


def clamp_to(lay, x, y):
    """Clamp a projected point into the target keyboard window; returns (x, y, clamped?)."""
    x0, y0, x1, y1 = lay.bounds
    cx = min(max(x, x0), x1 - 1)
    cy = min(max(y, y0), y1 - 1)
    return cx, cy, (cx != x or cy != y)


def project_stroke(src, dst, pts):
    """Map a stroke [(x, y, t)] from src pixel space to dst pixels through key units; clamps into dst window.
    Returns (points, n_clamped). Timestamps are kept (ms from the first sample); duplicate timestamps keep the
    last sample so the final touch position survives."""
    out, n_cl = [], 0
    t0 = pts[0][2]
    for x, y, t in pts:
        u, v = src.to_units(x, y)
        X, Y = dst.from_units(u, v)
        X, Y, cl = clamp_to(dst, X, Y)
        n_cl += cl
        p = [round(X, 1), round(Y, 1), int(round(t - t0))]
        if out and p[2] <= out[-1][2]:
            if p[2] == out[-1][2]:
                out[-1] = p
            continue  # out-of-order sample (rare browser glitch): drop
        out.append(p)
    return out, n_cl


# ------------------------------------------------------------------------------------------- splits

def stable_hash(*parts):
    h = hashlib.sha256("\x1f".join(str(p) for p in parts).encode("utf-8")).hexdigest()
    return int(h[:15], 16) / float(16 ** 15)


def balanced_split(source, participants):
    """Exact half/half participant split for small studies: rank by stable hash, first half is held out."""
    ranked = sorted(participants, key=lambda p: stable_hash("pboard-eval-" + VERSION, source, p))
    half = (len(ranked) + 1) // 2
    return {p: ("test" if i < half else "tune") for i, p in enumerate(ranked)}


def split_of(source, participant, test_fraction=0.5):
    """Deterministic participant-level split: 'test' (held out) or 'tune'."""
    return "test" if stable_hash("pboard-eval-" + VERSION, source, participant) < test_fraction else "tune"


# ------------------------------------------------------------------------------------------- text

def word_core(tok):
    """Strip leading/trailing punctuation; keep inner apostrophes/hyphens. Curly apostrophes -> '."""
    tok = tok.replace("’", "'").replace("‘", "'")
    i, j = 0, len(tok)
    while i < j and not tok[i].isalnum():
        i += 1
    while j > i and not tok[j - 1].isalnum():
        j -= 1
    return tok[i:j]


# ------------------------------------------------------------------------------------------- io

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def open_text(path, mode="rt"):
    if path.endswith(".gz"):
        return gzip.open(path, mode, encoding="utf-8")
    return open(path, mode, encoding="utf-8")


def read_jsonl(path):
    with open_text(path) as f:
        for line in f:
            if line.strip():
                yield json.loads(line)
