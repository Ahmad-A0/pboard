#!/usr/bin/env python3
"""Generate noisy synthetic tap / swipe traces for a keyboard geometry.

Usage (devshell python, isolated mode):
  python3 -I tools/bench/gen_traces.py --geometry tools/bench/geometry/gboard_pixel8.json \
      --sentences tools/bench/sentences/heldout_v1.txt --section tap --kind tap \
      --out tools/bench/sets/gboard_pixel8/tap_v1.jsonl [--seed 1] [--sigma 0.2:0.4] ...

One JSONL line per intended word (format shared with the JVM engine eval):
  {"context": "<text before the word, incl. trailing space>", "target": "<word>",
   "kind": "tap"|"swipe", "points": [[x, y, tMs], ...], ...extra annotation fields}
Points are SCREEN pixels of the given geometry (keyboard-local = screen - geometry.keyboard origin);
t is ms from the item's first touch. For "tap" each point is one touch-down (held for holdMs[i]);
the final point is the spacebar tap that ends the word. For "swipe" the points are one continuous
stroke (DOWN at the first, UP at the last); the IME's auto-space separates words.
A sidecar <out>.meta.json embeds the full geometry and every generator parameter.
"""
import argparse
import json
import math
import os
import random

LETTERS = set("abcdefghijklmnopqrstuvwxyz")


def load_sentences(path, section):
    out, cur = [], None
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("# --- section:"):
                cur = s.split(":", 1)[1].strip()
                continue
            if not s or s.startswith("#"):
                continue
            if section in (None, cur):
                out.append(s)
    return out


class Geo:
    def __init__(self, path):
        with open(path, encoding="utf-8") as f:
            self.raw = json.load(f)
        self.kw = float(self.raw["keyWidth"])
        self.keys = {k["name"]: k for k in self.raw["keys"]}
        self.letters = {k["label"]: k for k in self.raw["keys"] if k["label"] in LETTERS}
        hits = [k.get("hit") or k["visual"] for k in self.raw["keys"]]
        self.top = min(h[1] for h in hits)
        self.bottom = max(h[3] for h in hits)
        letter_hits = [k.get("hit") or k["visual"] for k in self.letters.values()]
        self.letter_top = min(h[1] for h in letter_hits)
        self.letter_bottom = max(h[3] for h in letter_hits)

    def centre(self, ch):
        k = self.letters[ch]
        return k["cx"], k["cy"]

    def space(self):
        return self.keys["space"]

    def decode(self, x, y):
        """Literal key under (x, y) by hit box; "<strip>" above the keys, "<off>" elsewhere outside."""
        if y < self.top:
            return "<strip>"
        for k in self.raw["keys"]:
            h = k.get("hit") or k["visual"]
            if h[0] <= x < h[2] and h[1] <= y < h[3]:
                return k["label"] if k["label"] else "<%s>" % k["name"]
        return "<off>"


def letters_of(word):
    return [c for c in word.lower() if c in LETTERS]


# ------------------------------------------------------------------------------------------- taps

def typo_sequence(chars, rng, p):
    """Applies typist slips to the intended key sequence. Returns (keys, list of slip names)."""
    seq, slips = list(chars), []
    i = 0
    out = []
    while i < len(seq):
        c = seq[i]
        r = rng.random()
        if len(seq) > 2 and r < p["omit"]:
            slips.append("omit:%d" % i)
            i += 1
            continue
        if i + 1 < len(seq) and seq[i + 1] != c and r < p["omit"] + p["transpose"]:
            out += [seq[i + 1], c]
            slips.append("transpose:%d" % i)
            i += 2
            continue
        out.append(c)
        if r > 1 - p["double"]:
            out.append(c)
            slips.append("double:%d" % i)
        i += 1
    return out, slips


