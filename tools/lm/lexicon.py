"""Build the lexicon of a locale: word list with frequencies, canonical capitalisation and flags.

Inputs:  tools/data/work/<locale>/text/*.counts.tsv (train splits), wordfreq 'large' list, optional spell
         list (SCOWL for English), curated tools/lm/data/{offensive,misspellings}_<lang>.txt.
Outputs: tools/data/work/<locale>/lexicon.tsv   word <TAB> frequency <TAB> flags   (sorted by frequency)
         tools/data/work/<locale>/repair.tsv    form <TAB> word   (e.g. dont -> don't; used to encode text)

Usage: tools/lm/run.sh lexicon.py <locale>
"""

import collections
import math
import os
import re as stdre
import sys
import tarfile

import regex as re

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import langs  # noqa: E402
from paths import LM_DATA, RAW, work  # noqa: E402
from textnorm import NUM, UNK, COMMA, TextNorm  # noqa: E402

F_OFFENSIVE = 1
F_NO_SPACE_AFTER = 2   # elided clitic: the next word attaches without a space ("l'" + "homme")

W_CHAT, W_GEN, W_WF = 0.55, 0.30, 0.15     # how lexicon frequencies blend the evidence
CHAT_CASE_WEIGHT = 0.3                      # chat text is sloppy about case: trust it less for casing
MIN_VARIANT_SHARE, MIN_VARIANT_COUNT = 0.04, 10
MIN_FREE_LOWER_FREQ = 2e-8                  # floor for lowercase words not in the spell list


def load_counts(text_dir, names):
    out = {}
    for n in names:
        path = os.path.join(text_dir, f"{n}.counts.tsv")
        d = {}
        with open(path, encoding="utf-8") as f:
            for line in f:
                tok, mid, init = line.rstrip("\n").split("\t")
                d[tok] = (int(mid), int(init))
        out[n] = d
    return out


def load_scowl(regions=("american", "british"), max_level=80):
    """Map lowercase word -> (min level, set of cased spellings) from SCOWL lists (English + given regions)."""
    tf = tarfile.open(os.path.join(RAW, "scowl", "scowl-2020.12.07.tar.gz"))
    out = {}
    pat = stdre.compile(r"/final/(english|variant_1|" + "|".join(regions) +
                        r")-(words|upper|contractions|proper-names|abbreviations)\.(\d+)$")
    for m in tf.getmembers():
        mm = pat.search(m.name)
        if not mm or int(mm.group(3)) > max_level:
            continue
        level = int(mm.group(3))
        for w in tf.extractfile(m).read().decode("iso-8859-1").split("\n"):
            w = w.strip()
            if not w:
                continue
            lv, forms = out.get(w.lower(), (999, set()))
            forms.add(w)
            out[w.lower()] = (min(lv, level), forms)
    return out


def load_varcon(max_level=70):
    """Parse VarCon into rows: {"level", "annotated", "words": {word: set(tags)}}.

    Tags are e.g. "A", "B", "Bv", "Z" (see VarCon README).  Only clusters at SCOWL level <= max_level."""
    tf = tarfile.open(os.path.join(RAW, "varcon", "varcon-2020.12.07.tar.gz"))
    text = tf.extractfile("varcon-2020.12.07/varcon.txt").read().decode("iso-8859-1")
    rows, level = [], 999
    for line in text.split("\n"):
        if line.startswith("# "):
            m = stdre.search(r"\(level (\d+)\)", line)
            level = int(m.group(1)) if m else 999
            continue
        if not line.strip() or line.startswith("#") or level > max_level:
            continue
        body, _, note = line.partition("|")
        words = {}
        for part in body.split(" / "):
            tags, _, word = part.partition(": ")
            if word.strip():
                words[word.strip()] = set(tags.split())
        rows.append({"level": level, "annotated": bool(note.strip()), "words": words})
    return rows


def primary(row, cat):
    """The preferred spelling of a VarCon row for category cat ('A' or 'B'), or None."""
    for w, tags in row["words"].items():
        if cat in tags:
            return w
    return None


def load_offensive(lang):
    exact, roots = set(), []
    path = os.path.join(LM_DATA, f"offensive_{lang}.txt")
    if os.path.exists(path):
        lines = open(path, encoding="utf-8").read().split("\n")
    else:  # no curated list yet: fall back to the raw LDNOOBW single words
        p = os.path.join(RAW, "ldnoobw", f"{lang}.txt")
        lines = [w for w in open(p, encoding="utf-8").read().split("\n") if " " not in w] if os.path.exists(p) else []
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("*"):
            roots.append(line[1:])
        elif line.endswith("+"):
            base = line[:-1]
            exact.add(base)
            for suf in ("s", "es", "ed", "ing", "er", "ers", "y", "in", "in'"):
                exact.add(base + suf)
                if base.endswith("e"):
                    exact.add(base[:-1] + suf)
        else:
            exact.add(line)
    return exact, roots


