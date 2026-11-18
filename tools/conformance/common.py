"""Shared helpers for the conformance harness (tools/conformance): paths, geometry, external tools."""
import hashlib
import json
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.join(ROOT, "tools", "conformance")
GEOMETRY = os.path.join(ROOT, "tools", "bench", "geometry", "gboard_pixel8.json")
HUMAN = os.path.join(ROOT, "tools", "data", "human", "splits", "v1")
OUT = os.path.join(HERE, "out")

GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
PBOARD = "dev.pboard/.ime.PboardIme"
IMES = {"gboard": GBOARD, "pboard": PBOARD}

# Screen regions (Pixel 8 profile, both keyboards share Gboard's geometry to within a pixel).
REGION_TOP = 1300          # top of the area hashed and saved per observation (popups reach above the strip)
STRIP_TOP, STRIP_BOTTOM = 1517, 1639
KEYS_BOTTOM = 2290         # below: the navigation band


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def load_json(p):
    with open(p) as f:
        return json.load(f)


def read_jsonl(p):
    import gzip
    op = gzip.open if p.endswith(".gz") else open
    with op(p, "rt") as f:
        return [json.loads(l) for l in f if l.strip()]


def write_jsonl(p, rows):
    os.makedirs(os.path.dirname(os.path.abspath(p)), exist_ok=True)
    with open(p, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


class Geometry:
    def __init__(self, path=GEOMETRY):
        g = load_json(path)
        self.g = g
        self.keys = {k["name"]: k for k in g["keys"]}
        self.key_width = g.get("keyWidth", 107.0)
        self.slots = g["suggestionStrip"]["slots"]  # centre, left, right

    def centre(self, name):
        k = self.keys[name]
        return k["cx"], k["cy"]

    def hit(self, x, y):
        """Name of the key whose hit rectangle contains (x, y), or None (strip, nav band)."""
        for k in self.g["keys"]:
            l, t, r, b = k["hit"]
            if l <= x < r and t <= y < b:
                return k["name"]
        return None

    def depth(self, x, y):
        """(key, fraction of the key width from its nearest horizontal hit edge, nearest neighbour)."""
        name = self.hit(x, y)
        if name is None:
            return None, None, None
        l, t, r, b = self.keys[name]["hit"]
        dl, dr = x - l, r - x
        frac = min(dl, dr) / self.key_width
        # neighbour on the near side, same row
        side_x = l - 1 if dl < dr else r + 1
        return name, round(frac, 3), self.hit(side_x, y)

    def letter_keys(self):
        return [k for k in self.g["keys"] if len(k["name"]) == 1 and k["name"].isalpha()]


def tool(name):
    """Path of an external tool; tesseract comes from the flake's nixpkgs when not on PATH."""
    p = shutil.which(name)
    if p:
        return p
    if name == "tesseract":
        env = os.environ.get("TESSERACT")
        if env:
            return env
        cache = os.path.join(OUT, ".tesseract_path")
        if os.path.exists(cache):
            p = open(cache).read().strip()
            if os.path.exists(p):
                return p
        out = subprocess.run(["nix", "build", "--no-link", "--print-out-paths", "--inputs-from", ROOT,
                              "nixpkgs#tesseract"], capture_output=True, text=True, check=True).stdout.strip()
        p = os.path.join(out.splitlines()[-1], "bin", "tesseract")
        os.makedirs(OUT, exist_ok=True)
        open(cache, "w").write(p)
        return p
    raise SystemExit("missing tool: " + name)


def gray(path, crop=None, scale=None):
    """Grey pixels of an image (optionally cropped x,y,w,h and scaled): (w, h, bytes)."""
    args = [tool("magick"), path]
    if crop:
        x, y, w, h = crop
        args += ["-crop", "%dx%d+%d+%d" % (w, h, x, y), "+repage"]
    if scale:
        args += ["-filter", "box", "-resize", "%g%%" % (scale * 100)]
    args += ["-colorspace", "gray", "-depth", "8", "-format", "%w %h\n", "-write", "info:-", "gray:-"]
    out = subprocess.run(args, capture_output=True, check=True).stdout
    nl = out.index(b"\n")
    w, h = map(int, out[:nl].split())
    return w, h, out[nl + 1:]


def rgb(path, crop=None):
    """RGB pixels of an image (optionally cropped): (w, h, bytes)."""
    args = [tool("magick"), path]
    if crop:
        x, y, w, h = crop
        args += ["-crop", "%dx%d+%d+%d" % (w, h, x, y), "+repage"]
    args += ["-depth", "8", "-format", "%w %h\n", "-write", "info:-", "rgb:-"]
    out = subprocess.run(args, capture_output=True, check=True).stdout
    nl = out.index(b"\n")
    w, h = map(int, out[:nl].split())
    return w, h, out[nl + 1:]