def gen_tap(word, geo, rng, a):
    sigma = rng.uniform(*a.sigma) * geo.kw
    keys, slips = typo_sequence(letters_of(word), rng, {"omit": a.p_omit, "transpose": a.p_transpose, "double": a.p_double})
    pts, holds, literal = [], [], []
    t = 0.0
    prev = None
    resampled = 0
    for c in keys:
        cx, cy = geo.centre(c)
        # A tap that lands on a function key (shift, delete, ?123, emoji, enter) or above the keys
        # (suggestion strip / toolbar) triggers UI actions outside the scope of decoding, so it is
        # redrawn; letter-to-letter and letter-to-space misses are kept.
        for _ in range(50):
            x = rng.gauss(cx, sigma)
            y = rng.gauss(cy + a.bias_y * geo.kw, sigma)
            lab = geo.decode(x, y)
            if lab == " " or lab in LETTERS:
                break
            resampled += 1
        if prev is not None:
            d = math.hypot(x - prev[0], y - prev[1]) / geo.kw
            # Fitts-style inter-key interval: base + slope * log2(1 + distance in key widths), noisy.
            iki = a.iki_base + a.iki_slope * math.log2(1 + d)
            iki *= math.exp(rng.gauss(0, a.iki_jitter))
            t += min(max(iki, a.iki_min), a.iki_max)
        pts.append([round(x, 1), round(y, 1), int(round(t))])
        holds.append(hold(rng, a))
        literal.append(geo.decode(x, y))
        prev = (x, y)
    # Word terminator. People hit the bar right of centre (thumb) and in its upper half; a few miss
    # it upwards onto the bottom letter row (p_space_miss), which an IME sees as one more letter.
    sp = geo.space()
    sx = rng.gauss(sp["cx"] + a.space_offset_x * geo.kw, a.space_sigma_x * geo.kw)
    sx = min(max(sx, sp["visual"][0] + 10), sp["visual"][2] - 10)
    space_miss = rng.random() < a.p_space_miss
    if space_miss:
        row = geo.keys["m"]
        sy = rng.gauss(row["cy"] + 0.25 * row["height"], 0.1 * geo.kw)
        sy = min(sy, row["hit"][3] - 2)
    else:
        sy = rng.gauss(sp["cy"] + a.space_dy * geo.kw, a.space_sigma_y * geo.kw)
        sy = min(max(sy, sp["hit"][1] + 2), sp["hit"][3] - 2)
    d = math.hypot(sx - prev[0], sy - prev[1]) / geo.kw if prev else 0
    t += min(max((a.iki_base + a.iki_slope * math.log2(1 + d)) * math.exp(rng.gauss(0, a.iki_jitter)), a.iki_min), a.iki_max)
    pts.append([round(sx, 1), round(sy, 1), int(round(t))])
    holds.append(hold(rng, a))
    lit = "".join(literal)
    term = geo.decode(sx, sy)
    return {"points": pts, "holdMs": holds, "literal": lit, "slips": slips,
            "clean": (not slips) and lit == "".join(letters_of(word)) and not space_miss,
            "sigmaKw": round(sigma / geo.kw, 3), "resampled": resampled,
            "terminatorKeyOnTarget": "space" if term == " " else term}


def hold(rng, a):
    """Touch duration: lognormal around hold_mean with relative spread hold_sd / hold_mean."""
    return int(round(min(max(a.hold_mean * math.exp(rng.gauss(0, a.hold_sd / a.hold_mean)), 15), 300)))


# ------------------------------------------------------------------------------------------ swipe

def catmull_rom(pts, n_per_seg=24):
    """Centripetal-ish uniform Catmull-Rom through pts; returns a dense polyline and segment ends."""
    if len(pts) == 1:
        return list(pts), [0]
    ext = [pts[0]] + list(pts) + [pts[-1]]
    out, seg_end = [], []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        for j in range(n_per_seg):
            s = j / n_per_seg
            s2, s3 = s * s, s * s * s
            out.append(tuple(0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * s + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * s2
                                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * s3) for k in (0, 1)))
        seg_end.append(len(out))
    out.append(tuple(pts[-1]))
    return out, seg_end


