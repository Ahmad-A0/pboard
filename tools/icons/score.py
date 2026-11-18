#!/usr/bin/env python3
"""Scores pboard's icons against Gboard 18.6 and measures where pboard draws them.

  tools/icons/py tools/icons/score.py --pboard DIR --shapes old|new [--json out.json]

DIR is a pboard capture of the same states on the same phone (tools/icons/phone_capture.sh). Per target:

  variant  the shape (old = previous Symbols.kt / hand-drawn shift, new = gen_symbols.ICONS) fitted freely to Gboard's
           pixels (best box and centre): how good the glyph itself is.
  drawn    pboard's screenshot crop compared directly with Gboard's crop at the same screen window (same soft IoU):
           what the user sees, including size and position errors. Only when pboard shows the icon at the same place.
  box/pos  the box and centre that fit Gboard (G) and the ones pboard actually draws (P, fitted on pboard's capture),
           in reference px (420 dpi m-units, as Dimens.m() takes them) - their difference is the layout correction.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fitlib as F  # noqa: E402
import symlib as S  # noqa: E402
from targets import TARGETS  # noqa: E402

PHONE_DENSITY = 2.75   # dev phone, 440 dpi
REF_DENSITY = 2.625    # Dimens.m() units (420 dpi)
TO_M = REF_DENSITY / PHONE_DENSITY


def mean(xs):
    xs = list(xs)
    return sum(xs) / len(xs) if xs else float('nan')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ref', default=S.ref_dir('gboard18/set'))
    ap.add_argument('--pboard', default=None)
    ap.add_argument('--shapes', choices=['old', 'new'], default='new')
    ap.add_argument('--only', default=None)
    ap.add_argument('--json', default=None)
    a = ap.parse_args()
    import re
    rows = []
    for t in TARGETS:
        if a.only and not re.search(a.only, t['id']):
            continue
        shape = F.old_shape(t['old']) if a.shapes == 'old' else F.new_shape(t['const'])
        if shape is None:
            rows.append(dict(id=t['id'], shape=t['old'], variant=None))
            continue
        gts = [F.Target(F.load(os.path.join(a.ref, s + '.png')), x, y, t['half']) for s, x, y in t['samples']]
        gf = [F.fit(shape, g) for g in gts]
        row = dict(id=t['id'], shape=t['old'] if a.shapes == 'old' else t['const'],
                   variant=mean(r['score'] for r in gf),
                   g_box=mean(r['box'] for r in gf) * TO_M,
                   g_cx=mean(r['cx'] for r in gf), g_cy=mean(r['cy'] for r in gf))
        if a.pboard and t['pboard']:
            pts = []
            for s, x, y in t['pboard']:
                p = os.path.join(a.pboard, s + '.png')
                if os.path.exists(p):
                    pts.append(((s, x, y), F.Target(F.load(p), x, y, t['half'])))
            if pts:
                pf = [F.fit(shape, pt) for _, pt in pts]
                row.update(p_box=mean(r['box'] for r in pf) * TO_M,
                           p_cx=mean(r['cx'] for r in pf), p_cy=mean(r['cy'] for r in pf))
                g_by_key = {k: g for k, g in zip(t['samples'], gts)}
                direct = [F.compare(g_by_key[k].cov, pt.cov)[0] for k, pt in pts if k in g_by_key]
                if direct:
                    row['drawn'] = mean(direct)
                # Offsets relative to each sample's nominal centre, so the same icon at another toolbar slot compares.
                g0x, g0y = mean(x for _, x, _ in t['samples']), mean(y for _, _, y in t['samples'])
                p0x, p0y = mean(k[1] for k, _ in pts), mean(k[2] for k, _ in pts)
                row['d_cx'] = ((row['g_cx'] - g0x) - (row['p_cx'] - p0x)) * TO_M
                row['d_cy'] = ((row['g_cy'] - g0y) - (row['p_cy'] - p0y)) * TO_M
        rows.append(row)

    def f(v, fmt):
        return format(v, fmt) if isinstance(v, float) and v == v else '-'

    print(f"{'target':<15} {'shape':<15} {'variant':>7} {'drawn':>7} {'G box':>7} {'P box':>7} {'dx':>6} {'dy':>6}  (m-units)")
    for r in rows:
        print(f"{r['id']:<15} {r['shape']:<15} {f(r.get('variant'), '.4f'):>7} {f(r.get('drawn'), '.4f'):>7} "
              f"{f(r.get('g_box'), '.1f'):>7} {f(r.get('p_box'), '.1f'):>7} {f(r.get('d_cx'), '+.1f'):>6} "
              f"{f(r.get('d_cy'), '+.1f'):>6}")
    if a.json:
        with open(a.json, 'w') as fh:
            json.dump(rows, fh, indent=1)


if __name__ == '__main__':
    main()
