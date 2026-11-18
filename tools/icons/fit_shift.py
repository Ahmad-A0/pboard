#!/usr/bin/env python3
"""Tunes the hand-drawn shift arrow (Icons.drawShift) against Gboard 18.6's three shift states, and compares it with
the best Material Symbols candidates (fit.py's search results, if present).

  tools/icons/py tools/icons/fit_shift.py

State 0 (outline): stroke width and glyph width w (free centre). States 1 (filled) and 2 (locked) are then fitted with w
and the centre fixed to state 0's, as drawShift draws all three from the same key centre and width: filled-arrow scale,
and for the lock the lift, bar gap and bar stroke. All in units of w/60 like the Kotlin code.
"""
import itertools
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fitlib as F  # noqa: E402
import symlib as S  # noqa: E402

REF = S.ref_dir('gboard18/set')
TO_M = 2.625 / 2.75
X, Y, HALF = 85, 2042, 46


def tgt(shot):
    return F.Target(F.load(os.path.join(REF, shot + '.png')), X, Y, HALF)


def main():
    off = [tgt('light_qwerty_lower'), tgt('dark_qwerty_lower')]
    on = [tgt('light_text_autocap'), tgt('dark_text_autocap')]
    lock = [tgt('light_qwerty_capslock')]

    def mean_fit(shape, ts, **kw):
        rs = [F.fit(shape, t, **kw) for t in ts]
        return sum(r['score'] for r in rs) / len(rs), rs

    old = {s: mean_fit(F.HandShift(s), ts)[0] for s, ts in ((0, off), (1, on), (2, lock))}
    print('old hand-drawn (free box/centre):', {k: round(v, 4) for k, v in old.items()})

    best = None
    for stroke in [4.4 + 0.2 * i for i in range(-2, 10)]:
        sc, rs = mean_fit(F.HandShift(0, stroke=stroke), off)
        if best is None or sc > best[0]:
            best = (sc, stroke, rs)
    sc0, stroke, rs0 = best
    w = rs0[0]['box']
    c = [(r['cx'], r['cy']) for r in rs0]
    print(f'state 0: stroke {stroke:.1f}, w {w * TO_M:.2f} m, score {sc0:.4f}; centre {c[0][0]:.2f},{c[0][1]:.2f}')

    def fixed(shape, ts):
        rs = [F.fit(shape, t, box_fixed=w, centre_fixed=c[0]) for t in ts]
        return sum(r['score'] for r in rs) / len(rs)

    best1 = max(((fixed(F.HandShift(1, stroke=stroke, on_scale=k), on), k)
                 for k in [0.86 + 0.01 * i for i in range(16)]))
    print(f'state 1: on_scale {best1[1]:.2f}, score {best1[0]:.4f}')
    free1 = mean_fit(F.HandShift(1, stroke=stroke, on_scale=best1[1]), on)[1]
    print(f'         free-centre check: dy {free1[0]["cy"] - c[0][1]:+.2f} px, box {free1[0]["box"] / w:.3f} w')

    grid = itertools.product([best1[1] - 0.02, best1[1], best1[1] + 0.02], [4.0, 5.0, 6.0, 7.0], [8.0, 9.0, 10.0, 11.0],
                             [4.4, 5.0, 5.6])
    best2 = max((fixed(F.HandShift(2, stroke=stroke, lock_scale=ls, lift=lf, gap=g, bar=b), lock), ls, lf, g, b)
                for ls, lf, g, b in grid)
    sc2, ls, lf, g, b = best2
    # Refine
    for _ in range(8):
        for d in itertools.product([-0.01, 0, 0.01], [-0.5, 0, 0.5], [-0.5, 0, 0.5], [-0.3, 0, 0.3]):
            cand = (ls + d[0], lf + d[1], g + d[2], b + d[3])
            s = fixed(F.HandShift(2, stroke=stroke, lock_scale=cand[0], lift=cand[1], gap=cand[2], bar=cand[3]), lock)
            if s > sc2:
                sc2, (ls, lf, g, b) = s, cand
    print(f'state 2: lock_scale {ls:.2f}, lift {lf:.1f}, gap {g:.1f}, bar {b:.1f}, score {sc2:.4f}')
    # Same comparison with old geometry at the fixed width/centre (as pboard draws all three states).
    print('old geometry at that w/centre:', round(fixed(F.HandShift(0), off), 4), round(fixed(F.HandShift(1), on), 4),
          round(fixed(F.HandShift(2), lock), 4))
    print('Material Symbols best (free box/centre): shift FILL0 / shift FILL1 / shift_lock FILL1 - see fit.py output')


if __name__ == '__main__':
    main()
