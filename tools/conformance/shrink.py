#!/usr/bin/env python3
"""Shrinks divergent scenarios to minimal reproductions (delta debugging), re-running both keyboards
from their clean state for every round.

  shrink.py --serial S --runs <dir with gboard/ pboard/ divergences.json> [--top 20] [--rounds 3] --out DIR

For the best example of each of the top clusters (scenario cut after the divergent step):
  round 1   three candidates: (a) the field pre-filled with the text and cursor just before the divergent
            step, then that step alone; (b) pre-filled up to the start of the current word, then the steps
            of that word; (c) the whole prefix (control: must reproduce in a fresh run).
  rounds 2+ ddmin over the smallest reproducing candidate's steps (the divergent step always stays).
A candidate reproduces when its final step diverges with the same signature (kind and subkind for
strip/timing clusters, whose wording carries counts). All candidates of a round share one batch per
keyboard (fresh state per batch). Writes <out>/<n>.json (repro scenario) and <out>/shrink.json.
"""
import argparse
import copy
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import read_jsonl, write_jsonl, load_json, log  # noqa: E402
import analyze as A  # noqa: E402
import diff as D  # noqa: E402
import device as DV  # noqa: E402


def same(div, target):
    if div["kind"] != target["kind"]:
        return False
    if div["kind"] in ("text", "cursor", "focus", "keyboard"):
        strip = lambda s: re.sub(r"\s*\[real-time\]$", "", s)
        return strip(div["signature"]) == strip(target["signature"])
    return div.get("subkind") == target.get("subkind")


def candidates_round1(sc, ex):
    k = ex["step"]
    steps = sc["steps"][:k + 1]
    out = []
    g = ex["G"]
    prev = ex.get("prevText")
    if k >= 0 and prev is not None:
        a = copy.deepcopy(sc)
        a["field"] = dict(sc["field"], text=prev)
        a["steps"] = [steps[-1]]
        sel = ex.get("prevSel")
        if sel is not None and sel != len(prev):
            # the playground puts the cursor at the end; move it as the app would
            a["steps"] = [{"op": "app", "cmd": "setsel", "selStart": sel, "selEnd": sel, "m": {"k": "app", "cmd": "setsel"}}] + a["steps"]
        out.append(("prefill-step", a))
        # a real-time burst cut right after the divergent keystroke
        mk = re.search(r"keystroke (\d+)", ex.get("detail") or "")
        last = steps[-1]
        if mk and (last.get("m") or {}).get("k") == "burst" and int(mk.group(1)) + 1 < len(last["strokes"]):
            k = int(mk.group(1))
            cut = copy.deepcopy(a)
            st = copy.deepcopy(last)
            st["strokes"] = st["strokes"][:k + 1]
            st["m"] = dict(st["m"], taps=st["m"]["taps"][:k + 1])
            cut["steps"] = [s_ for s_ in cut["steps"][:-1]] + [st]
            out.append(("burst-cut", cut))
        # start of the current word: last step after which the text ended with a separator
        b = None
        texts = ex.get("_texts") or []
        for j in range(k - 1, -1, -1):
            t = texts[j] if j < len(texts) else None
            if t is not None and (t == "" or t[-1] in " .,\n"):
                b = copy.deepcopy(sc)
                b["field"] = dict(sc["field"], text=t)
                b["steps"] = steps[j + 1:]
                break
        if b is not None and len(b["steps"]) > 1:
            out.append(("prefill-word", b))
    c = copy.deepcopy(sc)
    c["steps"] = steps
    out.append(("prefix", c))
    return out


def ddmin_candidates(sc, n):
    """Complements of n chunks of the steps before the last one."""
    body, last = sc["steps"][:-1], sc["steps"][-1]
    if not body:
        return []
    n = min(n, len(body))
    size = (len(body) + n - 1) // n
    out = []
    for i in range(0, len(body), size):
        c = copy.deepcopy(sc)
        c["steps"] = body[:i] + body[i + size:] + [last]
        out.append(("ddmin-%d-%d" % (n, i), c))
    return out


ROUND_LOAD = {}


def run_round(serial, cands, work, rnd):
    """serial: one device, or "a,b" to run the Gboard and pboard batches of the round concurrently."""
    devs = serial.split(",")
    out = os.path.join(work, "round%d" % rnd)
    loadp = os.path.join(work, "round%d.load" % rnd)
    if os.environ.get("CONFORM_SHRINK_REUSE") == "1" and all(
            os.path.exists(os.path.join(out, ime, "steps.jsonl")) for ime in ("gboard", "pboard")):
        # an interrupted shrink: reuse this round's analysed batches (same candidates: they are deterministic)
        ROUND_LOAD[rnd] = float(open(loadp).read()) if os.path.exists(loadp) else \
            float(os.environ.get("CONFORM_SHRINK_LOAD%d" % rnd, "nan"))
        log("== shrink round %d: reusing %s" % (rnd, out))
        return compare_round(out)
    if not DV.is_phone(devs[0]):
        ROUND_LOAD[rnd] = round(DV.wait_for_quiet(), 1)
        os.makedirs(work, exist_ok=True)
        open(loadp, "w").write(str(ROUND_LOAD[rnd]))
    rows = []
    for key, (label, sc) in cands:
        r = copy.deepcopy(sc)
        r["id"] = "shrink/%s/%s" % (key, label)
        rows.append(r)
    calib = [s.to_json() for s in __import__("scenarios").calib()]
    path = os.path.join(work, "round%d.jsonl" % rnd)
    write_jsonl(path, calib + rows)
    if len(devs) >= 2:
        import threading
        errs = []

        def leg(dev, ime):
            try:
                DV.run_batch(dev, ime, path, os.path.join(out, ime))
            except BaseException as e:  # noqa: B902 (SystemExit from the device layer)
                errs.append("%s on %s: %r" % (ime, dev, e))
        ts = [threading.Thread(target=leg, args=(devs[0], "gboard")), threading.Thread(target=leg, args=(devs[1], "pboard"))]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        if errs:
            raise SystemExit("!! shrink round %d failed: %s" % (rnd, "; ".join(errs)))
        for ime in ("gboard", "pboard"):
            A.analyze(os.path.join(out, ime))
    else:
        for ime in ("gboard", "pboard"):
            DV.run_batch(serial, ime, path, os.path.join(out, ime))
            A.analyze(os.path.join(out, ime))
    return compare_round(out)


