#!/usr/bin/env python3
"""Before/after crop grid: Gboard 18.6 | pboard before | pboard after for every target, one image per theme.

  tools/icons/py tools/icons/grid.py --before DIR --after DIR [--out tools/icons/out]

Crops come straight from the screenshots (same window for all three columns where pboard shows the icon at the same
place; pboard's own position otherwise). Icons the pboard capture doesn't show (header sticker/GIF with the phone's
toolbar order, the locked shift, the clipboard edit button) are rendered from the old / new shape at the fitted size,
marked "render". Each row is labelled with the drawn soft-IoU score before -> after (see score.py).
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fitlib as F  # noqa: E402
import symlib as S  # noqa: E402
from targets import TARGETS  # noqa: E402

ZOOM = 3
LABEL_W = 330


def crop(img, x, y, half):
    x0, y0 = int(round(x - half)), int(round(y - half))
    return img[y0:y0 + 2 * half, x0:x0 + 2 * half].astype(np.uint8)


def render_like(tg, shape, ref_target):
    """Renders `shape` fitted to `ref_target`, painted with tg's colours (fg over bg)."""
    r = F.fit(shape, ref_target)
    cov = r['render'][..., None]
    return (ref_target.bg * (1 - cov) + ref_target.fg * cov).astype(np.uint8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ref', default=S.ref_dir('gboard18/set'))
    ap.add_argument('--before', required=True)
    ap.add_argument('--after', required=True)
    ap.add_argument('--out', default=os.path.join(S.HERE, 'out'))
    a = ap.parse_args()
    for theme in ('light', 'dark'):
        rows = []
        for t in TARGETS:
            gs = [s for s in t['samples'] if s[0].startswith(theme)]
            if not gs:
                continue
            shot, x, y = gs[0]
            h = t['half']
            gimg = F.load(os.path.join(a.ref, shot + '.png'))
            gcrop = crop(gimg, x, y, h)
            gt = F.Target(gimg, x, y, h)
            cells = [gcrop]
            label = [t['id']]
            ps = [s for s in t['pboard'] if s[0] == shot] or [s for s in t['pboard'] if s[0].startswith(theme)]
            scores = []
            for d, shape in ((a.before, F.old_shape(t['old'])), (a.after, F.new_shape(t['const']))):
                path = os.path.join(d, (ps[0][0] if ps else shot) + '.png')
                if ps and os.path.exists(path):
                    pimg = F.load(path)
                    _, px, py = ps[0]
                    cells.append(crop(pimg, px, py, h))
                    pt = F.Target(pimg, px, py, h)
                    scores.append(F.compare(gt.cov, pt.cov)[0] if (px, py) == (x, y) else None)
                else:
                    cells.append(render_like(None, shape, gt))
                    scores.append('render')
            s = ' -> '.join('render' if v == 'render' else ('-' if v is None else f'{v:.3f}') for v in scores)
            label.append(s)
            rows.append((cells, label))
        cw = max(c.shape[1] for cells, _ in rows for c in cells) * ZOOM
        ch = [max(c.shape[0] for c in cells) * ZOOM for cells, _ in rows]
        W = LABEL_W + 3 * (cw + 6)
        H = 30 + sum(h + 6 for h in ch)
        bg = (255, 255, 255) if theme == 'light' else (24, 24, 24)
        fg = (0, 0, 0) if theme == 'light' else (230, 230, 230)
        im = Image.new('RGB', (W, H), bg)
        dr = ImageDraw.Draw(im)
        for i, name in enumerate(('Gboard 18.6', 'pboard before', 'pboard after')):
            dr.text((LABEL_W + i * (cw + 6) + 4, 8), name, fill=fg)
        yy = 30
        for (cells, label), rh in zip(rows, ch):
            dr.text((6, yy + rh // 2 - 14), label[0], fill=fg)
            dr.text((6, yy + rh // 2 + 2), 'drawn ' + label[1], fill=fg)
            for i, c in enumerate(cells):
                tile = Image.fromarray(c).resize((c.shape[1] * ZOOM, c.shape[0] * ZOOM), Image.NEAREST)
                im.paste(tile, (LABEL_W + i * (cw + 6), yy))
            yy += rh + 6
        out = os.path.join(a.out, f'grid_{theme}.png')
        im.save(out)
        print('wrote', out)


if __name__ == '__main__':
    main()
