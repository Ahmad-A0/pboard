"""Target extraction and candidate fitting/scoring for icon variant selection.

Coverage maps: a reference crop becomes per-pixel ink coverage in 0..1 by projecting each pixel colour onto the
background->ink colour axis (so anti-aliasing is kept as partial coverage, independent of theme colours). Candidates are
rasterised with Skia (as Android does) at a fitted box size and centre, and compared with:

  score  soft IoU of the two coverage maps after a small Gaussian blur (sigma 0.6 px):
         sum(min(a, b)) / sum(max(a, b)). 1.0 = identical; tolerant to sub-pixel AA differences.
  bin    IoU of the binarised (>= 0.5) masks, each blurred (sigma 0.6 px) - the plain "ink mask" view.
  stroke mean stroke width estimate 2 * area / perimeter (perimeter = sum of |grad coverage|), px.
  bbox   ink bounding box (w, h) from coverage-weighted extents, px.
"""
import os

import numpy as np
from PIL import Image
from scipy import ndimage

import symlib as S

SIGMA = 0.6
_img_cache = {}


def load(path):
    a = _img_cache.get(path)
    if a is None:
        a = np.asarray(Image.open(path).convert('RGB')).astype(np.float32)
        _img_cache[path] = a
    return a


class Target:
    """Ink coverage of one reference icon. `x0, y0` = screen position of the window's top-left corner."""

    def __init__(self, img, cx, cy, half, keep_border=False):
        x0, y0 = int(round(cx - half)), int(round(cy - half))
        crop = img[y0:y0 + 2 * half, x0:x0 + 2 * half]
        self.x0, self.y0 = x0, y0
        self.w = self.h = 2 * half
        q = (crop // 4).astype(np.int32)
        keys = (q[..., 0] << 16) | (q[..., 1] << 8) | q[..., 2]
        vals, counts = np.unique(keys, return_counts=True)
        mode = vals[np.argmax(counts)]
        bg = crop[keys == mode].mean(axis=0)
        dist = np.linalg.norm(crop - bg, axis=2)
        fg = crop[dist >= np.percentile(dist, 99.5)].mean(axis=0)
        axis = fg - bg
        cov = ((crop - bg) @ axis) / float(axis @ axis)
        cov = np.clip(cov, 0, 1)
        # Keep only components that do not touch the window edge (neighbouring glyphs, pill outlines).
        lab, n = ndimage.label(cov > 0.08, structure=np.ones((3, 3)))
        keep = np.zeros(n + 1, bool)
        for i in range(1, n + 1):
            ys, xs = np.nonzero(lab == i)
            touches = ys.min() == 0 or xs.min() == 0 or ys.max() == self.h - 1 or xs.max() == self.w - 1
            keep[i] = keep_border or not touches
        mask = keep[lab]
        mask = ndimage.binary_dilation(mask, iterations=1)
        self.cov = (cov * mask).astype(np.float32)
        self.bg, self.fg = bg, fg
        self.stats = stats(self.cov)


def blur(a):
    return ndimage.gaussian_filter(a, SIGMA, mode='constant')


def stats(c):
    area = float(c.sum())
    gy, gx = np.gradient(c)
    per = float(np.hypot(gx, gy).sum())
    ys, xs = np.nonzero(c > 0.5)
    if len(xs) == 0:
        return dict(area=area, stroke=0, cx=0, cy=0, w=0, h=0)
    yy, xx = np.mgrid[0:c.shape[0], 0:c.shape[1]]
    cx = float((c * xx).sum() / area)
    cy = float((c * yy).sum() / area)
    # Sub-pixel extents: sum of coverage on the outermost rows/cols beyond the solid core.
    colsum = c.max(axis=0)
    rowsum = c.max(axis=1)
    w = float(colsum.sum())
    h = float(rowsum.sum())
    return dict(area=area, stroke=2 * area / per if per else 0, cx=cx, cy=cy, w=w, h=h)


def compare(t, c):
    a, b = blur(t), blur(c)
    soft = float(np.minimum(a, b).sum() / max(np.maximum(a, b).sum(), 1e-6))
    ba, bb = blur((t >= 0.5).astype(np.float32)), blur((c >= 0.5).astype(np.float32))
    binv = float(np.minimum(ba, bb).sum() / max(np.maximum(ba, bb).sum(), 1e-6))
    return soft, binv


def fit(cmds, target, scale_range=(0.80, 1.25), box_fixed=None, centre_fixed=None):
    """Best box and centre for `cmds` against `target`. Returns dict(score, bin, box, cx, cy (screen px), stats)."""
    shape = cmds if hasattr(cmds, 'render') else CmdShape(cmds)
    bx0, by0, bx1, by1 = shape.bounds()
    gw, gh = (bx1 - bx0) / S.EM, (by1 - by0) / S.EM
    ts = target.stats
    if box_fixed:
        box0 = box_fixed
    else:
        box0 = 0.5 * (ts['w'] / max(gw, 1e-3) + ts['h'] / max(gh, 1e-3))
    W, H = target.w, target.h
    tcx, tcy = ts['cx'], ts['cy']

    def place(box, cx=None, cy=None):
        """Render with the glyph ink centroid aligned to the target's (unless the centre is given)."""
        if cx is None:
            # Ink centroid in viewport fraction, measured once per box at a neutral position.
            r = shape.render(box, W / 2, H / 2, W, H)
            st = stats(r)
            if st['area'] == 0:
                return None, None, None
            cx = W / 2 + (tcx - st['cx'])
            cy = H / 2 + (tcy - st['cy'])
        return shape.render(box, cx, cy, W, H), cx, cy

    best = None
    if box_fixed:
        boxes = [box_fixed]
    else:
        boxes = []
        lo, hi = scale_range
        f = lo
        while f <= hi + 1e-9:
            boxes.append(box0 * f)
            f += 0.02
    for box in boxes:
        if centre_fixed:
            cx, cy = centre_fixed[0] - target.x0, centre_fixed[1] - target.y0
            r, _, _ = place(box, cx, cy)
        else:
            r, cx, cy = place(box)
        if r is None:
            continue
        s, b = compare(target.cov, r)
        if best is None or s > best[0]:
            best = (s, b, box, cx, cy)
    if best is None:
        return None
    s, b, box, cx, cy = best
    # Refine: finer box steps, then a sub-pixel offset grid.
    if not box_fixed:
        for f in np.arange(-0.012, 0.0121, 0.004):
            bb = box * (1 + f)
            r, ccx, ccy = place(bb) if not centre_fixed else place(bb, cx, cy)
            if r is None:
                continue
            s2, b2 = compare(target.cov, r)
            if s2 > s:
                s, b, box, cx, cy = s2, b2, bb, ccx, ccy
    if not centre_fixed:
        base = (cx, cy)
        for dx in np.arange(-0.75, 0.76, 0.25):
            for dy in np.arange(-0.75, 0.76, 0.25):
                if dx == 0 and dy == 0:
                    continue
                r, _, _ = place(box, base[0] + dx, base[1] + dy)
                s2, b2 = compare(target.cov, r)
                if s2 > s:
                    s, b, cx, cy = s2, b2, base[0] + dx, base[1] + dy
    r, _, _ = place(box, cx, cy)
    return dict(score=s, bin=b, box=box, cx=cx + target.x0, cy=cy + target.y0, stats=stats(r), render=r)


class CmdShape:
    """A Material Symbols outline drawn as pboard's Icons.draw does (viewport mapped onto `box` px)."""

    def __init__(self, cmds):
        self.cmds = cmds

    def bounds(self):
        return S.bounds(self.cmds)

    def render(self, box, cx, cy, w, h):
        return S.render(self.cmds, box, cx, cy, w, h)


class HandShift:
    """pboard's hand-drawn shift arrow (Icons.drawShift before this change); `box` = the glyph width w; state 0 off,
    1 on, 2 locked. Mirrors the Kotlin geometry (units of w/60, stroked with round joins and caps)."""

    ARROW = [(0, -25.6), (26.8, 1.2), (14.6, 1.2), (14.6, 25.4), (-14.6, 25.4), (-14.6, 1.2), (-26.8, 1.2)]

    # Geometry parameters in units of w/60: outline stroke; filled-arrow width scale for the on / locked states; the
    # locked arrow's lift, the gap from its stem to the bar's centre line and the bar's stroke.
    OLD = dict(stroke=4.4, on_scale=1.0, lock_scale=0.86, lift=5.0, gap=9.0, bar=4.4)

    def __init__(self, state, **params):
        self.state = state
        self.p = dict(self.OLD, **params)

    def _arrow(self, sw, ox, oy):
        import skia
        k = sw / 60.0
        p = skia.Path()
        p.moveTo(ox + self.ARROW[0][0] * k, oy + self.ARROW[0][1] * k)
        for x, y in self.ARROW[1:]:
            p.lineTo(ox + x * k, oy + y * k)
        p.close()
        return p

    def render(self, box, cx, cy, w, h):
        import skia
        s = box / 60.0
        surf = skia.Surface.MakeRaster(skia.ImageInfo.MakeA8(w, h))
        c = surf.getCanvas()
        c.clear(skia.ColorTRANSPARENT)

        def paint(style, width):
            return skia.Paint(AntiAlias=True, Color=skia.ColorWHITE, StrokeWidth=width,
                              StrokeJoin=skia.Paint.kRound_Join, StrokeCap=skia.Paint.kRound_Cap, Style=style)

        p = self.p
        if self.state == 2:
            sw = box * p['lock_scale']
            oy = cy - p['lift'] * s
            c.drawPath(self._arrow(sw, cx, oy), paint(skia.Paint.kStrokeAndFill_Style, p['stroke'] * sw / 60.0))
            y = oy + 25.4 * sw / 60.0 + p['gap'] * s
            c.drawLine(cx - 14.6 * sw / 60.0, y, cx + 14.6 * sw / 60.0, y, paint(skia.Paint.kStroke_Style, p['bar'] * s))
        elif self.state == 1:
            sw = box * p['on_scale']
            c.drawPath(self._arrow(sw, cx, cy), paint(skia.Paint.kStrokeAndFill_Style, p['stroke'] * sw / 60.0))
        else:
            c.drawPath(self._arrow(box, cx, cy), paint(skia.Paint.kStroke_Style, p['stroke'] * s))
        a = surf.makeImageSnapshot().toarray(colorType=skia.kAlpha_8_ColorType)
        return a.reshape(h, w).astype(np.float32) / 255.0

    def bounds(self):
        """Ink bounds with `box` = 960 (so fractions of w come out like S.bounds' viewport units)."""
        r = self.render(480, 480, 480, 960, 960)  # box 480 in a 960 frame, then scaled x2 below
        ys, xs = np.nonzero(r > 0.5)
        return xs.min() * 2.0, ys.min() * 2.0, (xs.max() + 1) * 2.0, (ys.max() + 1) * 2.0


# The icons pboard drew before the variant selection: Symbols.kt as of this commit (Rounded, wght 400, opsz 24 from an
# older Material Symbols release) plus the hand-drawn shift arrow.
OLD_REV = 'c8a604c'
_old = None


def old_paths():
    global _old
    if _old is None:
        import subprocess
        cache = os.path.join(S.HERE, 'out', 'Symbols.before.kt')
        if not os.path.exists(cache):
            os.makedirs(os.path.dirname(cache), exist_ok=True)
            src = subprocess.run(['git', '-C', S.REPO, 'show', f'{OLD_REV}:app/src/main/java/dev/pboard/ime/view/Symbols.kt'],
                                 capture_output=True, text=True, check=True).stdout
            with open(cache, 'w') as fh:
                fh.write(src)
        import re
        _old = dict(re.findall(r'const val (\w+) = "([^"]+)"', open(cache).read()))
    return _old


def old_shape(name):
    """Shape pboard drew for `name` before: an old Symbols constant or 'hand:<state>'. None if unknown."""
    if name.startswith('hand:'):
        return HandShift(int(name[5:]))
    d = old_paths().get(name)
    return CmdShape(S.svg_to_cmds(d)) if d else None


def new_shape(const):
    """Shape pboard draws now for a Symbols constant (gen_symbols.ICONS) or 'hand:<state>'."""
    if const.startswith('hand:'):
        return HandShift(int(const[5:]))
    from gen_symbols import ICONS
    c = ICONS[const]
    return CmdShape(S.glyph_commands(c['style'], c['glyph'], c['fill'], c['wght'], c['grad'], c['opsz']))
