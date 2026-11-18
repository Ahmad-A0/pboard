"""Shared helpers for the icon tools: Material Symbols variable-font outlines and Skia rasterisation.

Material Symbols glyphs live on a 960-unit em with the baseline at the bottom (font y up). Android/SVG path data uses the
viewport (0,-960)..(960,0): svg_y = -font_y. pboard draws a symbol by mapping that viewport onto a `box`-px square
centred on (cx, cy) (see app/.../ime/view/Icons.kt), which is what [render] reproduces.
"""
import os
import re

import numpy as np
import skia
from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_DIR = os.path.join(HERE, '.cache', 'fonts')
STYLES = ('Outlined', 'Rounded', 'Sharp')
EM = 960.0

_fonts = {}
_glyphsets = {}


def font(style):
    if style not in _fonts:
        path = os.path.join(FONT_DIR, f'MaterialSymbols{style}.ttf')
        if not os.path.exists(path):
            raise SystemExit(f'missing {path}: run tools/icons/fetch_fonts.sh')
        f = TTFont(path, lazy=True)
        cps = {}
        with open(os.path.join(FONT_DIR, f'MaterialSymbols{style}.codepoints')) as fh:
            for line in fh:
                name, cp = line.split()
                cps[name] = int(cp, 16)
        _fonts[style] = (f, f.getBestCmap(), cps)
    return _fonts[style]


def axes_key(fill, wght, grad, opsz):
    return (float(fill), float(wght), float(grad), float(opsz))


def glyph_commands(style, name, fill=0, wght=400, grad=0, opsz=24):
    """Outline of icon `name` at the given axis location, as a list of (op, points) in font units (y up)."""
    f, cmap, cps = font(style)
    key = (style, axes_key(fill, wght, grad, opsz))
    gs = _glyphsets.get(key)
    if gs is None:
        gs = f.getGlyphSet(location={'FILL': fill, 'wght': wght, 'GRAD': grad, 'opsz': opsz})
        _glyphsets[key] = gs
    if name not in cps:
        raise KeyError(f'{name} not in Material Symbols {style}')
    rec = _RecPen(gs)
    gs[cmap[cps[name]]].draw(rec)
    return rec.cmds


class _RecPen(BasePen):
    def __init__(self, gs):
        super().__init__(gs)
        self.cmds = []

    def _moveTo(self, p): self.cmds.append(('M', [p]))
    def _lineTo(self, p): self.cmds.append(('L', [p]))
    def _qCurveToOne(self, p1, p2): self.cmds.append(('Q', [p1, p2]))
    def _curveToOne(self, p1, p2, p3): self.cmds.append(('C', [p1, p2, p3]))
    def _closePath(self): self.cmds.append(('Z', []))
    def _endPath(self): self.cmds.append(('Z', []))


def _num(v):
    """Shortest decimal for v rounded to 0.1 unit (0.01 px at 96 px - far below a pixel)."""
    s = f'{round(v, 1):.1f}'.rstrip('0').rstrip('.')
    if s in ('-0', ''):
        s = '0'
    return s


def to_svg(cmds):
    """Absolute SVG path data in the (0,-960)..(960,0) viewport (svg_y = -font_y). Degenerate segments (variable-font
    outlines collapse some points onto each other at most axis locations) and empty contours are dropped."""
    out = []
    contour = []
    cur = start = None
    drawn = False

    def flush():
        nonlocal contour, drawn
        if drawn:
            out.extend(contour)
            out.append('Z')
        contour = []
        drawn = False

    def r(p):
        return (round(p[0], 1), round(-p[1], 1))

    for op, pts in cmds:
        if op == 'M':
            flush()
            cur = start = r(pts[0])
            contour.append(f'M{_num(cur[0])} {_num(cur[1])}')
            continue
        if op == 'Z':
            flush()
            cur = start
            continue
        q = [r(p) for p in pts]
        if all(p == cur for p in q):
            continue
        drawn = True
        end = q[-1]
        if op == 'L' and end[1] == cur[1]:
            contour.append(f'H{_num(end[0])}')
        elif op == 'L' and end[0] == cur[0]:
            contour.append(f'V{_num(end[1])}')
        else:
            contour.append(op + ' '.join(f'{_num(x)} {_num(y)}' for x, y in q))
        cur = end
    flush()
    return re.sub(r' -', '-', ''.join(out))


_SVG_TOKEN = re.compile(r'[MmLlHhVvQqTtCcSsZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')


