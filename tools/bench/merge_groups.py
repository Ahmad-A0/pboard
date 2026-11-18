#!/usr/bin/env python3
"""Merge per-group replay results (run.sh) in group order and decide which groups are valid.

Usage: merge_groups.py <out-dir> <results-name>   (results-name: results.jsonl or predict.jsonl)

A reset group is valid iff
  - its sanity probe ("becuase" + space on key centres, three times) was corrected all three times,
  - it produced a result for every item, and
  - no item stayed timing-degraded (real-time injection stretched beyond the limit after retries), and
  - the host was not overloaded while it ran: max 1-min load <= OVERLOAD_FACTOR x cores (default 3.5)
    and max PSI memory "some avg10" <= OVERLOAD_PSI % (default 25) (run.sh samples both every 5 s;
    groups run before that monitor existed carry no load_max and are not checked), and
  - the keyboard never stopped responding: two or more consecutive items whose field text did not
    change at all (after all retries) mean the IME was hidden/crashed or the taps went nowhere - an
    isolated unchanged item counts as the keyboard's error instead; if a re-run reproduces exactly the
    same unchanged items, it is the IME's own deterministic behaviour and counts as its errors, and
  - it does not overlap a window listed in <out>/../overload_windows.txt or $OVERLOAD_WINDOWS
    (lines "<start-epoch> <end-epoch> <reason>": host incidents reported after the fact).
Writes <out>/<results-name> (all groups; each line gets "group" and "groupValid"),
<out>/<results-name minus .jsonl>_valid.jsonl (valid groups only) and <out>/groups.json.
"""
import glob
import json
import os
import re
import sys


def main():
    out, res = sys.argv[1], sys.argv[2]
    timing = {}
    tpath = os.path.join(out, "logs", "timing.txt")
    if os.path.exists(tpath):
        for line in open(tpath):
            kv = dict(re.findall(r"(\w+)=(\S+)", line))
            if "group" in kv:
                timing[kv["group"]] = kv
    windows = []
    for wp in [os.path.join(os.path.dirname(os.path.abspath(out)), "overload_windows.txt"), os.environ.get("OVERLOAD_WINDOWS", "")]:
        if wp and os.path.exists(wp):
            for line in open(wp):
                f = line.split(None, 2)
                if len(f) >= 2 and not line.startswith("#"):
                    windows.append((int(f[0]), int(f[1]), f[2].strip() if len(f) > 2 else ""))
    of, op = float(os.environ.get("OVERLOAD_FACTOR", "3.5")), float(os.environ.get("OVERLOAD_PSI", "25"))
    groups, all_lines, valid_lines = [], [], []
    for gpath in sorted(glob.glob(os.path.join(out, "groups", "*.jsonl"))):
        g = os.path.basename(gpath)[:-6]
        n_items = sum(1 for l in open(gpath) if l.strip())
        rpath = os.path.join(out, "res", g + ".jsonl")
        recs = [json.loads(l) for l in open(rpath) if l.strip()] if os.path.exists(rpath) else []
        spath = os.path.join(out, "logs", "sanity_result_%s.jsonl" % g)
        sanity = [json.loads(l)["after"] for l in open(spath) if l.strip()] if os.path.exists(spath) else None
        sanity_ok = sanity is None or (len(sanity) == 3 and all(s.strip().lower() == "because" for s in sanity))
        degraded = sum(1 for r in recs if r.get("timingDegraded"))
        complete = len(recs) == n_items
        reasons = []
        if not sanity_ok:
            reasons.append("sanity %s" % sanity)
        if degraded:
            reasons.append("%d timing-degraded" % degraded)
        if not complete:
            reasons.append("%d/%d results" % (len(recs), n_items))
        run = best = 0
        for r in recs:
            run = run + 1 if r.get("after") == r.get("before") else 0
            best = max(best, run)
        if best >= 2:
            # the IME's own deterministic behaviour (e.g. a stray tap puts it in a state that ignores
            # the next taps) reproduces item for item in a re-run; a crash/hidden keyboard does not
            same = set(r["i"] for r in recs if r.get("after") == r.get("before"))
            reproduced = False
            for old in glob.glob(os.path.join(out, "res", g + ".jsonl.invalid-*")) + glob.glob(os.path.join(out, "res", g + ".invalid-*")):
                try:
                    o = [json.loads(l) for l in open(old) if l.strip()]
                except ValueError:
                    continue
                if len(o) == len(recs) and set(r["i"] for r in o if r.get("after") == r.get("before")) == same:
                    reproduced = True
            if not reproduced:
                reasons.append("keyboard unresponsive (%d consecutive items unchanged; valid once a re-run reproduces them)" % best)
        t = timing.get(g, {})
        if t.get("load_max") and t.get("cores") and float(t["load_max"]) > of * int(t["cores"]):
            reasons.append("host overloaded (load %s > %.1f x %s cores)" % (t["load_max"], of, t["cores"]))
        if t.get("psi_max") and float(t["psi_max"]) > op:
            reasons.append("memory pressure (PSI some avg10 %s%% > %s%%)" % (t["psi_max"], op))
        t0, t1 = t.get("start"), t.get("end")
        if not t1 and t.get("seconds") and os.path.exists(rpath):  # older timing lines: end = result file mtime
            t1 = int(os.path.getmtime(rpath)); t0 = t1 - int(t["seconds"])
        if t0 and t1:
            for a, b, why in windows:
                if int(t0) < b and int(t1) > a:
                    reasons.append("overlaps host incident %s" % why)
        valid = not reasons
        parts = sorted({r.get("participant") for r in recs if r.get("participant")})
        info = {"group": g, "items": n_items, "results": len(recs), "valid": valid, "reasons": reasons,
                "sanity": sanity, "degraded": degraded, "participants": parts}
        info.update({k: timing.get(g, {}).get(k) for k in ("device", "seconds", "load_start", "load_end", "waited", "load_max", "psi_max")})
        groups.append(info)
        for r in recs:
            r["group"] = g
            r["groupValid"] = valid
            line = json.dumps(r) + "\n"
            all_lines.append(line)
            if valid:
                valid_lines.append(line)
    with open(os.path.join(out, res), "w") as f:
        f.writelines(all_lines)
    with open(os.path.join(out, res[:-6] + "_valid.jsonl"), "w") as f:
        f.writelines(valid_lines)
    with open(os.path.join(out, "groups.json"), "w") as f:
        json.dump(groups, f, indent=1)
    nv = sum(g["valid"] for g in groups)
    print("== groups: %d valid / %d (%d of %d items in valid groups)" % (
        nv, len(groups), len(valid_lines), len(all_lines)))
    for g in groups:
        if not g["valid"]:
            print("   invalid group %s %s on %s: %s" % (g["group"], g["participants"] or "", g["device"], "; ".join(g["reasons"])))


if __name__ == "__main__":
    main()
