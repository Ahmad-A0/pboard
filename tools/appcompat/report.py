#!/usr/bin/env python3
"""Before/after matrix: one Gboard run against two pboard runs (e.g. main and a fixed build) on the same image.

  report.py <gboard-dir> <before-dir> <after-dir> [--md out.md]

Each cell shows the class of the before run, and "->" the after run's when it differs (see compare.py for the
classes). Families (apps) group the fields; the differences of the after run are listed below the table.
"""
import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apps as A  # noqa: E402
import compare as C  # noqa: E402


def judge_all(g, p):
    return {sc: C.judge(g.get(sc, {}), p.get(sc, {})) for sc in g if sc in p}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("gboard")
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("--md")
    ap.add_argument("--fields", default="", help="only these fields (comma-separated)")
    a = ap.parse_args()
    G, B, F = C.load(a.gboard), C.load(a.before), C.load(a.after)
    if a.fields:
        keep = set(a.fields.split(","))
        G = {k: v for k, v in G.items() if k.rsplit("/", 1)[0] in keep}
    rb, ra = judge_all(G, B), judge_all(G, F)
    names = []
    rows = collections.OrderedDict()
    for sc in sorted(G, key=lambda s: ([f["id"] for f in A.FIELDS].index(s.rsplit("/", 1)[0])
                                       if s.rsplit("/", 1)[0] in A.BY_ID else 99, s)):
        f, n = sc.rsplit("/", 1)
        if n not in names:
            names.append(n)
        rows.setdefault(f, {})[n] = sc
    lines = ["| app | field | " + " | ".join(n.replace("_", " ") for n in names) + " |",
             "|---|---|" + "---|" * len(names)]
    cb, ca = collections.Counter(), collections.Counter()
    for f, cells in rows.items():
        fam = A.BY_ID[f]["family"] if f in A.BY_ID else ""
        out = []
        for n in names:
            sc = cells.get(n)
            if not sc:
                out.append("")
                continue
            b = rb[sc][0] if sc in B else None
            x = ra[sc][0] if sc in F else None
            if b:
                cb[b] += 1
            if x:
                ca[x] += 1
            sb = C.SHORT.get(b, "-") if b else "-"
            sa = C.SHORT.get(x, "-") if x else "-"
            out.append(sb if sb == sa else "%s → %s" % (sb, sa))
        lines.append("| %s | %s | %s |" % (fam, f, " | ".join(out)))
    order = [c for c in C.ORDER if cb[c] or ca[c]]
    lines += ["", "| | " + " | ".join(order) + " |", "|---|" + "---|" * len(order),
              "| before | " + " | ".join(str(cb[c]) for c in order) + " |",
              "| after | " + " | ".join(str(ca[c]) for c in order) + " |", "",
              "Remaining differences (after):", ""]
    for sc, (c, k, detail) in sorted(ra.items(), key=lambda x: (C.ORDER.index(x[1][0]), x[0])):
        if c not in ("pass", "word-choice") and sc in F:
            lines.append("- **%s** `%s` (step %s): %s" % (c, sc, k, detail))
    md = "\n".join(lines) + "\n"
    if a.md:
        open(a.md, "w").write(md)
    sys.stdout.write(md)


if __name__ == "__main__":
    main()
