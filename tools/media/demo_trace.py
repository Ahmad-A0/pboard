#!/usr/bin/env python3
"""Touch trace for the README demo (tools/media/demo_gif.sh): one tapped sentence, then glide-typed words.

Usage: demo_trace.py <geometry.json> <out.jsonl> --tap "lets grab cofee " --expect "Let's grab coffee " --glide see,you

The geometry comes from tools/bench/measure_geometry.py on a screenshot of the device being recorded; the output is
the JSONL format of the bench injector (bench/src/main/java/dev/pboard/bench/Replay.kt): item 0 taps the sentence
(keys as typed, typos included; `<`, `^`, `>` tap the left, middle or right suggestion), the following items are one stroke per glide word, each with the text
expected in the field before it (`--expect` plus the glide words so far) so that the injector keeps typing.
"""
import argparse
import json
import math
import random

SEG = 24  # polyline points per key-to-key leg


def catmull_rom(pts):
    """Uniform Catmull-Rom spline through pts (complex numbers), SEG points per leg."""
    p = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(p) - 2):
        a, b, c, d = p[i - 1], p[i], p[i + 1], p[i + 2]
        for k in range(SEG):
            t = k / SEG
            out.append(0.5 * ((2 * b) + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t * t
                              + (3 * b - a - 3 * c + d) * t ** 3))
    out.append(pts[-1])
    return out


def glide(word, centre, kw, rng, speed):
    """One stroke through the word's keys: [[x, y, tMs], ...] at ~120 Hz, slowing at each key."""
    keys = [c for i, c in enumerate(word) if i == 0 or c != word[i - 1]]
    via = [complex(*centre[c]) + complex(rng.gauss(0, 0.06 * kw), rng.gauss(0, 0.06 * kw)) for c in keys]
    poly = catmull_rom(via)
    cum = [0.0]
    for a, b in zip(poly, poly[1:]):
        cum.append(cum[-1] + abs(b - a))
    # Time along the path: each key-to-key leg is a minimum-jerk move, and consecutive legs overlap a little.
    legs = [cum[SEG * (i + 1)] - cum[SEG * i] for i in range(len(via) - 1)]
    durs = [70 + leg / (speed * kw / 100.0) for leg in legs]
    starts, t = [], 60.0
    for d in durs:
        starts.append(t)
        t += d * 0.8
    total = starts[-1] + durs[-1] + 50

    def s_at(tt):
        s = 0.0
        for st, d, leg in zip(starts, durs, legs):
            u = min(max((tt - st) / d, 0.0), 1.0)
            s += leg * (10 * u ** 3 - 15 * u ** 4 + 6 * u ** 5)
        return s

    def at(s):
        i = max(1, next((k for k, c in enumerate(cum) if c >= s), len(cum) - 1))
        span = cum[i] - cum[i - 1]
        f = 0 if span <= 0 else (s - cum[i - 1]) / span
        return poly[i - 1] + (poly[i] - poly[i - 1]) * f

    pts, tt = [], 0.0
    while tt < total:
        z = at(s_at(tt))
        pts.append([round(z.real, 1), round(z.imag, 1), int(round(tt))])
        tt += 1000.0 / 120
    pts.append([round(poly[-1].real, 1), round(poly[-1].imag, 1), int(round(total)) + 1])
    return [p for i, p in enumerate(pts) if i == 0 or p[2] > pts[i - 1][2]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("geometry")
    ap.add_argument("out")
    ap.add_argument("--tap", required=True, help="what is tapped: letters, spaces, . and , keys; < ^ > = the left, middle, right suggestion")
    ap.add_argument("--expect", required=True, help="the field's text after the taps")
    ap.add_argument("--glide", default="", help="comma-separated words, one stroke each")
    ap.add_argument("--interval", type=float, default=165.0, help="mean ms between taps")
    ap.add_argument("--speed", type=float, default=0.62, help="glide speed, key widths per 100 ms")
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    with open(a.geometry, encoding="utf-8") as f:
        geo = json.load(f)
    kw = float(geo["keyWidth"])
    centre = {k["name"]: (k["cx"], k["cy"]) for k in geo["keys"]}
    centre[" "] = centre["space"]
    strip = geo["keyboard"]["y"] + (geo["rows"][0]["top"] - geo["keyboard"]["y"]) / 2.0
    for i, ch in enumerate("<^>"):  # the left, middle and right suggestion
        centre[ch] = (geo["screen"]["width"] * (0.21 + 0.29 * i), strip)

    pts, holds, t, prev = [], [], 0.0, ""
    for ch in a.tap:
        if pts:
            gap = a.interval * math.exp(rng.gauss(0, 0.22))
            if ch == " " or prev == " ":
                gap *= 1.25  # a breath around word boundaries
            if ch in "<^>":
                gap += 420  # time to read the strip
            t += gap
        x, y = centre[ch]
        pts.append([round(x + rng.gauss(0, 0.08 * kw), 1), round(y + rng.gauss(0, 0.08 * kw), 1), int(round(t))])
        holds.append(round(min(max(rng.gauss(70, 12), 45), 100)))
        prev = ch
    items = [{"context": "", "target": a.expect.strip(), "kind": "tap", "points": pts, "holdMs": holds}]
    text = a.expect
    for w in [w for w in a.glide.split(",") if w]:
        items.append({"context": text, "target": w, "kind": "swipe", "points": glide(w, centre, kw, rng, a.speed)})
        text += w + " "
    with open(a.out, "w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it) + "\n")
    print(text)


if __name__ == "__main__":
    main()
