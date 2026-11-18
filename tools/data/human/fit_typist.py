#!/usr/bin/env python3
"""Fit a realistic typist model from the human *tune* splits and write tools/data/human/typist_model.json.

Usage (repo root, after build.py):  python3 -I tools/data/human/fit_typist.py [--max-swipes 20000]

Only tune-split participants are used (held-out test participants never inform the synthetic generators).
Units: tap offsets are key-relative on the participant's own keyboard, in key pitches (x) and row pitches (y);
swipe quantities are measured after projection onto the Pixel-8 Gboard geometry, in target key widths (kw =
107 px) on both axes unless named *_kh (key heights = row pitch, 154.75 px).
"""
import argparse
import collections
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
from common import GRID, HERE, LETTERS, OUT, REPO, SPLITS, VERSION, load_target, read_jsonl, stable_hash

LEFT_HALF = set("qwertasdfgzxcvb")  # thumb-side proxy: keys left of the y/h/n column


# ------------------------------------------------------------------------------------------ stats

def q(vals, ps=(0.05, 0.25, 0.5, 0.75, 0.95)):
    v = sorted(vals)
    if not v:
        return {}
    out = {}
    for p in ps:
        k = (len(v) - 1) * p
        lo, hi = int(math.floor(k)), int(math.ceil(k))
        out["p%02d" % round(p * 100)] = round(v[lo] + (v[hi] - v[lo]) * (k - lo), 4)
    return out


def mean(v):
    return sum(v) / len(v) if v else float("nan")


def sd(v):
    if len(v) < 2:
        return float("nan")
    m = mean(v)
    return math.sqrt(sum((x - m) ** 2 for x in v) / (len(v) - 1))


def mad_sigma(v):
    m = q(v, (0.5,))["p50"]
    return 1.4826 * q([abs(x - m) for x in v], (0.5,))["p50"]


def kurtosis(v):
    m, s = mean(v), sd(v)
    return sum(((x - m) / s) ** 4 for x in v) / len(v) - 3.0 if s > 0 else float("nan")


def cov2(xs, ys):
    mx, my = mean(xs), mean(ys)
    n = len(xs) - 1
    sxx = sum((x - mx) ** 2 for x in xs) / n
    syy = sum((y - my) ** 2 for y in ys) / n
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / n
    return [[r4(sxx), r4(sxy)], [r4(sxy), r4(syy)]]


def r4(x):
    return round(x, 4) if isinstance(x, float) and math.isfinite(x) else x


def lognormal(v):
    lv = [math.log(x) for x in v if x > 0]
    return {"mu": r4(mean(lv)), "sigma": r4(sd(lv)), "median": r4(math.exp(mean(lv)))}


def solve(A, b):
    """Gaussian elimination for small dense systems."""
    n = len(b)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(M[r][c]))
        M[c], M[p] = M[p], M[c]
        for r in range(n):
            if r != c and M[c][c] != 0:
                f = M[r][c] / M[c][c]
                M[r] = [a - f * bb for a, bb in zip(M[r], M[c])]
    return [M[i][n] / M[i][i] for i in range(n)]


def ols(X, y):
    k = len(X[0])
    A = [[sum(r[i] * r[j] for r in X) for j in range(k)] for i in range(k)]
    b = [sum(r[i] * yy for r, yy in zip(X, y)) for i in range(k)]
    return solve(A, b)


def huber_ols(X, y, iters=8):
    """Iteratively reweighted least squares with Huber weights (robust to the long right tail)."""
    beta = ols(X, y)
    for _ in range(iters):
        res = [yy - sum(bi * xi for bi, xi in zip(beta, r)) for r, yy in zip(X, y)]
        s = 1.4826 * q([abs(e) for e in res], (0.5,))["p50"] or 1.0
        w = [1.0 if abs(e) <= 1.345 * s else 1.345 * s / abs(e) for e in res]
        k = len(X[0])
        A = [[sum(wi * r[i] * r[j] for r, wi in zip(X, w)) for j in range(k)] for i in range(k)]
        b = [sum(wi * r[i] * yy for r, yy, wi in zip(X, y, w)) for i in range(k)]
        beta = solve(A, b)
    return beta


