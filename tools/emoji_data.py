#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji/emoji.txt from Unicode's emoji-test.txt (Unicode License v3).

Source: https://unicode.org/Public/emoji/latest/emoji-test.txt (18.0 at the time of writing)
Output: one line per emoji-panel cell, in panel order:
    <category>\\t<emoji>[\\t<long-press row>...]
where each long-press row is space-separated emoji. A cell with no rows has no long-press picker.

Which emoji get a cell (the rules Gboard 18.6's panel follows, measured on device; the data is Unicode's):
  * only fully-qualified forms (no unqualified / minimally-qualified duplicates such as a bare U+263A);
  * no components: the "Component" group (skin-tone modifiers, hair styles) is dropped, and regional-indicator
    letters are not in emoji-test.txt at all;
  * skin-tone forms are never cells: they are attached to their base (the entry whose CLDR name is the same
    without the tone qualifiers) as one long-press row  [base, light .. dark];
    two-person emoji (handshake, couples, holding hands, ...) get a 5x5 grid instead, row = first person's tone,
    column = second's, the single-tone forms on the diagonal, below a row with the base;
  * gendered forms are folded under the gender-neutral one: 'woman X' / 'man X' (ZWJ + female/male sign, or
    woman / man + ZWJ + Y for person + ZWJ + Y, or the single-character trios below) become rows of the
    neutral cell, ordered neutral, woman, man, each with its tones; the 'facing right' forms (ZWJ + right
    arrow) add the same three rows below (Gboard shows them behind a flip button). Emoji with two-person
    tone grids keep their gendered forms as own cells;
  * families of gendered members ('family: man, woman, boy', ...) and the bare U+1F46A family are not
    listed: the gender-neutral families (adult, adult, child) are.
