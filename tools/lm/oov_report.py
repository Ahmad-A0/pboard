"""Lexicon coverage report: OOV rate of the human evaluation TUNE splits and of the held-out chat sets, with the
most frequent missing words and why the lexicon builder rejected them.

Usage: tools/lm/run.sh oov_report.py <locale> [--words N]
(TEST splits are never read: words are never added from them.)
"""

import collections
import gzip
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import langs  # noqa: E402
from paths import TOOLS, work  # noqa: E402
from textnorm import TextNorm  # noqa: E402

HUMAN = os.path.join(TOOLS, "data", "human", "splits", "v1")
SETS = ["swipe_futo", "swipe_hws", "tap_tsi", "tap_vtap", "text_typos", "text_wikipedia"]


def lexicon_keys(locale, tn):
    keys = set()
    with open(os.path.join(work(locale), "lexicon.tsv"), encoding="utf-8") as f:
        for line in f:
            keys.add(tn.lower(line.split("\t", 1)[0]))
    return keys


def human_targets(name):
    with gzip.open(os.path.join(HUMAN, f"{name}.tune.jsonl.gz"), "rt", encoding="utf-8") as f:
        for line in f:
            t = json.loads(line).get("target", "")
            if t:
                yield t


def chat_targets(locale, name):
    p = os.path.join(work(locale, "eval"), f"{name}.txt")
    for line in open(p, encoding="utf-8"):
        for t in line.split():
            if t not in (",", "<num>", "<unk>"):
                yield t


def main(locale, top=40):
    L = langs.get(locale)
    tn = TextNorm(L)
    keys = lexicon_keys(locale, tn)
    folds = {tn.fold(k) for k in keys}
    report = {}
    missing_all = collections.Counter()
    print(f"{'set':<15} {'words':>7}  {'OOV':>6} {'no-apos':>8} {'+hyphen':>8}   most frequent strict misses")
    for name in SETS + ["chat", "dailydialog", "nussms_us"]:
        targets = list(human_targets(name) if name in SETS else chat_targets(locale, name))
        words = [t for t in targets if any(c.isalpha() for c in t)]
        miss = collections.Counter(tn.lower(w) for w in words if tn.lower(w).strip("'") not in keys
                                   and tn.lower(w) not in keys)
        n_miss = sum(miss.values())
        # secondary measures: apostrophe-insensitive (what completion/swipe match: "im" -> "I'm"), and also
        # treating hyphenated compounds as covered when every part is a word ("cover-up")
        no_apos = sum(c for w, c in miss.items() if tn.fold(w) not in folds)
        no_hyph = sum(c for w, c in miss.items() if tn.fold(w) not in folds and not
                      ("-" in w and all(p and tn.fold(p) in folds for p in w.split("-"))))
        report[name] = {"words": len(words), "oov": n_miss / max(1, len(words)),
                        "oov_apostrophe_insensitive": no_apos / max(1, len(words)),
                        "oov_hyphen_split": no_hyph / max(1, len(words)), "top": miss.most_common(top)}
        if name in SETS:
            missing_all.update(miss)
        n = max(1, len(words))
        print(f"{name:<15} {len(words):>7}  {n_miss / n:6.2%} {no_apos / n:8.2%} {no_hyph / n:8.2%}   "
              + " ".join(f"{w}({c})" for w, c in miss.most_common(12)), flush=True)
    json.dump(report, open(os.path.join(work(locale), "oov_report.json"), "w"), indent=1)
    return missing_all


if __name__ == "__main__":
    a = sys.argv[1:]
    main(a[0], int(a[a.index("--words") + 1]) if "--words" in a else 40)
