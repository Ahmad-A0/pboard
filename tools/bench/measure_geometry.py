#!/usr/bin/env python3
"""Measure an IME's key geometry from a screenshot (no OCR; layout-aware).

Usage: measure_geometry.py <screenshot.png> <out.json> [--name NAME] [--ime IME_ID]
       [--strip-slots 3] [--layout qwerty-en]

Expects the keyboard in its lowercase QWERTY letter layout above the navigation bar, on a uniform
background. Keys are detected as runs of pixels that differ from the keyboard background, row bands
of height >= 80 px are key rows, and labels are assigned from the known row layout:
  row 1: q..p (10), row 2: a..l (9), row 3: shift z..m delete (9), row 4: ?123 , emoji space . enter (6)
The output follows tools/bench/README.md ("Geometry format"): all coordinates are screen pixels;
`keyboard` gives the keyboard window origin so keyboard-local = screen - origin.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngio  # noqa: E402

ROWS = [
    list("qwertyuiop"),
    list("asdfghjkl"),
    ["shift"] + list("zxcvbnm") + ["delete"],
    ["symbols", ",", "emoji", "space", ".", "enter"],
]
# Codes the replay/generator understand for non-letter keys.
LABEL = {"space": " ", "delete": "\b", "enter": "\n"}


def dist(a, b):
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]), abs(a[2] - b[2]))


def runs(mask, min_len):
    out, start = [], None
    for i, m in enumerate(mask + [False]):
        if m and start is None:
            start = i
        elif not m and start is not None:
            if i - start >= min_len:
                out.append((start, i))
            start = None
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("png")
    ap.add_argument("out")
    ap.add_argument("--name", default="gboard_pixel8")
    ap.add_argument("--ime", default="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
    ap.add_argument("--density", type=int, default=420)
    ap.add_argument("--search-from", type=int, default=1000, help="screen y to start looking for the keyboard")
    ap.add_argument("--strip-png", help="second screenshot with next-word suggestions showing (slot positions)")
    args = ap.parse_args()

    im = pngio.read_png(args.png)
    W, H = im.width, im.height
    # Keyboard background: the most common colour of the left-most column in the lower screen part.
    col = [im.px(2, y) for y in range(args.search_from, H)]
    bg = max(set(col), key=col.count)
    # Keyboard top: the last row above the keys where the colour is not bg along x=2 (the shadow
    # line drawn at the top edge of the keyboard window), scanning down from search_from.
    top = None
    for y in range(args.search_from, H):
        if dist(im.px(2, y), bg) > 2 and dist(im.px(W // 2, y), bg) > 2:
            top = y
        elif top is not None:
            break
    kb_top = (top + 1) if top is not None else args.search_from

    # Row bands: rows where many pixels differ from bg.
    def keyish(y):
        return [dist(im.px(x, y), bg) > 6 for x in range(W)]

    row_counts = [sum(keyish(y)) if y >= kb_top else 0 for y in range(H)]
    bands = runs([c > 250 for c in row_counts], 20)
    key_bands = [b for b in bands if b[1] - b[0] >= 80][:4]
    strip_bands = [b for b in bands if b[0] >= kb_top and b[1] <= (key_bands[0][0] if key_bands else H) and b[1] - b[0] < 80]
    if len(key_bands) != 4:
        sys.exit(f"expected 4 key rows, found bands {bands}")

    keys, rows_out = [], []
    for r, (y0, y1) in enumerate(key_bands):
        # Sample at 30% height (above most glyphs, below rounded corners).
        ys = y0 + int((y1 - y0) * 0.3)
        xr = []
        for r0, r1 in runs(keyish(ys), 1):  # merge glyph anti-aliasing gaps (< 6 px) inside a key
            if xr and r0 - xr[-1][1] < 6:
                xr[-1] = (xr[-1][0], r1)
            else:
                xr.append((r0, r1))
        xr = [r for r in xr if r[1] - r[0] >= 25]
        if len(xr) != len(ROWS[r]):
            sys.exit(f"row {r}: expected {len(ROWS[r])} keys, found {len(xr)}: {xr}")
        rows_out.append({"top": y0, "bottom": y1})
        for name, (x0, x1) in zip(ROWS[r], xr):
            keys.append({"name": name, "label": LABEL.get(name, name if len(name) == 1 else ""), "row": r,
                         "visual": [x0, y0, x1, y1], "cx": (x0 + x1) / 2, "cy": (y0 + y1) / 2})

    # Hit boxes: extend each key halfway into the gaps around it (rows touch at the gap midpoints).
    for r in range(4):
        rk = [k for k in keys if k["row"] == r]
        top_b = (key_bands[r - 1][1] + key_bands[r][0]) / 2 if r > 0 else key_bands[0][0] - (key_bands[1][0] - key_bands[0][1]) / 2
        bot_b = (key_bands[r][1] + key_bands[r + 1][0]) / 2 if r < 3 else key_bands[3][1] + (key_bands[1][0] - key_bands[0][1]) / 2
        for i, k in enumerate(rk):
            x0, _, x1, _ = k["visual"]
            left = (rk[i - 1]["visual"][2] + x0) / 2 if i > 0 else 0
            right = (x1 + rk[i + 1]["visual"][0]) / 2 if i + 1 < len(rk) else W
            k["hit"] = [left, top_b, right, bot_b]
            k["width"] = right - left
            k["height"] = bot_b - top_b

    letters = [k for k in keys if len(k["label"]) == 1 and k["label"].isalpha()]
    pitch = sorted(k["width"] for k in letters)[len(letters) // 2]
    strip = None
    if strip_bands or kb_top < key_bands[0][0]:
        s0, s1 = kb_top, key_bands[0][0] - (key_bands[1][0] - key_bands[0][1]) / 2
        cy = (s0 + s1) / 2
        strip = {"top": s0, "bottom": s1,
                 "slots": [{"cx": W * (i + 0.5) / 3, "cy": cy} for i in (1, 0, 2)]}
        if args.strip_png:
            # Screenshot with suggestions showing: find the thin vertical slot dividers and the
            # right-hand (mic) button; slots lie between a mirrored left margin, dividers, and it.
            sp = pngio.read_png(args.strip_png)
            yc = int(cy)
            segs = runs([dist(sp.px(x, yc), bg) > 8 for x in range(W)], 1)
            divs = []
            for x0, x1 in segs:
                if x1 - x0 <= 5:
                    xm = (x0 + x1) // 2
                    col = [dist(sp.px(xm, y), bg) > 8 for y in range(int(s0), int(s1))]
                    if sum(col) >= 40 and all(dist(sp.px(xm - 6, y), bg) <= 8 for y in range(yc - 15, yc + 15)):
                        divs.append(((x0 + x1 - 1) / 2, sp.px(xm, yc)))
            right = next((x0 for x0, x1 in segs if x1 - x0 > 60 and x0 > W / 2), W)
            left = W - right
            if len(divs) == 2:
                (d1, c1), (d2, _) = divs
                edges = [left, d1, d2, right]
                centres = [(edges[i] + edges[i + 1]) / 2 for i in range(3)]
                strip["slots"] = [{"cx": centres[i], "cy": cy} for i in (1, 0, 2)]  # middle = best
                ys = [int(cy) - 15, int(cy), int(cy) + 15]
                strip["ready"] = {"points": [[round(d), y] for d in (d1, d2) for y in ys], "rgb": list(c1), "tol": 14,
                                  "note": "suggestion dividers; absent when the strip shows the toolbar"}
            else:
                print("warning: expected 2 strip dividers, found %s; keeping thirds" % divs)

    geo = {
        "format": "pboard-bench-geometry/1",
        "name": args.name,
        "ime": args.ime,
        "source": f"measured from screenshot {os.path.basename(args.png)} by tools/bench/measure_geometry.py",
        "coordinates": "screen",
        "screen": {"width": W, "height": H, "density": args.density},
        "keyboard": {"x": 0, "y": kb_top, "width": W, "height": H - kb_top},
        "keyWidth": pitch,
        "rows": rows_out,
        "suggestionStrip": strip,
        "keys": keys,
    }
    if os.path.exists(args.out):  # keep hand-added fields such as the IME state check
        with open(args.out) as f:
            old = json.load(f)
        if "stateCheck" in old:
            geo["stateCheck"] = old["stateCheck"]
    with open(args.out, "w") as f:
        json.dump(geo, f, indent=1)
    print(f"keyboard top {kb_top}, bg {bg}, key pitch {pitch:.1f}px, {len(keys)} keys, rows {key_bands}")


if __name__ == "__main__":
    main()