def gen_swipe(word, geo, rng, a):
    chars = letters_of(word)
    keys = [c for i, c in enumerate(chars) if i == 0 or c != chars[i - 1]]  # no repeats in a swipe
    sigma = rng.uniform(*a.swipe_sigma) * geo.kw
    via = []
    lo, hi = geo.letter_top + 0.15 * geo.kw, geo.letter_bottom - 0.15 * geo.kw
    for c in keys:
        cx, cy = geo.centre(c)
        # Keep the stroke on the letter rows: a gesture starting on the strip would press a toolbar button.
        via.append([rng.gauss(cx, sigma), min(max(rng.gauss(cy + a.bias_y * geo.kw, sigma), lo), hi)])
    # Corner cutting: pull interior via points toward the chord of their neighbours.
    cut = []
    for i in range(1, len(via) - 1):
        f = rng.uniform(0, a.corner_cut)
        mx = (via[i - 1][0] + via[i + 1][0]) / 2
        my = (via[i - 1][1] + via[i + 1][1]) / 2
        cut.append((via[i][0] + f * (mx - via[i][0]), via[i][1] + f * (my - via[i][1])))
    via = [via[0]] + [list(c) for c in cut] + [via[-1]] if len(via) > 2 else via
    if len(via) == 1:  # single-key word cannot be swiped meaningfully
        return None
    poly, seg_end = catmull_rom(via)
    # Arc length table.
    cum = [0.0]
    for i in range(1, len(poly)):
        cum.append(cum[-1] + math.hypot(poly[i][0] - poly[i - 1][0], poly[i][1] - poly[i - 1][1]))
    seg_len = []
    prev = 0
    for e in seg_end:
        seg_len.append(cum[min(e, len(cum) - 1)] - cum[prev])
        prev = min(e, len(cum) - 1)
    # Velocity: overlapping minimum-jerk bells, one per segment (speed dips but never stops at keys).
    speed = rng.uniform(*a.swipe_speed) * geo.kw / 100.0  # px per ms
    durs = [a.seg_base + L / speed for L in seg_len]
    starts, t = [], a.swipe_dwell * rng.uniform(0.5, 1.5)
    dwell = t
    for d in durs:
        starts.append(t)
        t += d * (1 - a.overlap)
    total = starts[-1] + durs[-1] + a.swipe_dwell * rng.uniform(0.3, 1.0)

    def s_at(tt):
        s = 0.0
        for st, d, L in zip(starts, durs, seg_len):
            tau = min(max((tt - st) / d, 0.0), 1.0)
            s += L * (10 * tau ** 3 - 15 * tau ** 4 + 6 * tau ** 5)
        return s

    def point_at(s):
        lo, hi = 0, len(cum) - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if cum[mid] < s:
                lo = mid + 1
            else:
                hi = mid
        i = max(lo, 1)
        span = cum[i] - cum[i - 1]
        f = 0 if span <= 0 else (s - cum[i - 1]) / span
        return (poly[i - 1][0] + f * (poly[i][0] - poly[i - 1][0]), poly[i - 1][1] + f * (poly[i][1] - poly[i - 1][1]))

    hz = rng.choice(a.hz)
    dt = 1000.0 / hz
    pts, tt = [], 0.0
    while tt < total:
        x, y = point_at(s_at(tt))
        y = min(max(y, geo.letter_top + 2), geo.bottom - 2)
        pts.append([round(x + rng.gauss(0, a.sensor_px), 1), round(y + rng.gauss(0, a.sensor_px), 1), int(round(tt))])
        tt += dt * (1 + rng.uniform(-0.05, 0.05))
    x, y = point_at(cum[-1])
    pts.append([round(x, 1), round(y, 1), int(round(total))])
    # Drop timestamp collisions caused by rounding.
    dedup = [pts[0]]
    for p in pts[1:]:
        if p[2] > dedup[-1][2]:
            dedup.append(p)
    return {"points": dedup, "hz": hz, "sigmaKw": round(sigma / geo.kw, 3), "durationMs": dedup[-1][2],
            "pathKw": round(cum[-1] / geo.kw, 2), "dwellMs": int(dwell)}


# ------------------------------------------------------------------------------------------- main

