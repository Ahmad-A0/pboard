#!/usr/bin/env python3
"""Touch trace for the README demo (tools/media/demo_gif.sh), built from recorded human typing.

Usage: demo_trace.py <geometry.json> <out.jsonl> <step> [<step> ...]

Steps, performed in order on one clock:
  sentence=<id prefix>   a tapped sentence from tap_tsi / tap_vtap, e.g. tsi/user01/task4/6: every word's touch-down
                         positions and times as the person typed them, typos included
  glide=<id>             a glide-typed word from swipe_futo / swipe_hws: the recorded stroke with its sample times
  keys=<keys>            made-up taps on named keys (letters, . and ,; _ is the space bar)
  emoji=X,Y,AX,AY        made-up taps: the emoji key, an emoji at screen position X,Y, the panel's ABC key at AX,AY

The recorded touches come from the human evaluation sets in tools/data/human/splits/v1 (sources and licences in its
ATTRIBUTION.txt). They are re-projected from the geometry the sets are stored in onto <geometry.json>, the keyboard
being recorded (measured with tools/bench/measure_geometry.py), by the same affine fit as tools/bench/prepare_set.py.

What the sets do not have is made up here, in the range people show: how long a tap is held (the tap sets record
touch-down times only), the pause between two tapped words (each word's times start at zero), and the pauses between
glides, which come from different people (the recorded pauses of the one set that has them, about a second, are
those of a test app prompting word by word).

`--slow N` writes the same performance N times slower, for a recording that is sped up again afterwards.

Output: one item of kind "strokes" for the bench injector (bench/src/main/java/dev/pboard/bench/Replay.kt), and
<out>.timing.json: for every step its label and the trace times (ms) of its first touch-down and last touch-up, and
of every touch-up.
"""
import argparse
import glob
import gzip
import json
import os
import random
import statistics
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools", "bench"))
import prepare_set  # noqa: E402

SPLITS = os.path.join(ROOT, "tools", "data", "human", "splits", "v1")
SOURCE_GEOMETRY = os.path.join(ROOT, "tools", "data", "human", "geometry", "gboard_pixel8.json")


def human_items(kind):
    for path in sorted(glob.glob(os.path.join(SPLITS, kind + "_*.jsonl.gz"))):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            for line in f:
                yield json.loads(line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("geometry")
    ap.add_argument("out")
    ap.add_argument("steps", nargs="+")
    ap.add_argument("--glide-gap", default="170:340", help="pause between two glides, ms (uniform)")
    ap.add_argument("--slow", type=float, default=1.0, help="stretch every time in the trace by this factor")
    ap.add_argument("--seed", type=int, default=3)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    gap_lo, gap_hi = (float(v) for v in a.glide_gap.split(":"))
    geo = prepare_set.load(a.geometry)
    to_screen, _ = prepare_set.affine(prepare_set.anchors(prepare_set.load(SOURCE_GEOMETRY)), prepare_set.anchors(geo))
    key = {k["name"]: k for k in geo["keys"]}
    key["_"] = key["space"]
    taps = glides = None

    def near(name):
        """A made-up touch on a key: scattered around its centre like a thumb's."""
        k = key[name]
        w = k["visual"][2] - k["visual"][0]
        return [round(k["cx"] + rng.gauss(0, min(0.12 * w, 14)), 1), round(k["cy"] + rng.gauss(0, 12), 1)]

    strokes, steps = [], []
    clock = 0.0       # trace time of the last touch-up so far
    last = None       # kind of the previous step

    def tap(x, y, t, next_t=None):
        hold = min(max(rng.gauss(62, 14), 35), 95)
        if next_t is not None:
            hold = max(min(hold, next_t - t - 10), 20)
        strokes.append([[x, y, round(t)], [x, y, round(t + hold)]])
        return t + hold

    def step(label, kind, first, ups):
        nonlocal clock, last
        steps.append({"label": label, "kind": kind, "down": round(first), "up": round(ups[-1]), "ups": [round(u) for u in ups]})
        clock, last = ups[-1], kind

    for spec in a.steps:
        what, _, arg = spec.partition("=")
        if what == "sentence":
            taps = taps or list(human_items("tap"))
            words = sorted((it for it in taps if it["id"].rsplit("/w", 1)[0] == arg),
                           key=lambda it: int(it["id"].rsplit("/w", 1)[1]))
            if not words:
                sys.exit("no tapped sentence " + arg)
            # The pause before a word (not recorded): about the person's own pace between keys.
            pace = statistics.median(b[2] - a_[2] for it in words for a_, b in zip(it["points"], it["points"][1:]))
            for it in words:
                t0 = clock + (0 if last is None else pace * rng.uniform(0.9, 1.3))
                pts = [[round(c, 1) for c in to_screen(p[0], p[1])] + [t0 + p[2]] for p in it["points"]]
                ups = [tap(p[0], p[1], p[2], q[2] if q else None) for p, q in zip(pts, pts[1:] + [None])]
                step(it["target"], "tap", t0, ups)
        elif what == "glide":
            glides = glides or {it["id"]: it for it in human_items("swipe")}
            it = glides.get(arg) or sys.exit("no glide " + arg)
            t0 = clock + (rng.uniform(gap_lo, gap_hi) if last == "glide" else rng.uniform(260, 340))
            pts = [[round(c, 1) for c in to_screen(p[0], p[1])] + [round(t0 + p[2])] for p in it["points"]]
            strokes.append(pts)
            step(it["target"], "glide", t0, [pts[-1][2]])
        elif what == "keys":
            down = clock + (rng.uniform(90, 130) if last == "tap" else rng.uniform(230, 300))
            first, ups = down, []
            for ch in arg:
                ups.append(tap(*near(ch), down))
                down += rng.uniform(150, 190)
            step(arg, "tap", first, ups)
        elif what == "emoji":
            ex, ey, ax, ay = (float(v) for v in arg.split(","))
            t = clock + rng.uniform(240, 300)
            ups = [tap(*near("emoji"), t), tap(ex, ey, t + 620), tap(ax, ay, t + 1080)]
            step("emoji", "tap", t, ups)
        else:
            sys.exit("unknown step " + spec)

    # Slow motion (the timing file stays in true time). A tap is not held longer than 170 ms: no long-press.
    for st in strokes:
        if len(st) == 2:
            st[1][2] = round(st[0][2] * a.slow + min((st[1][2] - st[0][2]) * a.slow, 170))
            st[0][2] = round(st[0][2] * a.slow)
        else:
            for p in st:
                p[2] = round(p[2] * a.slow)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(json.dumps({"context": "", "target": "demo", "kind": "strokes", "strokes": strokes},
                           separators=(",", ":")) + "\n")
    with open(a.out + ".timing.json", "w", encoding="utf-8") as f:
        json.dump(steps, f, indent=1)


if __name__ == "__main__":
    main()
