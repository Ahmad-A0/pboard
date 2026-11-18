#!/usr/bin/env python3
"""Synthetic grammar errors in clean text, for threshold tuning (dev splits) and per-family recall (test splits).

Each clean sentence that offers a site gets exactly one injected error of a family the checker targets
(confusable word, pronoun agreement, verb form after a modal/do/have, "could of", a/an, doubled word, "alot",
lowercase "i", "more taller", "suppose to"). The gold edit restores the original. Output: the gec_data.py JSONL
format, so gec_score.py scores it ("annotators": one list with the single edit; "family" names the injection).

    python3 -I tools/textintel/gec_synth.py --in clean.txt --out synth.jsonl --name synth_chat_dev --n 4000
"""

import argparse
import json
import random
import re

CONFUSION_SETS = [
    ["their", "there", "they're"], ["your", "you're"], ["its", "it's"], ["whose", "who's"], ["then", "than"],
    ["lose", "loose"], ["affect", "effect"], ["to", "too"], ["were", "where", "we're"], ["weather", "whether"],
    ["accept", "except"], ["advice", "advise"], ["quiet", "quite"], ["passed", "past"], ["peace", "piece"],
    ["hear", "here"], ["of", "off"], ["breath", "breathe"], ["brake", "break"], ["principal", "principle"],
    ["know", "no"], ["knew", "new"], ["buy", "by", "bye"], ["which", "witch"], ["allowed", "aloud"],
    ["threw", "through"], ["week", "weak"], ["whole", "hole"], ["write", "right"], ["thought", "though"],
    ["are", "our"], ["an", "and"], ["weight", "wait"], ["meat", "meet"],
]
CONF_PAIRS = False
CONF = {}
for s in CONFUSION_SETS:
    for w in s:
        CONF.setdefault(w, set()).update(x for x in s if x != w)

WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)*")

THIRD = {"he", "she", "it"}
NON3 = {"i", "you", "we", "they"}
MODALS = ["can", "could", "will", "would", "should", "might", "must", "can't", "won't", "didn't", "doesn't", "did"]
# comparatives that "more" can legitimately precede, or that are not adjectives ("more later", "more more")
COMP_SKIP = {"more", "most", "less", "least", "fewer", "lesser", "later", "latter", "further", "farther", "sooner",
             "earlier"}
DOUBLABLE = {"the", "a", "to", "of", "in", "on", "for", "with", "and", "is", "was", "I", "my", "it", "you", "be"}


def load_morph(path):
    verbs = {}
    by_s3, by_base, by_pp = {}, {}, {}
    comps = {}
    for line in open(path, encoding="utf-8"):
        p = line.split()
        if not p or p[0].startswith("#"):
            continue
        if p[0] == "V":
            b, past, pp, ing, s3 = p[1:6]
            verbs[b] = (past, pp, ing, s3)
            by_s3[s3] = b
            by_base[b] = b
            if pp != past:
                by_pp[pp] = b
        elif p[0] == "A":
            comps[p[2]] = p[1]
    return verbs, by_s3, by_base, by_pp, comps


def case_like(orig, w):
    if orig[:1].isupper():
        return w[:1].upper() + w[1:]
    return w


