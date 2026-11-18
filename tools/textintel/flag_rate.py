#!/usr/bin/env python3
"""Sentences per 1,000 with any grammar flag, per set (the false-alarm rate on text that is correct by construction,
e.g. the prompts of the human typing test splits), for the shipped thresholds.

    python3 -I tools/textintel/flag_rate.py --cands cands.jsonl --thresholds cands.thresholds.json --model nn \
        --screen 4 --gold clean_human_tap_tsi.jsonl ... [--show 20]
"""

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gec_tune  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cands", nargs="+", required=True)
    ap.add_argument("--thresholds", required=True)
    ap.add_argument("--model", default="nn")
    ap.add_argument("--screen", type=float, default=math.inf)
    ap.add_argument("--gold", nargs="+", required=True)
    ap.add_argument("--show", type=int, default=0)
    a = ap.parse_args()
    cands = gec_tune.load_cands(a.cands)
    preds = gec_tune.predict(cands, gec_tune.read_thresholds(a.thresholds), a.model, a.screen)
    for path in a.gold:
        rows = [json.loads(l) for l in open(path, encoding="utf-8")]
        rows = [r for r in rows if r["id"] in cands]
        if not rows:
            continue
        flagged = [r for r in rows if preds.get(r["id"])]
        no_i = [r for r in rows if any(e["rule"] != "i_lower" for e in preds.get(r["id"], []))]
        n = len(rows)
        print(f"{rows[0]['set']:28s} n={n:5d}  sentences flagged {len(flagged):4d} = {1000 * len(flagged) / n:6.1f}/1k"
              f"  (without lone-i flags: {len(no_i):4d} = {1000 * len(no_i) / n:6.1f}/1k)")
        for r in flagged[: a.show]:
            for e in preds[r["id"]]:
                t = r["text"]
                print(f"    [{e['rule']}] {t[:e['cs']]}[{t[e['cs']:e['ce']]}→{e['cor']}]{t[e['ce']:]}")


if __name__ == "__main__":
    main()