def rng_pair(s):
    lo, hi = s.split(":")
    return float(lo), float(hi)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--geometry", required=True)
    ap.add_argument("--sentences", default="tools/bench/sentences/heldout_v1.txt")
    ap.add_argument("--section", default=None, help="sentence-file section to use (tap / swipe)")
    ap.add_argument("--kind", choices=["tap", "swipe", "predict"], default="tap")
    ap.add_argument("--per-sentence", type=int, default=3, help="predict: probes per sentence")
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--max-words", type=int, default=0, help="stop after this many items (0 = all)")
    ap.add_argument("--typist-model", help="typist_model.json: take defaults from its genTracesArgs + extensions")
    ap.add_argument("--pairs", help="sanity mode: 'typed:target,...' clean taps of `typed`, scored against `target`")
    # Tap typist model. Defaults = human fit v1 (tools/data/human/typist_model.json, TSI/Vertanen
    # tune splits; see docs/eval-data.md section 5): per-word isotropic sd 0.21-0.28 kw, slight
    # upward bias, no Fitts effect, lognormal IKI (median 183 ms), 45 ms holds, space bar hit
    # +1.23 kw right of centre and 0.22 rows high, 6.9 % of terminators on the bottom letter row.
    ap.add_argument("--sigma", type=rng_pair, default=(0.2093, 0.2791), help="tap noise sd range, key widths (per word)")
    ap.add_argument("--bias-y", type=float, default=-0.0548, help="systematic vertical offset, key widths (+ = down)")
    # Slip rates per letter. Human first-pass misspellings are mostly substitutions (covered by the
    # spatial noise); these are ~1/5 of the first version's rates.
    ap.add_argument("--p-omit", type=float, default=0.003)
    ap.add_argument("--p-transpose", type=float, default=0.002)
    ap.add_argument("--p-double", type=float, default=0.002)
    ap.add_argument("--iki-base", type=float, default=183.0562)
    ap.add_argument("--iki-slope", type=float, default=0.0)
    ap.add_argument("--iki-jitter", type=float, default=0.5381, help="lognormal sd of inter-key interval")
    ap.add_argument("--iki-min", type=float, default=79.0)
    ap.add_argument("--iki-max", type=float, default=439.2)
    ap.add_argument("--hold-mean", type=float, default=45.3686)
    ap.add_argument("--hold-sd", type=float, default=20.4068)
    ap.add_argument("--space-sigma-x", type=float, default=0.3364, help="space tap sd, key widths")
    ap.add_argument("--space-offset-x", type=float, default=1.2337, help="space tap mean x right of bar centre, kw")
    ap.add_argument("--space-dy", type=float, default=-0.3231, help="space tap mean y offset, kw (- = up)")
    ap.add_argument("--space-sigma-y", type=float, default=0.2710, help="space tap y sd, kw")
    ap.add_argument("--p-space-miss", type=float, default=0.0685, help="terminator lands on the bottom letter row")
    # Swipe model (human fit v1, FUTO/HWS tune: median duration ~800 ms, little corner cutting).
    ap.add_argument("--swipe-sigma", type=rng_pair, default=(0.1854, 0.2876), help="via-point sd range, key widths")
    ap.add_argument("--corner-cut", type=float, default=0.0986, help="max pull of interior via points to the chord")
    ap.add_argument("--swipe-speed", type=rng_pair, default=(2.1365, 4.9852), help="key widths per 100 ms")
    ap.add_argument("--seg-base", type=float, default=229.7835, help="ms added to every segment")
    ap.add_argument("--overlap", type=float, default=0.3, help="fraction by which segment bells overlap")
    ap.add_argument("--swipe-dwell", type=float, default=10.0, help="ms resting at start/end")
    ap.add_argument("--hz", type=lambda s: [int(v) for v in s.split(",")], default=[60, 90, 120])
    ap.add_argument("--sensor-px", type=float, default=1.5)
    pre, _ = ap.parse_known_args()
    if pre.typist_model:
        with open(pre.typist_model, encoding="utf-8") as f:
            tm = json.load(f)
        g = {k: (tuple(v) if isinstance(v, list) and k in ("sigma", "swipe_sigma", "swipe_speed") else v)
             for k, v in tm["genTracesArgs"].items() if not k.startswith("_") and k != "extensions"}
        ext = tm["genTracesArgs"].get("extensions", {})
        kw, row = tm["targetKeyPx"]
        st = ext.get("spaceTap")
        if st:
            g["space_offset_x"] = st["dxFractionOfBarWidth"]["mean"] * tm["targetSpaceWidthPx"] / kw
            g["space_dy"] = st["dyRowPitch"]["mean"] * row / kw
            g["space_sigma_y"] = st["dyRowPitch"]["sd"] * row / kw
        miss = tm.get("tap", {}).get("tsi", {}).get("terminatorTapNotOnSpaceOrPeriod")
        if miss is not None:
            g["p_space_miss"] = miss
        ap.set_defaults(**g)
    a = ap.parse_args()

    geo = Geo(a.geometry)
    rng = random.Random(a.seed)
    sentences = load_sentences(a.sentences, a.section) if not a.pairs else []
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    n = 0
    counts = {"tap": 0, "swipe": 0, "predict": 0}
    with open(a.out, "w", encoding="utf-8") as f:
        for si, sent in enumerate(sentences):
            words = sent.split()
            if a.kind == "predict":
                # Next-word prediction probes: prefix is pre-filled, the last context word is typed
                # cleanly (taps on key centres, natural timing) + space, then the strip is read.
                positions = sorted(rng.sample(range(1, len(words)), min(a.per_sentence, len(words) - 1)))
                for wi in positions:
                    clean = argparse.Namespace(**vars(a))
                    clean.sigma, clean.bias_y, clean.space_sigma_x = (0.0, 0.0), 0.0, 0.0
                    clean.space_offset_x = clean.space_dy = clean.space_sigma_y = clean.p_space_miss = 0.0
                    clean.p_omit = clean.p_transpose = clean.p_double = 0.0
                    g = gen_tap(words[wi - 1], geo, rng, clean)
                    item = {"context": " ".join(words[:wi]) + " ", "target": words[wi], "kind": "predict",
                            "points": g["points"], "holdMs": g["holdMs"],
                            "prefix": " ".join(words[:wi - 1]) + (" " if wi > 1 else ""),
                            "typed": words[wi - 1], "id": "s%03dw%02d" % (si, wi), "sentence": si, "word": wi}
                    f.write(json.dumps(item, separators=(",", ":")) + "\n")
                    counts["predict"] += 1
                    n += 1
                continue
            for wi, w in enumerate(words):
                ctx = " ".join(words[:wi]) + (" " if wi else "")
                kind = a.kind
                g = gen_swipe(w, geo, rng, a) if kind == "swipe" else None
                if g is None:  # tap mode, or a one-key word in swipe mode (a person would tap it)
                    kind = "tap"
                    g = gen_tap(w, geo, rng, a)
                item = {"context": ctx, "target": w, "kind": kind, "points": g.pop("points"),
                        "id": "s%03dw%02d" % (si, wi), "sentence": si, "word": wi}
                item.update(g)
                f.write(json.dumps(item, separators=(",", ":")) + "\n")
                counts[kind] += 1
                n += 1
                if a.max_words and n >= a.max_words:
                    break
            if a.max_words and n >= a.max_words:
                break
        if a.pairs:
            clean = argparse.Namespace(**vars(a))
            clean.sigma, clean.bias_y, clean.space_sigma_x = (0.0, 0.0), 0.0, 0.0
            clean.space_offset_x = clean.space_dy = clean.space_sigma_y = clean.p_space_miss = 0.0
            clean.p_omit = clean.p_transpose = clean.p_double = 0.0
            for pi, pair in enumerate(a.pairs.split(",")):
                typed, target = pair.split(":")
                g = gen_tap(typed, geo, rng, clean)
                item = {"context": "", "target": target, "kind": "tap", "points": g.pop("points"),
                        "id": "pair%02d" % pi, "typed": typed}
                item.update(g)
                f.write(json.dumps(item, separators=(",", ":")) + "\n")
                counts["tap"] += 1
                n += 1
    meta = {"format": "pboard-bench-traces/1", "items": n, "counts": counts, "sentences": a.sentences,
            "section": a.section, "kind": a.kind, "seed": a.seed,
            "params": {k: v for k, v in vars(a).items() if k not in ("geometry", "sentences", "out")},
            "geometryPath": a.geometry, "geometry": geo.raw}
    with open(a.out + ".meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=1)
    print("wrote %d items (%s) to %s" % (n, counts, a.out))


if __name__ == "__main__":
    main()