def load_misspellings(lang):
    """Returns (misspelled keys, keys whose lowercase spelling only is banned, repairs)."""
    path = os.path.join(LM_DATA, f"misspellings_{lang}.txt")
    bad, bad_lower, fix = set(), set(), {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.split("#")[0].strip()
            if not line:
                continue
            w, _, corr = line.partition("->")
            w = w.strip()
            if w.endswith("!"):  # "id! -> I'd": lowercase "id" is a typo, "ID" stays a word
                w = w[:-1]
                bad_lower.add(w)
            else:
                bad.add(w)
            if corr.strip():
                fix[w] = corr.strip()
    return bad, bad_lower, fix


def lemmas(w):
    """Candidate base forms of an English inflection (plural, past, -ing, comparative, -ly, possessive)."""
    out = set()
    if w.endswith("'s"):
        out.add(w[:-2])
    for suf in ("s", "es", "ed", "ing", "er", "est", "ers", "ly", "d"):
        if w.endswith(suf) and len(w) - len(suf) >= 3:
            base = w[: -len(suf)]
            out.add(base)
            if suf in ("ed", "ing", "er", "est", "ers"):
                out.add(base + "e")                               # hoped -> hope
                if len(base) >= 2 and base[-1] == base[-2]:
                    out.add(base[:-1])                            # stopped -> stop
            if base.endswith("i") and suf in ("es", "ed", "er", "est", "ers", "ly"):
                out.add(base[:-1] + "y")                          # cities -> city, happily -> happy
    if w.endswith("ies"):
        out.add(w[:-3] + "y")
    return out


def deletes1(w):
    return {w[:i] + w[i + 1:] for i in range(len(w))}


def main(locale):
    L = langs.get(locale)
    tn = TextNorm(L)
    text_dir = work(locale, "text")
    out_dir = work(locale)
    script_ok = re.compile(r"^[\p{Latin}\p{M}']+$") if L.lang not in ("ru", "uk", "el", "ar", "he", "hi") else None
    word_re = re.compile(r"^(?:'?[\p{L}\p{M}]+(?:'[\p{L}\p{M}]+)*)$")

    chat = load_counts(text_dir, L.chat_sources)
    gen = load_counts(text_dir, L.general_sources)

    # ---- aggregate per lowercase key
    cnt = {"chat": collections.Counter(), "gen": collections.Counter()}
    casing = collections.defaultdict(collections.Counter)   # lk -> variant -> weighted mid-sentence count
    init_case = collections.defaultdict(collections.Counter)
    nsrc = collections.Counter()
    totals = {"chat": 0, "gen": 0}
    for group, srcs in (("chat", chat), ("gen", gen)):
        for d in srcs.values():
            seen = set()
            for tok, (mid, init) in d.items():
                if tok in (NUM, UNK, COMMA) or not word_re.match(tok):
                    continue
                lk = tn.lower(tok)
                cnt[group][lk] += mid + init
                totals[group] += mid + init
                casing[lk][tok] += mid * (1.0 if group == "gen" else CHAT_CASE_WEIGHT)
                init_case[lk][tok] += init
                if lk not in seen and mid + init >= 2:
                    seen.add(lk)
                    nsrc[lk] += 1
    import wordfreq
    try:
        wf = wordfreq.get_frequency_dict(L.lang, wordlist="large")
    except Exception:
        wf = wordfreq.get_frequency_dict(L.lang, wordlist="best")
    wf = {tn.lower(k): v for k, v in wf.items()}

    regions = tuple(sorted({r for d in L.dialects.values() for r in d.scowl})) or ("american",)
    scowl = load_scowl(regions) if L.spell_lists == "scowl" else {}
    varcon = load_varcon() if "varcon" in L.lexicon_sources else []
    offensive_exact, offensive_roots = load_offensive(L.lang)
    misspelled, misspelled_lower, repairs_manual = load_misspellings(L.lang)

    def freq(lk):
        return (W_CHAT * cnt["chat"][lk] / max(1, totals["chat"]) + W_GEN * cnt["gen"][lk] / max(1, totals["gen"])
                + W_WF * wf.get(lk, 0.0))

    keys = set(cnt["chat"]) | set(cnt["gen"]) | set(wf)
    print(f"[lexicon] {len(keys):,} candidate keys", flush=True)

    # ---- hard filters
    def well_formed(lk):
        if not word_re.match(lk) or len(lk) > 30:
            return False
        if script_ok and not script_ok.match(lk):
            return False
        if re.search(r"(.)\1\1", lk):
            return False
        if lk.startswith("'") and lk not in L.leading_apostrophe:
            return False
        if lk.endswith("'") and lk not in L.clitics:
            return False
        return True

    f = {}
    for lk in keys:
        if well_formed(lk) and lk not in misspelled:
            f[lk] = freq(lk)
    print(f"[lexicon] {len(f):,} well-formed keys", flush=True)

    # apostrophe-less spellings of frequent contractions ("dont", "id") are typos, unless a real word
    def is_contraction(lk):
        if any(lk.endswith(s) for s in L.contraction_suffixes):
            return True
        stem, apos, clitic = lk.rpartition("'")
        return bool(apos) and clitic in ("m", "re", "ve", "ll", "d", "s") and stem in L.contraction_stems

    contraction_of = {}
    for lk, v in f.items():
        if "'" in lk and v > 0 and is_contraction(lk):
            bare = lk.replace("'", "")
            if bare != lk and (bare not in contraction_of or f[contraction_of[bare]] < v):
                contraction_of[bare] = lk
    banned_lower = set(misspelled_lower)
    for bare, lk in contraction_of.items():
        if bare in f and f[lk] >= 3 * f[bare] and scowl.get(bare, (999,))[0] > 50:
            banned_lower.add(bare)

    # typo detection: rare words within one edit of a much more frequent word. Contractions are not used as
    # neighbours ("donut" is not a typo of "don't"; apostrophe-less contractions are handled above), and words
    # that wordfreq attests well (Twitter/Reddit/subtitles/books) are real words even if short ("pls", "thx").
    strong = [lk for lk, v in f.items() if v >= 1e-6 and "'" not in lk and (not scowl or lk in scowl)]
    del_index = collections.defaultdict(list)
    for w in strong:
        del_index[w].append(w)
        for d in deletes1(w):
            del_index[d].append(w)

    def is_typo(lk, v):
        if v >= 3e-6:
            return False
        evidence = wf.get(lk, 0.0)
        if evidence >= 1e-6:
            return False
        best = 0.0
        for k in deletes1(lk) | {lk}:
            for w in del_index.get(k, ()):
                if w != lk and _edit1(lk, w):
                    best = max(best, f[w])
        return best >= (30 if evidence < 3e-7 else 300) * v

    # ---- membership
    def support(lk):
        return (cnt["chat"][lk] >= 2) + (cnt["gen"][lk] >= 2) + (lk in wf)

    accepted = set()
    free = []
    for lk, v in f.items():
        if len(lk) == 1 and v < 1e-3 and lk not in L.single_letter_words:
            continue
        lv = scowl.get(lk, (999,))[0] if scowl else 999
        if lv <= 50 and support(lk) >= 1 and v > 0:
            accepted.add(lk)
        elif lv <= 70 and v >= 1.5e-8 and support(lk) >= 1:
            accepted.add(lk)
        elif lv <= 80 and v >= 1e-7:
            accepted.add(lk)
        elif v >= 4e-9 and support(lk) >= 2:
            free.append((v, lk))
    free.sort(reverse=True)
    n_spell = len(accepted)
    for v, lk in free:
        if len(accepted) >= L.lexicon_target:
            break
        if not is_typo(lk, v):
            accepted.add(lk)
    # inflections of accepted words are words even when individually rare ("calderas", "harassments"): accept
    # them when the spell list has them, or when they are attested in two sources
    n_before = len(accepted)
    for lk, v in f.items():
        if lk in accepted or len(lk) < 4:
            continue
        lv = scowl.get(lk, (999,))[0] if scowl else 999
        if not (lv <= 60 or (lv <= 80 and support(lk) >= 1) or support(lk) >= 2):
            continue
        if any(stem in accepted and stem not in banned_lower for stem in lemmas(lk)):
            accepted.add(lk)
    print(f"[lexicon] accepted {len(accepted):,} keys ({n_spell:,} via spell list, {len(accepted) - n_before:,} "
          f"inflections of accepted words)", flush=True)

    # ---- dialect spelling variants (VarCon): both spellings are always known; unambiguous pairs form one
    # n-gram class so the shared model pools their statistics and each dialect overlay picks its spelling.
    ambiguous = {w for row in varcon if row["annotated"] for w in row["words"]}
    class_of = {}
    n_added = 0
    for row in varcon:
        a, b = primary(row, "A"), primary(row, "B")
        if not a or not b or a == b or a != a.lower() or b != b.lower():
            continue
        if (a in accepted) or (b in accepted):
            for w in (a, b):
                if w not in accepted and w in f and well_formed(w):
                    accepted.add(w)
                    n_added += 1
        if not row["annotated"] and a not in ambiguous and b not in ambiguous and a in accepted and b in accepted:
            class_of[a] = a
            class_of[b] = a
    print(f"[lexicon] VarCon: {n_added} variant spellings added, {len(class_of) // 2:,} spelling classes", flush=True)

    # ---- casing variants
    entries = []  # (word, freq, flags)

    def offensive(word):
        lw = tn.lower(word)
        if lw in offensive_exact:
            return word == lw or not word[:1].isupper()  # "dick" yes, "Dick" (name) no
        return any(r in lw for r in offensive_roots) and not (word[:1].isupper() and word[1:] == word[1:].lower())

    for lk in accepted:
        var = casing.get(lk, {})
        tot = sum(var.values())
        chosen = []
        if tot >= 3:
            ranked = sorted(var.items(), key=lambda kv: -kv[1])
            for i, (w, c) in enumerate(ranked):
                if c <= 0:
                    continue
                share = c / tot
                if lk in banned_lower and w == lk:
                    continue
                is_caps = len(w) > 1 and w.upper() == w and w.lower() != w
                if is_caps and len(w) >= 5 and share < 0.5:
                    continue
                if not chosen or (share >= MIN_VARIANT_SHARE and c >= MIN_VARIANT_COUNT and len(chosen) < 3):
                    chosen.append((w, c))
        if not chosen:
            # little mid-sentence evidence: spell-list casing, else the casing actually observed (wordfreq is
            # all-lowercase, so it says nothing about case), else lowercase
            forms = scowl.get(lk, (0, set()))[1] if scowl else set()
            seen_mid = [(w, c) for w, c in var.items() if c > 0]
            if forms:
                w = sorted(forms, key=lambda x: (x == x.lower(), x))[0] if lk not in forms else lk
            elif seen_mid:
                w = max(seen_mid, key=lambda kv: kv[1])[0]
            elif init_case.get(lk):
                w = init_case[lk].most_common(1)[0][0]
            else:
                w = lk
            if lk in banned_lower and w == lk:
                continue
            chosen = [(w, 1.0)]
        # rare lowercase words outside the spell list are mostly foreign words, lowercased names and jargon:
        # as "known" words they would block autocorrection, so keep only reasonably frequent ones
        if lk not in scowl and chosen[0][0] == lk and f[lk] < MIN_FREE_LOWER_FREQ:
            continue
        # an all-lowercase variant of a word whose main form is capitalised ("iphone" next to "iPhone") is
        # sloppy typing, not a separate word, unless it is common in its own right
        if len(chosen) > 1 and chosen[0][0] != lk:
            tot_c = sum(c for _, c in chosen)
            chosen = [(w, c) for w, c in chosen if w != lk or c >= 0.25 * tot_c]
        z = sum(c for _, c in chosen) or 1.0
        for w, c in chosen:
            flags = 0
            if offensive(w):
                flags |= F_OFFENSIVE
            if tn.lower(w) in L.clitics:
                flags |= F_NO_SPACE_AFTER
            entries.append((w, f[lk] * c / z, flags))

    entries.sort(key=lambda e: (-e[1], e[0]))
    with open(os.path.join(out_dir, "lexicon.tsv"), "w", encoding="utf-8") as fo:
        for w, v, fl in entries:
            fo.write(f"{w}\t{v:.6e}\t{fl}\t{class_of.get(w, '')}\n")

    # ---- repair map for encoding training text
    words_by_lk = {}
    for w, v, fl in entries:
        words_by_lk.setdefault(tn.lower(w), w)
    repair = {}
    for bare, lk in contraction_of.items():
        if lk in words_by_lk and (bare not in words_by_lk or bare in banned_lower):
            repair[bare] = words_by_lk[lk]
    for bad, corr in repairs_manual.items():
        if tn.lower(corr) in words_by_lk:
            repair[bad] = words_by_lk[tn.lower(corr)]
    with open(os.path.join(out_dir, "repair.tsv"), "w", encoding="utf-8") as fo:
        for k in sorted(repair):
            fo.write(f"{k}\t{repair[k]}\n")
    n_off = sum(1 for e in entries if e[2] & F_OFFENSIVE)
    print(f"[lexicon] {locale}: {len(entries):,} entries, {n_off} offensive, {len(repair)} repairs, "
          f"{len(banned_lower)} banned lowercase forms", flush=True)


def _edit1(a, b):
    """True if Damerau-Levenshtein distance(a, b) == 1."""
    la, lb = len(a), len(b)
    if abs(la - lb) > 1:
        return False
    if la == lb:
        diff = [i for i in range(la) if a[i] != b[i]]
        if len(diff) == 1:
            return True
        return len(diff) == 2 and diff[1] == diff[0] + 1 and a[diff[0]] == b[diff[1]] and a[diff[1]] == b[diff[0]]
    if la > lb:
        a, b = b, a
    i = 0
    while i < len(a) and a[i] == b[i]:
        i += 1
    return a[i:] == b[i + 1:]


if __name__ == "__main__":
    main(sys.argv[1])
