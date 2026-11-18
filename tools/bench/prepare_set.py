#!/usr/bin/env python3
"""Make any shared-format trace set replayable on a given IME geometry.

Usage: prepare_set.py <in.jsonl> <target-geometry.json> <out.jsonl> [--source-geometry G.json]
       [--no-space]

1. Re-projection: if the set was made for another geometry (--source-geometry, or the
   "geometry"/"geometryPath" of <in>.meta.json), every point is mapped with the affine transform that
   best maps the source letter-key centres (plus space) onto the target's (least squares). Sets
   already in the target geometry (same "name") are left untouched.
2. Word termination: a "tap" item whose last touch is not on the spacebar gets a space tap appended
   at the spacebar centre (+180 ms), because a person ends a word with space and IMEs only
   autocorrect on commit. Marked with "autoSpace": true. Disable with --no-space. Items that carry a
   "terminator" annotation (human sets) already end with the person's real terminator tap and are
   left alone (end-to-end), unless --snap-terminator moves off-bar terminators onto the bar.
Input may be gzipped (.jsonl.gz).
Items of kind "predict" are passed through (their taps are re-projected too).
"""
import argparse
import gzip
import json
import os


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def anchors(geo):
    pts = {}
    for k in geo["keys"]:
        lab = k.get("label", "")
        if (len(lab) == 1 and lab.isalpha()) or k.get("name") == "space":
            pts[k.get("name", lab)] = (k["cx"], k["cy"])
    return pts


def solve3(m, v):
    """Solve a 3x3 linear system (Cramer's rule)."""
    def det(a):
        return (a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]))
    d = det(m)
    out = []
    for c in range(3):
        mc = [row[:] for row in m]
        for r in range(3):
            mc[r][c] = v[r]
        out.append(det(mc) / d)
    return out


def affine(src, dst):
    """Least-squares x' = a x + b y + c, y' = d x + e y + f over shared anchor names."""
    names = sorted(set(src) & set(dst))
    if len(names) < 3:
        raise SystemExit("geometries share fewer than 3 anchor keys")
    ata = [[0.0] * 3 for _ in range(3)]
    atx = [0.0] * 3
    aty = [0.0] * 3
    for n in names:
        row = (src[n][0], src[n][1], 1.0)
        for i in range(3):
            for j in range(3):
                ata[i][j] += row[i] * row[j]
            atx[i] += row[i] * dst[n][0]
            aty[i] += row[i] * dst[n][1]
    px, py = solve3(ata, atx), solve3(ata, aty)
    resid = max(abs(px[0] * src[n][0] + px[1] * src[n][1] + px[2] - dst[n][0]) +
                abs(py[0] * src[n][0] + py[1] * src[n][1] + py[2] - dst[n][1]) for n in names)
    return (lambda x, y: (px[0] * x + px[1] * y + px[2], py[0] * x + py[1] * y + py[2])), resid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inp")
    ap.add_argument("geometry")
    ap.add_argument("out")
    ap.add_argument("--source-geometry")
    ap.add_argument("--no-space", action="store_true")
    ap.add_argument("--snap-terminator", action="store_true",
                    help="human sets: move a word terminator that missed the space bar onto it")
    a = ap.parse_args()

    tgt = load(a.geometry)
    src = None
    if a.source_geometry:
        src = load(a.source_geometry)
    elif os.path.exists(a.inp + ".meta.json"):
        meta = load(a.inp + ".meta.json")
        src = meta.get("geometry")
        if src is None and meta.get("geometryPath") and os.path.exists(meta["geometryPath"]):
            src = load(meta["geometryPath"])
    transform = None
    if src is not None and src.get("name") != tgt.get("name"):
        transform, resid = affine(anchors(src), anchors(tgt))
        print("re-projecting %s -> %s (max anchor residual %.1f px)" % (src.get("name"), tgt.get("name"), resid))
    elif src is None:
        print("no source geometry known; assuming the set is already in %s coordinates" % tgt.get("name"))
    space = next(k for k in tgt["keys"] if k.get("name") == "space")
    sh = space.get("hit") or space["visual"]
    n = added = 0
    opener = gzip.open if a.inp.endswith(".gz") else open
    snapped = 0
    with opener(a.inp, "rt", encoding="utf-8") as f, open(a.out, "w", encoding="utf-8") as g:
        for line in f:
            if not line.strip():
                continue
            it = json.loads(line)
            if transform:
                it["points"] = [[round(c, 1) for c in transform(p[0], p[1])] + [p[2]] for p in it["points"]]
            if it["kind"] == "tap" and "terminator" in it:
                # Human sets: the recorded word terminator may have landed off the bar (on the
                # bottom letter row). End-to-end runs keep it; --snap-terminator moves it to the bar.
                x, y, t = it["points"][-1]
                if a.snap_terminator and not (sh[0] <= x < sh[2] and sh[1] <= y < sh[3]):
                    it["points"][-1] = [space["cx"], space["cy"], t]
                    it["terminatorSnapped"] = True
                    snapped += 1
            elif it["kind"] == "tap" and not a.no_space and it["points"]:
                x, y, t = it["points"][-1]
                if not (sh[0] <= x < sh[2] and sh[1] <= y < sh[3]):
                    it["points"].append([space["cx"], space["cy"], t + 180])
                    if "holdMs" in it:
                        it["holdMs"].append(75)
                    it["autoSpace"] = True
                    added += 1
            g.write(json.dumps(it, separators=(",", ":")) + "\n")
            n += 1
    print("prepared %d items (%d space taps added, %d terminators snapped) -> %s" % (n, added, snapped, a.out))


if __name__ == "__main__":
    main()
