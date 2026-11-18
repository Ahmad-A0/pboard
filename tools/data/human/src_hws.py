"""How We Swipe (Leiva et al., MobileHCI 2021; OSF sj67f, MIT) -> swipe traces.

1,338 volunteers (recruited via TypingMaster / social media) swiped 16 sentences each in a mobile web app: one
third EnronMobile memorable sentences, the rest 4-word sequences of random words (frequent, common, infrequent,
out-of-vocabulary). The canvas keyboard (swipetest js/keyboard-impl.js) has 10 key pitches across and rows of
heights h, h, h, 0.7h (letters + a dummy bottom row), so pitch_x = keyb_width / 10, pitch_y = keyb_height / 3.7.
A swipe counts as an error (is_err=1) unless it starts/ends within 1.25 key sizes of the first/last letter and
passes near every letter; erroneous attempts had to be redone. Only accepted swipes (is_err=0) are emitted, so the
set is biased towards swipes a lenient key-sequence check accepts (failed attempts are counted in the stats).
Some log lines carry a duplicated is_err column (the app flagged queued events twice); the first one is used.
"""
import csv
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
from common import GRID, LETTERS, Layout, RAW, project_stroke, split_of

SRC = "hws"
DIR = os.path.join(RAW, "howweswipe")


def logs():
    with zipfile.ZipFile(os.path.join(DIR, "swipelogs.zip")) as z:
        for name in sorted(z.namelist()):
            if name.endswith(".log"):
                uid = os.path.basename(name)[:-4]
                with z.open(name) as f:
                    yield uid, f.read().decode("utf-8", errors="replace").splitlines()


def phrase_sets():
    out = {}
    with open(os.path.join(DIR, "stats-words.tsv"), encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            out[(r["username"], r["sentence"])] = r["dataset"]
    return out


def gestures(lines):
    """Yield gestures: dict(sentence, word, err, W, H, pts=[(x, y, t)])."""
    hdr = lines[0].split()
    ix = {k: i for i, k in enumerate(hdr)}
    cur = None
    for line in lines[1:]:
        p = line.split(" ")
        if len(p) < len(hdr):
            continue
        ev = p[ix["event"]]
        if ev == "touchstart":
            if cur and cur["pts"]:
                yield cur
            cur = {"sentence": p[ix["sentence"]], "word": p[ix["word"]], "err": p[ix["is_err"]],
                   "W": float(p[ix["keyb_width"]]), "H": float(p[ix["keyb_height"]]), "pts": []}
        if cur is None:
            continue
        cur["pts"].append((float(p[ix["x_pos"]]), float(p[ix["y_pos"]]), int(p[ix["timestamp"]])))
        if ev in ("touchend", "touchcancel"):
            yield cur
            cur = None
    if cur and cur["pts"]:
        yield cur


def convert(dst):
    sets = phrase_sets()
    stats = {"users": 0, "gestures": 0, "accepted": 0, "failed_attempts": 0, "words": 0, "skipped_word": 0,
             "skipped_short": 0, "skipped_context": 0, "duplicate_gestures": 0}
    cache = {}
    for uid, lines in logs():
        if not lines:
            continue
        stats["users"] += 1
        split = split_of(SRC, uid)
        pos, last_sentence, retried, seen = 0, None, False, set()
        for g in gestures(lines):
            if (g["pts"][0][2], g["word"]) in seen:  # the app sometimes uploaded an event batch twice
                stats["duplicate_gestures"] += 1
                continue
            seen.add((g["pts"][0][2], g["word"]))
            stats["gestures"] += 1
            if g["sentence"] != last_sentence:
                pos, last_sentence, retried = 0, g["sentence"], False
            words = g["sentence"].split("_")
            if g["err"] != "0":
                stats["failed_attempts"] += 1
                retried = True
                continue
            stats["accepted"] += 1
            retry, retried = retried, False
            # Some words' events are missing from the logs, so locate the word at or after the expected position.
            wi = next((j for j in range(pos, len(words)) if words[j] == g["word"]), None)
            if wi is None:
                stats["skipped_context"] += 1
                continue
            pos = wi + 1
            target = g["word"]
            if len(target) < 2 or any(c not in LETTERS for c in target):
                stats["skipped_word"] += 1
                continue
            W, H = g["W"], g["H"]
            if (W, H) not in cache:
                pw, ph = W / 10.0, H / 3.7
                cache[(W, H)] = Layout("hws_%gx%g" % (W, H), {c: (u * pw, v * ph) for c, (u, v) in GRID.items()},
                                       bounds=(0, 0, W, H))
            src = cache[(W, H)]
            out, n_cl = project_stroke(src, dst, g["pts"])
            if len(out) < 3 or out[-1][2] < 30:
                stats["skipped_short"] += 1
                continue
            stats["words"] += 1
            yield {
                "context": " ".join(words[:wi]) + (" " if wi else ""), "target": target, "kind": "swipe",
                "points": out, "id": "%s/%s/%s/w%d/%d" % (SRC, uid, g["sentence"], wi, g["pts"][0][2]),
                "src": SRC, "participant": uid, "split": split, "phraseSet": sets.get((uid, g["sentence"]), "?"),
                "srcCanvasPx": [W, H], "srcKeyAspect": round((H / 3.7) / (W / 10), 3), "clampedPts": n_cl,
                "afterFailedAttempt": retry,
            }
    convert.stats = stats


if __name__ == "__main__":
    import json
    from common import load_target
    n = 0
    for it in convert(load_target()):
        n += 1
        if n <= 2:
            print(json.dumps(it)[:300])
    print(n, convert.stats)
