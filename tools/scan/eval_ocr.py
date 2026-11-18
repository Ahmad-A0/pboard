"""Scan-text engine evaluation: synthetic camera photos of printed text, CER for Tesseract vs PaddleOCR (RapidOCR ONNX).

  tools/scan/eval_ocr.py N   (needs tesseract on PATH, pip: pillow numpy rapidocr-onnxruntime opencv-python-headless;
  tessdata_fast eng.traineddata in $SCAN_EVAL_DIR/tessdata_fast)
"""
import glob, io, os, random, subprocess, sys, time
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.environ.get("SCAN_EVAL_DIR", os.path.join(HERE, ".work"))
OUT = os.path.join(WORK, 'imgs'); os.makedirs(OUT, exist_ok=True)
SENT = os.path.join(HERE, '..', 'bench', 'sentences', 'heldout_v1.txt')
FONTS = [f for pat in ['/nix/store/*dejavu-fonts-2.37/share/fonts/truetype/DejaVuSans.ttf',
                       '/nix/store/*dejavu-fonts-2.37/share/fonts/truetype/DejaVuSerif.ttf',
                       '/nix/store/*liberation-fonts-2.1.5/share/fonts/truetype/LiberationSans-Regular.ttf',
                       '/nix/store/*liberation-fonts-2.1.5/share/fonts/truetype/LiberationSerif-Regular.ttf',
                       '/nix/store/*liberation-fonts-2.1.5/share/fonts/truetype/LiberationMono-Regular.ttf']
         for f in glob.glob(pat)[:1]]
EXTRA = ['Call 0207 946 0958 or email jo.bloggs@example.co.uk before 5:30pm.',
         'Order #A-48213 ships on 12/03/2026, total $1,249.99 incl. VAT.',
         'Wi-Fi: Guest_5G  Password: blue-Giraffe-42', 'Meet at 221B Baker Street, London NW1 6XE.']


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def norm(s):
    return ' '.join(s.replace('\n', ' ').split())


def render(rng, lines, font):
    size = rng.randint(30, 46)
    f = ImageFont.truetype(font, size)
    pad = 40
    w = max(int(f.getlength(l)) for l in lines) + 2 * pad
    h = len(lines) * int(size * 1.45) + 2 * pad
    paper = tuple(int(x) for x in rng.choice([(250, 250, 248), (245, 242, 235), (255, 255, 255), (238, 240, 242)]))
    img = Image.new('RGB', (w, h), paper)
    d = ImageDraw.Draw(img)
    ink = rng.choice([(20, 20, 20), (40, 40, 60), (10, 10, 10)])
    for i, l in enumerate(lines):
        d.text((pad, pad + i * int(size * 1.45)), l, font=f, fill=ink)
    return img


