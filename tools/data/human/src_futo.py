"""swipe.futo.org (FUTO, MIT) -> swipe traces.

Donors swiped prompted words (sentences from Common Voice's Wikipedia text) on a web QWERTY canvas of three letter
rows (no space row) on their own phones. Coordinates are canvas-normalised [0,1]; the canvas size in CSS px is
recorded per swipe, so key pitch = canvas_width / 10 and row pitch = canvas_height / 3. The publisher's splits are
session-disjoint: their dev split is our tune split and their test split is our held-out test split.
Landscape swipes are dropped (different key aspect ratio), as are words with fewer than two letters.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
from common import LETTERS, Layout, RAW, project_stroke, word_core

SRC = "futo"
DIR = os.path.join(RAW, "futo_swipe")
FILES = {"tune": "dev.jsonl", "test": "test.jsonl"}


def norm_layout():
    with open(os.path.join(DIR, "swipe-5", "layouts", "qwerty.json"), encoding="utf-8") as f:
        return {k["letter"]: (k["cx"], k["cy"]) for k in json.load(f)["keys"]}


def convert(dst, split):
    centres_n = norm_layout()
    stats = {"records": 0, "words": 0, "skipped_orientation": 0, "skipped_word": 0, "skipped_short": 0,
             "skipped_dual": 0, "invalid_sentence_flag": 0}
    cache = {}
    with open(os.path.join(DIR, FILES[split]), encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            stats["records"] += 1
            if not str(d.get("orientation", "")).startswith("portrait"):
                stats["skipped_orientation"] += 1
                continue
            if not isinstance(d["data"], list):
                stats["skipped_dual"] += 1
                continue
            target = word_core(d["word"])
            letters = [c for c in target.lower() if c in LETTERS]
            if len(letters) < 2 or len(letters) != sum(c.isalpha() for c in target):
                stats["skipped_word"] += 1
                continue
            pts = d["data"]
            if len(pts) < 3 or pts[-1]["t"] - pts[0]["t"] < 30:
                stats["skipped_short"] += 1
                continue
            W, H = float(d["canvas_width"]), float(d["canvas_height"])
            key = (W, H)
            if key not in cache:
                cache[key] = Layout("futo_%gx%g" % key, {c: (x * W, y * H) for c, (x, y) in centres_n.items()},
                                    bounds=(0, 0, W, H))
            src = cache[key]
            out, n_cl = project_stroke(src, dst, [(p["x"] * W, p["y"] * H, p["t"]) for p in pts])
            if len(out) < 3:
                stats["skipped_short"] += 1
                continue
            toks = d["sentence"].split()
            wi = d["word_idx"]
            stats["words"] += 1
            stats["invalid_sentence_flag"] += bool(d.get("potentially_invalid_sentence"))
            yield {
                "context": " ".join(toks[:wi]) + (" " if wi else ""), "target": target, "kind": "swipe",
                "points": out, "id": "%s/%s/%s" % (SRC, split, d["id"]), "src": SRC, "participant": d["session"],
                "split": split, "srcCanvasPx": [W, H], "srcKeyAspect": round((H / 3) / (W / 10), 3),
                "clampedPts": n_cl, "futoDistance": round(d["distance"], 2),
                "potentiallyInvalidSentence": bool(d.get("potentially_invalid_sentence")),
            }
    convert.stats = stats


if __name__ == "__main__":
    from common import load_target
    n = 0
    for it in convert(load_target(), "test"):
        n += 1
        if n <= 2:
            print(json.dumps(it)[:300])
    print(n, convert.stats)
