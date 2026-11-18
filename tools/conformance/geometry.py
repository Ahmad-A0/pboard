#!/usr/bin/env python3
"""Measures an IME's key and strip geometry from two screenshots (no OCR), per device and IME.

  geometry.py --letters letters.png --strip strip.png --name NAME --ime IME --density DPI --out geo.json

letters.png: lowercase letters page in the playground (nocap field, empty: the strip shows the toolbar).
strip.png:   the same with suggestions showing ("hel" prefilled): used for the strip's dividers and text area.
Output: tools/bench geometry format (pboard-bench-geometry/1) plus suggestionStrip.textX (the strip's text
area between the side buttons). Keys are runs of pixels that differ from the keyboard background on a scan
line through the lower part of each key row (below the labels and hint digits); hit rectangles extend
halfway into the gaps and to the screen edges, like Gboard's.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import rgb  # noqa: E402

ROWS = [list("qwertyuiop"), list("asdfghjkl"), ["shift"] + list("zxcvbnm") + ["delete"],
        ["symbols", ",", "emoji", "space", ".", "enter"]]
LABEL = {"space": " ", "delete": "\b", "enter": "\n", "shift": "", "symbols": "", "emoji": ""}


def load(path):
    w, h, px = rgb(path)
    return w, h, px


def pix(img, x, y):
    w, h, px = img
    i = (y * w + x) * 3
    return px[i], px[i + 1], px[i + 2]


def diff(a, b):
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]), abs(a[2] - b[2]))


def runs(mask, min_len):
    out, start = [], None
    for i, m in enumerate(list(mask) + [False]):
        if m and start is None:
            start = i
        elif not m and start is not None:
            if i - start >= min_len:
                out.append((start, i))
            start = None
    return out


def measure(letters, strip, density):
    img = load(letters)
    W, H = img[0], img[1]
    s = density / 420.0
    # keyboard background: the colour of the band between the keys and the navigation handle
    from collections import Counter
    c = Counter(pix(img, x, y) for y in range(H - int(170 * s), H - int(120 * s), 3) for x in range(0, W, 9))
    bg = c.most_common(1)[0][0]
    # key-face mask per row; row bands = rows where >= 35 % of the width is key face
    def frac(y):
        return sum(1 for x in range(0, W, 4) if diff(pix(img, x, y), bg) > 10) / (W / 4)
    band_rows = [frac(y) > 0.35 for y in range(H)]
    bands = [b for b in runs(band_rows, int(70 * s)) if b[0] > H * 0.4]
    # the strip (toolbar) row is a band too only if its buttons are tall; keep the last four key rows
    if len(bands) < 4:
        raise SystemExit("found %d key rows: %s" % (len(bands), bands))
    bands = bands[-4:]
    keys = []
    pitch = []
    for r, (t, b) in enumerate(bands):
        y = t + int((b - t) * 0.84)
        mask = [diff(pix(img, x, y), bg) > 10 for x in range(W)]
        rr = runs(mask, int(25 * s))
        names = ROWS[r]
        if len(rr) != len(names):
            # glyph-free scan lines can still be split by a dark glyph descender: merge tiny gaps
            merged = []
            for a in rr:
                if merged and a[0] - merged[-1][1] < int(6 * s):
                    merged[-1] = (merged[-1][0], a[1])
                else:
                    merged.append(a)
            rr = merged
        if len(rr) != len(names):
            raise SystemExit("row %d: expected %d keys, found %d: %s" % (r + 1, len(names), len(rr), rr))
        for (l, rt), n in zip(rr, names):
            keys.append({"name": n, "row": r, "visual": [l, t, rt, b]})
        if r == 0:
            pitch = [(rr[i + 1][0] - rr[i][0]) for i in range(len(rr) - 1)]
    # hit rectangles: halfway into gaps; outer keys to the screen edges; rows halfway between bands
    gaps_v = [bands[i + 1][0] - bands[i][1] for i in range(3)]
    gv = sorted(gaps_v)[1]
    for k in keys:
        r = k["row"]
        l, t, rt, b = k["visual"]
        row = [x for x in keys if x["row"] == r]
        i = row.index(k)
        hl = 0 if i == 0 else (row[i - 1]["visual"][2] + l) / 2
        hr = W if i == len(row) - 1 else (rt + row[i + 1]["visual"][0]) / 2
        ht = t - gv / 2 if r == 0 else (bands[r - 1][1] + t) / 2
        hb = b + gv / 2 if r == 3 else (b + bands[r + 1][0]) / 2
        k["hit"] = [hl, ht, hr, hb]
        k["cx"] = (l + rt) / 2
        k["cy"] = (t + b) / 2
        k["width"] = hr - hl
        k["height"] = hb - ht
        k["label"] = LABEL.get(k["name"], k["name"])
    first_top = keys[0]["hit"][1]
    # strip: from the top of the IME window to the first row's hit top. The window top is the first row
    # above the keys where the colour leaves the keyboard background (the app's background differs) or,
    # failing that, a strip height of 48 dp.
    ytop = int(first_top)
    probe = [int(W * 0.02), int(W * 0.5), int(W * 0.98)]
    while ytop > first_top - 200 * s and all(diff(pix(img, x, ytop - 1), bg) < 6 or True for x in probe):
        line = Counter(pix(img, x, ytop - 1) for x in range(0, W, 5)).most_common(1)[0][0]
        if diff(line, bg) > 3:
            break
        ytop -= 1
    if first_top - ytop < 60 * s or first_top - ytop > 190 * s:
        ytop = int(first_top - 48 * 2.625 * s)
    # strip text area and dividers from the suggestions screenshot
    simg = load(strip)
    sy0, sy1 = int(ytop + 4), int(first_top - 4)
    cols = []
    for x in range(W):
        n = sum(1 for y in range(sy0, sy1, 2) if diff(pix(simg, x, y), bg) > 30)
        cols.append(n)
    ink = runs([v > 0 for v in cols], 1)
    # side buttons: the first ink cluster from the left and the last from the right
    left_end = ink[0][1] if ink and ink[0][0] < W * 0.12 else int(0.098 * W)
    right_start = ink[-1][0] if ink and ink[-1][1] > W * 0.88 else int(0.902 * W)
    # never closer to the edges than the side buttons' usual extent (~10 % of the width each side)
    tx0, tx1 = max(left_end + int(8 * s), int(0.098 * W)), min(right_start - int(8 * s), int(0.902 * W))
    divs = []
    for x in range(tx0, tx1):
        col = [diff(pix(simg, x, y), bg) for y in range(sy0, sy1)]
        best = run = 0
        for v in col:
            run = run + 1 if 8 < v < 120 else 0
            best = max(best, run)
        if best >= 35 * s and (not divs or x - divs[-1] > 10):
            divs.append(x)
    divs = [d for d in divs if tx0 + 40 * s < d < tx1 - 40 * s]
    edges = [tx0] + divs + [tx1]
    centres = [(a + b) / 2 for a, b in zip(edges, edges[1:])]
    cy = (ytop + first_top) / 2
    if len(centres) == 3:
        slots = [centres[1], centres[0], centres[2]]
    else:
        slots = [W / 2, tx0 + (tx1 - tx0) / 6, tx1 - (tx1 - tx0) / 6]
    letter_w = sorted(pitch)[len(pitch) // 2] if pitch else W / 10
    return {
        "format": "pboard-bench-geometry/1", "coordinates": "screen",
        "screen": {"width": W, "height": H, "density": density},
        "keyboard": {"x": 0, "y": int(ytop), "width": W, "height": H - int(ytop)},
        "keyWidth": letter_w,
        "keys": keys,
        "suggestionStrip": {"top": ytop, "bottom": first_top, "textX": [tx0, tx1],
                            "slots": [{"cx": x, "cy": cy} for x in slots], "dividers": divs},
        "background": list(bg),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--letters", required=True)
    ap.add_argument("--strip", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--ime", required=True)
    ap.add_argument("--density", type=int, required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    g = measure(a.letters, a.strip, a.density)
    g["name"], g["ime"] = a.name, a.ime
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    json.dump(g, open(a.out, "w"), indent=1)
    k = {x["name"]: x for x in g["keys"]}
    print("%s: keyboard top %d, strip %d-%d text %s dividers %s, keyWidth %.1f, q %s, space %s" % (
        a.name, g["keyboard"]["y"], g["suggestionStrip"]["top"], g["suggestionStrip"]["bottom"],
        g["suggestionStrip"]["textX"], g["suggestionStrip"]["dividers"], g["keyWidth"], k["q"]["visual"], k["space"]["visual"]))


if __name__ == "__main__":
    main()
