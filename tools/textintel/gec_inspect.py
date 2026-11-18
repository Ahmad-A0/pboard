#!/usr/bin/env python3
"""Prints the candidates of one rule group on a set, highest LM score first, for manual adjudication.

    python3 -I tools/textintel/gec_inspect.py --cands cands_dev.jsonl --set clean_chat_dev.jsonl --group "conf:its>it's" \
        --model nn --min 0 --n 30
"""

import argparse
import json


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cands", nargs="+", required=True)
    ap.add_argument("--set", nargs="+", required=True)
    ap.add_argument("--group", required=True, help="a family name, a rule, or a rule prefix ending in *")
    ap.add_argument("--model", default="nn")
    ap.add_argument("--min", type=float, default=-99)
    ap.add_argument("--n", type=int, default=30)
    a = ap.parse_args()
    text = {}
    for p in a.set:
        for line in open(p, encoding="utf-8"):
            o = json.loads(line)
            text[o["id"]] = o["text"]
    rows = []
    for p in a.cands:
        for line in open(p, encoding="utf-8"):
            o = json.loads(line)
            if o["id"] not in text:
                continue
            for c in o["cands"]:
                g = c["rule"] if c["family"] == "confusion" else c["family"]
                ok = g == a.group or c["family"] == a.group or (a.group.endswith("*") and g.startswith(a.group[:-1]))
                if ok and c.get(a.model) is not None and c[a.model] >= a.min:
                    rows.append((c[a.model], c["rule"], o["id"], c))
    rows.sort(key=lambda r: -r[0])
    print(f"{len(rows)} candidates >= {a.min}")
    for d, rule, sid, c in rows[: a.n]:
        t = text[sid]
        s = t[: c["cs"]] + "[" + t[c["cs"]:c["ce"]] + "→" + c["cor"] + "]" + t[c["ce"]:]
        print(f"{d:7.2f} {rule:22s} {s}")


if __name__ == "__main__":
    main()
