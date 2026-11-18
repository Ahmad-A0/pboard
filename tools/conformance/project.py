#!/usr/bin/env python3
"""Projects scenarios written for the reference geometry (gboard_pixel8) onto another device/IME geometry.

  project.py <scenarios.jsonl> <target-geometry.json> <out.jsonl> [--phone-safe]

The same intended input lands in the same place *relative to the keys* on every keyboard: a tap 17 % of a
key width inside `r` stays 17 % inside `r`, so layouts with different key sizes (Gboard 18.6 vs pboard on
the phone) get equivalent input.
  letters-page points  x: piecewise inside the key's hit rectangle (same key name); y: piecewise by row bands
  symbols-page taps    x scaled by screen width; y by row bands (the symbols grid shares the row bands)
  suggestion picks     the target's slot centre (centre / left / right)
  drags                start point projected; the movement keeps its physical length (scaled by density)
  above / below keys   offsets from the strip top / the last row's bottom are kept
--phone-safe drops scenarios that touch UI outside the text field and keyboard keys (emoji panel buttons,
long-press menus that can open settings, the system IME picker); see PHONE_UNSAFE.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import GEOMETRY, load_json, read_jsonl, write_jsonl  # noqa: E402

PHONE_UNSAFE = ("calib/letters#emoji", "qol/TP-11/", "qol/TF-11/", "qol/LT-08/", "qol/CO-08/")


class Geo:
    def __init__(self, g):
        self.g = g
        self.keys = {k["name"]: k for k in g["keys"]}
        rows = {}
        for k in g["keys"]:
            r = k.get("row")
            if r is None:
                continue
            t, b = k["hit"][1], k["hit"][3]
            lo, hi = rows.get(r, (t, b))
            rows[r] = (min(lo, t), max(hi, b))
        self.rows = [rows[r] for r in sorted(rows)]
        st = g["suggestionStrip"]
        self.strip = (st["top"], st["bottom"])
        self.width = g["screen"]["width"]
        self.density = g["screen"].get("density", 420)
        self.slots = st["slots"]

    def breaks(self):
        return [self.strip[0]] + [r[0] for r in self.rows] + [self.rows[-1][1]]

    def row_of(self, y):
        for i, (t, b) in enumerate(self.rows):
            if t <= y < b:
                return i
        return None

    def key_at(self, x, row):
        for k in self.g["keys"]:
            if k.get("row") == row and k["hit"][0] <= x < k["hit"][2]:
                return k
        return None


def with_rows(g):
    """Bench geometries lack "row": derive it from the key's vertical centre."""
    if all("row" in k for k in g["keys"]):
        return g
    ys = sorted({round(k["cy"]) for k in g["keys"]})
    for k in g["keys"]:
        k["row"] = min(range(len(ys)), key=lambda i: abs(ys[i] - k["cy"]))
    return g


def map_y(y, s, d):
    bs, bd = s.breaks(), d.breaks()
    if y < bs[0]:
        return bd[0] - (bs[0] - y) * d.density / s.density
    if y >= bs[-1]:
        return bd[-1] + (y - bs[-1]) * d.density / s.density
    for i in range(len(bs) - 1):
        if bs[i] <= y < bs[i + 1]:
            f = (y - bs[i]) / (bs[i + 1] - bs[i])
            return bd[i] + f * (bd[i + 1] - bd[i])
    return y


def map_xy(x, y, s, d, page="letters"):
    y2 = map_y(y, s, d)
    if page != "letters":
        return x * d.width / s.width, y2
    r = s.row_of(y)
    if r is None:
        return x * d.width / s.width, y2
    ks = s.key_at(x, r)
    kd = d.keys.get(ks["name"]) if ks else None
    if not ks or not kd:
        return x * d.width / s.width, y2
    l, rr = ks["hit"][0], ks["hit"][2]
    u = (x - l) / (rr - l)
    return kd["hit"][0] + u * (kd["hit"][2] - kd["hit"][0]), y2


def project_step(st, s, d):
    m = st.get("m") or {}
    if st["op"] != "gesture":
        return st
    k = m.get("k")
    page = "symbols" if m.get("page") == "symbols" else "letters"
    out = dict(st)
    if k == "slot":
        slot = {"centre": d.slots[0], "left": d.slots[1], "right": d.slots[2]}[m["slot"]]
        out["strokes"] = [[[round(slot["cx"], 1), round(slot["cy"], 1), p[2]] for p in stroke] for stroke in st["strokes"]]
        out["requireSuggestions"] = True
        return out
    if k in ("drag",):
        stroke = st["strokes"][0]
        x0, y0 = stroke[0][0], stroke[0][1]
        nx0, ny0 = map_xy(x0, y0, s, d, page)
        sc = d.density / s.density
        out["strokes"] = [[[round(nx0 + (p[0] - x0) * sc, 1), round(ny0 + (p[1] - y0) * sc, 1), p[2]] for p in stroke]]
        return out
    out["strokes"] = []
    for stroke in st["strokes"]:
        ns = []
        for p in stroke:
            nx, ny = map_xy(p[0], p[1], s, d, page)
            ns.append([round(nx, 1), round(ny, 1), p[2]])
        out["strokes"].append(ns)
    return out


def project(rows, target, phone_safe=False, source=GEOMETRY):
    s = Geo(with_rows(load_json(source)))
    d = Geo(with_rows(load_json(target)))
    out = []
    for sc in rows:
        if phone_safe and any(sc["id"].startswith(p.split("#")[0]) and "#" not in p for p in PHONE_UNSAFE):
            continue
        sc = dict(sc)
        steps = [project_step(st, s, d) for st in sc["steps"]]
        if phone_safe and sc["id"] == "calib/letters":
            # the emoji panel's own buttons differ between versions: stop at opening it
            cut = next((i for i, st in enumerate(steps) if (st.get("m") or {}).get("label") == "emoji"), None)
            if cut is not None:
                steps = steps[:cut + 1]
        sc["steps"] = steps
        if phone_safe:
            # Return / Done in a single-line field hides the keyboard; harmless, but keep the rest useful
            pass
        out.append(sc)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenarios")
    ap.add_argument("target")
    ap.add_argument("out")
    ap.add_argument("--phone-safe", action="store_true")
    a = ap.parse_args()
    rows = project(read_jsonl(a.scenarios), a.target, a.phone_safe)
    write_jsonl(a.out, rows)
    print("%d scenarios -> %s" % (len(rows), a.out))


if __name__ == "__main__":
    main()