def photo(rng, doc):
    # paper on a table, perspective, lighting gradient, blur, noise, JPEG, phone-ish resolution
    W, H = int(doc.width * 1.35), int(doc.height * 1.5)
    bg = Image.new('RGB', (W, H), tuple(rng.randint(60, 140) for _ in range(3)))
    ox, oy = (W - doc.width) // 2, (H - doc.height) // 2
    bg.paste(doc, (ox, oy))
    j = lambda s: rng.uniform(-s, s)
    sx, sy = doc.width * 0.06, doc.height * 0.06
    src = [(ox, oy), (ox + doc.width, oy), (ox + doc.width, oy + doc.height), (ox, oy + doc.height)]
    dst = [(x + j(sx), y + j(sy)) for x, y in src]
    coeffs = find_coeffs(dst, src)
    img = bg.transform((W, H), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    img = img.rotate(rng.uniform(-4, 4), resample=Image.BICUBIC, fillcolor=(90, 90, 90))
    a = np.asarray(img).astype(np.float32)
    gx = np.linspace(rng.uniform(0.7, 1.0), rng.uniform(0.85, 1.1), a.shape[1])[None, :, None]
    gy = np.linspace(rng.uniform(0.75, 1.05), rng.uniform(0.8, 1.05), a.shape[0])[:, None, None]
    a = a * gx * gy + np.random.default_rng(rng.randint(0, 1 << 30)).normal(0, rng.uniform(3, 9), a.shape)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    img = img.filter(ImageFilter.GaussianBlur(rng.uniform(0.4, 1.4)))
    scale = 1600 / max(img.size)
    img = img.resize((int(img.width * scale), int(img.height * scale)), Image.BILINEAR)
    buf = io.BytesIO(); img.save(buf, 'JPEG', quality=int(rng.uniform(60, 85)))
    return Image.open(io.BytesIO(buf.getvalue())).convert('RGB')


def find_coeffs(pa, pb):
    m = []
    for p1, p2 in zip(pa, pb):
        m.append([p1[0], p1[1], 1, 0, 0, 0, -p2[0] * p1[0], -p2[0] * p1[1]])
        m.append([0, 0, 0, p1[0], p1[1], 1, -p2[1] * p1[0], -p2[1] * p1[1]])
    A = np.matrix(m, dtype=np.float64); B = np.array(pb).reshape(8)
    return np.array(np.dot(np.linalg.inv(A.T * A) * A.T, B)).reshape(8)


def tesseract(path, lang='eng', tessdata=None):
    t = time.time()
    cmd = ['tesseract', path, '-', '-l', lang] + (['--tessdata-dir', tessdata] if tessdata else [])
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    return out, time.time() - t


def tess_fast(path):
    return tesseract(path, tessdata=os.path.join(WORK, 'tessdata_fast'))


def word_recall(hyp, ref):
    from collections import Counter
    h, r = Counter(hyp.split()), Counter(ref.split())
    return sum(min(c, h[w]) for w, c in r.items()), sum(r.values())


_rapid = None
def rapid(path):
    global _rapid
    from rapidocr_onnxruntime import RapidOCR
    if _rapid is None:
        _rapid = RapidOCR()
    t = time.time()
    res, _ = _rapid(path)
    dt = time.time() - t
    if not res:
        return '', dt
    # reading order: by line (y centre bands), then x
    boxes = [(np.mean([p[1] for p in b]), min(p[0] for p in b), txt, max(p[1] for p in b) - min(p[1] for p in b)) for b, txt, _ in res]
    boxes.sort()
    lines, cur, cy, ch = [], [], None, None
    for y, x, txt, h in boxes:
        if cy is None or abs(y - cy) < 0.5 * ch:
            cur.append((x, txt)); cy = y if cy is None else cy; ch = h if ch is None else ch
        else:
            lines.append(' '.join(t for _, t in sorted(cur))); cur = [(x, txt)]; cy, ch = y, h
    if cur: lines.append(' '.join(t for _, t in sorted(cur)))
    return '\n'.join(lines), dt


def main(n):
    rng = random.Random(7)
    sents = [l.strip() for l in open(SENT) if len(l.strip()) > 20]
    tot = {'tesseract': [0, 0, 0.0, 0, 0], 'tess_fast': [0, 0, 0.0, 0, 0], 'rapidocr': [0, 0, 0.0, 0, 0]}
    for i in range(n):
        lines = rng.sample(sents, rng.randint(3, 6)) + ([rng.choice(EXTRA)] if rng.random() < 0.6 else [])
        lines = [l[:60] for l in lines]
        doc = render(rng, lines, rng.choice(FONTS))
        img = photo(rng, doc)
        p = os.path.join(OUT, f'{i:03d}.jpg'); img.save(p)
        ref = norm('\n'.join(lines))
        for name, fn in (('tesseract', tesseract), ('tess_fast', tess_fast), ('rapidocr', rapid)):
            hyp, dt = fn(p)
            e = lev(norm(hyp), ref)
            tot[name][0] += e; tot[name][1] += len(ref); tot[name][2] += dt
            wr = word_recall(norm(hyp), ref); tot[name][3] += wr[0]; tot[name][4] += wr[1]
            if i < 3: print(name, i, round(e / len(ref), 3), repr(norm(hyp)[:90]))
    for k, (e, l, t, wh, wn) in tot.items():
        print(f'{k}: CER {e / l:.3f}  ({e}/{l} chars), word recall {wh / wn:.3f}, {t / n:.2f} s/image')


if __name__ == '__main__':
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 20)
