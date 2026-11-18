#!/usr/bin/env python3
"""Sharding for parallel conformance runs (several emulators per keyboard).

  shard.py split --scenarios S --n N --out DIR      -> DIR/shard-<k>.jsonl, k = 0..N-1
  shard.py merge --scenarios S --out DEST SRC...    -> one batch directory from the shards' batch directories

split: every shard starts with the calibration scenarios (each batch classifies keyboard states against its own
references); the rest is assigned deterministically, in scenario order, to the shard with the least estimated
runner time so far. The Gboard and pboard legs use the same shard files, so shard k of each keyboard sees the same
scenarios in the same order (same learning history on both sides).

merge: concatenates the shards' observations (calibration scenarios from shard 0 only), restored to scenario
order; images are content-addressed, so they are hard-linked into one img/. info.json is shard 0's plus a
`shards` list (device, prepare/runner info per shard).
"""
import argparse
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

RESET_MS = 1500     # runner's resetMs per scenario
GAP_MS = 1200       # runner's default gap after a step
IDLE_MS = 450       # typical idle detection after a step (median 190-260 ms + sampling)


def read_jsonl(p):
    return [json.loads(l) for l in open(p) if l.strip()]


def write_jsonl(p, rows):
    with open(p, "w") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def cost_ms(row):
    t = RESET_MS
    for st in row.get("steps", []):
        dur = 0.0
        for stroke in st.get("strokes") or []:
            if stroke:
                dur = max(dur, stroke[-1][2])
        t += dur + st.get("gap", GAP_MS) + IDLE_MS + st.get("settle", 0) + st.get("ms", 0)
    return t


def split(rows, n):
    calib = [r for r in rows if r["id"].startswith("calib/")]
    rest = [r for r in rows if not r["id"].startswith("calib/")]
    shards = [list(calib) for _ in range(n)]
    load = [0.0] * n
    for r in rest:
        k = min(range(n), key=lambda i: (load[i], i))
        shards[k].append(r)
        load[k] += cost_ms(r)
    return shards, load


def merge(order_rows, dest, srcs):
    order = {r["id"]: i for i, r in enumerate(order_rows)}
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.makedirs(os.path.join(dest, "img"))

    def keep(k, sc):
        return k == 0 or not sc.startswith("calib/")

    for name, field in (("steps.jsonl", "sc"), ("obs.jsonl", "sc"), ("scenarios.jsonl", "id"),
                        ("scenarios.device.jsonl", "id")):
        rows = []
        for k, s in enumerate(srcs):
            p = os.path.join(s, name)
            if os.path.exists(p):
                rows += [r for r in read_jsonl(p) if keep(k, r.get(field, ""))]
        if rows:
            rows.sort(key=lambda r: order.get(r.get(field), 10 ** 6))  # stable: steps keep their order
            write_jsonl(os.path.join(dest, name), rows)
    for name in ("load.jsonl", "anchors.jsonl"):
        with open(os.path.join(dest, name), "w") as out:
            for s in srcs:
                p = os.path.join(s, name)
                if os.path.exists(p):
                    out.write(open(p).read())
    crash = "".join(open(os.path.join(s, "crash.txt")).read() for s in srcs if os.path.exists(os.path.join(s, "crash.txt")))
    if crash:
        open(os.path.join(dest, "crash.txt"), "w").write(crash)
    for s in srcs:
        d = os.path.join(s, "img")
        for f in os.listdir(d) if os.path.isdir(d) else []:
            t = os.path.join(dest, "img", f)
            if not os.path.exists(t):
                try:
                    os.link(os.path.join(d, f), t)
                except OSError:
                    shutil.copy2(os.path.join(d, f), t)
    infos = [json.load(open(os.path.join(s, "info.json"))) if os.path.exists(os.path.join(s, "info.json")) else {}
             for s in srcs]
    info = dict(infos[0])
    info["elapsedS"] = max(i.get("elapsedS") or 0 for i in infos)
    info["shards"] = [{"dir": os.path.basename(s), "serial": i.get("serial"), "apk": i.get("apk"),
                       "density": i.get("density"), "elapsedS": i.get("elapsedS"), "pboardBuild": i.get("pboardBuild")}
                      for s, i in zip(srcs, infos)]
    json.dump(info, open(os.path.join(dest, "info.json"), "w"), indent=1)
    metas = [json.load(open(os.path.join(s, "analysis.json"))) for s in srcs if os.path.exists(os.path.join(s, "analysis.json"))]
    if metas:
        meta = dict(metas[0])
        meta["calibrationWarnings"] = [w for m in metas for w in m.get("calibrationWarnings", [])]
        meta["images"] = len(os.listdir(os.path.join(dest, "img")))
        meta["shards"] = len(metas)
        json.dump(meta, open(os.path.join(dest, "analysis.json"), "w"), indent=1)
    if os.path.exists(os.path.join(srcs[0], "state.png")):
        shutil.copy2(os.path.join(srcs[0], "state.png"), os.path.join(dest, "state.png"))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("split")
    a.add_argument("--scenarios", required=True)
    a.add_argument("--n", type=int, required=True)
    a.add_argument("--out", required=True)
    m = sub.add_parser("merge")
    m.add_argument("--scenarios", required=True)
    m.add_argument("--out", required=True)
    m.add_argument("srcs", nargs="+")
    args = ap.parse_args()
    rows = read_jsonl(args.scenarios)
    if args.cmd == "split":
        os.makedirs(args.out, exist_ok=True)
        shards, load = split(rows, args.n)
        for k, sh in enumerate(shards):
            write_jsonl(os.path.join(args.out, "shard-%d.jsonl" % k), sh)
        print("   shards: " + ", ".join("%d: %d scenarios ~%.1f min" % (k, len(sh), l / 60000)
                                       for k, (sh, l) in enumerate(zip(shards, load))))
    else:
        merge(rows, args.out, args.srcs)
        print("   merged %d shard(s) -> %s" % (len(args.srcs), args.out))


if __name__ == "__main__":
    main()
