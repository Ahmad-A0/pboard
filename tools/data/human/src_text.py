"""Text-level sets: real misspellings (autocorrect) and mobile-representative target sentences (prediction).

  wikipedia   Wikipedia:Lists of common misspellings/For machines (CC BY-SA 4.0): ~4.3k frequent misspellings
              fixed by editors, word level, no context. Split by the correct word (all misspellings of a word
              stay on one side).
  enron typos EnronMobile build files (no explicit licence -> local-only): the raw BlackBerry e-mails next to the
              authors' hand-corrected versions; word-aligned differences are genuine mobile typing errors with
              their sentence context. Split by sender.
  sentences   EnronMobile mobile_orig.txt (2,239 genuine mobile e-mail sentences, no explicit licence ->
              local-only), split by sender, for next-word prediction / keystroke-savings evaluation.
Tap-derived literal strings (what the touched keys spell, before any correction) are produced in build.py from
the tap traces so they inherit the participant split.
"""
import difflib
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
from common import RAW, stable_hash, split_of, VERSION

WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")


def damerau(a, b):
    """Optimal-string-alignment distance (adjacent transpositions count 1)."""
    la, lb = len(a), len(b)
    d = [[0] * (lb + 1) for _ in range(la + 1)]
    for i in range(la + 1):
        d[i][0] = i
    for j in range(lb + 1):
        d[0][j] = j
    for i in range(1, la + 1):
        for j in range(1, lb + 1):
            c = 0 if a[i - 1] == b[j - 1] else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[la][lb]


# ------------------------------------------------------------------------------------- wikipedia

def wikipedia():
    path = os.path.join(RAW, "wikipedia_misspellings", "for_machines.wiki")
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if "->" not in line or line.startswith(("<", "=", "*")):
                continue
            typo, fix = line.split("->", 1)
            typo = typo.strip()
            targets = [t.strip() for t in fix.split(",") if t.strip()]
            if not typo.isalpha() or not targets or any(not t.replace("'", "").isalpha() for t in targets):
                continue
            tgt = targets[0]
            out.append({"context": "", "typed": typo, "target": tgt, "targets": targets, "src": "wikipedia",
                        "error": True, "dist": damerau(typo.lower(), tgt.lower()),
                        "split": "test" if stable_hash("pboard-eval-" + VERSION, "wikipedia", tgt.lower()) < 0.5 else "tune",
                        "id": "wikipedia/%s" % typo})
    return out


# ------------------------------------------------------------------------------------- enronmobile

def _enron_zip():
    return zipfile.ZipFile(os.path.join(RAW, "enronmobile", "enronmobile.zip"))


def _parse_msg(text):
    lines = text.replace("\r", "").split("\n")
    path = lines[0].strip()
    sender, body, in_body = "", [], False
    for ln in lines[1:]:
        if in_body:
            body.append(ln)
        elif ln.startswith("From:"):
            sender = ln[5:].strip().lower()
            m = re.search(r"[\w.\-+]+@[\w.\-]+", sender)
            sender = m.group(0) if m else sender
        elif ln.startswith("Subject:"):
            in_body = True
    return path, sender, body


def enron_messages():
    """{maildir path: (sender, orig body lines, fixed body lines)}"""
    z = _enron_zip()
    orig, fixed = {}, {}
    for n in z.namelist():
        base = os.path.basename(n)
        if "/build/orig/" in n and base.startswith("orig"):
            p, s, b = _parse_msg(z.read(n).decode("latin-1"))
            orig[p] = (s, b)
        elif "/build/fixed/" in n and base.startswith("fixed") and base != "fixed":
            p, s, b = _parse_msg(z.read(n).decode("latin-1"))
            fixed[p] = (s, b)
    return {p: (fixed[p][0] or orig[p][0], orig[p][1], fixed[p][1]) for p in fixed if p in orig}


def enron_typos():
    out, seen = [], set()
    for path, (sender, ob, fb) in sorted(enron_messages().items()):
        otoks = [m.group(0) for m in WORD.finditer(" ".join(ob))]
        ftoks, fpos = [], []  # fixed tokens with (line, char offset) for sentence-prefix context
        for li, ln in enumerate(fb):
            for m in WORD.finditer(ln):
                ftoks.append(m.group(0))
                fpos.append((li, m.start()))
        sm = difflib.SequenceMatcher(a=[t.lower() for t in otoks], b=[t.lower() for t in ftoks], autojunk=False)
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag != "replace" or (i2 - i1) != (j2 - j1):
                continue
            for k in range(i2 - i1):
                typed, tgt = otoks[i1 + k], ftoks[j1 + k]
                d = damerau(typed.lower(), tgt.lower())
                if d == 0 or d > 2 or len(tgt) < 2:
                    continue
                li, off = fpos[j1 + k]
                ctx = fb[li][:off]
                key = (ctx, typed, tgt)
                if key in seen:
                    continue
                seen.add(key)
                out.append({"context": ctx, "typed": typed, "target": tgt, "src": "enron", "error": True,
                            "dist": d, "participant": sender, "split": split_of("enron", sender),
                            "id": "enron/%s/%d" % (path.split("maildir/")[-1], j1 + k)})
    return out


def enron_sentences():
    z = _enron_zip()
    sender_of = {}
    for path, (sender, ob, fb) in enron_messages().items():
        for ln in fb:
            sender_of.setdefault(ln.strip(), sender)
    meta = {}
    for ln in z.read("enronmobile/metadata.txt").decode("latin-1").splitlines()[1:]:
        p = ln.split("\t")
        if len(p) > 8:
            meta[p[0]] = p[8]
    out = []
    for ln in z.read("enronmobile/mobile_orig.txt").decode("latin-1").splitlines():
        if "\t" not in ln:
            continue
        sid, text = ln.split("\t", 1)
        text = text.strip()
        sender = sender_of.get(text, "")
        part = sender or ("unknown:" + sid)
        out.append({"id": "enronmobile/" + sid, "text": text, "category": meta.get(sid, "?"),
                    "participant": part, "senderKnown": bool(sender), "split": split_of("enronmobile", part)})
    return out


if __name__ == "__main__":
    import collections
    w = wikipedia()
    print("wikipedia", len(w), collections.Counter(x["split"] for x in w), w[:2])
    t = enron_typos()
    print("enron typos", len(t), collections.Counter(x["split"] for x in t))
    for x in t[:25]:
        print("  %-40r %-14s -> %s" % (x["context"][-40:], x["typed"], x["target"]))
    s = enron_sentences()
    print("sentences", len(s), collections.Counter(x["split"] for x in s), sum(x["senderKnown"] for x in s),
          len({x["participant"] for x in s}))
