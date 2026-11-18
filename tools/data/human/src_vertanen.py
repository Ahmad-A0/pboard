"""Vertanen & Kristensson IUI 2023 noisy-typing dataset (CC BY 4.0) -> word-level tap traces (phone subset).

Only the phone conditions closest to everyday typing are used:
  vt_exp2 *_space  - Nexus 4, full-size portrait QWERTY, key pop-ups + nearest-key echo, spaces typed (24 people)
  vt_exp3 *_normal - Nexus 4, 60x40 mm keyboard, same feedback, delimiter free choice          (12 people)
Participants typed whole sentences for a sentence-level decoder (VelociTap), were told to go fast and did not
correct as they typed, so these taps are noisier than everyday typing: treat this as a stress set. The authors'
forced alignment (test_word/) splits each sentence into one tap group per reference word; delimiter taps are not
part of the word groups, so each trace gets a synthetic terminating space (terminator="synthetic").
Each tap has touch-down..touch-up samples: we emit the touch-down position and keep holdMs = up - down.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
from common import LETTERS, Layout, RAW, balanced_split, clamp_to, word_core

SRC = "vtap"
DIR = os.path.join(RAW, "vertanen_noisy")
CONDITIONS = [("vt_exp2", "_space.log", "nexus4"), ("vt_exp3", "_normal.log", "nexus4_normal")]
SYNTH_TERM_MS = 250


def ensure_extracted():
    base = os.path.join(DIR, "iui")
    if not os.path.isdir(os.path.join(base, "noisy_typing", "phone")):
        import zipfile
        with zipfile.ZipFile(os.path.join(DIR, "vertanen_noisy_typing_iui2023.zip")) as z:
            for m in z.namelist():  # refuse path traversal in the untrusted archive
                if m.startswith("/") or ".." in m.split("/"):
                    raise ValueError("unsafe member %r" % m)
            z.extractall(base)
    js = os.path.join(DIR, "json")
    if not os.path.exists(os.path.join(js, "noisy_typing", "json", "keyboards.json")):
        import zipfile
        with zipfile.ZipFile(os.path.join(DIR, "vertanen_noisy_typing_json.zip")) as z:
            z.extract("noisy_typing/json/keyboards.json", js)
    return os.path.join(base, "noisy_typing", "phone"), os.path.join(js, "noisy_typing", "json", "keyboards.json")


def layouts(kb_path):
    with open(kb_path, encoding="utf-8") as f:
        kbs = {k["keyboard"]: k for k in json.load(f)}
    out = {}
    for name in {c[2] for c in CONDITIONS}:
        keys = {k["character"]: k for k in kbs[name]["keys"]}
        centres = {c: (k["x_center"], k["y_center"]) for c, k in keys.items() if c in LETTERS}
        lay = Layout(name, centres)
        vis_w = keys["q"]["width"]
        sp = keys["<sp>"]
        # Published widths are visual; make the space width pitch-based like every other layout we use.
        lay.specials["space"] = (sp["x_center"], sp["y_center"], sp["width"] + (lay.px - vis_w), lay.py)
        out[name] = lay
    return out


def parse_log(path):
    blocks, cur = [], None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("ID:"):
                cur = {"id": line[3:].strip(), "IN": []}
                blocks.append(cur)
            elif cur is not None and ":" in line[:6]:
                tag, val = line.split(":", 1)
                val = val[1:] if val.startswith(" ") else val
                if tag == "IN":
                    taps = []
                    for tap in val.split("|") if val.strip() else []:
                        obs = [tuple(float(v) for v in o.split(",")) for o in tap.split(";") if o]
                        if obs:
                            taps.append(obs)
                    cur["IN"].append(taps)
                else:
                    cur[tag] = val
    return blocks


def convert(dst):
    phone, kb_path = ensure_extracted()
    lays = layouts(kb_path)
    files = []
    for exp, suffix, kb in CONDITIONS:
        d = os.path.join(phone, exp, "test_word")
        for fn in sorted(os.listdir(d)):
            if fn.endswith(suffix):
                files.append((exp, fn[: -len(suffix)], kb, os.path.join(d, fn)))
    splits = balanced_split(SRC, {"%s/%s" % (e, p) for e, p, _, _ in files})
    stats = {"sentences": 0, "words": 0, "aligned_1to1": 0, "skipped_empty": 0, "skipped_misaligned_sentence": 0}
    for exp, pid, kb, path in files:
        src = lays[kb]
        part = "%s/%s" % (exp, pid)
        for bi, b in enumerate(parse_log(path)):
            ref = b.get("REF", "").split()
            if len(ref) != len(b["IN"]):
                stats["skipped_misaligned_sentence"] += 1
                continue
            stats["sentences"] += 1
            orig = b.get("ORIG", "").split()
            use_orig = len(orig) == len(ref) and all(word_core(o).lower().replace("'", "") == r.replace("'", "")
                                                     for o, r in zip(orig, ref))
            toks = orig if use_orig else ref
            for wi, taps in enumerate(b["IN"]):
                target = word_core(toks[wi])
                letters = [c for c in target.lower() if c in LETTERS]
                if not taps or not letters or len(letters) != len([c for c in target.lower() if c.isalpha()]):
                    stats["skipped_empty"] += 1
                    continue
                aligned = len(taps) == len(letters)
                stats["words"] += 1
                stats["aligned_1to1"] += aligned
                t0 = taps[0][0][2]
                pts, offs, holds, lit = [], [], [], []
                for k, obs in enumerate(taps):
                    x, y, t = obs[0]
                    if aligned:
                        du, dv = src.offset_of(letters[k], x, y)
                        X, Y = dst.place(letters[k], du, dv)
                        offs.append([round(du, 3), round(dv, 3)])
                    else:
                        X, Y = dst.from_units(*src.to_units(x, y))
                        offs.append(None)
                    X, Y, _ = clamp_to(dst, X, Y)
                    pts.append([round(X, 1), round(Y, 1), int(round(t - t0))])
                    holds.append(int(round(obs[-1][2] - obs[0][2])))
                    l = src.literal(x, y)
                    lit.append(" " if l == "space" else l)
                sx, sy = dst.place("space", 0.0, 0.0)
                pts.append([round(sx, 1), round(sy, 1), pts[-1][2] + SYNTH_TERM_MS])
                yield {
                    "context": " ".join(toks[:wi]) + (" " if wi else ""), "target": target, "kind": "tap",
                    "points": pts, "holdMs": holds + [sorted(holds)[len(holds) // 2]],  # synthetic tap: word median
                    "id": "%s/%s/%s/%s.%d/w%d" % (SRC, exp, pid, b["id"], bi, wi), "src": SRC, "participant": part,
                    "split": splits[part], "terminator": "synthetic", "keyboard": kb,
                    "intended": "".join(letters) + " ", "literal": "".join(lit) + " ", "alignedTaps": aligned,
                    "offsetsKu": offs + [None],
                }
    convert.stats = stats


if __name__ == "__main__":
    from common import load_target
    n = 0
    for it in convert(load_target()):
        n += 1
        if n <= 2:
            print(json.dumps(it)[:400])
    print(n, convert.stats)
