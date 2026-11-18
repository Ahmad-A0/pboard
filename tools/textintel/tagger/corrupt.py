#!/usr/bin/env python3
"""Synthetic grammar errors for training the tagger: injects errors into clean sentences and returns one tag per
word of the corrupted sentence (the tag that restores the original; see gectag.py).

Every family is gated by context so that the corrupted sentence is (almost always) really wrong: a corruption that
leaves a valid sentence would teach the model to flag correct text, which is what precision cannot afford.

Families: confusable words, subject-verb agreement after pronouns (incl. be/have/do), verb form after
modals/do/to, participle after have, -ing after be, a/an, doubled words, article deletion/insertion, preposition
swap/deletion/insertion, noun number after determiners, determiner agreement, auxiliary deletion, past tense with a
past-time marker, run-together words (alot), lowercase i, double comparatives (more taller), could of,
suppose(d) to / use(d) to, let me to.
"""

import random
import re

from gectag import CONFUSABLES, PREP_GROUPS, RUN_TOGETHER, case_like

CONF = {}
for _s in CONFUSABLES:
    for _w in _s:
        CONF.setdefault(_w, set()).update(x for x in _s if x != _w)
CONF = {k: sorted(v) for k, v in CONF.items()}
PREP_ALT = {}
for _g in PREP_GROUPS:
    for _w in _g:
        PREP_ALT.setdefault(_w, set()).update(x for x in _g if x != _w)
PREP_ALT = {k: sorted(v) for k, v in PREP_ALT.items()}
MERGE = {tuple(v.split()): k for k, v in RUN_TOGETHER.items()}

THIRD = {"he", "she", "it", "everyone", "everybody", "someone", "somebody", "nobody", "anyone", "anybody"}
NON3 = {"i", "you", "we", "they"}
SUBJ = THIRD | NON3
MODALS = {"can", "could", "will", "would", "should", "might", "must", "may", "shall", "can't", "couldn't", "won't",
          "wouldn't", "shouldn't", "mustn't", "don't", "doesn't", "didn't", "do", "does", "did", "i'll", "you'll",
          "he'll", "she'll", "we'll", "they'll", "it'll", "cannot"}
HAVE = {"have", "has", "had", "i've", "you've", "we've", "they've", "haven't", "hasn't", "hadn't"}
BE = {"am", "is", "are", "was", "were", "be", "been", "i'm", "you're", "we're", "they're", "isn't", "aren't",
      "wasn't", "weren't"}
NO_DOUBLE = {"that", "had", "very", "really", "so", "no", "bye", "ha", "haha", "yes", "yeah", "ok", "okay", "oh",
             "well", "is", "do", "much", "many", "far", "more", "too", "again", "over", "round", "by", "hey", "hi",
             "please", "go", "now", "never", "on", "and", "lol", "wow", "yay", "hello", "knock", "blah", "far", "bla"}
SG_DET = {"a", "an", "one", "each", "every", "this", "another"}
PL_DET = {"these", "those", "many", "several", "few", "both", "two", "three", "four", "five", "six", "seven",
          "eight", "nine", "ten", "various", "numerous", "twenty", "hundred"}
INS_THE_BEFORE = {"my", "your", "his", "her", "their", "our", "its", "this", "these", "those", "some", "every",
                  "each", "me", "him", "them", "us"}
DETERMINERS = {"a", "an", "the", "my", "your", "his", "her", "their", "our", "its", "this", "that", "these", "those",
               "some", "any", "no", "every", "each", "many", "much", "few", "several", "both", "all", "more", "most"}
EXTRA_PREP = {"discuss": "about", "mention": "about", "describe": "about", "emphasize": "on", "emphasise": "on",
              "attend": "to", "approach": "to", "marry": "with", "lack": "of", "resemble": "to", "contact": "with",
              "request": "for", "consider": "about", "visit": "to", "call": "to", "reach": "to", "return": "back"}
CONTRACT = {"i": "i'm", "you": "you're", "we": "we're", "they": "they're", "he": "he's", "she": "she's", "it": "it's"}
OBJ = {"me", "him", "her", "us", "them", "you", "it"}
PAST_MARK = re.compile(r"\b(yesterday|last (night|week|month|year|weekend|summer|winter|time|monday|tuesday|"
                       r"wednesday|thursday|friday|saturday|sunday)|ago)\b", re.I)