# ------------------------------------------------------------------------------------------- taps

def fit_taps(items, name):
    """items: tap traces of one source (tune split)."""
    by_key = collections.defaultdict(lambda: ([], []))
    by_part = collections.defaultdict(lambda: ([], []))
    allx, ally = [], []
    space_x, space_y = [], []
    iki, iki_rep, iki_space, iki_x = [], [], [], []
    holds = []
    lit_err = lit_n = 0
    words = words_err = corrected = term_n = term_miss = 0
    tap_count_delta = collections.Counter()
    wpm_part = collections.defaultdict(lambda: [0, 0.0])
    for it in items:
        offs = it["offsetsKu"]
        keys = it["intended"]
        pts = it["points"]
        words += 1
        typed = it["literal"][:-1]
        words_err += typed != it["target"].lower()
        if it.get("terminator") in ("space", "period"):
            term_n += 1
            term_miss += it["literal"][-1] not in " ."
        corrected += bool(it.get("correctedInWord"))
        if it["src"] == "vtap":
            tap_count_delta[max(-2, min(2, len(pts) - 1 - len(it["intended"].strip())))] += 1
        for k, o in enumerate(offs):
            if o is None:
                continue
            key = keys[k]
            du, dv = o
            if key == " ":
                space_x.append(du)
                space_y.append(dv)
                continue
            by_key[key][0].append(du)
            by_key[key][1].append(dv)
            if key in LETTERS:
                allx.append(du)
                ally.append(dv)
                by_part[it["participant"]][0].append(du)
                by_part[it["participant"]][1].append(dv)
                lit_n += 1
                lit_err += it["literal"][k] != key
        # Inter-key intervals between consecutive real taps (synthetic terminators excluded).
        real = len(pts) - (1 if it.get("terminator") == "synthetic" else 0)
        for k in range(1, real if it.get("alignedTaps", True) else 0):  # key pairs need 1:1 tap alignment
            dt = pts[k][2] - pts[k - 1][2]
            if dt <= 0 or dt > 3000:
                continue
            a, b = keys[k - 1] if k - 1 < len(keys) else None, keys[k] if k < len(keys) else None
            if b == " ":
                iki_space.append(dt)
            elif a == b:
                iki_rep.append(dt)
            elif a in GRID and b in GRID:
                d = math.hypot(GRID[a][0] - GRID[b][0], (GRID[a][1] - GRID[b][1]) * 1.45)  # ~key widths
                iki.append(dt)
                iki_x.append((d, (a in LEFT_HALF) != (b in LEFT_HALF), dt))
        if real >= 2:
            wpm_part[it["participant"]][0] += real
            wpm_part[it["participant"]][1] += pts[real - 1][2] - pts[0][2]
        hm = it.get("holdMs") or []
        for k, h in enumerate(hm[:real]):  # synthetic terminator holds are excluded
            if h is not None and 0 < h < 1000:
                holds.append(h)

    per_key = {}
    for key in sorted(by_key):
        xs, ys = by_key[key]
        if len(xs) >= 20:
            per_key[key] = {"n": len(xs), "mean": [r4(mean(xs)), r4(mean(ys))], "cov": cov2(xs, ys)}
    rows = {}
    for r, row in enumerate(["qwertyuiop", "asdfghjkl", "zxcvbnm"]):
        xs = [x for k in row for x in by_key[k][0]]
        ys = [y for k in row for y in by_key[k][1]]
        rows[str(r)] = {"n": len(xs), "mean": [r4(mean(xs)), r4(mean(ys))], "sd": [r4(sd(xs)), r4(sd(ys))]}
    cols = {}
    for side, ks in (("left_edge_qaz", "qaz"), ("centre_tgyhb", "tgyhb"), ("right_edge_plm", "plm")):
        xs = [x for k in ks for x in by_key[k][0]]
        cols[side] = {"n": len(xs), "mean_dx": r4(mean(xs))}
    pm = {p: (mean(v[0]), mean(v[1]), sd(v[0]), sd(v[1])) for p, v in by_part.items() if len(v[0]) >= 50}
    within_x = mean([v[2] ** 2 for v in pm.values()])
    within_y = mean([v[3] ** 2 for v in pm.values()])
    between_x = sd([v[0] for v in pm.values()]) ** 2
    between_y = sd([v[1] for v in pm.values()]) ** 2
    r2 = [math.hypot(x, y * 1.45) for x, y in zip(allx, ally)]
    # Distance dependence of IKI (Fitts-style) and hand alternation, robust fit dt = a + b*log2(1+d) + c*cross.
    X = [[1.0, math.log2(1 + d), 1.0 if cross else 0.0] for d, cross, _ in iki_x]
    y = [dt for _, _, dt in iki_x]
    beta = huber_ols(X, y) if len(X) > 50 else [float("nan")] * 3
    cross_iki = [dt for d, c, dt in iki_x if c]
    same_iki = [dt for d, c, dt in iki_x if not c]
    wpm = [60000.0 * n / 5.0 / t for n, t in wpm_part.values() if t > 0 and n > 20]
    out = {
        "participants": len(by_part), "words": words, "letterTaps": len(allx),
        "offsetUnits": "dx in key pitches of the intended key, dy in row pitches (participant's own keyboard)",
        "letters": {
            "mean": [r4(mean(allx)), r4(mean(ally))], "sd": [r4(sd(allx)), r4(sd(ally))],
            "cov": cov2(allx, ally), "robustSigma_MAD": [r4(mad_sigma(allx)), r4(mad_sigma(ally))],
            "excessKurtosis": [r4(kurtosis(allx)), r4(kurtosis(ally))],
            "outsideOwnKey": r4(sum(abs(x) > 0.5 or abs(y) > 0.5 for x, y in zip(allx, ally)) / len(allx)),
            "beyondOneKeyWidth": r4(sum(d > 1.0 for d in r2) / len(r2)),
            "radialQuantiles_kw": q(r2),
            "byRow": rows, "edgeColumns": cols,
            "betweenParticipant": {
                "participantsWith50Taps": len(pm),
                "sdOfParticipantMeans": [r4(math.sqrt(between_x)), r4(math.sqrt(between_y))],
                "medianWithinParticipantSd": [r4(q([v[2] for v in pm.values()], (0.5,))["p50"]),
                                              r4(q([v[3] for v in pm.values()], (0.5,))["p50"])],
                "withinParticipantSdQuantiles_x": q([v[2] for v in pm.values()]),
                "withinParticipantSdQuantiles_y": q([v[3] for v in pm.values()]),
                "betweenShareOfVariance": [r4(between_x / (between_x + within_x)), r4(between_y / (between_y + within_y))],
            },
        },
        "perKey": per_key,
        "space": {"n": len(space_x), "dxFractionOfBarWidth": {"mean": r4(mean(space_x)), "sd": r4(sd(space_x))},
                  "dyRowPitch": {"mean": r4(mean(space_y)), "sd": r4(sd(space_y))}} if space_x else None,
        "timing": {
            "ikiMs": dict(q(iki), **lognormal(iki), n=len(iki)),
            "ikiSameKeyRepeatMs": dict(q(iki_rep), n=len(iki_rep)),
            "ikiLetterToSpaceMs": dict(q(iki_space), n=len(iki_space)) if iki_space else None,
            "ikiModel": {"form": "iki = a + b*log2(1 + d_kw) + c*crossHalves (Huber IRLS)",
                         "a": r4(beta[0]), "b": r4(beta[1]), "c": r4(beta[2])},
            "ikiSameHalfMedianMs": q(same_iki, (0.5,)).get("p50"), "ikiCrossHalfMedianMs": q(cross_iki, (0.5,)).get("p50"),
            "holdMs": dict(q(holds), **lognormal(holds), n=len(holds)) if holds else None,
            "wpmPerParticipant": q(wpm),
        },
        "errors": {
            "letterTapLandsOnOtherKey": r4(lit_err / lit_n) if lit_n else None,
            "wordsWhoseTouchedKeysMisspellTarget": r4(words_err / words),
            "wordsCorrectedInWord": r4(corrected / words) if name == "tsi" else None,
            "terminatorTapNotOnSpaceOrPeriod": r4(term_miss / term_n) if term_n else None,
            "tapCountMinusLetters": {str(k): v for k, v in sorted(tap_count_delta.items())} if tap_count_delta else None,
        },
    }
    return out


