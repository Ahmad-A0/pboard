"""Locale-aware text normalisation and tokenisation shared by every stage of the LM pipeline.

Conventions (the parts that matter on device are mirrored by dev.pboard.engine.lm and stored in the pack):
  * NFKC, typographic apostrophes/quotes folded to ASCII.
  * Words are runs of Unicode letters + combining marks; where the locale allows it, internal apostrophes
    stay inside the word ("don't").  Elided clitics ("l'", "d'") are split off as their own tokens.
    Hyphenated compounds are split.
  * Numbers become <num>; other tokens containing digits, URLs, e-mail addresses, @handles and #hashtags
    become <unk>.  ',', ';', ':' and spaced dashes become the clause token ','.  '.', '!', '?', '...' and
    emoji end a sentence.  Other punctuation is dropped.
  * Case mapping is per code point: locale overrides (Turkish I/İ) first, then Unicode simple lowercase.
    This is exactly what Character.toLowerCase(int) does on the JVM, so keys agree across platforms.
"""

import unicodedata

import regex as re

NUM = "<num>"
UNK = "<unk>"
COMMA = ","

_TRANS = str.maketrans({
    "’": "'", "‘": "'", "ʼ": "'", "´": "'", "`": "'", "′": "'", "‛": "'",
    "“": '"', "”": '"', "„": '"', "«": '"', "»": '"', "″": '"',
    "—": " - ", "–": " - ", "‒": " - ", "―": " - ",
    " ": " ", " ": " ", "​": "", "‌": "", "‍": "", "﻿": "", "­": "",
    "\t": " ", "\r": " ",
})

# Characters whose Python full lowercase mapping is not a single code point; use the Unicode simple mapping.
_SIMPLE_LOWER = {"İ": "i"}


def normalize(text: str) -> str:
    return unicodedata.normalize("NFKC", text).translate(_TRANS)


def _is_emoji(ch: str) -> bool:
    cp = ord(ch)
    return (cp >= 0x2190 and unicodedata.category(ch) in ("So", "Sk", "Cs")) or 0x1F000 <= cp <= 0x1FAFF


class TextNorm:
    def __init__(self, L):
        self.L = L
        lt = r"\p{L}\p{M}"
        word = rf"'?[{lt}]+(?:'[{lt}]+)*" if L.apostrophe_words else rf"[{lt}]+"
        self._re = re.compile(
            rf"""
            (?P<url>(?:https?://|www\.)\S+|[\w.+-]+@[\w-]+\.[\w.]+|[@#]\w+)
           |(?P<acro>(?:[{lt}]\.){{2,}})
           |(?P<num>[$£€]?\d+(?:[.,:/]\d+)*(?:st|nd|rd|th|s|am|pm|k|m|%)?(?![\w]))
           |(?P<alnum>[\w\p{{M}}]*\d[\w\p{{M}}]*)
           |(?P<word>{word})
           |(?P<eos>[.!?]+|[。！？])
           |(?P<comma>[,;:،、]|\s-+\s|\s-+$)
           |(?P<amp>&)
           |(?P<other>\S)
            """,
            re.X,
        )
        self.abbrev = set(L.abbreviations)
        self.leading = set(L.leading_apostrophe)
        self.clitics = sorted(L.clitics, key=len, reverse=True)
        self.case = dict(L.case_overrides)
        self._fold_cache = {}

    # ------------------------------------------------------------------ case and folding
    def lower_char(self, ch: str) -> str:
        if ch in self.case:
            return self.case[ch]
        low = ch.lower()
        return low if len(low) == 1 else _SIMPLE_LOWER.get(ch, ch)

    def lower(self, s: str) -> str:
        return "".join(self.lower_char(c) for c in s)

    def fold_char(self, ch: str) -> str:
        """Lowercase + strip diacritics (unless kept for this locale) + locale overrides (apostrophe -> '')."""
        r = self._fold_cache.get(ch)
        if r is None:
            low = self.lower_char(ch)
            if low in self.L.fold_overrides:
                r = self.L.fold_overrides[low]
            elif low in self.L.fold_keep or ord(low) < 0x80:
                r = low
            else:
                base = "".join(c for c in unicodedata.normalize("NFD", low) if not unicodedata.combining(c))
                r = base if base else low
            self._fold_cache[ch] = r
        return r

    def fold(self, s: str) -> str:
        return "".join(self.fold_char(c) for c in s)

    # ------------------------------------------------------------------ tokenisation
    def sentences(self, text: str):
        """Yield tokenised sentences (lists of str) from raw text. Line breaks always end a sentence."""
        for line in normalize(text).split("\n"):
            line = line.strip()
            if not line:
                continue
            cur = []
            for m in self._re.finditer(" " + line + " "):
                kind = m.lastgroup
                tok = m.group(kind)
                if kind == "word":
                    if tok.startswith("'"):
                        tok = tok if tok.lower() in self.leading else tok[1:]
                    if self.clitics:
                        low = self.lower(tok)
                        for c in self.clitics:
                            if low.startswith(c) and len(low) > len(c):
                                cur.append(tok[: len(c)])
                                tok = tok[len(c):]
                                break
                    cur.append(tok)
                elif kind == "eos":
                    prev = cur[-1] if cur else ""
                    if tok == "." and prev and (self.lower(prev) in self.abbrev or (len(prev) == 1 and prev.isupper())):
                        continue
                    if cur:
                        yield _finish(cur)
                        cur = []
                elif kind == "comma":
                    if cur and cur[-1] != COMMA:
                        cur.append(COMMA)
                elif kind == "num":
                    cur.append(NUM)
                elif kind in ("url", "alnum"):
                    cur.append(UNK)
                elif kind == "acro":
                    cur.append(tok.replace(".", ""))
                elif kind == "amp" and self.L.lang == "en":
                    cur.append("and")
                elif kind == "other" and _is_emoji(tok) and cur:
                    yield _finish(cur)
                    cur = []
            if cur:
                yield _finish(cur)


def _finish(toks):
    while toks and toks[-1] == COMMA:
        toks.pop()
    while toks and toks[0] == COMMA:
        toks.pop(0)
    return toks


def utf16_key(s: str) -> bytes:
    """Sort key giving UTF-16 code-unit order (= Kotlin/Java String.compareTo)."""
    return s.encode("utf-16-be", "surrogatepass")


if __name__ == "__main__":
    import sys
    sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
    import langs
    tn = TextNorm(langs.get(sys.argv[1] if len(sys.argv) > 1 else "en_US"))
    for s in tn.sentences(sys.stdin.read()):
        print(" ".join(s))
