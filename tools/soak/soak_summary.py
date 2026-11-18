#!/usr/bin/env python3
"""Summary of a soak run (tools/soak/soak.py): crashes, ANRs, process restarts, memory / thread / view trends, wake locks.

  soak_summary.py <out-dir>      writes <out-dir>/summary.md and prints it
"""
import json
import os
import re
import sys


def slope(xs, ys):
    n = len(xs)
    if n < 3:
        return 0.0
    mx, my = sum(xs) / n, sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else 0.0


def main():
    d = sys.argv[1]
    info = json.load(open(os.path.join(d, "info.json"))) if os.path.exists(os.path.join(d, "info.json")) else {}
    rows = []
    p = os.path.join(d, "samples.tsv")
    if os.path.exists(p):
        lines = open(p).read().splitlines()
        head = lines[0].split("\t")
        for l in lines[1:]:
            rows.append(dict(zip(head, l.split("\t"))))
    out = ["## Soak: %s" % os.path.basename(d.rstrip("/")), ""]
    if rows:
        t0, t1 = int(rows[0]["time"]), int(rows[-1]["time"])
        out.append("- duration: %.2f h (%s to %s), %d samples; fields: %s" % (
            (t1 - t0) / 3600, info.get("start", "?"), info.get("end", "?"), len(rows), ", ".join(info.get("fields", []))))
    steps = 0
    setup_fail = 0
    no_kb = 0
    op_fail = 0
    obs = os.path.join(d, "obs.jsonl")
    if os.path.exists(obs):
        for l in open(obs):
            try:
                r = json.loads(l)
            except ValueError:
                continue
            if r.get("step", -1) >= 0:
                steps += 1
            if r.get("setupFailed"):
                setup_fail += 1
                no_kb += "IME not shown" in r["setupFailed"]
            if r.get("opFailed"):
                op_fail += 1
    out.append("- rounds over the fields: %s; observed input steps: %d; scenario starts that failed: %d (keyboard not "
               "shown: %d); steps whose action failed: %d" % (info.get("rounds", "?"), steps, setup_fail, no_kb, op_fail))
    pids = []
    for r in rows:
        if r["pid"] != "-" and (not pids or pids[-1] != r["pid"]):
            pids.append(r["pid"])
    ev = open(os.path.join(d, "events.txt")).read() if os.path.exists(os.path.join(d, "events.txt")) else ""
    lost = ev.count("DEVICE LOST")
    out.append("- pboard process ids over the run: %s (%d change%s; %d of them from the emulator itself dying and being "
               "restarted, which also restarts pboard)" % (", ".join(pids) or "-", max(0, len(pids) - 1),
                                                           "" if len(pids) == 2 else "s", lost))
    crash_txt = open(os.path.join(d, "crash_buffer.txt")).read() if os.path.exists(os.path.join(d, "crash_buffer.txt")) else ""
    pc = len(re.findall(r"Process: dev\.pboard\b", crash_txt))
    other = len(re.findall(r"FATAL EXCEPTION", crash_txt)) - pc
    drop = open(os.path.join(d, "dropbox.txt")).read() if os.path.exists(os.path.join(d, "dropbox.txt")) else ""
    entries = re.split(r"\n(?=========================================\n|\d{4}-\d\d-\d\d \d\d:\d\d:\d\d )", drop)
    anr = sum(1 for e in entries if "_anr" in e[:200] and "Process: dev.pboard" in e)
    dcrash = sum(1 for e in entries if ("data_app_crash" in e[:200] or "native_crash" in e[:200]) and "Process: dev.pboard" in e)
    out.append("- pboard crashes in the crash buffer: %d (other processes: %d); dropbox: %d pboard ANR, %d pboard crash entries" % (
        pc, max(0, other), anr, dcrash))
    if rows:
        def series(k):
            xs, ys = [], []
            for r in rows:
                if r.get(k, "-").lstrip("-").isdigit() and r[k] != "-":
                    xs.append((int(r["time"]) - t0) / 3600)
                    ys.append(int(r[k]))
            return xs, ys
        for k, label, scale, unit in [("pss_kb", "PSS", 1024, "MB"), ("java_kb", "Java heap", 1024, "MB"),
                                      ("native_kb", "native heap", 1024, "MB"), ("views", "Views", 1, ""),
                                      ("viewroots", "ViewRootImpl", 1, ""), ("activities", "Activities", 1, ""),
                                      ("local_binders", "local binders", 1, ""), ("proxy_binders", "proxy binders", 1, ""),
                                      ("threads", "threads", 1, "")]:
            xs, ys = series(k)
            if not ys:
                continue
            # trend after the first quarter (warm-up: caches, JIT, language model pages)
            q = len(xs) // 4
            s = slope(xs[q:], ys[q:]) / scale
            out.append("- %s: first %.1f, last %.1f, min %.1f, max %.1f%s; trend after warm-up %+.2f%s/h" % (
                label, ys[0] / scale, ys[-1] / scale, min(ys) / scale, max(ys) / scale, (" " + unit) if unit else "", s,
                (" " + unit) if unit else ""))
        wl = [int(r["wakelocks"]) for r in rows if r.get("wakelocks", "-").isdigit()]
        out.append("- samples with a pboard wake lock held: %d of %d" % (sum(1 for x in wl if x), len(wl)))
    md = "\n".join(out) + "\n"
    open(os.path.join(d, "summary.md"), "w").write(md)
    sys.stdout.write(md)


if __name__ == "__main__":
    main()