Categories (the panel's nine tabs) are Gboard's; they are assembled from Unicode subgroups (CATEGORIES).
The panel additionally hides what the system font cannot draw (EmojiData, Paint.hasGlyph).

usage: emoji_data.py <emoji-test.txt> <out.txt>
   e.g. tools/emoji_data.py third_party/unicode/emoji-test.txt app/src/main/assets/emoji/emoji.txt
Keep app/src/test/.../EmojiSetTest (per-category counts) in step when the Unicode version changes.
"""
import sys

# Panel categories in tab order: (Unicode group, [subgroups in panel order] or None for the whole group).
CATEGORIES = [
    ("Smileys and emotions", [("Smileys & Emotion", None),
                              ("People & Body", ["hand-fingers-open", "hand-fingers-partial", "hand-single-finger",
                                                 "hand-fingers-closed", "hands", "hand-prop", "body-parts",
                                                 "person-symbol"])]),
    ("People", [("People & Body", ["person", "person-gesture", "person-role", "person-fantasy", "person-activity",
                                   "person-sport", "person-resting", "family"])]),
    ("Animals and nature", [("Animals & Nature", None), ("Travel & Places", ["place-geographic", "sky & weather"])]),
    ("Food and drink", [("Food & Drink", None)]),
    ("Travel and places", [("Travel & Places", ["place-map", "place-building", "place-religious", "place-other",
                                                "transport-ground", "transport-water", "transport-air"])]),
    ("Activities", [("Activities", None), ("Objects", ["music", "musical-instrument", "light & video"])]),
    ("Objects", [("Objects", None), ("Travel & Places", ["hotel", "time"])]),
    ("Symbols", [("Symbols", None)]),
    ("Flags", [("Flags", None)]),
]
# A subgroup Unicode adds later goes to its group's usual category.
GROUP_DEFAULT = {"Smileys & Emotion": 0, "People & Body": 1, "Animals & Nature": 2, "Food & Drink": 3,
                 "Travel & Places": 4, "Activities": 5, "Objects": 6, "Symbols": 7, "Flags": 8}

TONE_NAMES = ["light skin tone", "medium-light skin tone", "medium skin tone", "medium-dark skin tone",
              "dark skin tone"]
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}
ZWJ, VS16 = "‍", "️"
FEMALE, MALE = ZWJ + "♀" + VS16, ZWJ + "♂" + VS16
RIGHT = ZWJ + "➡" + VS16
WOMAN, MAN, PERSON = "\U0001F469", "\U0001F468", "\U0001F9D1"
# Gender trios that are single characters (neutral: (woman, man)): CLDR's person / child / older person / ...
TRIOS = {"\U0001F9D1": ("\U0001F469", "\U0001F468"),   # person: woman, man
         "\U0001F9D2": ("\U0001F467", "\U0001F466"),   # child: girl, boy
         "\U0001F9D3": ("\U0001F475", "\U0001F474"),   # older person: old woman, old man
         "\U0001FAC5": ("\U0001F478", "\U0001F934"),   # person with crown: princess, prince
         "\U0001FAC4": ("\U0001F930", "\U0001FAC3"),   # pregnant person: pregnant woman, pregnant man
         "\U0001F9D1‍\U0001F384": ("\U0001F936", "\U0001F385")}   # mx claus: mrs claus, santa claus


def parse(path):
    """-> [(emoji, name, group, subgroup)] of the fully-qualified non-component entries, in file order."""
    out, group, sub = [], None, None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
        elif line.startswith("# subgroup:"):
            sub = line.split(":", 1)[1].strip()
        elif line and not line.startswith("#") and group != "Component":
            cps, rest = line.split(";", 1)
            status, comment = rest.split("#", 1)
            if status.strip() != "fully-qualified":
                continue
            emoji = "".join(chr(int(c, 16)) for c in cps.split())
            name = comment.split(None, 2)[2].strip()   # "<emoji> E<version> <name>"
            out.append((emoji, name, group, sub))
    return out


def split_tones(name):
    """'kiss: woman, man, light skin tone, dark skin tone' -> ('kiss: woman, man', (0, 4))."""
    if ":" not in name:
        return name, ()
    head, tail = name.split(":", 1)
    parts = [p.strip() for p in tail.split(",")]
    tones = tuple(TONE_NAMES.index(p) for p in parts if p in TONE_NAMES)
    rest = [p for p in parts if p not in TONE_NAMES]
    if tones and all(p == "person" for p in rest):
        rest = []          # 'kiss: person, person, light skin tone' is a tone of 'kiss'
    return (head + (": " + ", ".join(rest) if rest else "")), tones


def build(entries):
    by_name = {}
    bases = []             # [emoji, name, group, subgroup]
    tones = {}             # base emoji -> {tone tuple: emoji}
    for emoji, name, group, sub in entries:
        base_name, t = split_tones(name)
        if t:
            tones.setdefault(by_name[base_name], {})[t] = emoji
        else:
            by_name[name] = emoji
            bases.append((emoji, name, group, sub))
    known = {e for e, *_ in bases}
    two_person = {b for b, ts in tones.items() if any(len(t) == 2 for t in ts)}

    def tone_row(e):
        ts = tones.get(e, {})
        return [e] + [ts[(i,)] for i in range(5) if (i,) in ts]

    def neutral_of(e):
        """(neutral emoji, gender 0/1/2, facing right) for a gendered form, else None."""
        right = e.endswith(RIGHT)
        core = e[:-len(RIGHT)] if right else e
        for n, (w, m) in TRIOS.items():
            if core in (w, m):
                return n, 1 if core == w else 2, right
        for sign, g in ((FEMALE, 1), (MALE, 2)):
            if sign in core:
                n = core.replace(sign, "")
                for cand in (n, n + VS16):
                    if cand in known:
                        return cand, g, right
        for lead, g in ((WOMAN, 1), (MAN, 2)):
            if core.startswith(lead + ZWJ):
                n = PERSON + core[len(lead):]
                if n in known:
                    return n, g, right
        if right:
            return core, 0, True
        return None

    rows = {}              # neutral emoji -> {(gender, right): emoji}
    folded = set()
    for e, *_ in bases:
        if e in two_person:
            continue
        r = neutral_of(e)
        if r is None or r[0] not in known or r[0] in two_person:
            continue
        n, g, right = r
        rows.setdefault(n, {(0, False): n})[(g, right)] = e
        folded.add(e)

    def dropped(e, name):
        # Gendered families and the bare family glyph: the gender-neutral families stand for them.
        members = name.split(":", 1)[1].split(",") if name.startswith("family:") else []
        return name == "family" or any(m.strip() in ("man", "woman", "boy", "girl") for m in members)

    def category(group, sub):
        for i, (_, parts) in enumerate(CATEGORIES):
            for j, (g, subs) in enumerate(parts):
                if g == group and (subs is None or sub in subs):
                    return i, j, (subs.index(sub) if subs else 0)
        print(f"warning: no category for {group} / {sub}", file=sys.stderr)
        return GROUP_DEFAULT[group], 99, 0

    cells = []
    for order, (e, name, group, sub) in enumerate(bases):
        if e in folded or dropped(e, name):
            continue
        cat, part, subpos = category(group, sub)
        # Subgroups of a group that is not taken whole keep their CATEGORIES order; whole groups keep Unicode's.
        key = (cat, part, subpos, order)
        if e in two_person:
            ts = tones[e]
            grid = [[ts[(a,) if a == b else (a, b)] for b in range(5)] for a in range(5)]
            layout = [[e]] + grid
        elif e in rows:
            forms = rows[e]
            layout = [tone_row(forms[k]) for k in sorted(forms, key=lambda k: (k[1], k[0]))]
            if all(len(r) == 1 for r in layout):
                layout = [[r[0] for r in layout]]   # no skin tones: one row  [neutral, woman, man]
        else:
            row = tone_row(e)
            layout = [row] if len(row) > 1 else []
        cells.append((key, cat, e, layout))
    cells.sort(key=lambda c: c[0])
    return [(cat, e, layout) for _, cat, e, layout in cells]


def main():
    src, out = sys.argv[1], sys.argv[2]
    cells = build(parse(src))
    with open(out, "w", encoding="utf-8") as f:
        for cat, e, layout in cells:
            f.write("\t".join([str(cat), e] + [" ".join(r) for r in layout]) + "\n")
    counts = [0] * len(CATEGORIES)
    for cat, _, _ in cells:
        counts[cat] += 1
    print(len(cells), "cells:", ", ".join(f"{CATEGORIES[i][0]} {n}" for i, n in enumerate(counts)))


if __name__ == "__main__":
    main()