# ----------------------------------------------------------------------------------------- swipes

def ideal_keys(target):
    ks = [c for c in target.lower() if c in LETTERS]
    return [c for i, c in enumerate(ks) if i == 0 or c != ks[i - 1]]


def align(path, centres):
    """Monotone alignment of key centres to path samples minimising summed distance (DP)."""
    K, N = len(centres), len(path)
    INF = float("inf")
    prev = [math.dist(centres[0], p) for p in path]
    back = []
    for k in range(1, K):
        best, arg = INF, 0
        cur, bk = [INF] * N, [0] * N
        for j in range(N):
            if prev[j] < best:
                best, arg = prev[j], j
            cur[j] = best + math.dist(centres[k], path[j])
            bk[j] = arg
        back.append(bk)
        prev = cur
    j = min(range(N), key=lambda i: prev[i])
    idx = [j]
    for k in range(K - 2, -1, -1):
        j = back[k][j]
        idx.append(j)
    return idx[::-1]


def fit_swipes(items, dst, name):
    kw, kh = dst.px, dst.py
    U = lambda x, y: ((x - dst.ox) / kw, (y - dst.oy) / kw)  # isotropic target key widths
    centre_kw = {c: U(*dst.centres[c]) for c in LETTERS}
    dur, L_ideal, L_actual, nseg, speeds = [], [], [], [], []
    start_off, end_off, start_dist, end_dist, over_end, over_start = [], [], [], [], [], []
    dwell_start, dwell_end, dts = [], [], []
    cut_frac, cut_perp, miss, pass_lat = [], [], [], []
    prof_bins = [[] for _ in range(20)]
    X, Y, kept = [], [], []
    for it in items:
        keys = ideal_keys(it["target"])
        if len(keys) < 2:
            continue
        P = [U(x, y) for x, y, _ in it["points"]]
        T = [t for _, _, t in it["points"]]
        if T[-1] <= 0 or len(P) < 4:
            continue
        C = [centre_kw[c] for c in keys]
        li = sum(math.dist(a, b) for a, b in zip(C, C[1:]))
        la = sum(math.dist(a, b) for a, b in zip(P, P[1:]))
        if li < 0.3:
            continue
        kept.append(it)
        dur.append(T[-1])
        L_ideal.append(li)
        L_actual.append(la / li)
        nseg.append(len(keys) - 1)
        speeds.append(la / T[-1] * 100.0)
        X.append([1.0, len(keys) - 1, li])
        Y.append(T[-1])
        dts += [b - a for a, b in zip(T, T[1:]) if b > a][:30]
        # start / end
        sx, sy = P[0][0] - C[0][0], P[0][1] - C[0][1]
        ex, ey = P[-1][0] - C[-1][0], P[-1][1] - C[-1][1]
        start_off.append((sx, sy * kw / kh))
        end_off.append((ex, ey * kw / kh))
        start_dist.append(math.hypot(sx, sy))
        end_dist.append(math.hypot(ex, ey))
        d0 = (C[1][0] - C[0][0], C[1][1] - C[0][1])
        n0 = math.hypot(*d0) or 1
        over_start.append(-(sx * d0[0] + sy * d0[1]) / n0)  # >0: started before (outside) the first key
        d1 = (C[-1][0] - C[-2][0], C[-1][1] - C[-2][1])
        n1 = math.hypot(*d1) or 1
        over_end.append((ex * d1[0] + ey * d1[1]) / n1)  # >0: ran past the last key
        # dwell: time until moved 0.2 kw from the first sample / from reaching the final position
        j = next((k for k in range(len(P)) if math.dist(P[k], P[0]) > 0.2), len(P) - 1)
        dwell_start.append(T[j])
        j = next((k for k in range(len(P) - 1, -1, -1) if math.dist(P[k], P[-1]) > 0.2), 0)
        dwell_end.append(T[-1] - T[j])
        # speed profile over normalised time
        for k in range(1, len(P)):
            dt = T[k] - T[k - 1]
            if dt <= 0:
                continue
            b = min(19, int(20 * (T[k] + T[k - 1]) / 2 / T[-1]))
            prof_bins[b].append(math.dist(P[k], P[k - 1]) / dt * T[-1] / max(la, 1e-6))
        # interior keys: corner cutting / misses
        if len(keys) >= 3:
            idx = align(P, C)
            for k in range(1, len(keys) - 1):
                c = C[k]
                p = P[idx[k]]
                a, b = C[k - 1], C[k + 1]
                m = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                v_in = (c[0] - a[0], c[1] - a[1])
                v_out = (b[0] - c[0], b[1] - c[1])
                ni, no = math.hypot(*v_in), math.hypot(*v_out)
                if ni < 1e-6 or no < 1e-6:
                    continue
                turn = math.degrees(math.acos(max(-1, min(1, (v_in[0] * v_out[0] + v_in[1] * v_out[1]) / ni / no))))
                dist = math.dist(p, c)
                miss.append(dist > 0.5)
                if turn >= 45:
                    cm = (m[0] - c[0], m[1] - c[1])
                    ncm = math.hypot(*cm)
                    if ncm > 0.2:
                        f = ((p[0] - c[0]) * cm[0] + (p[1] - c[1]) * cm[1]) / ncm ** 2
                        cut_frac.append(f)
                        cut_perp.append(((p[0] - c[0]) * -cm[1] + (p[1] - c[1]) * cm[0]) / ncm)
                elif turn <= 20:
                    pass_lat.append(((p[0] - c[0]) * -v_in[1] + (p[1] - c[1]) * v_in[0]) / ni)
    beta = huber_ols(X, Y)
    by_part = collections.defaultdict(list)
    for it, sp in zip(kept, speeds):
        by_part[it["participant"]].append(sp)
    part_speed = [q(v, (0.5,))["p50"] for v in by_part.values() if len(v) >= 10]
    sxs = [o[0] for o in start_off]
    sys_ = [o[1] for o in start_off]
    exs = [o[0] for o in end_off]
    eys = [o[1] for o in end_off]
    hz = [1000.0 / d for d in dts if d > 0]
    return {
        "swipes": len(dur), "participants": len({it["participant"] for it in items}),
        "durationMs": q(dur), "idealPathKw": q(L_ideal), "pathLengthRatio": q(L_actual),
        "meanSpeedKwPer100ms": q(speeds),
        "participantMedianSpeedKwPer100ms": dict(q(part_speed), participants=len(part_speed)),
        "durationModel": {"form": "durationMs = a + b*segments + c*idealPathKw (Huber IRLS)",
                          "a": r4(beta[0]), "b": r4(beta[1]), "c": r4(beta[2]),
                          "impliedCruiseSpeedKwPer100ms": r4(100.0 / beta[2]) if beta[2] > 0 else None},
        "speedProfile": {"bins": 20, "unit": "speed / (pathLength/duration), median per normalised-time bin",
                         "median": [r4(q(b, (0.5,)).get("p50", float("nan"))) for b in prof_bins]},
        "start": {"offsetMean_kw_kh": [r4(mean(sxs)), r4(mean(sys_))], "offsetCov_kw_kh": cov2(sxs, sys_),
                  "distanceKw": q(start_dist), "alongFirstSegmentBeforeKeyKw": q(over_start),
                  "dwellMs": q(dwell_start)},
        "end": {"offsetMean_kw_kh": [r4(mean(exs)), r4(mean(eys))], "offsetCov_kw_kh": cov2(exs, eys),
                "distanceKw": q(end_dist), "overshootAlongLastSegmentKw": q(over_end), "dwellMs": q(dwell_end)},
        "corners": {"n": len(cut_frac),
                    "cutFractionTowardNeighbourMidpoint": dict(q(cut_frac), mean=r4(mean(cut_frac))),
                    "perpendicularOffsetKw_sd": r4(sd(cut_perp)),
                    "passThroughLateralOffsetKw_sd": r4(sd(pass_lat)), "passThroughN": len(pass_lat),
                    "interiorKeysMissedBeyondHalfKw": r4(sum(miss) / len(miss)) if miss else None},
        "samplingHz": q(hz),
    }


