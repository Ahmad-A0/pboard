#!/usr/bin/env python3
"""Compares pboard's app-compatibility run with Gboard's (same scenarios, same image) and classifies differences.

  compare.py <gboard-dir> <pboard-dir> [--md out.md] [--json out.json] [--title T]

Per scenario, the first step where the field differs decides the verdict (later steps inherit the difference):
  pass            text and selection equal after every step (or the only difference is a word choice)
  word-choice     one word differs between otherwise equal texts (auto-correction / glide decoding, not compatibility)
  capitalization  the texts differ only in letter case (auto-capitalisation)
  cursor-desync   same text, different selection
  lost-input      pboard's text is missing characters Gboard's has (and has nothing extra)
  garbled         any other text difference (duplicated, misplaced or extra characters)
  stuck-composing the page reports an open composition after the scenario where Gboard's is closed
  no-keyboard     pboard's keyboard was not shown where Gboard's was
  crash           pboard's process died (pid changed) or the app died / lost the front
  setup           the scenario could not start on pboard's run only (field not reached)
Classes are judged against Gboard 18.6 on the same image: a difference both keyboards share is not pboard's.
"""
import argparse
import collections
import json
import os
import re
import sys

ORDER = ["crash", "no-keyboard", "lost-input", "garbled", "stuck-composing", "cursor-desync", "capitalization", "setup",
         "word-choice", "pass"]
SHORT = {"pass": "ok", "word-choice": "ok*", "cursor-desync": "CUR", "lost-input": "LOST", "garbled": "GARB",
         "stuck-composing": "COMP", "no-keyboard": "NOKB", "crash": "CRASH", "setup": "n/a", "capitalization": "CASE"}


def load(d):
    rows = collections.defaultdict(dict)
    p = os.path.join(d, "obs.jsonl")
    if os.path.exists(p):
        for l in open(p):
            l = l.strip()
            if not l:
                continue
            try:
                r = json.loads(l)
            except ValueError:
                continue
            rows[r["sc"]][r["step"]] = r
    return rows


def words(s):
    return re.findall(r"\S+", s or "")


def lev(a, b):
    if a == b:
        return 0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def is_subsequence(short, long):
    it = iter(long)
    return all(c in it for c in short)