def svg_to_cmds(d, viewport=960.0):
    """Parse SVG path data (as stored in Symbols.kt) into font-unit commands (y up, 960 em)."""
    toks = _SVG_TOKEN.findall(d)
    i = 0
    cmds = []
    x = y = sx = sy = 0.0
    lastq = None
    lastc = None
    op = None
    k = EM / viewport

    def num():
        nonlocal i
        v = float(toks[i]); i += 1
        return v

    def emit(o, pts):
        # SVG viewport coords -> font units: 24-unit viewports (0..24, y down) are scaled; 960 ones are (0,-960) based.
        if viewport == 960.0:
            cmds.append((o, [(px, -py) for px, py in pts]))
        else:
            cmds.append((o, [(px * k, EM - py * k) for px, py in pts]))

    while i < len(toks):
        t = toks[i]
        if re.match(r'[A-Za-z]', t):
            op = t; i += 1
            if op in 'Zz':
                cmds.append(('Z', [])); x, y = sx, sy; lastq = lastc = None
                continue
        rel = op.islower()
        o = op.upper()
        if o == 'M':
            nx, ny = num(), num()
            if rel: nx += x; ny += y
            x, y, sx, sy = nx, ny, nx, ny
            emit('M', [(x, y)])
            op = 'l' if rel else 'L'
            lastq = lastc = None
        elif o == 'L':
            nx, ny = num(), num()
            if rel: nx += x; ny += y
            x, y = nx, ny; emit('L', [(x, y)]); lastq = lastc = None
        elif o == 'H':
            nx = num(); x = nx + x if rel else nx; emit('L', [(x, y)]); lastq = lastc = None
        elif o == 'V':
            ny = num(); y = ny + y if rel else ny; emit('L', [(x, y)]); lastq = lastc = None
        elif o == 'Q':
            c1 = [num(), num()]; e = [num(), num()]
            if rel: c1[0] += x; c1[1] += y; e[0] += x; e[1] += y
            emit('Q', [tuple(c1), tuple(e)]); lastq = tuple(c1); x, y = e; lastc = None
        elif o == 'T':
            e = [num(), num()]
            if rel: e[0] += x; e[1] += y
            c1 = (2 * x - lastq[0], 2 * y - lastq[1]) if lastq else (x, y)
            emit('Q', [c1, tuple(e)]); lastq = c1; x, y = e; lastc = None
        elif o == 'C':
            c1 = [num(), num()]; c2 = [num(), num()]; e = [num(), num()]
            if rel:
                for p in (c1, c2, e): p[0] += x; p[1] += y
            emit('C', [tuple(c1), tuple(c2), tuple(e)]); lastc = tuple(c2); x, y = e; lastq = None
        elif o == 'S':
            c2 = [num(), num()]; e = [num(), num()]
            if rel:
                for p in (c2, e): p[0] += x; p[1] += y
            c1 = (2 * x - lastc[0], 2 * y - lastc[1]) if lastc else (x, y)
            emit('C', [c1, tuple(c2), tuple(e)]); lastc = tuple(c2); x, y = e; lastq = None
        else:
            raise ValueError(f'unsupported path op {op}')
    return cmds


def skia_path(cmds, box, ox, oy):
    """Skia path for font-unit cmds drawn as pboard does: viewport mapped onto box px with its top-left at (ox, oy)."""
    s = box / EM
    p = skia.Path()
    p.setFillType(skia.PathFillType.kWinding)
    for op, pts in cmds:
        q = [(ox + px * s, oy + (EM - py) * s) for px, py in pts]
        if op == 'M': p.moveTo(*q[0])
        elif op == 'L': p.lineTo(*q[0])
        elif op == 'Q': p.quadTo(*q[0], *q[1])
        elif op == 'C': p.cubicTo(*q[0], *q[1], *q[2])
        elif op == 'Z': p.close()
    return p


_paint = skia.Paint(AntiAlias=True, Color=skia.ColorWHITE)


def render(cmds, box, cx, cy, w, h, x0=0.0, y0=0.0):
    """Coverage (0..1 float32, h x w) of the symbol with its viewport centred on (cx, cy) in a window whose top-left
    pixel corner is (x0, y0)."""
    surf = skia.Surface.MakeRaster(skia.ImageInfo.MakeA8(w, h))
    c = surf.getCanvas()
    c.clear(skia.ColorTRANSPARENT)
    c.drawPath(skia_path(cmds, box, cx - box / 2 - x0, cy - box / 2 - y0), _paint)
    a = surf.makeImageSnapshot().toarray(colorType=skia.kAlpha_8_ColorType)
    return a.reshape(h, w).astype(np.float32) / 255.0


def render_path(path, w, h, dx=0.0, dy=0.0):
    surf = skia.Surface.MakeRaster(skia.ImageInfo.MakeA8(w, h))
    c = surf.getCanvas()
    c.clear(skia.ColorTRANSPARENT)
    c.translate(dx, dy)
    c.drawPath(path, _paint)
    a = surf.makeImageSnapshot().toarray(colorType=skia.kAlpha_8_ColorType)
    return a.reshape(h, w).astype(np.float32) / 255.0


def bounds(cmds):
    """Ink bounds (x0, y0, x1, y1) in viewport units (y down, 0..960) from the outline's control-point hull."""
    p = skia_path(cmds, EM, 0, 0)
    r = p.computeTightBounds()
    return r.left(), r.top(), r.right(), r.bottom()


REPO = os.path.abspath(os.path.join(HERE, '..', '..'))


def _main_checkout():
    """The main checkout when REPO is a linked git worktree (its .git is a file naming the main .git), else REPO."""
    try:
        with open(os.path.join(REPO, '.git'), encoding='utf-8') as fh:
            gitdir = fh.read().strip().partition('gitdir: ')[2]
    except OSError:
        return REPO
    head, sep, _ = gitdir.partition('/.git/worktrees/')
    return head if sep else REPO


def ref_dir(sub):
    """docs/ref/<sub> (gitignored reference shots); a git worktree falls back to the main checkout's copy."""
    for base in (REPO, _main_checkout()):
        p = os.path.join(base, 'docs', 'ref', sub)
        if os.path.isdir(p):
            return p
    return os.path.join(REPO, 'docs', 'ref', sub)
