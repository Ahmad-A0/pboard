#!/usr/bin/env python3
"""Summarise the injection timing self-test (Replay mode=timing) output.

Usage: timing_report.py timing.json [--json out.json]

For every synthetic swipe it checks what PlaygroundActivity actually received:
  - timestamp fidelity: share of scheduled samples whose exact eventTime (relative to DOWN, +-1 ms)
    was delivered, and the number of extra (resampled) samples Android's input consumer added;
  - real-time behaviour: lateness of each injection call vs its schedule, the worst stretch of a
    gap between consecutive events ("stall"), and dispatch latency (receipt uptime - eventTime).
"""
import json
import sys


def pct(v, q):
    if not v:
        return float("nan")
    v = sorted(v)
    return v[min(len(v) - 1, int(q * (len(v) - 1) + 0.5))]


def main():
    path = sys.argv[1]
    with open(path) as f:
        data = json.load(f)
    by_hz = {}
    for key, r in data.items():
        hz = int(round(r["hz"]))
        s = by_hz.setdefault(hz, {"runs": 0, "sched": 0, "matched": 0, "extra": 0, "late": [], "stall": [],
                                  "lag": [], "intervals": [], "ts_err": []})
        s["runs"] += 1
        sched = r["scheduledRel"]
        rec = r["received"]
        down = next((e for e in rec if e["action"] == 0), None)
        if down is None:
            continue
        t0 = down["t"]
        delivered = []
        for e in rec:
            for h in e["hist"]:
                delivered.append(h[2] - t0)
            delivered.append(e["t"] - t0)
            s["lag"].append(e["recv"] - e["t"])
        dset = set(delivered)
        matched = [t for t in sched if t in dset or (t - 1) in dset or (t + 1) in dset]
        s["sched"] += len(sched)
        s["matched"] += len(matched)
        s["extra"] += max(0, len(delivered) - len(matched))
        for t in sched:
            best = min(delivered, key=lambda d: abs(d - t))
            s["ts_err"].append(abs(best - t))
        s["late"] += r["injectLateMs"]
        s["stall"].append(r["stallMs"])
        s["intervals"] += [b - a for a, b in zip(sched, sched[1:])]
    out = {}
    lines = ["| rate | swipes | samples scheduled | delivered with exact timestamp | extra resampled | max ts err (ms) |"
             " inject late p50/p95/max (ms) | stall p50/max (ms) | dispatch latency p50/p95 (ms) |",
             "|---|---|---|---|---|---|---|---|---|"]
    for hz in sorted(by_hz):
        s = by_hz[hz]
        o = {"swipes": s["runs"], "scheduled": s["sched"], "matched": s["matched"],
             "matchedPct": 100.0 * s["matched"] / max(1, s["sched"]), "extraResampled": s["extra"],
             "maxTimestampErrMs": max(s["ts_err"]) if s["ts_err"] else None,
             "injectLate": [pct(s["late"], .5), pct(s["late"], .95), max(s["late"])],
             "stall": [pct(s["stall"], .5), max(s["stall"])],
             "dispatchLatency": [pct(s["lag"], .5), pct(s["lag"], .95)]}
        out[hz] = o
        lines.append("| %d Hz | %d | %d | %d (%.1f%%) | %d | %d | %.1f / %.1f / %.1f | %.1f / %.1f | %d / %d |" % (
            hz, o["swipes"], o["scheduled"], o["matched"], o["matchedPct"], o["extraResampled"], o["maxTimestampErrMs"],
            *o["injectLate"], *o["stall"], *o["dispatchLatency"]))
    print("\n".join(lines))
    if "--json" in sys.argv:
        with open(sys.argv[sys.argv.index("--json") + 1], "w") as f:
            json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
