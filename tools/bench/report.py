#!/usr/bin/env python3
"""Side-by-side markdown table of score.json files (e.g. Gboard vs pboard).

Usage: report.py NAME=path/to/score.json [NAME=...] ...
Rows: tap WER, tap word accuracy, false-correction rate, noisy recovery, swipe word accuracy,
swipe WER, prediction hit@1 / hit@3. Each score.json may hold any subset (one per run.sh call).
"""
import json
import sys


def main():
    cols = {}
    for arg in sys.argv[1:]:
        name, path = arg.split("=", 1)
        with open(path) as f:
            s = json.load(f)
        cols.setdefault(name, {}).update(s)
    rows = [
        ("tap items", lambda s: s["tap"]["items"], "%d"),
        ("tap WER", lambda s: 100 * s["tap"]["wer"], "%.2f %%"),
        ("tap word accuracy", lambda s: 100 * s["tap"]["wordAccuracy"], "%.2f %%"),
        ("false-correction rate (clean taps)", lambda s: "%.2f %% (%d/%d)" % (
            100 * s["tap"]["falseCorrectionRate"], s["tap"]["falseCorrections"], s["tap"]["cleanItems"]), "%s"),
        ("noisy-word recovery", lambda s: "%.2f %% (%d/%d)" % (
            100 * s["tap"]["noisyRecoveryRate"], s["tap"]["noisyRecovered"], s["tap"]["noisyItems"]), "%s"),
        ("swipe items", lambda s: s["swipe"]["items"], "%d"),
        ("swipe word accuracy", lambda s: 100 * s["swipe"]["wordAccuracy"], "%.2f %%"),
        ("swipe WER", lambda s: 100 * s["swipe"]["wer"], "%.2f %%"),
        ("prediction contexts", lambda s: s["predict"]["items"], "%d"),
        ("next-word hit@1", lambda s: 100 * s["predict"]["hit1"], "%.2f %%"),
        ("next-word hit@3", lambda s: 100 * s["predict"]["hit3"], "%.2f %%"),
    ]
    names = list(cols)
    print("| metric | " + " | ".join(names) + " |")
    print("|---|" + "---|" * len(names))
    for label, fn, fmt in rows:
        vals = []
        for n in names:
            try:
                vals.append(fmt % fn(cols[n]))
            except (KeyError, TypeError):
                vals.append("-")
        if any(v != "-" for v in vals):
            print("| %s | %s |" % (label, " | ".join(vals)))


if __name__ == "__main__":
    main()