# ------------------------------------------------------------------------------------------- main

def load(path, limit=None):
    items = list(read_jsonl(path))
    if limit and len(items) > limit:
        items = sorted(items, key=lambda it: stable_hash("fit", it["id"]))[:limit]
    return items


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--max-swipes", type=int, default=20000)
    ap.add_argument("--out", default=os.path.join(HERE, "typist_model.json"))
    a = ap.parse_args()
    dst = load_target()
    sdir, odir = os.path.join(SPLITS, VERSION), os.path.join(OUT, VERSION)
    used = []
    tap = {}
    for src in ("tsi", "vtap"):
        p = os.path.join(sdir, "tap_%s.tune.jsonl.gz" % src)
        used.append(os.path.relpath(p, REPO))
        tap[src] = fit_taps(load(p), src)
        print("taps", src, "done", file=sys.stderr)
    swipe = {}
    for src in ("futo", "hws"):
        p = os.path.join(odir, "swipe_%s_full.tune.jsonl.gz" % src)
        if not os.path.exists(p):
            p = os.path.join(sdir, "swipe_%s.tune.jsonl.gz" % src)
        used.append(os.path.relpath(p, REPO))
        swipe[src] = fit_swipes(load(p, a.max_swipes), dst, src)
        print("swipes", src, "done", file=sys.stderr)
    model = {"format": "pboard-typist-model/1", "version": VERSION, "fittedOn": used,
             "note": "tune splits only; see docs/eval-data.md for interpretation",
             "targetGeometry": dst.meta, "targetKeyPx": [dst.px, dst.py], "targetSpaceWidthPx": dst.specials["space"][2],
             "tap": tap, "swipe": swipe}
    model["genTracesArgs"] = recommend(model)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=1)
        f.write("\n")
    print("wrote", a.out, file=sys.stderr)


