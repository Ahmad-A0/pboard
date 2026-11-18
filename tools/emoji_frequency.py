#!/usr/bin/env python3
"""Builds the emoji popularity asset that ranks the suggestion strip's emoji (EmojiAnnotations.suggest).

Source: Emoji Sentiment Ranking 1.0 (Kralj Novak, Smailović, Sluban, Mozetič; Jožef Stefan Institute, 2015),
http://hdl.handle.net/11356/1048, CC BY-SA 4.0: occurrences of 969 emoji in a corpus of tweets in 13 languages.
Vendored in third_party/emoji-sentiment-ranking/. The output is an adaptation and is CC BY-SA 4.0 too.
(The dataset's Emojitracker_20150604.csv is not used: those counts were scraped from emojitracker.com, whose data
carries no licence of its own.)

Each counted emoji is mapped to the keyboard's fully-qualified form (the panel list, assets/emoji/emoji.txt, adds
VS16 where Unicode requires it). Output lines: "<emoji>\\t<occurrences>", most frequent first, after a "#" header.
Emoji newer than the 2015 corpus have no line; the app ranks them after the counted ones.

usage: emoji_frequency.py <Emoji_Sentiment_Data_v1.0.csv> <emoji.txt> <out.txt>
   e.g. tools/emoji_frequency.py third_party/emoji-sentiment-ranking/Emoji_Sentiment_Data_v1.0.csv \\
          app/src/main/assets/emoji/emoji.txt app/src/main/assets/emoji/frequency.txt
"""
import csv
import sys

VS16 = "️"


def main():
    src, emoji_txt, out = sys.argv[1:4]
    panel = {}
    with open(emoji_txt, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                for e in parts[1:]:
                    for form in e.split(" "):
                        if form:
                            panel.setdefault(form.replace(VS16, ""), form)
    counts = {}
    with open(src, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            key = row["Emoji"].replace(VS16, "")
            form = panel.get(key)
            if form is None:
                continue
            counts[form] = counts.get(form, 0) + int(row["Occurrences"])
    with open(out, "w", encoding="utf-8") as f:
        f.write("# Emoji popularity: occurrences in tweets, from Emoji Sentiment Ranking 1.0 (Kralj Novak et al., 2015,\n")
        f.write("# http://hdl.handle.net/11356/1048), CC BY-SA 4.0. Adapted by tools/emoji_frequency.py; CC BY-SA 4.0.\n")
        for e, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])):
            f.write("%s\t%d\n" % (e, n))
    print("%d emoji counted (%d in the source)" % (len(counts), sum(1 for _ in open(src, encoding="utf-8")) - 1))


if __name__ == "__main__":
    main()
