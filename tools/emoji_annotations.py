#!/usr/bin/env python3
"""Builds the emoji search/suggestion keyword assets from Unicode CLDR annotations (Unicode License v3).

Sources (unicode-org/cldr, common/):
  annotations/en.xml, annotations/en_001.xml, annotations/en_GB.xml
  annotationsDerived/en.xml, annotationsDerived/en_001.xml
CLDR's locale chain is en_GB -> en_001 -> en; a value of "↑↑↑" means "inherit from the parent".

Output (assets/emoji/):
  annotations_en.txt     one line per panel emoji and its woman / man forms (order of emoji.txt): "<emoji>\\t<name>\\t<kw>|<kw>|..."
  annotations_en_GB.txt  only the lines whose resolved en_GB value differs from en (same format)

usage: emoji_annotations.py <cldr-common-dir> <emoji.txt> <out-dir>
"""
import os, sys
import xml.etree.ElementTree as ET

cldr, emoji_txt, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
VS16 = "️"


def load(path):
    """cp (without VS16) -> {"kw": [...], "tts": str} from one CLDR annotations file ("↑↑↑" left as is)."""
    res = {}
    if not os.path.exists(path):
        return res
    for a in ET.parse(path).getroot().iter("annotation"):
        cp = a.get("cp").replace(VS16, "")
        e = res.setdefault(cp, {})
        text = (a.text or "").strip()
        if a.get("type") == "tts":
            e["tts"] = text
        else:
            e["kw"] = text
    return res


def chain(*names):
    """Resolves a locale chain, most specific last."""
    out = {}
    for n in names:
        for sub in ("annotations", "annotationsDerived"):
            for cp, e in load(os.path.join(cldr, sub, n + ".xml")).items():
                d = out.setdefault(cp, {})
                for k, v in e.items():
                    if v and v != "↑↑↑":
                        d[k] = v
    return out


en = chain("en")
en_gb = chain("en", "en_001", "en_GB")

emoji, seen = [], set()
for line in open(emoji_txt, encoding="utf-8"):
    parts = line.rstrip("\n").split("\t")
    if len(parts) >= 2:
        # The cell, then the woman / man forms of its long-press picker (skin-tone forms are not searched).
        for e in [parts[1]] + [v for row in parts[2:] for v in row.split(" ")]:
            if e and e not in seen and not any(0x1F3FB <= ord(c) <= 0x1F3FF for c in e):
                seen.add(e)
                emoji.append(e)


def line_for(table, e):
    a = table.get(e.replace(VS16, ""))
    if not a or "tts" not in a:
        return None
    kws = [k.strip() for k in a.get("kw", "").split("|") if k.strip()]
    return f"{e}\t{a['tts']}\t{'|'.join(kws)}"


os.makedirs(out_dir, exist_ok=True)
missing = 0
with open(os.path.join(out_dir, "annotations_en.txt"), "w", encoding="utf-8") as f_en, \
        open(os.path.join(out_dir, "annotations_en_GB.txt"), "w", encoding="utf-8") as f_gb:
    for e in emoji:
        a, b = line_for(en, e), line_for(en_gb, e)
        if a is None:
            missing += 1
            continue
        f_en.write(a + "\n")
        if b is not None and b != a:
            f_gb.write(b + "\n")
print(f"{len(emoji)} emoji, {missing} without annotations", file=sys.stderr)