def compare_round(out):
    ga = read_jsonl(os.path.join(out, "gboard", "steps.jsonl"))
    pa = read_jsonl(os.path.join(out, "pboard", "steps.jsonl"))
    scen = {s["id"]: s for s in read_jsonl(os.path.join(out, "gboard", "scenarios.jsonl"))}
    divs = D.compare(ga, pa, scen)
    by = {}
    for d in divs:
        by.setdefault(d["sc"], []).append(d)
    return by


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--runs", required=True)
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    res = json.load(open(os.path.join(a.runs, "divergences.json")))
    scen = {s["id"]: s for s in read_jsonl(os.path.join(a.runs, "scenarios.jsonl"))}
    gsteps = {}
    for r in read_jsonl(os.path.join(a.runs, "gboard", "steps.jsonl")):
        if not r.get("aborted"):
            gsteps[(r["sc"], r["step"])] = r.get("text")
    os.makedirs(a.out, exist_ok=True)
    work = os.path.join(a.out, "work")
    todo = {}
    for i, c in enumerate([c for c in res["clusters"] if not c["allowed"]][:a.top]):
        ex = c["examples"][0]
        sc = scen.get(ex["sc"])
        if sc is None or ex["step"] < 0:
            continue
        ex["_texts"] = [gsteps.get((ex["sc"], j)) for j in range(len(sc["steps"]))]
        todo[str(i)] = {"cluster": c["signature"], "kind": c["kind"], "target": ex, "sc": sc, "best": None}
    # round 1
    cands = []
    for key, t in todo.items():
        for label, cand in candidates_round1(t["sc"], t["target"]):
            cands.append((key, (label, cand)))
    log("== shrink round 1: %d candidates for %d clusters" % (len(cands), len(todo)))
    by = run_round(a.serial, cands, work, 1)
    for key, (label, cand) in cands:
        t = todo[key]
        ds = by.get("shrink/%s/%s" % (key, label), [])
        if any(same(d, t["target"]) for d in ds):
            t["confirmedLoad"] = ROUND_LOAD.get(1)
        if any(same(d, t["target"]) for d in ds) and (t["best"] is None or len(cand["steps"]) < len(t["best"]["steps"])):
            t["best"] = cand
            t["bestLabel"] = label
    # ddmin rounds
    n = {k: 2 for k in todo}
    for rnd in range(2, a.rounds + 1):
        cands = []
        for key, t in todo.items():
            if t["best"] is not None and len(t["best"]["steps"]) > 1:
                cands += [(key, c) for c in ddmin_candidates(t["best"], n[key])]
        if not cands:
            break
        log("== shrink round %d: %d candidates" % (rnd, len(cands)))
        by = run_round(a.serial, cands, work, rnd)
        for key, t in todo.items():
            found = None
            for k2, (label, cand) in cands:
                if k2 == key and any(same(d, t["target"]) for d in by.get("shrink/%s/%s" % (key, label), [])):
                    if found is None or len(cand["steps"]) < len(found["steps"]):
                        found = cand
            if found is not None:
                t["best"] = found
                t["bestLabel"] = "ddmin"
                n[key] = max(2, n[key] - 1)
            else:
                n[key] *= 2
    summary = []
    for key, t in todo.items():
        rec = {"cluster": t["cluster"], "kind": t["kind"], "scenario": t["sc"]["id"], "step": t["target"]["step"],
               "reproduced": t["best"] is not None, "load": t.get("confirmedLoad", ROUND_LOAD.get(1))}
        if t["best"] is not None:
            r = copy.deepcopy(t["best"])
            r["id"] = "repro/%s" % key
            p = os.path.abspath(os.path.join(a.out, "%s.json" % key))
            json.dump(r, open(p, "w"), indent=1, ensure_ascii=False)
            rec.update({"repro": p, "steps": len(r["steps"]), "how": t.get("bestLabel"), "field": r["field"]})
        summary.append(rec)
    json.dump(summary, open(os.path.join(a.out, "shrink.json"), "w"), indent=1, ensure_ascii=False)
    for s in summary:
        print("%-5s %-60s %s" % ("ok" if s["reproduced"] else "NOREP", s["cluster"][:60], s.get("repro", "")))


if __name__ == "__main__":
    main()
