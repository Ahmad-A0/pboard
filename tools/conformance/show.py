#!/usr/bin/env python3
"""Prints analysed observations side by side: show.py <run-dir> [<other-run-dir>] [--sc REGEX]"""
import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import read_jsonl  # noqa: E402


def line(r):
    if r is None:
        return "-"
    if r.get("aborted"):
        return "ABORTED %s" % r.get("focusWindow")
    s = r.get("strip") or {}
    slots = " | ".join(x["text"] + ("*" if s.get("bold") == i else "") for i, x in enumerate(s.get("slots", [])))
    kb = r.get("kb") or {}
    k = kb.get("page", "?") + ("/" + kb["shift"] if kb.get("shift") else "") + ("+ov" if kb.get("overlay") else "")
    comp = "" if (r.get("compStart") or -1) < 0 else " c%s-%s" % (r.get("compStart"), r.get("compEnd"))
    return "%-24r s%s-%s%s  %-14s %-8s %s" % (r.get("text"), r.get("selStart"), r.get("selEnd"), comp, k,
                                              s.get("mode", "?")[:8], slots)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--sc", default=".")
    a = ap.parse_args()
    data = [{(r["sc"], r.get("step")): r for r in read_jsonl(os.path.join(d, "steps.jsonl"))} for d in a.runs]
    scen = read_jsonl(os.path.join(a.runs[0], "scenarios.jsonl"))
    for sc in scen:
        if not re.search(a.sc, sc["id"]):
            continue
        print("== %s  %s" % (sc["id"], sc.get("field")))
        for i in range(-1, len(sc["steps"])):
            m = sc["steps"][i].get("m", {}) if i >= 0 else {}
            what = m.get("k", "") + (" " + str(m.get("key") or m.get("word") or m.get("sym") or m.get("slot") or m.get("label") or "") if m else "")
            rows = [d.get((sc["id"], i)) for d in data]
            if all(r is None for r in rows):
                continue
            print("  %3d %-16s G %s" % (i, what[:16], line(rows[0])))
            for r in rows[1:]:
                print("  %3s %-16s P %s" % ("", "", line(r)))


if __name__ == "__main__":
    main()
