#!/usr/bin/env python3
"""Shared pieces of the GECToR-style grammar tagger: word tokenisation, the tag vocabulary, English morphology
and turning predicted tags into character edits.

One tag per word says how to fix that word:
    KEEP                    nothing to do
    DELETE                  remove the word (and the space before it)
    CASE_CAP                capitalise it ("i" -> "I")
    VB VBZ VBD VBN VBG      re-inflect a verb (base, 3rd person -s, past, participle, -ing)
    NSG NPL                 make a noun singular / plural
    R:<w>                   replace the word with <w> (closed vocabulary: confusables and function words)
    A:<w>                   keep the word and append <w> after it (closed vocabulary)

Morphology: verbs and adjectives from app/src/main/assets/textintel/en_morph.txt (AGID-derived), nouns built here
from AGID's infl.txt restricted to frequent words (AGID: Kevin Atkinson, permissive licence; see NOTICE).
"""

import re

WORD_RE = re.compile(r"[A-Za-z0-9]+(?:['’][A-Za-z]+)*|[^\sA-Za-z0-9]")

# --- vocabulary -------------------------------------------------------------------------------------------------

CONFUSABLES = [
    ["their", "there", "they're"], ["your", "you're"], ["its", "it's"], ["whose", "who's"], ["then", "than"],
    ["to", "too", "two"], ["lose", "loose"], ["affect", "effect"], ["were", "where", "we're"],
    ["weather", "whether"], ["accept", "except"], ["advice", "advise"], ["quiet", "quite"], ["passed", "past"],
    ["peace", "piece"], ["hear", "here"], ["of", "off"], ["breath", "breathe"], ["brake", "break"],
    ["principal", "principle"], ["know", "no", "now"], ["knew", "new"], ["buy", "by", "bye"], ["which", "witch"],
    ["allowed", "aloud"], ["threw", "through"], ["week", "weak"], ["whole", "hole"], ["write", "right"],
    ["though", "thought"], ["are", "our"], ["an", "and"], ["wait", "weight"], ["meet", "meat"], ["lead", "led"],
    ["from", "form"], ["let's", "lets"], ["clothes", "cloths"], ["desert", "dessert"], ["site", "sight"],
    ["compliment", "complement"], ["sense", "since"], ["wear", "where"], ["sale", "sell"], ["role", "roll"],
    ["fair", "fare"], ["plain", "plane"], ["tail", "tale"], ["waist", "waste"], ["mail", "male"],
]

ARTICLES = ["a", "an", "the"]
PREPS = ["in", "on", "at", "to", "for", "of", "with", "from", "about", "by", "into", "since", "during", "over"]
PREP_GROUPS = [["in", "on", "at"], ["to", "for"], ["of", "for"], ["with", "by"], ["about", "of", "on"],
               ["from", "of"], ["into", "in"], ["at", "to"], ["since", "for"], ["during", "for"], ["in", "into"],
               ["on", "about"], ["with", "to"]]
DETS = ["this", "these", "that", "those", "much", "many"]
AUX = ["is", "are", "am", "was", "were", "be", "been", "has", "have", "had", "do", "does", "did", "don't",
       "doesn't", "didn't", "isn't", "aren't", "wasn't", "weren't", "hasn't", "haven't", "will", "i'm", "he's",
       "she's"]
RUN_TOGETHER = {"alot": "a lot", "aswell": "as well", "infact": "in fact", "eachother": "each other",
                "atleast": "at least", "noone": "no one", "thankyou": "thank you", "ofcourse": "of course",
                "infront": "in front", "abit": "a bit", "alittle": "a little", "everytime": "every time",
                "incase": "in case"}
APPEND_WORDS = ["the", "a", "an", "to", "of", "in", "on", "at", "for", "with", "about", "from", "is", "are", "am",
                "was", "were", "be", "been", "have", "has", "will", "it", "that"]

SIMPLE_TAGS = ["KEEP", "DELETE", "CASE_CAP", "VB", "VBZ", "VBD", "VBN", "VBG", "NSG", "NPL"]


def build_tags():
    rep = []
    for s in CONFUSABLES:
        rep += s
    rep += ARTICLES + PREPS + DETS + AUX + list(RUN_TOGETHER.values())
    seen = set()
    rep = [w for w in rep if not (w in seen or seen.add(w))]
    return SIMPLE_TAGS + ["R:" + w for w in rep] + ["A:" + w for w in APPEND_WORDS]


