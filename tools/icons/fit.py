#!/usr/bin/env python3
"""Search Material Symbols variants (style x FILL x wght x GRAD x opsz, plus box size and position) for the best match
to Gboard's rendered icons, and score pboard's current path data the same way.

  tools/icons/py tools/icons/fit.py [--ref docs/ref/gboard18/set] [--only REGEX] [--jobs N] [--out tools/icons/out]

For each target in targets.py and each candidate glyph/style/FILL: a coarse axis grid, then a local refinement around
the best few points. Every candidate is fitted (box size and sub-pixel centre) to each reference sample and scored with
the soft-IoU metric of fitlib (mean over samples). Writes <out>/fit.json (all results) and prints a summary.
"""
import argparse
import itertools
import json
import multiprocessing as mp
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fitlib as F  # noqa: E402
import symlib as S  # noqa: E402
from targets import TARGETS  # noqa: E402


WGHT = [100, 200, 300, 400, 500, 600, 700]
GRAD = [-50, 0, 100, 200]
OPSZ = [20, 24, 32, 40, 48]

_targets = {}
REF = None


def targets_for(t):
    key = t['id']
    if key not in _targets:
        _targets[key] = [F.Target(F.load(os.path.join(REF, shot + '.png')), x, y, t['half']) for shot, x, y in t['samples']]
    return _targets[key]


def evaluate(t, cmds):
    res = [F.fit(cmds, tg) for tg in targets_for(t)]
    if any(r is None for r in res):
        return None
    n = len(res)
    return dict(
        score=sum(r['score'] for r in res) / n,
        bin=sum(r['bin'] for r in res) / n,
        box=sum(r['box'] for r in res) / n,
        cx=sum(r['cx'] for r in res) / n, cy=sum(r['cy'] for r in res) / n,
        stroke=sum(r['stats']['stroke'] for r in res) / n,
        w=sum(r['stats']['w'] for r in res) / n, h=sum(r['stats']['h'] for r in res) / n,
        per_sample=[round(r['score'], 4) for r in res],
    )


def search(job):
    t, glyph, style, fill = job
    seen = {}

    def ev(w, g, o):
        w = min(max(w, 100), 700); g = min(max(g, -50), 200); o = min(max(o, 20), 48)
        k = (w, g, o)
        if k not in seen:
            try:
                r = evaluate(t, S.glyph_commands(style, glyph, fill, w, g, o))
            except KeyError:
                r = None
            seen[k] = r
        return seen[k]

    for w, g, o in itertools.product(WGHT, GRAD, OPSZ):
        ev(w, g, o)
        if all(v is None for v in seen.values()):
            return []
    # Refine twice around the best three points with shrinking steps.
    for dw, dg, do in ((50, 50, 4), (25, 25, 2)):
        top = sorted((k for k in seen if seen[k]), key=lambda k: -seen[k]['score'])[:3]
        for w, g, o in top:
            for a, b, c in itertools.product((-dw, 0, dw), (-dg, 0, dg), (-do, 0, do)):
                ev(w + a, g + b, o + c)
    out = []
    for (w, g, o), r in seen.items():
        if r:
            out.append(dict(target=t['id'], glyph=glyph, style=style, fill=fill, wght=w, grad=g, opsz=o, **r))
    return out


def init(ref):
    global REF
    REF = ref


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ref', default=S.ref_dir('gboard18/set'))
    ap.add_argument('--only', default=None)
    ap.add_argument('--jobs', type=int, default=max(1, os.cpu_count() - 2))
    ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out'))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    init(a.ref)
    todo = [t for t in TARGETS if not a.only or re.search(a.only, t['id'])]
    jobs = [(t, g, s, f) for t in todo for g in t['glyphs'] for s in S.STYLES for f in t['fill']]
    t0 = time.time()
    results = []
    with mp.Pool(a.jobs, initializer=init, initargs=(a.ref,)) as pool:
        for i, r in enumerate(pool.imap_unordered(search, jobs)):
            results.extend(r)
            print(f'\r{i + 1}/{len(jobs)} jobs, {time.time() - t0:.0f}s', end='', file=sys.stderr)
    print(file=sys.stderr)
    baseline = {}
    for t in todo:
        shape = F.old_shape(t['old'])
        if shape is not None:
            baseline[t['id']] = evaluate(t, shape)
    path = os.path.join(a.out, 'fit.json' if not a.only else f'fit_{re.sub(r"[^a-z_]", "", a.only)}.json')
    with open(path, 'w') as fh:
        json.dump(dict(ref=a.ref, results=results, baseline=baseline), fh)
    print(f'wrote {path}')
    for t in todo:
        rs = sorted((r for r in results if r['target'] == t['id']), key=lambda r: -r['score'])
        b = baseline.get(t['id'])
        ts = targets_for(t)
        tst = sum(x.stats['stroke'] for x in ts) / len(ts)
        print(f"\n{t['id']}: target stroke {tst:.2f}; old {t['old']} {b['score']:.4f} (stroke {b['stroke']:.2f})"
              if b else f"\n{t['id']}: target stroke {tst:.2f}")
        for r in rs[:6]:
            print(f"  {r['score']:.4f} bin {r['bin']:.4f}  {r['glyph']:<20} {r['style']:<8} FILL{r['fill']} "
                  f"wght{r['wght']:>4} GRAD{r['grad']:>4} opsz{r['opsz']:>3}  box {r['box']:.1f} stroke {r['stroke']:.2f}")


if __name__ == '__main__':
    main()
