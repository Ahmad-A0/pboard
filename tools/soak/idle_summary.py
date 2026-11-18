#!/usr/bin/env python3
"""Summary of an idle check (tools/soak/idle_check.sh): CPU, threads, memory and wake locks of pboard's process over
the window, and the batterystats deltas of its uid (wake locks, jobs, syncs, wakeup alarms, network, process CPU).

  idle_summary.py <out-dir>
"""
import collections
import os
import re
import sys

TYPES = {"wl": "wake lock", "jb": "job", "sy": "sync", "wua": "wakeup alarm", "nt": "network", "pr": "process",
         "cpu": "cpu", "awl": "aggregated wake lock", "jbc": "job completion", "jbd": "job reason"}
HZ = 100  # USER_HZ on Android kernels


def checkin(path, uid):
    """{(type, name): [numbers]} for the uid's per-app lines of `dumpsys batterystats --checkin`."""
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, errors="replace"):
        f = line.strip().split(",")
        if len(f) < 4 or f[1] != uid or f[2] != "l" or f[3] not in TYPES:
            continue
        nums, name = [], []
        for x in f[4:]:
            try:
                nums.append(float(x))
            except ValueError:
                name.append(x)
        out[(f[3], "/".join(name))] = nums
    return out


def grep(path, pat):
    if not os.path.exists(path):
        return []
    return [l.rstrip() for l in open(path, errors="replace") if re.search(pat, l)]


def main():
    d = sys.argv[1]
    uid = open(os.path.join(d, "uid.txt")).read().strip()
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(d, "samples.tsv"))][1:]
    print("## Idle check: %s\n" % os.path.basename(d.rstrip("/")))
    if not rows:
        print("no samples")
        return
    t0, t1 = int(rows[0][0]), int(rows[-1][0])
    pids = sorted({r[1] for r in rows})
    shown = collections.Counter(r[5] for r in rows)
    print("- window: %.1f min, %d samples; pboard pid(s): %s; keyboard shown in samples: %s" % (
        (t1 - t0) / 60, len(rows), ", ".join(pids), dict(shown)))
    live = [r for r in rows if r[2] not in ("-", "")]
    if len(live) >= 2 and live[0][1] == live[-1][1]:
        cpu = (float(live[-1][2]) - float(live[0][2])) / HZ
        print("- process CPU time over the window: %.2f s (%.3f %% of one core)" % (cpu, 100 * cpu / max(1, t1 - t0)))
        thr = [int(r[3]) for r in live if r[3].isdigit()]
        rss = [int(r[4]) for r in live if r[4].isdigit()]
        if thr:
            print("- threads: %d -> %d (min %d, max %d)" % (thr[0], thr[-1], min(thr), max(thr)))
        if rss:
            print("- RSS: %.1f -> %.1f MB" % (rss[0] / 1024, rss[-1] / 1024))
    elif not live:
        print("- pboard's process was not running (nothing to hold wake locks)")
    else:
        print("- pboard's process restarted during the window (pids %s)" % ", ".join(pids))
    wl = [int(r[6]) for r in rows if r[6].isdigit()]
    print("- samples with a pboard wake lock in `dumpsys power`: %d of %d" % (sum(1 for x in wl if x > 0), len(wl)))
    a, b = checkin(os.path.join(d, "start_checkin.txt"), uid), checkin(os.path.join(d, "end_checkin.txt"), uid)
    deltas = []
    for k in sorted(set(a) | set(b)):
        va, vb = a.get(k, []), b.get(k, [])
        n = max(len(va), len(vb))
        va, vb = va + [0] * (n - len(va)), vb + [0] * (n - len(vb))
        dv = [y - x for x, y in zip(va, vb)]
        if any(abs(x) > 0 for x in dv):
            deltas.append((k, dv))
    def bt(path):  # battery realtime of the stats window (ms), to notice a reset in between
        for line in open(path, errors="replace") if os.path.exists(path) else []:
            f = line.split(",")
            if len(f) > 5 and f[2:4] == ["l", "bt"]:
                return float(f[5])
        return None
    b0, b1 = bt(os.path.join(d, "start_checkin.txt")), bt(os.path.join(d, "end_checkin.txt"))
    if b0 is not None and b1 is not None and b1 < b0:
        print("- (battery stats were reset during the window: the end snapshot covers only the last %.0f s, so the "
              "batterystats line below says little; the per-sample wake-lock and CPU readings above cover the window)" % (b1 / 1000))
    print("- batterystats deltas for uid %s (wake locks, jobs, syncs, wakeup alarms, network, cpu): %s" % (
        uid, "none (all zero)" if not deltas else ""))
    for (t, name), dv in deltas:
        print("    - %s %s: %s" % (TYPES[t], name or "-", ", ".join("%g" % x for x in dv)))
    pk = r"dev\.pboard(?![.\w])"  # dev.pboard itself, not dev.pboard.bench / .webfield
    alarms = grep(os.path.join(d, "end_alarm.txt"), pk)
    jobs = grep(os.path.join(d, "end_jobs.txt"), r"JOB #.*" + pk)
    svcs = [l for l in grep(os.path.join(d, "end_services.txt"), r"ServiceRecord") if re.search(pk, l)]
    power = open(os.path.join(d, "end_power.txt"), errors="replace").read() if os.path.exists(os.path.join(d, "end_power.txt")) else ""
    current = power.split("Wake Locks: size", 1)[1].split("\n\n", 1)[0] if "Wake Locks: size" in power else ""
    locks = [l for l in current.splitlines()[1:] if re.search(r"uid=%s\b" % uid, l) or re.search(pk, l)]
    net = grep(os.path.join(d, "end_netstats.txt"), r"\buid=%s\b" % uid) if uid else []
    print("- alarms naming dev.pboard: %d; scheduled jobs: %d; running services: %s" % (
        len(alarms), len(jobs), "; ".join(s.strip() for s in svcs) or "none"))
    print("- wake locks held by pboard at the end: %d; netstats entries for the uid: %d" % (len(locks), len(net)))
    for l in (alarms + jobs + locks)[:10]:
        print("    " + l.strip())


if __name__ == "__main__":
    main()