TAGS = build_tags()
TAG_ID = {t: i for i, t in enumerate(TAGS)}

# --- morphology -------------------------------------------------------------------------------------------------


class Morph:
    def __init__(self, morph_path, nouns_path=None):
        self.verbs = {}       # base -> (past, pp, ing, s3)
        self.noun_ambig = set()
        self.comps = {}       # comparative -> base
        self.sups = {}        # superlative -> base
        self.adjs = set()
        self.form_base = {}   # any verb form -> base (most frequent verb first)
        self.form_kind = {}   # form -> set of kinds {VB, VBD, VBN, VBG, VBZ}
        for line in open(morph_path, encoding="utf-8"):
            p = line.split()
            if not p or p[0].startswith("#"):
                continue
            if p[0] == "V":
                b, past, pp, ing, s3 = p[1:6]
                self.verbs[b] = (past, pp, ing, s3)
                if len(p) > 6 and p[6] == "n":
                    self.noun_ambig.add(b)
                for k, f in (("VB", b), ("VBD", past), ("VBN", pp), ("VBG", ing), ("VBZ", s3)):
                    self.form_base.setdefault(f, b)
                    self.form_kind.setdefault(f, set()).add(k)
            elif p[0] == "A":
                self.adjs.add(p[1])
                self.comps[p[2]] = p[1]
                self.sups[p[3]] = p[1]
        self.sg2pl, self.pl2sg = {}, {}
        if nouns_path:
            for line in open(nouns_path, encoding="utf-8"):
                sg, pl = line.split()
                if sg in self.adjs or sg in ADJ_NOT_NOUNS:
                    continue  # "good goods", "red reds": adjectives that AGID also lists as nouns
                self.sg2pl.setdefault(sg, pl)
                self.pl2sg.setdefault(pl, sg)

    def verb_form(self, word, kind):
        """The `kind` form of the verb `word` (lowercase in, lowercase out) or None."""
        b = self.form_base.get(word)
        if b is None:
            return None
        past, pp, ing, s3 = self.verbs[b]
        return {"VB": b, "VBD": past, "VBN": pp, "VBG": ing, "VBZ": s3}[kind]

    def noun_form(self, word, kind):
        if kind == "NPL":
            return self.sg2pl.get(word)
        return self.pl2sg.get(word)


SG_CUES = {"the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that", "every", "each", "another"}
PL_CUES = {"the", "my", "your", "his", "her", "our", "their", "these", "those", "many", "some", "few", "several",
           "two", "three", "all", "both", "other"}


ADJ_NOT_NOUNS = {"right", "fine", "special", "specific", "extra", "local", "black", "white", "red", "blue", "green",
                 "short", "regular", "original", "general", "major", "total", "favorite", "favourite", "particular",
                 "main", "current", "high", "low", "great", "good", "wrong", "future", "individual", "professional",
                 "chemical", "natural", "personal", "official", "final", "potential", "national", "international",
                 "mobile", "classic", "daily", "weekly", "monthly", "private", "public", "positive", "negative"}
NOT_NOUNS = {"one", "other", "second", "third", "four", "five", "six", "seven", "eight", "nine", "ten", "first",
             "last", "next", "few", "many", "much", "more", "most", "less", "least", "self", "half"}


def noun_usage(text_paths, max_lines=3000000):
    """Counts how often each word follows a determiner, from running text: AGID lists "like", "know", "think" as
    nouns too, and the noun table must only hold words that really are used as nouns."""
    import collections
    tot, sg, pl = collections.Counter(), collections.Counter(), collections.Counter()
    n = 0
    for p in text_paths:
        for line in open(p, encoding="utf-8"):
            ws = line.lower().split()
            tot.update(ws)
            for a, b in zip(ws, ws[1:]):
                if a in SG_CUES:
                    sg[b] += 1
                if a in PL_CUES:
                    pl[b] += 1
            n += 1
            if n >= max_lines:
                return tot, sg, pl
    return tot, sg, pl