def sites(text, toks, morph):
    verbs, by_s3, by_base, by_pp, comps = morph
    out = []  # (family, start, end, wrong_text)
    low = [t.group(0).lower() for t in toks]
    for i, m in enumerate(toks):
        w = low[i]
        prev = low[i - 1] if i > 0 and text[toks[i - 1].end():m.start()] == " " else None
        nxt = low[i + 1] if i + 1 < len(toks) and text[m.end():toks[i + 1].start()] == " " else None
        if w in CONF:
            alt = random.choice(sorted(CONF[w]))
            out.append(("conf" if not CONF_PAIRS else f"conf:{alt}>{w}", m.start(), m.end(), case_like(m.group(0), alt)))
        if prev in THIRD and w in by_s3 and w != by_s3[w]:
            out.append(("sva", m.start(), m.end(), by_s3[w]))
        if prev in NON3 and w in by_base and w not in ("be", "have", "do"):
            out.append(("sva", m.start(), m.end(), verbs[w][3]))
        if prev in THIRD and w in ("has", "doesn't", "is"):
            out.append(("sva", m.start(), m.end(), {"has": "have", "doesn't": "don't", "is": "are"}[w]))
        if prev in ("they", "we", "you") and w in ("were", "are", "have", "don't"):
            out.append(("sva", m.start(), m.end(), {"were": "was", "are": "is", "have": "has", "don't": "doesn't"}[w]))
        if prev == "i" and w in ("am", "have"):
            out.append(("sva", m.start(), m.end(), {"am": "is", "have": "has"}[w]))
        if prev in MODALS and w in verbs and w not in ("be", "have", "do"):
            past, pp, ing, s3 = verbs[w]
            out.append(("modal_form", m.start(), m.end(), random.choice([past, s3])))
        if prev in ("have", "has", "had", "i've", "you've", "we've", "they've") and w in by_pp:
            out.append(("perfect", m.start(), m.end(), verbs[by_pp[w]][0]))
        if w == "have" and prev in ("could", "should", "would", "must", "might"):
            out.append(("modal_of", m.start(), m.end(), "of"))
        if w in ("a", "an") and nxt is not None and m.group(0).islower():
            out.append(("a_an", m.start(), m.end(), "an" if w == "a" else "a"))
        if m.group(0) in DOUBLABLE and nxt is not None:
            out.append(("double", m.start(), m.end(), m.group(0) + " " + m.group(0)))
        if w == "lot" and prev == "a":
            out.append(("spacing", toks[i - 1].start(), m.end(), "alot"))
        if m.group(0) in ("I", "I'm", "I've", "I'll", "I'd") and i > 0:
            out.append(("i_lower", m.start(), m.end(), "i" + m.group(0)[1:]))
        if w in comps and w not in COMP_SKIP and prev not in ("more", "most", "much", "even", "far", "a", "lot", "the"):
            out.append(("comparative", m.start(), m.end(), "more " + m.group(0)))
        if w in ("supposed", "used") and nxt == "to" and prev is not None:
            out.append(("supposed", m.start(), m.end(), m.group(0)[:-1]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", required=True, nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--morph", default="app/src/main/assets/textintel/en_morph.txt")
    ap.add_argument("--n", type=int, default=4000)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--clean-out", help="also write untouched sentences from the other half (false-alarm set)")
    ap.add_argument("--clean-n", type=int, default=20000)
    ap.add_argument("--conf-pairs", action="store_true",
                    help="only confusable words, balanced per (written > meant) pair (family conf:<written>><meant>)")
    a = ap.parse_args()
    global CONF_PAIRS
    CONF_PAIRS = a.conf_pairs
    random.seed(a.seed)
    morph = load_morph(a.morph)
    lines = []
    for p in a.inp:
        # the LM pipeline wrote numbers as <num>: put a digit back so the text reads as typed
        lines += [l.strip().replace("<num>", "3") for l in open(p, encoding="utf-8") if 4 <= len(l.split()) <= 40]
    random.shuffle(lines)
    # balance families: draw a family first, then a sentence offering it
    by_family = {}
    for k, text in enumerate(lines[:len(lines) // 2]):
        toks = list(WORD.finditer(text))
        for site in sites(text, toks, morph):
            if CONF_PAIRS and not site[0].startswith("conf:"):
                continue
            by_family.setdefault(site[0], []).append((k, text, site))
    fams = sorted(by_family)
    per = a.n // len(fams) if not CONF_PAIRS else a.n
    rows = []
    used = set()
    for fam in fams:
        cand = by_family[fam]
        random.shuffle(cand)
        taken = 0
        for k, text, (f, s, e, wrong) in cand:
            if taken >= per:
                break
            if k in used:
                continue
            used.add(k)
            taken += 1
            new = text[:s] + wrong + text[e:]
            # the gold edit on the corrupted text restores the original; an inserted word is a deletion edit
            orig = text[s:e]
            cs, ce, cor = s, s + len(wrong), orig
            if fam == "double":
                cs, ce, cor = s + len(orig), s + len(wrong), ""
            elif fam == "comparative":
                cs, ce, cor = s, s + len("more "), ""
            edit = {"ts": -1, "te": -1, "cs": cs, "ce": ce, "type": fam, "cor": cor}
            toks = [(m.start(), m.end()) for m in re.finditer(r"[A-Za-z0-9]+(?:['’][A-Za-z]+)*|[^\sA-Za-z0-9]", new)]
            ts = next(i for i, (x, y) in enumerate(toks) if y > cs)
            te = ts
            while te < len(toks) and toks[te][0] < ce:
                te += 1
            # cor in token terms: what tokens [ts, te) must become
            full = new[toks[ts][0]:cs] + cor + new[ce:toks[te - 1][1]] if te > ts else cor
            edit.update(ts=ts, te=te, cor=" ".join(re.findall(r"[A-Za-z0-9]+(?:['’][A-Za-z]+)*|[^\sA-Za-z0-9]", full)))
            rows.append({"id": f"{a.name}-{len(rows)}", "set": a.name, "family": fam, "text": new,
                         "tokens": [new[x:y] for x, y in toks], "spans": toks, "annotators": [[edit]]})
    with open(a.out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(a.name, len(rows), {f: sum(1 for r in rows if r["family"] == f) for f in fams})
    if a.clean_out:
        clean = lines[len(lines) // 2:len(lines) // 2 + a.clean_n]
        with open(a.clean_out, "w", encoding="utf-8") as f:
            for k, text in enumerate(clean):
                toks = [(m.start(), m.end()) for m in re.finditer(r"[A-Za-z0-9]+(?:['’][A-Za-z]+)*|[^\sA-Za-z0-9]", text)]
                f.write(json.dumps({"id": f"{a.name}_clean-{k}", "set": a.name + "_clean", "text": text,
                                    "tokens": [text[x:y] for x, y in toks], "spans": toks, "annotators": [[]]}) + "\n")


if __name__ == "__main__":
    main()
