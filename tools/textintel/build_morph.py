#!/usr/bin/env python3
"""Builds the grammar checker's English morphology table (app/src/main/assets/textintel/en_morph.txt).

Source: AGID 2016.01.19 (Automatically Generated Inflection Database, Kevin Atkinson; permissive licence with
notice, part-of-speech data from WordNet 1.6 - see NOTICE). Only entries whose base form is among the [--top]
most frequent words of the LM lexicon are kept, and only the preferred inflection (variant level 0).

Output lines (space separated, lowercase):
    V <base> <past> <participle> <ing> <3sg> [n]     n = the base or the -s form is also a common noun
    A <base> <comparative> <superlative>

    python3 -I tools/textintel/build_morph.py --agid tools/data/raw/agid/agid-2016.01.19/infl.txt \
        --lexicon tools/data/work/textintel/lexicon.tsv --out app/src/main/assets/textintel/en_morph.txt
"""

import argparse
import re

ENTRY = re.compile(r"^([A-Za-z']+) ([VNA])(\??): (.*)$")

# modals and function words that AGID also lists as (rare) verbs
NOT_VERBS = {"can", "could", "will", "would", "shall", "should", "may", "might", "must", "ought", "up", "out", "just",
             "yes", "no", "okay", "ok", "well", "people", "down", "off", "over", "back", "but", "if", "so", "too",
             "even", "still", "near", "further", "far", "better", "best", "worse", "more", "less", "like", "please",
             "thank", "thanks", "lol", "hello", "hi", "bye", "wow", "oh", "um", "uh", "ah", "ha", "yeah", "nope"}


def first_form(field):
    """The preferred form of one ' | ' field: the first entry without a variant level above 0."""
    for part in field.split(", "):
        part = re.sub(r"\{[^}]*\}", "", part).strip()
        toks = part.split()
        if not toks:
            continue
        if "?" in toks[0]:
            continue  # AGID: not in its word list, only a guess
        word = toks[0].rstrip("~<!")
        level = float(toks[1]) if len(toks) > 1 and re.match(r"^[0-9.]+$", toks[1]) else 0.0
        if level < 1 and re.match(r"^[a-z']+$", word):
            return word
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--agid", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--top", type=int, default=40000)
    a = ap.parse_args()

    rank, full = {}, {}
    for i, line in enumerate(open(a.lexicon, encoding="utf-8")):
        w = line.split("\t", 1)[0].lower()
        full.setdefault(w, i)
        if i < a.top:
            rank.setdefault(w, i)

    verbs, adjs, nouns = {}, {}, set()
    for line in open(a.agid, encoding="utf-8"):
        m = ENTRY.match(line.rstrip("\n"))
        if not m:
            continue
        base, pos, unsure, rest = m.groups()
        if unsure or base != base.lower() or base not in rank:
            continue
        fields = [f.strip() for f in rest.split(" | ")]
        if pos == "N":
            nouns.add(base)
            for f in fields:
                w = first_form(f)
                if w:
                    nouns.add(w)
        elif pos == "V" and base != "be":
            forms = [first_form(f) for f in fields]
            if None in forms:
                continue
            if len(forms) == 3:
                past, ing, s3 = forms
                pp = past
            elif len(forms) == 4:
                past, pp, ing, s3 = forms
            else:
                continue
            # a real verb of everyday text: its forms are words people type (drops "woulded", "justed")
            if base in NOT_VERBS or any(w not in full for w in (past, ing, s3)) or full[ing] > a.top:
                continue
            verbs[base] = (past, pp, ing, s3)
        elif pos == "A":
            forms = [first_form(f) for f in fields]
            if len(forms) == 2 and None not in forms and all(w in full and full[w] < a.top for w in forms):
                adjs[base] = tuple(forms)

    with open(a.out, "w", encoding="utf-8") as f:
        f.write("# pboard English morphology for the grammar checker. Derived from AGID 2016.01.19\n")
        f.write("# (c) 2000-2014 Kevin Atkinson, with WordNet 1.6 (c) 1997 Princeton University; see NOTICE.\n")
        f.write("# V base past participle ing 3sg [n = base or 3sg is also a noun]; A base comparative superlative\n")
        for b in sorted(verbs, key=lambda w: rank[w]):
            past, pp, ing, s3 = verbs[b]
            flag = " n" if (b in nouns or s3 in nouns) else ""
            f.write(f"V {b} {past} {pp} {ing} {s3}{flag}\n")
        for b in sorted(adjs, key=lambda w: rank[w]):
            c, s = adjs[b]
            f.write(f"A {b} {c} {s}\n")
    print(f"{len(verbs)} verbs, {len(adjs)} adjectives")


if __name__ == "__main__":
    main()
