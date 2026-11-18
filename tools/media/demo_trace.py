#!/usr/bin/env python3
"""Touch trace for the README demo (tools/media/demo_gif.sh), built from recorded human typing.

Usage: demo_trace.py <geometry.json> <out.jsonl> --sentence tsi/user01/task4/6 --keys " " \
           --glide <id>,<id>,... --emoji X,Y --abc X,Y

The touches come from the human evaluation sets in tools/data/human/splits/v1 (see ATTRIBUTION.txt there for the
sources and their licences):
  --sentence  id prefix of one tapped sentence (tap_tsi / tap_vtap): every word's touch-down positions and times as
              the person typed them, typos included, one trace item per word (ending with their space tap; the
              time between two words is not in the sets);
  --glide     ids of glide-typed words (swipe_futo / swipe_hws), one stroke each with its recorded sample times.
Both are re-projected from the geometry the sets are stored in onto <geometry.json>, the keyboard being recorded
(measured with tools/bench/measure_geometry.py), by the same affine fit as tools/bench/prepare_set.py.

Not recorded, so made up here: `--keys` (taps on named keys after the sentence, e.g. a second space for a full stop) and
the last item (space, the emoji key, an emoji at screen position --emoji, the ABC key at --abc).

Output: the JSONL format of the bench injector (bench/src/main/java/dev/pboard/bench/Replay.kt). Every item carries
the text expected in the field before it, so the injector keeps typing; the last line printed is the expected final
text without the emoji.
"""
import argparse
import glob
import gzip
import json
import os
import random
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
    ap.add_argument("--sentence", required=True, help="id prefix of a tapped sentence, e.g. tsi/user01/task4/6")
    ap.add_argument("--keys", default="", help="keys tapped right after the sentence (made up): letters, space, . and ,")
    ap.add_argument("--glide", default="", help="comma-separated ids of glide-typed words")
    ap.add_argument("--emoji", help="X,Y of an emoji on the open emoji panel")
    ap.add_argument("--abc", help="X,Y of the emoji panel's ABC key")
    ap.add_argument("--seed", type=int, default=3)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    geo = prepare_set.load(a.geometry)
    to_screen, _ = prepare_set.affine(prepare_set.anchors(prepare_set.load(SOURCE_GEOMETRY)), prepare_set.anchors(geo))
    key = {k["name"]: k for k in geo["keys"]}
    key[" "] = key["space"]

    def project(points):
        return [[round(c, 1) for c in to_screen(p[0], p[1])] + [p[2]] for p in points]

    def near(name):
        """A made-up touch on a key: scattered around its centre like a thumb's."""
        k = key[name]
        w = k["visual"][2] - k["visual"][0]
        return [round(k["cx"] + rng.gauss(0, min(0.12 * w, 14)), 1), round(k["cy"] + rng.gauss(0, 12), 1)]

    items, text = [], ""

    def add(kind, target, points, after, **extra):
        nonlocal text
        items.append(dict(context=text, target=target, kind=kind, points=points, **extra))
        text = after

    words = sorted((it for it in human_items("tap") if it["id"].rsplit("/w", 1)[0] == a.sentence),
                   key=lambda it: int(it["id"].rsplit("/w", 1)[1]))
    if not words:
        sys.exit("no tapped sentence " + a.sentence)
    for n, it in enumerate(words):
        pts, extra = project(it["points"]), {}
        holds = list(it["holdMs"]) if "holdMs" in it else None
        after = text + it["target"] + " "
        if n == len(words) - 1 and a.keys:
            t = pts[-1][2]
            for ch in a.keys:
                t += round(rng.uniform(150, 200))
                pts.append(near(ch) + [t])
                if holds:
                    holds.append(60)
            # A second space after a word makes a full stop.
            after = after.rstrip() + ". " if a.keys == " " else after + a.keys
        if holds:
            extra["holdMs"] = holds
        add("tap", it["target"], pts, after, id=it["id"], **extra)

    glides = {it["id"]: it for it in human_items("swipe")}
    sentence_start = text.rstrip().endswith((".", "!", "?"))
    for gid in [g for g in a.glide.split(",") if g]:
        it = glides.get(gid) or sys.exit("no glide " + gid)
        word = it["target"].capitalize() if sentence_start else it["target"]
        sentence_start = False
        sep = "" if text.endswith(" ") or not text else " "
        add("swipe", it["target"], project(it["points"]), text + sep + word, id=it["id"])
    typed = text

    if a.emoji and a.abc:
        ex, ey = (float(v) for v in a.emoji.split(","))
        ax, ay = (float(v) for v in a.abc.split(","))
        pts = [near(" ") + [0], near("emoji") + [330], [ex, ey, 1250], [ax, ay, 2050]]
        add("tap", "emoji", pts, text + " ", holdMs=[60, 70, 70, 70])

    with open(a.out, "w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it, separators=(",", ":")) + "\n")
    print(typed)


if __name__ == "__main__":
    main()
