#!/usr/bin/env python3
"""Baseline: LanguageTool (LGPL-2.1, run locally as a separate server; nothing of it enters pboard) on the
grammar-check evaluation sets, written as gec_score.py predictions.

Start a local server first (LanguageTool-6.x standalone zip, Java 17):
    java -cp languagetool-server.jar org.languagetool.server.HTTPServer --port 8081 --allow-origin
Then:
    python3 -I tools/textintel/lt_bench.py --sets bea_dev.jsonl ... --lang en-GB --mode grammar --out lt_pred.jsonl

--mode grammar: only the grammar-like categories (GRAMMAR, CONFUSED_WORDS, COLLOCATIONS, NONSTANDARD_PHRASES,
               MISC, MULTITOKEN_SPELLING, COMPOUNDING, CASING; sentence-start capitalisation off): what a grammar
               checker next to a separate spell checker would show.
--mode default: everything except the spelling rules (adds punctuation, typography, style).
"""

import argparse
import json
import urllib.parse
import urllib.request

GRAMMAR_CATS = "GRAMMAR,CONFUSED_WORDS,COLLOCATIONS,NONSTANDARD_PHRASES,MISC,MULTITOKEN_SPELLING,COMPOUNDING,CASING"


def check(url, text, lang, mode):
    data = {"text": text, "language": lang}
    if mode == "grammar":
        # (enabledOnly cannot be combined with disabledRules: sentence-start capitalisation is dropped below)
        data.update(enabledCategories=GRAMMAR_CATS, enabledOnly="true")
    else:
        data.update(disabledRules="MORFOLOGIK_RULE_EN_GB,MORFOLOGIK_RULE_EN_US")
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode())
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)["matches"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sets", nargs="+", required=True)
    ap.add_argument("--lang", default="en-GB")
    ap.add_argument("--mode", default="grammar", choices=["grammar", "default"])
    ap.add_argument("--url", default="http://127.0.0.1:8081/v2/check")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    with open(a.out, "w", encoding="utf-8") as f:
        for path in a.sets:
            for line in open(path, encoding="utf-8"):
                row = json.loads(line)
                edits = []
                for m in check(a.url, row["text"], a.lang, a.mode):
                    if a.mode == "grammar" and m["rule"]["id"] == "UPPERCASE_SENTENCE_START":
                        continue
                    s, n = m["offset"], m["length"]
                    reps = m.get("replacements") or []
                    cor = reps[0]["value"] if reps else row["text"][s:s + n]
                    edits.append({"cs": s, "ce": s + n, "cor": cor, "rule": m["rule"]["category"]["id"] + ":" + m["rule"]["id"]})
                f.write(json.dumps({"id": row["id"], "edits": edits}) + "\n")


if __name__ == "__main__":
    main()