def text_class(g, p):
    """Class of a text difference (g: Gboard's text, p: pboard's)."""
    wg, wp = words(g), words(p)
    if g != p and g.lower() == p.lower():
        return "capitalization"
    if len(wg) == len(wp) and wg != wp:
        diff = [(a, b) for a, b in zip(wg, wp) if a != b]
        if len(diff) == 1 and lev(diff[0][0].lower(), diff[0][1].lower()) <= max(2, len(diff[0][0]) // 2):
            # same shape, one word decoded / corrected differently
            if g.replace(diff[0][0], "", 1).strip() == p.replace(diff[0][1], "", 1).strip():
                return "word-choice"
    if g.strip() == p.strip() and g != p:
        return "garbled"  # spacing only
    if len(p) < len(g) and is_subsequence(p, g):
        return "lost-input"
    return "garbled"


def norm_pw(r):
    return r.get("text")


def judge(gs, ps):
    """(class, step index, detail) for one scenario's Gboard and pboard observations."""
    if not ps:
        return "setup", None, "no pboard observations"
    p0, g0 = ps.get(-1, {}), gs.get(-1, {})
    if p0.get("setupFailed"):
        if g0.get("setupFailed"):
            return "setup", -1, "both: " + p0["setupFailed"][:120]
        m = p0["setupFailed"]
        return ("no-keyboard" if "IME not shown" in m else "setup"), -1, m[:160]
    if g0.get("setupFailed"):
        return "setup", -1, "Gboard only: " + g0["setupFailed"][:120]
    steps = sorted(k for k in ps if k >= 0)
    pid0 = p0.get("imePid")
    apid0 = p0.get("appPid")
    gapid0 = g0.get("appPid")
    worst = ("pass", None, "")
    for k in steps:
        p, g = ps[k], gs.get(k)
        if g is None:
            continue
        if pid0 and pid0 > 0 and p.get("imePid") != pid0:
            return "crash", k, "pboard pid %s -> %s at '%s'" % (pid0, p.get("imePid"), p.get("label"))
        if apid0 and apid0 > 0 and p.get("appPid") != apid0 and (g.get("appPid") == gapid0):
            return "crash", k, "app pid %s -> %s at '%s' (Gboard's app kept running)" % (apid0, p.get("appPid"), p.get("label"))
        if g.get("ime") and not p.get("ime") and p.get("text") != g.get("text"):
            return "no-keyboard", k, "keyboard not shown at '%s'" % p.get("label")
        # terminal views expose their screen only as a description
        gt, pt = g.get("text") or g.get("desc"), p.get("text") or p.get("desc")
        if gt != pt:
            c = text_class(gt or "", pt or "")
            detail = "at '%s': Gboard %r | pboard %r" % (p.get("label"), gt, pt)
            if c not in ("word-choice", "capitalization"):
                return c, k, detail
            if c == "capitalization" and worst[0] in ("pass", "word-choice"):
                worst = (c, k, detail)
            if worst[0] == "pass":
                worst = (c, k, detail)
            continue
        if (g.get("selStart"), g.get("selEnd")) != (p.get("selStart"), p.get("selEnd")):
            return "cursor-desync", k, "at '%s': text %r, Gboard sel %s-%s | pboard sel %s-%s" % (
                p.get("label"), pt, g.get("selStart"), g.get("selEnd"), p.get("selStart"), p.get("selEnd"))
    # composition left open at the end (web page status line)
    last = steps[-1] if steps else None
    if last is not None and last in gs:
        gp, pp = gs[last].get("probe") or "", ps[last].get("probe") or ""
        if "comp=1" in pp and "comp=1" not in gp:
            return "stuck-composing", last, "page reports an open composition: %s" % pp
    return worst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("gboard")
    ap.add_argument("pboard")
    ap.add_argument("--md")
    ap.add_argument("--json")
    ap.add_argument("--title", default="pboard vs Gboard 18.6")
    a = ap.parse_args()
    G, P = load(a.gboard), load(a.pboard)
    res = {}
    for sc in sorted(set(G) & set(P)):  # scenarios both runs played
        c, k, detail = judge(G.get(sc, {}), P.get(sc, {}))
        res[sc] = {"class": c, "step": k, "detail": detail}
    fields = collections.OrderedDict()
    names = []
    for sc in res:
        f, n = sc.rsplit("/", 1)
        fields.setdefault(f, {})[n] = res[sc]
        if n not in names:
            names.append(n)
    counts = collections.Counter(r["class"] for r in res.values())
    lines = ["### %s" % a.title, "",
             "Scenarios: %d; %s" % (len(res), ", ".join("%s %d" % (c, counts[c]) for c in ORDER if counts[c])), "",
             "| field | " + " | ".join(names) + " |", "|---|" + "---|" * len(names)]
    for f, row in fields.items():
        lines.append("| %s | %s |" % (f, " | ".join(SHORT[row[n]["class"]] if n in row else "" for n in names)))
    lines += ["", "ok = same text and cursor as Gboard after every step; ok* = only a word decoded differently; "
              "CASE capitalisation only, CUR cursor desync, LOST lost input, GARB garbled text, COMP stuck composition, NOKB keyboard not shown, "
              "CRASH crash, n/a field not reached.", "", "#### Differences", ""]
    for sc, r in sorted(res.items(), key=lambda x: (ORDER.index(x[1]["class"]), x[0])):
        if r["class"] in ("pass",):
            continue
        lines.append("- **%s** `%s` (step %s): %s" % (r["class"], sc, r["step"], r["detail"]))
    md = "\n".join(lines) + "\n"
    if a.md:
        open(a.md, "w").write(md)
    if a.json:
        json.dump(res, open(a.json, "w"), indent=1, sort_keys=True)
    sys.stdout.write(md)


if __name__ == "__main__":
    main()
