#!/usr/bin/env python3
"""Contact sheet of strip crops for checking OCR by eye: sheet.py <run-dir> <out.png> [--sc REGEX] [--n 40]
Writes <out.png> (numbered strip crops) and <out.png>.txt (number, scenario/step, OCR slots)."""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import read_jsonl, tool, load_json  # noqa: E402
from analyze import Regions  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("out")
    ap.add_argument("--sc", default=".")
    ap.add_argument("--n", type=int, default=40)
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--mode", default="suggestions")
    ap.add_argument("--sample", type=int, default=0, help="random sample with this seed instead of the first n")
    a = ap.parse_args()
    info = load_json(os.path.join(a.run, "info.json"))
    R = Regions(info.get("geometry") or os.path.join(os.path.dirname(__file__), "..", "bench", "geometry", "gboard_pixel8.json"),
                info.get("regionTop") or 1300)
    seen, rows = set(), []
    for r in read_jsonl(os.path.join(a.run, "steps.jsonl")):
        if r.get("aborted") or not re.search(a.sc, r["sc"]) or r.get("ime") in seen:
            continue
        s = r.get("strip") or {}
        if a.mode and s.get("mode") != a.mode:
            continue
        seen.add(r["ime"])
        rows.append(r)
    if a.sample:
        import random
        random.Random(a.sample).shuffle(rows)
    rows = rows[a.start:a.start + a.n]
    tmp = a.out + ".d"
    os.makedirs(tmp, exist_ok=True)
    files, txt = [], []
    for i, r in enumerate(rows):
        f = os.path.join(tmp, "%03d.png" % i)
        subprocess.run([tool("magick"), os.path.join(a.run, "img", r["ime"] + ".png"), "-crop",
                        "%dx%d+0+%d" % (R.width, R.strip_bottom - R.strip_top, R.strip_top), "+repage",
                        "-resize", "60%", "-gravity", "west", "-background", "white", "-splice", "40x0",
                        "-pointsize", "22", "-annotate", "+2+0", str(i), f], check=True)
        files.append(f)
        s = r["strip"]
        txt.append("%3d %s/%s  %s" % (i, r["sc"], r["step"], " | ".join(x["text"] + ("*" if s.get("bold") == j else "")
                                                                         for j, x in enumerate(s.get("slots", [])))))
    subprocess.run([tool("magick")] + files + ["-append", a.out], check=True)
    open(a.out + ".txt", "w").write("\n".join(txt) + "\n")
    print("\n".join(txt))


if __name__ == "__main__":
    main()
