#!/usr/bin/env python3
"""Turns a run's raw observations (obs.jsonl + IME-area images) into symbolic per-step observations.

  analyze.py <run-dir> [--geometry geo.json]   -> <run-dir>/steps.jsonl

Per image (cached by its pixel hash under tools/conformance/out/cache/):
  strip     mode (toolbar | suggestions | other | none), slots (OCR text per slot, "<emoji>" for emoji),
            bold slot, divider positions. OCR: tesseract on the upscaled strip text area, split at the
            strip's own dividers; the case of a leading c/o/s/u/v/w/x/z is decided from the glyph height.
  keyboard  page (letters / symbols / symbols2 / emoji / field/<type> / hidden) and shift state
            (off / on / caps), by nearest reference image; references are the labelled steps of the run's
            calib/* scenarios (each keyboard is classified against its own references). "overlay" when the
            image is far from every reference (a popup or panel is open).
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import OUT, GEOMETRY, load_json, read_jsonl, write_jsonl, tool, gray, rgb, log  # noqa: E402

CACHE = os.path.join(OUT, "cache")
FEATURES_VERSION = 11


class Regions:
    """Screen regions of one device/IME geometry, in IME-area image coordinates (y - regionTop)."""

    def __init__(self, geo_path, region_top):
        g = load_json(geo_path)
        self.g = g
        self.region_top = region_top
        st = g["suggestionStrip"]
        self.strip_top = int(round(st["top"])) - region_top
        self.strip_bottom = int(round(st["bottom"])) - region_top
        kb = g["keyboard"]
        keys = g["keys"]
        self.keys_top = int(min(k["hit"][1] for k in keys)) - region_top
        self.keys_bottom = int(max(k["hit"][3] for k in keys)) - region_top
        self.width = g["screen"]["width"]
        self.density = g["screen"].get("density", 420)
        # The strip's text area excludes the side buttons (apps grid at the left, mic at the right).
        self.text_x0, self.text_x1 = st.get("textX", [int(0.098 * self.width), int(0.902 * self.width)])
        sh = next(k for k in keys if k["name"] == "shift")
        self.shift = [int(v) for v in sh["visual"]]
        self.shift[1] -= region_top
        self.shift[3] -= region_top


# ------------------------------------------------------------------ strip

AMBIG = set("cosuvwxz")


def _bg(px, w, h):
    from collections import Counter
    c = Counter()
    for y in range(0, h, 4):
        for x in range(0, w, 7):
            i = (y * w + x) * 3
            c[tuple(px[i:i + 3])] += 1
    return c.most_common(1)[0][0]


def strip_features(img, R, tess):
    x0, x1 = R.text_x0, R.text_x1
    top, bot = R.strip_top, R.strip_bottom
    w, h = x1 - x0, bot - top
    _, _, px = rgb(img, (x0, top, w, h))
    bg = _bg(px, w, h)
    bgl = sum(bg) / 3

    def diff(i):
        return max(abs(px[i] - bg[0]), abs(px[i + 1] - bg[1]), abs(px[i + 2] - bg[2]))

    def sat(i):
        return max(px[i:i + 3]) - min(px[i:i + 3])

    scale = R.density / 420.0
    # ink mask and per-column statistics
    ink = bytearray(w * h)
    con = bytearray(w * h)  # contrast against the strip background
    satcol = [0] * w
    for y in range(h):
        row = y * w
        for x in range(w):
            i = (row + x) * 3
            d = diff(i)
            con[row + x] = min(255, d)
            if d > 40:
                ink[row + x] = 1
                if sat(i) > 55:
                    satcol[x] += 1
    # dividers: thin vertical runs >= 42 px (at 420 dpi) of low-contrast grey, isolated
    divs = []
    min_run = int(40 * scale)
    x = 0
    while x < w:
        best = 0
        run = 0
        for y in range(h):
            i = (y * w + x) * 3
            d = diff(i)
            if 12 < d and sat(i) < 30:
                run += 1
                best = max(best, run)
            else:
                run = 0
        if best >= min_run:
            # isolated: columns 7 px away on both sides are strip background in those rows (a chip's
            # border has the chip's fill on one side)
            l = sum(1 for y in range(h) if diff((y * w + max(0, x - 7)) * 3) > 8)
            r = sum(1 for y in range(h) if diff((y * w + min(w - 1, x + 8)) * 3) > 8)
            if l < 6 and r < 6:
                divs.append(x + x0)
                x += 8
                continue
        x += 1
    # emoji blobs: columns with saturated pixels
    emoji = []
    run = None
    gap = 0
    for x in range(w + 1):
        on = x < w and satcol[x] >= int(4 * scale)
        if on:
            if run is None:
                run = x
            gap = 0
        elif run is not None:
            gap += 1
            if gap > int(14 * scale) or x == w:
                end = x - gap + 1
                if end - run >= int(20 * scale) and not any(d - x0 in range(run, end) for d in divs):
                    emoji.append((run + x0, end + x0))
                elif end - run >= int(20 * scale):
                    # a divider inside: split there
                    cut = [d - x0 for d in divs if run <= d - x0 < end]
                    edges = [run] + cut + [end]
                    for a_, b_ in zip(edges, edges[1:]):
                        if b_ - a_ >= int(20 * scale):
                            emoji.append((a_ + x0, b_ + x0))
                run = None
                gap = 0
    has_ink = sum(ink) > 30
    # OCR: mask emoji and dividers, upscale, threshold
    words = []
    if has_ink:
        prep = img + ".strip.png"
        draw = []
        for a, b in emoji:
            draw += ["-draw", "rectangle %d,0 %d,%d" % (a - x0 - 2, b - x0 + 2, h)]
        for d in divs:
            draw += ["-draw", "rectangle %d,0 %d,%d" % (d - x0 - 3, d - x0 + 3, h)]
        fill = "rgb(%d,%d,%d)" % bg
        subprocess.run([tool("magick"), img, "-crop", "%dx%d+%d+%d" % (w, h, x0, top), "+repage",
                        "-fill", fill] + draw + ["-colorspace", "gray", "-resize", "200%",
                        "-level", "15%,85%", prep], check=True, capture_output=True)
        if bgl < 128:
            subprocess.run([tool("magick"), prep, "-negate", prep], check=True, capture_output=True)
        out = subprocess.run([tess, prep, "-", "--psm", "7", "-c", "hocr_char_boxes=1", "hocr"],
                             capture_output=True, text=True).stdout
        os.remove(prep)
        words = parse_hocr(out, x0)
    # Glyphs from the ink mask (tesseract's character boxes are loose): runs of ink columns inside the word
    # box. When their number matches the OCR'd characters they are paired in order and give exact extents;
    # an i (dot above a stem: a vertical gap) is told from I / l. Stroke weight (bold = the autocorrection)
    # is measured on stems, in the middle third of the x-height band.
    for wd in words:
        a, b = max(0, wd["x0"] - x0 - 2), min(w, wd["x1"] - x0 + 2)
        cols = [x for x in range(a, b) if any(ink[y * w + x] for y in range(h))]
        segs = []
        for x in cols:
            if segs and x == segs[-1][1]:
                segs[-1][1] = x + 1
            else:
                segs.append([x, x + 1])
        ext = []
        for sa, sb in segs:
            rows = [y for y in range(h) if any(ink[y * w + x] for x in range(sa, sb))]
            ext.append((rows[0], rows[-1] + 1, any(rows[k + 1] - rows[k] > 1 for k in range(len(rows) - 1))))
        chars = wd.get("chars", [])
        wd["segs"] = len(segs)
        if chars and len(segs) == len(chars):
            for c, (t0, t1, gap) in zip(chars, ext):
                c["top"], c["bot"], c["exact"] = t0, t1, True
                if c["c"] in "Il|1i" or (c["c"] == "j" and not gap):
                    c["c"] = "i" if gap else ("l" if c["c"] in "il" else c["c"])
            wd["t"] = "".join(c["c"] for c in chars)
        # weight: stroke width from a distance transform of the glyphs (binarised at half the text
        # contrast): twice the median distance-to-background along the strokes' ridges. Bold (Gboard's mark
        # for the autocorrection) is ~1.3x the regular stroke of the same strip.
        wd["stroke"] = stroke_width(con, w, h, a, b)
    return {"divs": divs, "emoji": [[a, b] for a, b in emoji], "words": words, "ink": has_ink, "bg": list(bg)}


CHAR_RE = re.compile(r"<span class='ocrx_cinfo' title='x_bboxes (\d+) (\d+) (\d+) (\d+); x_conf ([\d.]+)'>(.*?)</span>", re.S)


def stroke_width(con, w, h, a, b):
    vals = [con[y * w + x] for y in range(h) for x in range(a, b) if con[y * w + x] > 40]
    if not vals:
        return 0
    thr = sorted(vals)[int(len(vals) * 0.97)] * 0.5
    W = b - a
    INF = 10 ** 6
    dist = [[0 if con[y * w + a + x] < thr else INF for x in range(W)] for y in range(h)]
    for y in range(h):          # chamfer 3-4, forward
        row = dist[y]
        for x in range(W):
            if row[x]:
                v = row[x]
                if x > 0: v = min(v, row[x - 1] + 3)
                if y > 0:
                    up = dist[y - 1]
                    v = min(v, up[x] + 3)
                    if x > 0: v = min(v, up[x - 1] + 4)
                    if x + 1 < W: v = min(v, up[x + 1] + 4)
                row[x] = v
    for y in range(h - 1, -1, -1):  # backward
        row = dist[y]
        for x in range(W - 1, -1, -1):
            if row[x]:
                v = row[x]
                if x + 1 < W: v = min(v, row[x + 1] + 3)
                if y + 1 < h:
                    dn = dist[y + 1]
                    v = min(v, dn[x] + 3)
                    if x + 1 < W: v = min(v, dn[x + 1] + 4)
                    if x > 0: v = min(v, dn[x - 1] + 4)
                row[x] = v
    ridge = []
    for y in range(1, h - 1):
        for x in range(1, W - 1):
            v = dist[y][x]
            if v and v < INF and ((v >= dist[y][x - 1] and v >= dist[y][x + 1]) or (v >= dist[y - 1][x] and v >= dist[y + 1][x])):
                ridge.append(v)
    if not ridge:
        return 0
    ridge.sort()
    return round(2 * sum(ridge) / len(ridge) / 3.0, 2)


def parse_hocr(out, x0):
    """Words with their character boxes (strip coordinates; OCR ran on a 2x upscale)."""
    import html
    words = []
    for m in re.finditer(r"<span class='ocrx_word'[^>]*?title='bbox (\d+) (\d+) (\d+) (\d+); x_wconf (\d+)'[^>]*>", out):
        start = m.end()
        nxt = out.find("<span class='ocrx_word'", start)
        body = out[start:nxt if nxt >= 0 else len(out)]
        chars = [{"c": html.unescape(c.group(6)), "top": int(c.group(2)) // 2, "bot": int(c.group(4)) // 2,
                  "x0": int(c.group(1)) // 2 + x0, "x1": int(c.group(3)) // 2 + x0}
                 for c in CHAR_RE.finditer(body)]
        t = "".join(c["c"] for c in chars)
        if not t.strip():
            continue
        words.append({"t": t, "x0": int(m.group(1)) // 2 + x0, "x1": int(m.group(3)) // 2 + x0,
                      "conf": int(m.group(5)), "chars": chars})
    return words


def strip_slots(sf, R, toolbar_like):
    """Groups OCR words and emoji into slots separated by the dividers."""
    if not sf["ink"]:
        return {"mode": "empty", "slots": []}
    # the toolbar matches the run's toolbar reference pixel for pixel (strict test) and has no dividers
    if toolbar_like and not sf["divs"]:
        return {"mode": "toolbar", "slots": []}
    words = [w for w in sf["words"] if w["conf"] >= 30 or re.fullmatch(r"[A-Za-z']+", w["t"])]
    if not words and not sf["emoji"]:
        return {"mode": "other", "slots": []}
    edges = [R.text_x0] + sorted(sf["divs"]) + [R.text_x1]
    slots = []
    for a, b in zip(edges, edges[1:]):
        toks = []
        for wd in words:
            c = (wd["x0"] + wd["x1"]) / 2
            if a <= c < b:
                toks.append((wd["x0"], wd["t"], wd))
        for ea, eb in sf["emoji"]:
            c = (ea + eb) / 2
            if a <= c < b:
                toks.append((ea, "<emoji>", None))
        toks.sort(key=lambda t: t[0])
        # empty slots between dividers count (Gboard leaves a slot blank rather than closing it up)
        if toks or len(edges) > 2:
            slots.append({"x": round((a + b) / 2), "text": " ".join(t[1] for t in toks),
                          "words": [t[2] for t in toks if t[2]]})
    return {"mode": "suggestions", "slots": slots}


def fix_case(slot_words, xheight, capheight):
    """Case of ambiguous letters (c o s u v w x z differ only in size) from each glyph's height, against
    unambiguous lowercase (a e m n r) or tall (ascenders, capitals) glyphs of the same word, else the
    run-wide medians [xheight, capheight]."""
    out = []
    for wd in slot_words:
        chars = wd.get("chars") or []
        hs = lambda cs: sorted(c["bot"] - c["top"] for c in cs)
        low = hs([c for c in chars if c["c"] in "aemnr" and c.get("exact")])
        tall = hs([c for c in chars if c["c"] in "bdfhklABDEFGHKLMNPRT" and c.get("exact")])
        xh = low[len(low) // 2] if low else (tall[len(tall) // 2] * 0.73 if tall else xheight)
        cap = tall[len(tall) // 2] if tall else (xh / 0.73 if xh else capheight)
        if not chars or not xh or not cap:
            out.append(wd["t"])
            continue
        mid = (xh + cap) / 2
        out.append("".join((c["c"].upper() if c["bot"] - c["top"] > mid else c["c"].lower())
                           if c["c"].lower() in AMBIG and c.get("exact") else c["c"] for c in chars))
    return out


def word_token(wd):
    """OCR word, or <emoji> for what is clearly not text (no letters/digits, or very low confidence)."""
    t = wd["t"]
    if t in ("|", "!", "1") and len(t) == 1:
        wd["t"] = t = "I"
    if not re.search(r"[A-Za-z0-9]", t) or (wd["conf"] < 40 and not re.fullmatch(r"[A-Za-z'’.-]+", t)):
        return None
    return t


# ------------------------------------------------------------------ keyboard

def keys_vector(img, R, scale=0.125):
    w, h, px = gray(img, (0, R.keys_top, R.width, R.keys_bottom - R.keys_top), scale)
    return w, h, px


def shift_vector(img, R):
    l, t, r, b = R.shift
    return gray(img, (l, t, r - l, b - t), 0.5)


def mad(a, b):
    n = min(len(a), len(b))
    if n == 0:
        return 999.0
    return sum(abs(a[i] - b[i]) for i in range(n)) / n


def blank(img, R):
    """The IME area shows no keyboard (hidden): almost uniform."""
    w, h, px = gray(img, (0, R.keys_top, R.width, R.keys_bottom - R.keys_top), 0.0625)
    mean = sum(px) / len(px)
    return sum(abs(p - mean) for p in px) / len(px) < 4.0


# ------------------------------------------------------------------ run analysis

def pack(b):
    """Grey vectors are cached zlib-compressed (base64): the cache stays small on a nearly full disk."""
    import base64, zlib
    return "z:" + base64.b64encode(zlib.compress(bytes(b), 9)).decode()


def unpack(v):
    import base64, zlib
    if isinstance(v, str) and v.startswith("z:"):
        return zlib.decompress(base64.b64decode(v[2:]))
    return bytes(v)


def image_features(args):
    h, img, R, tess, key = args
    cpath = os.path.join(CACHE, key, h + ".json")
    if os.path.exists(cpath):
        try:
            d = load_json(cpath)
            if d.get("v") == FEATURES_VERSION:
                return h, d
        except Exception:
            pass
    d = {"v": FEATURES_VERSION}
    d["blank"] = blank(img, R)
    d["strip"] = strip_features(img, R, tess)
    d["keys"] = pack(keys_vector(img, R)[2])
    d["shift"] = pack(shift_vector(img, R)[2])
    _, _, sp = gray(img, (R.text_x0, R.strip_top, R.text_x1 - R.text_x0, R.strip_bottom - R.strip_top), 0.25)
    d["stripVec"] = pack(sp)
    os.makedirs(os.path.dirname(cpath), exist_ok=True)
    atomic_json(cpath, d)
    return h, d


def atomic_json(path, obj):
    """Sharded runs analyse several batches at once and share the cache and the reference library."""
    tmp = "%s.%d.tmp" % (path, os.getpid())
    with open(tmp, "w") as f:
        json.dump(obj, f)
    os.replace(tmp, path)


def analyze(run_dir, geo_path=None, region_top=None):
    info = load_json(os.path.join(run_dir, "info.json")) if os.path.exists(os.path.join(run_dir, "info.json")) else {}
    geo_path = geo_path or info.get("geometry") or GEOMETRY
    region_top = region_top or info.get("regionTop") or 1300
    R = Regions(geo_path, region_top)
    obs = read_jsonl(os.path.join(run_dir, "obs.jsonl"))
    scen = {s["id"]: s for s in read_jsonl(os.path.join(run_dir, "scenarios.jsonl"))}
    tess = tool("tesseract")
    key = "%s_%d" % (os.path.splitext(os.path.basename(geo_path))[0], region_top)
    hashes = sorted({o["ime"] for o in obs if o.get("ime")})
    feats = {}
    jobs = [(h, os.path.join(run_dir, "img", h + ".png"), R, tess, key) for h in hashes
            if os.path.exists(os.path.join(run_dir, "img", h + ".png"))]
    # pixel work is pure Python: processes, not threads (the GIL)
    with cf.ProcessPoolExecutor(max_workers=int(os.environ.get("CONFORM_WORKERS", "3"))) as ex:
        for h, d in ex.map(image_features, jobs, chunksize=4):
            feats[h] = d
    for d in feats.values():
        for k in ("keys", "shift", "stripVec"):
            d[k] = unpack(d[k])
    log("   %s: %d images analysed" % (os.path.basename(run_dir), len(feats)))

    # references from calibration steps, validated: a keyboard image claimed by two labels keeps the label most
    # steps gave it (a calibration tap the IME dropped must not teach "symbols" with a letters image), and each
    # label keeps its most frequent image. Labels left without a reference come from the library of earlier
    # validated calibrations for the same IME and geometry (tools/conformance/out/refs/).
    from collections import Counter, defaultdict
    toolbar_refs = []
    claims = defaultdict(Counter)  # keys-area hash -> label counts
    by_label = defaultdict(Counter)  # label -> image hash counts
    for o in obs:
        sc = scen.get(o["sc"])
        if not sc or not o["sc"].startswith("calib/") or o.get("aborted"):
            continue
        st = o["step"]
        if st < 0:
            if o["sc"] == "calib/letters" and o.get("ime") in feats:
                toolbar_refs.append(feats[o["ime"]]["stripVec"])
            continue
        m = sc["steps"][st].get("m", {})
        if "label" in m and o.get("ime") in feats:
            claims[o.get("keys") or o["ime"]][m["label"]] += 1
            by_label[m["label"]][(o.get("keys") or o["ime"], o["ime"])] += 1
    warnings = []
    refs = []
    for label, imgs in by_label.items():
        ok = [(n, k) for k, n in imgs.items() if claims[k[0]].most_common(1)[0][0] == label]
        if not ok:
            warnings.append("calibration step(s) for %s showed the keyboard of %s" % (
                label, ", ".join(sorted({claims[k[0]].most_common(1)[0][0] for k in imgs}))))
            continue
        n, (kh, ih) = max(ok)
        refs.append((label, feats[ih]["keys"], feats[ih]["shift"]))
    lib_dir = os.path.join(OUT, "refs")
    lib_path = os.path.join(lib_dir, "%s_%s.json" % (os.path.splitext(os.path.basename(geo_path))[0], info.get("ime", "unknown")))
    lib = load_json(lib_path) if os.path.exists(lib_path) else {}
    have = {r[0] for r in refs}
    for label, v in lib.items():
        if label not in have:
            refs.append((label, unpack(v["keys"]), unpack(v["shift"])))
            warnings.append("reference for %s taken from the library (%s)" % (label, v.get("from", "?")))
    if not warnings and len(have) >= 6:
        os.makedirs(lib_dir, exist_ok=True)
        lib.update({r[0]: {"keys": pack(r[1]), "shift": pack(r[2]), "from": os.path.basename(run_dir)} for r in refs})
        atomic_json(lib_path, lib)
    for wmsg in warnings:
        log("   calibration: " + wmsg)
    # strip measures for the case fix: x-height vs cap-height tops, from all OCR words of this run
    tops_lower, tops_upper = [], []
    for d in feats.values():
        for wd in d["strip"]["words"]:
            for c in wd.get("chars") or []:
                if not c.get("exact"):
                    continue
                if c["c"] in "aemnr":
                    tops_lower.append(c["bot"] - c["top"])
                elif c["c"] in "ABDEFGHKLMNPRT":
                    tops_upper.append(c["bot"] - c["top"])
    med = lambda v: sorted(v)[len(v) // 2] if v else None
    xh, cap = med(tops_lower), med(tops_upper)
    if xh is not None and cap is None:
        cap = xh / 0.73
    # strokes for bold: per-run distribution
    strokes = sorted(wd["stroke"] for d in feats.values() for wd in d["strip"]["words"] if wd.get("stroke"))
    # typical (regular weight) stroke: lower half of the distribution
    stroke_med = strokes[len(strokes) // 2] if strokes else 0

    def classify(h):
        d = feats.get(h)
        if d is None:
            return {"page": "unknown"}
        if d["blank"]:
            return {"page": "hidden"}
        if not refs:
            return {"page": "unknown"}
        scored = sorted(((mad(d["keys"], r[1]), r[0], r) for r in refs), key=lambda t: t[0])
        dist, label, _ = scored[0]
        out = {"dist": round(dist, 1)}
        if label.startswith("letters/"):
            out["page"] = "letters"
            # letter case from the whole keyboard (labels change on all letter keys); then one-shot vs caps lock
            # from the shift key only
            if label == "letters/off":
                out["shift"] = "off"
            else:
                ss = sorted(((mad(d["shift"], r[2]), r[0]) for r in refs if r[0] in ("letters/on", "letters/caps")),
                            key=lambda t: t[0])
                out["shift"] = ss[0][1].split("/")[1] if ss else label.split("/")[1]
        else:
            out["page"] = label
        if dist > 9.0:
            out["overlay"] = True
        return out

    rows = []
    for o in obs:
        if o.get("aborted"):
            rows.append(o)
            continue
        r = {k: o.get(k) for k in ("sc", "step", "op", "text", "selStart", "selEnd", "compStart", "compEnd", "skipped",
                                   "idle", "idleMs", "t0", "tEnd", "gapActual", "hist", "windowFocus",
                                   "focusWindow", "playgroundDead", "ime")}
        h = o.get("ime")
        d = feats.get(h)
        if d:
            # toolbar: almost no pixel differs strongly from the run's toolbar reference (a mean difference is
            # too lenient on a dark theme, where a few words change little of the average)
            tl = any(sum(1 for x, y in zip(d["stripVec"], t) if abs(x - y) > 25) < 0.015 * len(t) for t in toolbar_refs)
            sl = strip_slots(d["strip"], R, tl)
            bold = None
            strokes_here = []
            for i, s in enumerate(sl["slots"]):
                ws = s.pop("words")
                # an emoji is seen as a saturated blob and/or as OCR junk next to it: count it once
                n_emoji = max(s["text"].count("<emoji>"), sum(1 for w in ws if word_token(w) is None))
                ws = [w for w in ws if word_token(w) is not None]
                s["text"] = " ".join([("I" if t in ("|", "!", "1") else t) for t in fix_case(ws, xh, cap)] + ["<emoji>"] * n_emoji)
                if ws:
                    s["stroke"] = max(w["stroke"] for w in ws)
                    strokes_here.append((i, s["stroke"]))
            # bold (the autocorrection): clearly heavier strokes than the other words of the same strip,
            # or than the run's typical word when the strip has a single word
            for i, st_ in strokes_here:
                others = [v for j, v in strokes_here if j != i]
                ref = sorted(others)[len(others) // 2] if others else stroke_med
                if ref and st_ >= ref * 1.12:
                    bold = i
            sl["bold"] = bold
            sl["divs"] = d["strip"]["divs"]
            r["strip"] = sl
            r["kb"] = classify(h)
        rows.append(r)
    # host load at each step (device uptime -> host time through the anchors written at each instrumentation)
    anchors = read_jsonl(os.path.join(run_dir, "anchors.jsonl")) if os.path.exists(os.path.join(run_dir, "anchors.jsonl")) else []
    loads = read_jsonl(os.path.join(run_dir, "load.jsonl")) if os.path.exists(os.path.join(run_dir, "load.jsonl")) else []
    if anchors and loads:
        import bisect
        lt = [x["t"] for x in loads]
        for r in rows:
            t0 = r.get("t0")
            if not t0:
                continue
            an = [x for x in anchors if x["devUptimeMs"] <= t0]
            an = an[-1] if an else anchors[0]
            ht = an["host"] + (t0 - an["devUptimeMs"]) / 1000.0
            k = min(max(bisect.bisect_left(lt, ht), 0), len(lt) - 1)
            if abs(lt[k] - ht) < 30:
                r["load1"] = loads[k]["load1"]
    write_jsonl(os.path.join(run_dir, "steps.jsonl"), rows)
    meta = {"refs": sorted({r[0] for r in refs}), "calibrationWarnings": warnings, "xheightTop": xh, "capTop": cap, "strokeMedian": stroke_med,
            "images": len(feats), "toolbarRefs": len(toolbar_refs)}
    json.dump(meta, open(os.path.join(run_dir, "analysis.json"), "w"), indent=1)
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--geometry")
    ap.add_argument("--region-top", type=int)
    a = ap.parse_args()
    analyze(a.run_dir, a.geometry, a.region_top)


if __name__ == "__main__":
    main()