def recommend(m):
    """Map the fitted statistics onto tools/bench/gen_traces.py parameters (tap: TSI = Gboard on a Pixel;
    swipe: FUTO). Only what gen_traces.py v1 can express; the 'extensions' block lists what it cannot."""
    t = m["tap"]["tsi"]
    aspect = m["targetKeyPx"][1] / m["targetKeyPx"][0]
    wx = t["letters"]["betweenParticipant"]["withinParticipantSdQuantiles_x"]
    tim = t["timing"]
    sw = m["swipe"]["futo"]
    cut = sw["corners"]["cutFractionTowardNeighbourMidpoint"]
    dm = sw["durationModel"]
    overlap = 0.3  # gen_traces default; per-segment and per-kw costs below assume it is kept
    return {
        "_comment": "Flags of tools/bench/gen_traces.py. sigma is per-word isotropic sd in key widths (human "
                    "y-spread in key widths ~= x-spread). iki_* reproduce the median and spread; human IKIs show "
                    "no Fitts distance effect for two-thumb typing (see extensions.handAlternation). "
                    "seg_base/swipe_speed are solved from durationModel assuming overlap=0.3.",
        "sigma": [wx.get("p05"), wx.get("p95")],
        "bias_y": r4(t["letters"]["mean"][1] * aspect),
        "iki_base": tim["ikiMs"]["median"], "iki_slope": 0.0, "iki_jitter": tim["ikiMs"]["sigma"],
        "iki_min": tim["ikiMs"].get("p05"), "iki_max": tim["ikiMs"].get("p95"),
        "hold_mean": (m["tap"]["vtap"]["timing"]["holdMs"] or {}).get("median"),
        "space_sigma_x": r4(t["space"]["dxFractionOfBarWidth"]["sd"] * m["targetSpaceWidthPx"] / m["targetKeyPx"][0]),
        "hold_sd": r4((m["tap"]["vtap"]["timing"]["holdMs"] or {}).get("median", 0) * (m["tap"]["vtap"]["timing"]["holdMs"] or {}).get("sigma", 0)),
        "swipe_sigma": [r4(sw["corners"]["passThroughLateralOffsetKw_sd"] * 0.8), r4(sw["corners"]["perpendicularOffsetKw_sd"] * 1.2)],
        "corner_cut": r4(2 * cut["mean"]),
        # The generator's minimum-jerk bells already add slow start/end phases, so the per-segment base and the
        # dwell are set below the raw fit; checked on 2026-11-08: gen_traces.py with these values gives a median
        # swipe of 784 ms and 162 ms per extra segment vs. FUTO tune 797 ms / 183 ms, corner-cut fraction mean
        # 0.059 vs 0.049, interior keys missed by >0.5 kw 12 % vs 16 % (defaults: 542 ms / 35 ms, 0.14, 42 %).
        "seg_base": r4(0.88 * dm["b"] / (1 - overlap)),
        "swipe_speed": [r4(100.0 * (1 - overlap) / dm["c"] * f) for f in (0.6, 1.4)] if dm["c"] > 0 else None,
        "overlap": overlap,
        "swipe_dwell": 10.0,
        "hz": [60, 90, 120],
        "extensions": {
            "perKeyMeanOffset": "tap.tsi.perKey[*].mean (key pitches / row pitches); edge keys a/z/q pulled inward",
            "perParticipantBias_sd": t["letters"]["betweenParticipant"]["sdOfParticipantMeans"],
            "handAlternation": {"sameHalfMedianMs": tim["ikiSameHalfMedianMs"], "crossHalfMedianMs": tim["ikiCrossHalfMedianMs"],
                                "sameKeyRepeatMedianMs": tim["ikiSameKeyRepeatMs"].get("p50")},
            "spaceTap": t["space"],
            "spaceTapMeanShiftKw": r4(t["space"]["dxFractionOfBarWidth"]["mean"] * m["targetSpaceWidthPx"] / m["targetKeyPx"][0]),
            "swipeEndOvershootKw": sw["end"]["overshootAlongLastSegmentKw"],
            "swipeEndOffsetCov_kw_kh": sw["end"]["offsetCov_kw_kh"],
            "swipeStartOffsetCov_kw_kh": sw["start"]["offsetCov_kw_kh"],
            "swipeEndDwellMs": sw["end"]["dwellMs"].get("p50"),
            "swipeSpeedProfile": sw["speedProfile"]["median"],
            "participantSwipeSpeedKwPer100ms": sw["participantMedianSpeedKwPer100ms"],
        },
    }


if __name__ == "__main__":
    main()