WEIGHTS = {"conf": 3.0, "sva": 2.0, "be_sva": 1.0, "modal_form": 1.5, "perfect": 1.0, "progressive": 1.0,
           "a_an": 1.0, "double": 0.35, "art_del": 1.2, "art_ins": 0.6, "prep_swap": 0.5, "prep_del": 0.6,
           "to_del": 0.6, "prep_ins": 0.4, "noun_num": 1.0, "det_agree": 0.5, "aux_del": 0.7, "tense": 0.5,
           "run_together": 0.5, "i_lower": 0.7, "comparative": 0.4, "could_of": 0.4, "supposed": 0.3, "let_to": 0.2}


def is_word(t):
    return t[:1].isalnum()


class Corruptor:
    def __init__(self, morph, seed=0):
        self.m = morph
        self.rng = random.Random(seed)

    # each site: (family, lo, hi, op) where [lo, hi] is the range of original indices it reads or writes; op is
    # ("rep", i, text, tag) | ("ins", pos, text) | ("del", i, tag_for_prev) | ("merge", i, text, tag)
    def sites(self, toks, text):
        m, rng = self.m, self.rng
        low = [t.lower().replace("’", "'") for t in toks]
        n = len(toks)
        out = []
        past_marker = PAST_MARK.search(text) is not None

        def kinds(w):
            return m.form_kind.get(w, ())

        for i in range(n):
            w = low[i]
            raw = toks[i]
            if not is_word(raw):
                continue
            p = low[i - 1] if i > 0 else None
            nx = low[i + 1] if i + 1 < n else None
            nx_word = nx is not None and is_word(toks[i + 1])
            p_word = p is not None and is_word(toks[i - 1])
            base = m.form_base.get(w)
            ks = kinds(w)
            # confusable words
            if w in CONF:
                alt = rng.choice(CONF[w])
                out.append(("conf", i - 1, i + 1, ("rep", i, case_like(raw, alt), "R:" + w)))
            # agreement after a pronoun subject
            if p in THIRD and "VBZ" in ks and "VB" not in ks and base not in ("be", "have", "do") \
                    and m.verbs[base][0] != base:
                out.append(("sva", i - 1, i + 1, ("rep", i, case_like(raw, base), "VBZ")))
            if p in NON3 and "VB" in ks and base == w and base not in ("be", "have", "do"):
                s3 = m.verbs[base][3]
                if s3 != w:
                    out.append(("sva", i - 1, i + 1, ("rep", i, case_like(raw, s3), "VB")))
            be = None
            if p in ("he", "she", "it"):
                be = {"is": "are", "was": "were", "has": "have", "doesn't": "don't", "does": "do", "isn't": "aren't",
                      "wasn't": "weren't", "hasn't": "haven't"}.get(w)
            elif p in ("you", "we", "they"):
                be = {"are": "is", "were": "was", "have": "has", "don't": "doesn't", "do": "does",
                      "aren't": "isn't", "weren't": "wasn't", "haven't": "hasn't"}.get(w)
            elif p == "i":
                be = {"am": rng.choice(["is", "are"]), "have": "has", "don't": "doesn't", "do": "does"}.get(w)
            if be:
                out.append(("be_sva", i - 1, i + 1, ("rep", i, case_like(raw, be), "R:" + w)))
            # verb form after a modal / do / to
            if p in MODALS and "VB" in ks and base == w and base != "be" and not (p in ("do", "does", "did") and base in m.noun_ambig):
                past, pp, ing, s3 = m.verbs[base]
                alt = rng.choice([past, s3, ing])
                if alt != w:
                    out.append(("modal_form", i - 1, i + 1, ("rep", i, case_like(raw, alt), "VB")))
            if p == "to" and p_word and "VB" in ks and base == w and base not in m.noun_ambig and base not in ("be",):
                past, pp, ing, s3 = m.verbs[base]
                alt = rng.choice([past, s3])
                if alt != w:
                    out.append(("modal_form", i - 1, i + 1, ("rep", i, case_like(raw, alt), "VB")))
            # participle after have
            if p in HAVE and "VBN" in ks and base not in ("be", "have"):
                past, pp, ing, s3 = m.verbs[base]
                if w == pp and past != pp:
                    out.append(("perfect", i - 1, i + 1, ("rep", i, case_like(raw, past), "VBN")))
                elif w == pp and base != pp and rng.random() < 0.3:
                    out.append(("perfect", i - 1, i + 1, ("rep", i, case_like(raw, base), "VBN")))
            # -ing after be
            if p in BE and "VBG" in ks:
                past, pp, ing, s3 = m.verbs[base]
                alt = base if base != pp and rng.random() < 0.6 else s3
                if alt != w and alt != pp:
                    out.append(("progressive", i - 1, i + 1, ("rep", i, case_like(raw, alt), "VBG")))
            # a / an
            if w in ("a", "an") and nx_word and nx[:1].isalpha():
                out.append(("a_an", i, i + 1, ("rep", i, case_like(raw, "an" if w == "a" else "a"), "R:" + w)))
            # doubled word
            if raw.isalpha() and w not in NO_DOUBLE and (len(w) <= 5 or rng.random() < 0.3) and nx is not None:
                out.append(("double", i, i + 1, ("ins", i + 1, raw)))
            # article deletion
            if w in ("a", "an") and p_word and nx_word and nx in m.sg2pl and nx not in m.form_kind:
                out.append(("art_del", i - 1, i + 1, ("del", i, "A:" + w)))
            if w == "the" and p_word and nx_word and nx in m.sg2pl and nx not in m.pl2sg and rng.random() < 0.5 \
                    and p not in DETERMINERS:
                out.append(("art_del", i - 1, i + 1, ("del", i, "A:the")))
            # article insertion
            if i >= 1 and w in INS_THE_BEFORE and p not in DETERMINERS and p_word:
                out.append(("art_ins", i - 1, i, ("ins", i, "the")))
            if i >= 1 and w in m.pl2sg and w not in m.sg2pl and p not in DETERMINERS and p not in PL_DET \
                    and p_word and not p.isdigit() and w not in m.form_kind:
                out.append(("art_ins", i - 1, i, ("ins", i, "a")))
            # prepositions
            if w in PREP_ALT and p_word and nx_word:
                out.append(("prep_swap", i - 1, i + 1, ("rep", i, case_like(raw, rng.choice(PREP_ALT[w])), "R:" + w)))
            if w in ("of", "in", "on", "at", "for", "with", "about", "from") and p_word and nx_word and i >= 1:
                out.append(("prep_del", i - 1, i + 1, ("del", i, "A:" + w)))
            if w == "to" and p_word and nx_word and "VB" in kinds(nx) and p not in MODALS:
                out.append(("to_del", i - 1, i + 1, ("del", i, "A:to")))
            if p in MODALS and "VB" in ks and base != "be" and i >= 1:
                out.append(("prep_ins", i - 1, i, ("ins", i, "to")))
            pb = m.form_base.get(p) if p else None
            if pb in EXTRA_PREP and nx is not None and w not in PREP_ALT and w not in ("back", "out", "up", "down") \
                    and (w in DETERMINERS or w in OBJ):
                out.append(("prep_ins", i - 1, i, ("ins", i, EXTRA_PREP[pb])))
            if p in ("let", "make", "made", "makes", "lets") and w in OBJ and nx_word and "VB" in kinds(nx):
                out.append(("let_to", i - 1, i + 2, ("ins", i + 1, "to")))
            # noun number
            if p in SG_DET and w in m.sg2pl and (p != "this" or w not in m.form_kind):
                out.append(("noun_num", i - 1, i + 1, ("rep", i, case_like(raw, m.sg2pl[w]), "NSG")))
            if (p in PL_DET or (p is not None and p.isdigit() and p not in ("1", "0"))) and w in m.pl2sg \
                    and w not in m.sg2pl:
                out.append(("noun_num", i - 1, i + 1, ("rep", i, case_like(raw, m.pl2sg[w]), "NPL")))
            # determiner agreement
            if w in ("this", "that") and nx in m.sg2pl and nx not in m.form_kind and nx not in m.pl2sg:
                out.append(("det_agree", i, i + 1, ("rep", i, case_like(raw, "these" if w == "this" else "those"),
                                                    "R:" + w)))
            if w in ("these", "those") and nx in m.pl2sg and nx not in m.sg2pl:
                out.append(("det_agree", i, i + 1, ("rep", i, case_like(raw, "this" if w == "these" else "that"),
                                                    "R:" + w)))
            if w == "many" and nx in m.pl2sg and nx not in m.sg2pl:
                out.append(("det_agree", i, i + 1, ("rep", i, case_like(raw, "much"), "R:many")))
            # auxiliary deletion: "I am going" -> "I going", "I'm going" -> "I going", "he has gone" -> "he gone"
            if p in SUBJ and w in ("am", "is", "are", "was", "were") and nx_word and "VBG" in kinds(nx) and i >= 1:
                out.append(("aux_del", i - 1, i + 1, ("del", i, "A:" + w)))
            if p in SUBJ and w in ("have", "has") and nx_word and "VBN" in kinds(nx) and "VBD" not in kinds(nx):
                out.append(("aux_del", i - 1, i + 1, ("del", i, "A:" + w)))
            if w in ("i'm", "you're", "we're", "they're", "he's", "she's", "it's") and nx_word \
                    and "VBG" in kinds(nx):
                bare = w.split("'")[0]
                out.append(("aux_del", i, i + 1, ("rep", i, case_like(raw, bare), "R:" + w)))
            # past tense with a past-time marker
            if past_marker and p in SUBJ and "VBD" in ks and "VB" not in ks and base not in ("be",):
                past, pp, ing, s3 = m.verbs[base]
                alt = s3 if p in ("he", "she", "it") else base
                if alt != w:
                    out.append(("tense", i - 1, i + 1, ("rep", i, case_like(raw, alt), "VBD")))
            # run-together words
            if nx is not None and (w, nx) in MERGE and text_gap_ok(toks, i):
                out.append(("run_together", i, i + 1, ("merge", i, case_like(raw, MERGE[(w, nx)]),
                                                       "R:" + w + " " + nx)))
            # lowercase i
            if raw in ("I", "I'm", "I've", "I'll", "I'd"):
                out.append(("i_lower", i, i, ("rep", i, "i" + raw[1:], "CASE_CAP")))
            # double comparative
            if (w in m.comps or w in m.sups) and p not in ("more", "most", "much", "even", "far", "a", "lot", "the",
                                                           "any", "no", "little", "bit", "way") and i >= 1:
                out.append(("comparative", i - 1, i, ("ins", i, "more" if w in m.comps else "most")))
            # could of
            if w == "have" and p in ("could", "would", "should", "must", "might") and nx_word and "VBN" in kinds(nx):
                out.append(("could_of", i - 1, i + 1, ("rep", i, case_like(raw, "of"), "R:have")))
            # suppose to / use to
            if w == "supposed" and nx == "to":
                out.append(("supposed", i, i + 1, ("rep", i, case_like(raw, "suppose"), "VBN")))
            if w == "used" and nx == "to" and p not in ("did", "didn't", "do", "does", "don't", "doesn't", "get",
                                                         "got", "be", "is", "was", "are", "were", "am", "i'm",
                                                         "been", "getting", "being") and i >= 1:
                out.append(("supposed", i, i + 1, ("rep", i, case_like(raw, "use"), "VBD")))
        return out

    def corrupt(self, toks, spaces, text, k):
        """toks/spaces: the clean sentence's words and space-before flags. Returns (toks, spaces, tags, families)."""
        rng = self.rng
        tags = ["KEEP"] * len(toks)
        if k == 0:
            return toks, spaces, tags, []
        by_fam = {}
        for s in self.sites(toks, text):
            by_fam.setdefault(s[0], []).append(s)
        chosen = []
        taken = set()
        for _ in range(k):
            fams = [f for f in by_fam if by_fam[f]]
            if not fams:
                break
            f = rng.choices(fams, weights=[WEIGHTS[x] for x in fams])[0]
            cand = by_fam[f]
            s = cand.pop(rng.randrange(len(cand)))
            lo, hi = max(s[1], 0), s[2]
            if any(j in taken for j in range(lo, hi + 1)):
                continue
            taken.update(range(lo, hi + 1))
            chosen.append(s)
        toks, spaces = list(toks), list(spaces)
        # apply right to left so that earlier indices stay valid
        def pos(s):
            op = s[3]
            return op[1]
        for s in sorted(chosen, key=pos, reverse=True):
            op = s[3]
            if op[0] == "rep":
                _, i, t, tag = op
                toks[i] = t
                tags[i] = tag
            elif op[0] == "ins":
                _, i, t = op
                toks.insert(i, t)
                spaces.insert(i, True)
                tags.insert(i, "DELETE")
            elif op[0] == "del":
                _, i, tag = op
                del toks[i], spaces[i], tags[i]
                tags[i - 1] = tag
            elif op[0] == "merge":
                _, i, t, tag = op
                toks[i:i + 2] = [t]
                spaces[i:i + 2] = [spaces[i]]
                tags[i:i + 2] = [tag]
        return toks, spaces, tags, [s[0] for s in chosen]


def text_gap_ok(toks, i):
    return True