def build_nouns(agid_path, lexicon_path, out_path, top=30000, usage=None, min_ratio=0.12):
    """Writes "singular plural" lines for frequent AGID nouns whose plural differs from the singular and (with
    usage counts) whose singular and plural both follow a determiner often enough to be nouns in practice."""
    rank = {}
    for i, line in enumerate(open(lexicon_path, encoding="utf-8")):
        if i >= top:
            break
        rank.setdefault(line.split("\t", 1)[0], i)
    if usage is not None:
        tot, sgc, plc = usage

        def nounish(w, c):
            return tot[w] >= 5 and c[w] / tot[w] >= min_ratio
    else:
        def nounish(w, c):
            return True
    ent = re.compile(r"^([a-z']+) N: (.*)$")
    out = []
    for line in open(agid_path, encoding="utf-8"):
        m = ent.match(line.rstrip("\n"))
        if not m or m.group(1) not in rank:
            continue
        sg = m.group(1)
        first = m.group(2).split(" | ")[0].split(", ")[0]
        first = re.sub(r"\{[^}]*\}", "", first).strip().split()
        if not first or "?" in first[0]:
            continue
        pl = first[0].rstrip("~<!")
        if len(first) > 1 and re.match(r"^[0-9.]+$", first[1]) and float(first[1]) >= 1:
            continue
        if pl != sg and re.match(r"^[a-z]+$", pl) and pl in rank and len(sg) > 2 and sg not in NOT_NOUNS \
                and (usage is None or (nounish(sg, sgc) and nounish(pl, plc) and tot[pl] >= 0.03 * tot[sg])):
            out.append((rank[sg], sg, pl))
    out.sort()
    with open(out_path, "w", encoding="utf-8") as f:
        for _, sg, pl in out:
            f.write(f"{sg} {pl}\n")
    return len(out)


# --- tokens and edits -------------------------------------------------------------------------------------------


def words(text):
    """[(word, start, end)] with the evaluation sets' tokenisation (contractions stay one word)."""
    return [(m.group(0), m.start(), m.end()) for m in WORD_RE.finditer(text)]


def case_like(orig, w):
    if w.split("'")[0] == "i" or w.split("’")[0] == "i":
        w = "I" + w[1:]
    if orig[:1].isupper() and len(orig) > 1 and orig.isupper() and len(w) > 1:
        return w.upper()
    if orig[:1].isupper():
        return w[:1].upper() + w[1:]
    return w


def case_class(w):
    """0: no capitals, 1: capitalised (or a single capital letter), 2: all capitals."""
    letters = [c for c in w if c.isalpha()]
    if not letters or not letters[0].isupper():
        return 0 if not any(c.isupper() for c in letters) else 1
    if len(letters) > 1 and all(c.isupper() for c in letters):
        return 2
    return 1


REDUP_OK = {"so", "ha", "haha", "hah", "bye", "no", "way", "very", "really", "yeah", "yes", "ok", "okay", "oh", "well",
            "now", "had", "that", "hey", "hi", "please", "never", "wow", "yay", "hello", "knock", "blah", "bla", "lol",
            "far", "again", "over", "round", "go", "hip", "boo", "chop", "tsk", "uh", "um", "hmm", "la", "na", "bang"}


def tag_to_edit(text, toks, i, tag, morph):
    """Character edit {"cs", "ce", "cor"} for tag on word i, or None when the tag cannot be applied."""
    w, s, e = toks[i]
    low = w.lower().replace("’", "'")
    if tag == "KEEP":
        return None
    if tag == "DELETE":
        if i > 0 and toks[i - 1][0].lower().replace("’", "'") == low and (low in REDUP_OK or (w[:1].isupper() and w != "I")):
            return None  # deliberate reduplication ("so so", "ha ha", "far far") or a name ("Jar Jar", "Two Two")
        if i > 0:
            return {"cs": toks[i - 1][2], "ce": e, "cor": ""}
        if len(toks) > 1:
            return {"cs": s, "ce": toks[1][1], "cor": ""}
        return {"cs": s, "ce": e, "cor": ""}
    if tag == "CASE_CAP":
        if not w[:1].islower():
            return None
        return {"cs": s, "ce": e, "cor": w[:1].upper() + w[1:]}
    if tag in ("VB", "VBZ", "VBD", "VBN", "VBG"):
        f = morph.verb_form(low, tag)
        if f is None or f == low:
            return None
        return {"cs": s, "ce": e, "cor": case_like(w, f)}
    if tag in ("NSG", "NPL"):
        f = morph.noun_form(low, tag)
        if f is None or f == low:
            return None
        return {"cs": s, "ce": e, "cor": case_like(w, f)}
    if tag.startswith("R:"):
        t = tag[2:]
        if t == low:
            return None
        return {"cs": s, "ce": e, "cor": case_like(w, t)}
    if tag.startswith("A:"):
        t = tag[2:]
        if t in ("i",):
            t = "I"
        return {"cs": e, "ce": e, "cor": " " + t}
    return None
