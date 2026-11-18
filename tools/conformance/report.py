#!/usr/bin/env python3
"""Writes the conformance report (markdown) from one or more diffed targets.

  report.py --target emulator=<runs-dir> [--target phone=<runs-dir>] [--shrink <dir>] [--control <runs-dir>]
            [--accuracy accuracy.json] --out docs/conformance/report.md [--clusters docs/conformance/clusters.json]

<runs-dir> holds gboard/ and pboard/ (analysed runs) and divergences.json (diff.py). Clusters are ranked by
(scenarios affected) x (user impact of the kind). For each: what Gboard does, what pboard does (from the
first example), a reproduction command, and the likely owning area.
"""
import argparse
import json
import os
import re
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import read_jsonl, load_json, ROOT  # noqa: E402

OWNER_RULES = [
    (r"letter tap: .*neighbouring key|tap-time", "engine (tap-time key resolution)"),
    (r"autocorrect|correction|strip mid-word|candidates|predictions|typed word is shown|emoji suggestions", "engine"),
    (r"glide: different word", "engine"),
    (r"bold", "engine + ime-ui"),
    (r"crash|died|focus", "ime-ui"),
]


def owner(c):
    for rx, o in OWNER_RULES:
        if re.search(rx, c["signature"]):
            return o
    if c["kind"] in ("keyboard", "cursor", "strip-layout", "timing", "composing", "focus"):
        return "ime-ui"
    return "ime-ui"


def obs(o):
    if not o:
        return "-"
    s = o.get("strip") or {}
    slots = " \\| ".join((x or "∅") + ("**" if s.get("bold") == i else "") for i, x in enumerate(s.get("slots") or []))
    kb = o.get("kb") or {}
    k = kb.get("page", "?") + ("/" + kb["shift"] if kb.get("shift") else "") + (" +popup" if kb.get("overlay") else "")
    return "text `%s` sel %s; strip %s%s; keyboard %s" % (
        (o.get("text") or "").replace("\n", "\\n"), o.get("sel", ["?"])[0], s.get("mode") or "?",
        (" [" + slots + "]") if slots else "", k)


def step_desc(ex, scen):
    sc = scen.get(ex["sc"]) or {}
    steps = sc.get("steps") or []
    m = ex.get("m") or {}
    k = m.get("k", "")
    if k == "tap":
        d = "tap on `%s`" % (m.get("sym") or m.get("key"))
        if m.get("depth") is not None and m.get("key") and len(m.get("key")) == 1:
            d += " (%.0f%% of a key width inside it, next to `%s`)" % (100 * m["depth"], m.get("nb"))
        return d
    if k == "burst":
        return "real-time burst of %d taps" % len(m.get("taps", []))
    if k == "glide":
        return "glide `%s`" % m.get("word")
    if k == "slot":
        return "tap the %s suggestion" % m.get("slot")
    if k == "drag":
        return "drag from `%s` by %+d px over %d ms" % (m.get("key"), m.get("dx", 0), m.get("ms", 0))
    if k == "hold":
        return "hold `%s` %d ms" % (m.get("key"), m.get("ms", 0))
    if k == "fieldtap":
        return "tap in the text at offset %s" % m.get("offset")
    if k == "app":
        return "the app runs `%s`" % m.get("cmd")
    if ex["step"] < 0:
        return "field focused"
    return k


def class_of(sid):
    return sid.split("/")[0]


def class_timing(d, scen):
    """Per scenario class: scenarios, steps, keystrokes, wall-clock minutes per keyboard (from the runner's clock)."""
    out = {}
    for ime in ("gboard", "pboard"):
        p = os.path.join(d, ime, "obs.jsonl")
        if not os.path.exists(p):
            continue
        spans = {}
        for r in read_jsonl(p):
            if r.get("aborted") or r.get("t0") is None:
                continue
            end = r["t0"] + (r.get("tEnd") or 0) + (r.get("observedMs") or 0)
            a, b = spans.get(r["sc"], (r["t0"], end))
            spans[r["sc"]] = (min(a, r["t0"]), max(b, end))
        for sid, (a, b) in spans.items():
            c = out.setdefault(class_of(sid), {"scenarios": set(), "steps": 0, "taps": 0, "ms": {}})
            if sid not in c["scenarios"]:
                c["scenarios"].add(sid)
                sc = scen.get(sid) or {"steps": []}
                c["steps"] += len(sc["steps"])
                c["taps"] += sum(len(st.get("strokes", [])) for st in sc["steps"] if st.get("op") == "gesture")
            c["ms"][ime] = c["ms"].get(ime, 0) + (b - a)
    return out


def speed_only(divs):
    """Signatures seen in a time-compressed replay of a sentence but not in the same sentence at real speed."""
    by = {}
    for dv in divs:
        m = re.match(r"human-sentence-x([0-9.]+)/(.*)$", dv["sc"])
        if m:
            by.setdefault((m.group(2), float(m.group(1))), set()).add(dv["kind"] + "|" + re.sub(r"\s*\[.*?\]$", "", dv["signature"]))
    found = {}
    for (base, sp), sigs in by.items():
        if sp >= 1.0:
            continue
        for sg in sigs - by.get((base, 1.0), set()):
            found.setdefault(sg, []).append((sp, base))
    return found


def load_target(d):
    res = json.load(open(os.path.join(d, "divergences.json")))
    scen = {s["id"]: s for s in read_jsonl(os.path.join(d, "gboard", "scenarios.jsonl"))}
    info = {ime: load_json(os.path.join(d, ime, "info.json")) for ime in ("gboard", "pboard")
            if os.path.exists(os.path.join(d, ime, "info.json"))}
    stats = {}
    for ime in ("gboard", "pboard"):
        p = os.path.join(d, ime, "steps.jsonl")
        if not os.path.exists(p):
            continue
        rows = [r for r in read_jsonl(p) if not r.get("aborted") and r.get("step", -1) >= 0]
        idle = [r for r in rows if r.get("idle")]
        ms = sorted(r["idleMs"] for r in idle if r.get("idleMs") is not None)
        ld = sorted(r["load1"] for r in rows if r.get("load1") is not None)
        stats[ime] = {"steps": len(rows), "idle": len(idle), "idleMedianMs": ms[len(ms) // 2] if ms else None,
                      "load": (ld[len(ld) // 2], ld[-1]) if ld else None, "loadN": len(ld),
                      "idleP95Ms": ms[int(len(ms) * 0.95)] if ms else None,
                      "aborted": sum(1 for r in read_jsonl(p) if r.get("aborted"))}
    return res, scen, info, stats


def repro_cmd(target, rep):
    if rep:
        return "`tools/conformance/run.sh --repro %s%s`" % (os.path.relpath(rep, ROOT), " --phone" if target == "phone" else "")
    return None


def gate_key(c):
    return c["kind"] + "|" + re.sub(r"\d+", "#", c["signature"])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", action="append", default=[], help="name=runs-dir")
    ap.add_argument("--shrink", action="append", default=[], help="name=shrink-dir")
    ap.add_argument("--control")
    ap.add_argument("--control-md", help="positive-control section as markdown (instead of --control)")
    ap.add_argument("--accuracy")
    ap.add_argument("--out", required=True)
    ap.add_argument("--clusters")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--notes", help="markdown appended verbatim (findings outside the step comparison)")
    ap.add_argument("--repro-dir", help="copy minimal repros here (e.g. docs/conformance/repros) and cite them")
    a = ap.parse_args()
    shrinks = {}
    for s in a.shrink:
        name, d = s.split("=", 1)
        p = os.path.join(d, "shrink.json")
        if os.path.exists(p):
            shrinks[name] = {x["cluster"]: x for x in json.load(open(p))}
    L = []
    w = L.append
    w("# Conformance report: pboard vs Gboard, keystroke by keystroke")
    w("")
    w("Generated %s by `tools/conformance/run.sh` (see tools/conformance/README.md). Every scenario is played "
      "identically on both keyboards and everything observable is compared after every step once the keyboard "
      "is idle: field text, selection, composing span, the strip's words (OCR) and layout, the keyboard page and "
      "shift state, and timing. The first divergence of each kind per scenario is clustered by what each "
      "keyboard did; clusters are ranked by scenarios affected x user impact." % time.strftime("%Y-%m-%d %H:%M"))
    w("")
    names = [t.split("=", 1)[0] for t in a.target]
    if any(n.startswith("farm") for n in names):
        w("Targets, in order of authority: **farm-440** and **farm-420** = Gboard 18.6.3 (x86_64) on the emulator farm read-only "
          "instances of the golden gb18-1 (verified per instance: synced look, `becuase` -> `because`, glide, main LM "
          "2026092216), at 440 dpi (the dev phone's 350-399 dp size set) and 420 dpi (the >= 400 dp set); each batch "
          "restarts the instance from snapshot `gboard_bench_base` and pboard is `pm clear`ed. **emulator-15.1** = Gboard "
          "15.1 on the local AVD (secondary; its pboard build is older, see its section). **phone** = Gboard 18.6 on the dev phone with its real account configuration "
          "(cross-check; nothing is reset there): clusters only seen there are listed separately.")
        w("")
    if a.accuracy and os.path.exists(a.accuracy):
        acc = load_json(a.accuracy)
        w("## Harness accuracy")
        w("")
        for line in acc.get("lines", []):
            w("- " + line)
        w("")
    if a.control_md and os.path.exists(a.control_md):
        w(open(a.control_md).read().rstrip())
        w("")
    elif a.control and os.path.exists(os.path.join(a.control, "divergences.json")):
        res = json.load(open(os.path.join(a.control, "divergences.json")))
        tf = [c for c in res["clusters"] if "neighbouring key" in c["signature"] and c["signature"].startswith("letter tap: G")]
        w("## Positive control (TF-01, tap-time key resolution)")
        w("")
        w("Run `%s` (human taps and fuzzing only, no hand-written TF-01 scenario; pboard built before the TF-01 fix):" % os.path.basename(a.control))
        if tf:
            for c in tf:
                srcs = Counter((e.get("src") or "?") for e in c["examples"])
                w("- **detected**: \"%s\" in %d scenario(s); occurrences by source: %s" % (
                    c["signature"], len(c["scenarios"]), ", ".join("%s %d" % kv for kv in srcs.items())))
                exs = sorted(c["examples"], key=lambda e: ((e.get("m") or {}).get("k") == "burst", e["sc"]))
                for e in exs[:5]:
                    m = e.get("m") or {}
                    where = (" (tap %.0f%% of a key width inside `%s`, next to `%s`)" % (100 * m["depth"], m.get("key"), m.get("nb"))
                             if m.get("depth") is not None else "")
                    w("  - `%s` step %d%s: text before `%s`; %s" % (e["sc"], e["step"], where, e.get("prevText"),
                                                                  (e.get("detail") or "").replace("|", "\\|")))
        else:
            w("- **not detected**")
        w("")
    keys_by_target = {}
    for t in a.target:
        name, d = t.split("=", 1)
        keys_by_target[name] = {gate_key(c) for c in json.load(open(os.path.join(d, "divergences.json")))["clusters"]}
    all_clusters = {}
    for t in a.target:
        name, d = t.split("=", 1)
        res, scen, info, stats = load_target(d)
        g, p = info.get("gboard", {}), info.get("pboard", {})
        w("## Target: %s" % name)
        w("")
        gv = (g.get("com.google.android.inputmethod.latin") or ["?"])[0]
        pv = (p.get("dev.pboard") or ["?", "?"])
        w("- Gboard: %s%s; pboard: %s (%s)" % (gv, (", main LM " + g["gboardMainLm"]) if g.get("gboardMainLm") else "",
                                            " ".join(pv), "apk " + p.get("apk", "?") if p.get("apk") else "installed build"))
        pbb = p.get("pboardBuild") or {}
        if pbb:
            w("- pboard build: %s" % ", ".join("%s %s" % (k, v) for k, v in pbb.items()))
        w("- State: %s" % ("Gboard restored from snapshot `%s` before its batch; pboard `pm clear`ed" % g.get("snapshot")
                           if g.get("snapshot") else "not reset (shared phone: neither app may be cleared)"))
        nsc = len([s for s in scen if not s.startswith("calib/")])
        nst = sum(len(s["steps"]) for k, s in scen.items() if not k.startswith("calib/"))
        w("- Scenarios: %d (%d steps) from %s" % (nsc, nst, ", ".join("%s %d" % (k, v) for k, v in
                                                                     Counter(s.get("src") for k, s in scen.items() if not k.startswith("calib/")).items())))
        for ime, st in stats.items():
            # which host the load was sampled on (load.jsonl rows carry host/cores since the farm runs)
            lp = os.path.join(d, ime, "load.jsonl")
            first = next(iter(read_jsonl(lp)), {}) if os.path.exists(lp) and os.path.getsize(lp) else {}
            host, cores = first.get("host", "host"), first.get("cores") or os.cpu_count() or 0
            w("- %s observations: %d steps, idle reached in %d (%.1f%%), settle median %s ms (p95 %s ms), %d scenarios aborted%s" % (
                ime, st["steps"], st["idle"], 100.0 * st["idle"] / max(1, st["steps"]), st["idleMedianMs"], st["idleP95Ms"], st["aborted"],
                "; %s load (1 min) median %.0f, max %.0f on %d cores (%d of %d steps sampled)" % (
                    host, st["load"][0], st["load"][1], cores, st.get("loadN", 0), st["steps"])
                if st.get("load") else "; host load not recorded for this batch"))
        tim = class_timing(d, scen)
        if tim:
            w("")
            w("Wall-clock by scenario class (runner clock, per keyboard):")
            w("")
            w("| class | scenarios | steps | keystrokes | Gboard min | pboard min | steps/min | keystrokes/min |")
            w("|---|---|---|---|---|---|---|---|")
            for k in sorted(tim):
                c = tim[k]
                mins = [v / 60000.0 for v in c["ms"].values()]
                mm = sum(mins) / len(mins) if mins else 0
                w("| %s | %d | %d | %d | %s | %s | %.0f | %.0f |" % (
                    k, len(c["scenarios"]), c["steps"], c["taps"],
                    "%.1f" % (c["ms"]["gboard"] / 60000.0) if "gboard" in c["ms"] else "-",
                    "%.1f" % (c["ms"]["pboard"] / 60000.0) if "pboard" in c["ms"] else "-",
                    c["steps"] / mm if mm else 0, c["taps"] / mm if mm else 0))
        cl = res["clusters"]
        kinds = Counter()
        kinds_sc = {}
        for c in cl:
            if c["allowed"] or c.get("intentSide") == "P":
                continue
            kinds[c["kind"]] += len(c["scenarios"])
            kinds_sc.setdefault(c["kind"], set()).update(c["scenarios"])
        w("")
        w("### Divergence counts by kind (not allowlisted)")
        w("")
        w("| kind | clusters | scenario-divergences | distinct scenarios |")
        w("|---|---|---|---|")
        for k, n in kinds.most_common():
            w("| %s | %d | %d | %d |" % (k, sum(1 for c in cl if c["kind"] == k and not c["allowed"] and c.get("intentSide") != "P"), n, len(kinds_sc[k])))
        allowed = [c for c in cl if c["allowed"]]
        w("| *allowlisted* | %d | %d | |" % (len(allowed), sum(len(c["scenarios"]) for c in allowed)))
        pw = [c for c in cl if c.get("intentSide") == "P" and not c["allowed"]]
        w("| *pboard wins (matched the intent, Gboard did not)* | %d | %d | |" % (len(pw), sum(len(c["scenarios"]) for c in pw)))
        w("")
        w("### Top divergences (ranked by scenarios x impact)")
        w("")
        sh = shrinks.get(name, {})
        wins = [c for c in cl if not c["allowed"] and c.get("intentSide") == "P"]
        cl = [c for c in cl if c.get("intentSide") != "P"]
        for i, c in enumerate([c for c in cl if not c["allowed"]][:a.top], 1):
            ex = c["examples"][0]
            w("**%d. %s** — %s, %d scenario(s), %d occurrence(s), sources: %s; owner: %s" % (
                i, c["signature"], c["kind"] + ("/" + c["subkind"] if c.get("subkind") else ""), len(c["scenarios"]),
                c["n"], ", ".join(c["srcs"]), owner(c)))
            w("")
            w("- Input: `%s` field (%s), %s%s; text before: `%s`" % (
                (ex.get("field") or {}).get("variant"), ", ".join("%s=%s" % kv for kv in (ex.get("field") or {}).items() if kv[0] not in ("variant", "text")) or "-",
                step_desc(ex, scen), "" if not ex.get("detail") else " — " + ex["detail"].replace("|", "\\|"),
                (ex.get("prevText") or "").replace("\n", "\\n")))
            w("- Gboard: %s" % obs(ex["G"]))
            w("- pboard: %s" % obs(ex["P"]))
            # (clusters split by ground truth after the shrink ran share the unsplit cluster's shrink result)
            rep = sh.get(c["signature"]) or sh.get(re.sub(r" \[(Gboard|pboard) matched the intended text\]$", "", c["signature"]))
            conf = []
            others = [o for o, ks in keys_by_target.items() if o != name and gate_key(c) in ks]
            if others:
                conf.append("also seen on the %s" % " and ".join(others))
            if rep is not None:
                conf.append(("reproduced in a fresh run (host load %s)" % rep.get("load")) if rep.get("reproduced")
                            else "did not reproduce in a fresh run (host load %s): treat as unconfirmed" % rep.get("load"))
            loads = [x for x in ((ex["G"] or {}).get("load1"), (ex["P"] or {}).get("load1")) if x is not None]
            if loads:
                conf.append("host load when first observed %.0f" % max(loads))
            if name == "phone":
                conf.append("phone run: IMEs on real hardware, host load irrelevant")
            if conf:
                w("- Confirmation: %s" % "; ".join(conf))
            if c.get("intent"):
                names = {"G": "only Gboard", "P": "only pboard", "both": "both", "neither": "neither"}
                w("- Ground truth (known intended text): %s" % ", ".join("%s %d" % (names[k], v) for k, v in sorted(c["intent"].items())))
            if rep and rep.get("reproduced") and a.repro_dir:
                import shutil
                os.makedirs(a.repro_dir, exist_ok=True)
                dst = os.path.join(a.repro_dir, "%s-%02d.json" % (name, i))
                shutil.copy(rep["repro"], dst)
                rep = dict(rep, repro=dst)
            cmd = repro_cmd(name, rep.get("repro") if rep and rep.get("reproduced") else None)
            if cmd:
                w("- Minimal repro (%d step%s, %s): %s" % (rep["steps"], "" if rep["steps"] == 1 else "s", rep.get("how"), cmd))
            else:
                mode = next((md for md in ("full", "control", "quick") if os.path.basename(d.rstrip("/")).endswith("_" + md)), "quick")
                w("- Repro: scenario `%s` up to step %d: `tools/conformance/run.sh --repro-scenario %s --steps %d --mode %s%s`%s" % (
                    ex["sc"], ex["step"], ex["sc"], ex["step"] + 1, mode, " --phone" if name == "phone" else "",
                    " (did not reproduce in isolation: depends on state built earlier)" if rep and not rep.get("reproduced") else ""))
            more = [e["sc"] + "#" + str(e["step"]) for e in c["examples"][1:4]]
            if more:
                w("- Also in: %s" % ", ".join(more))
            w("")
            all_clusters.setdefault(name, []).append({"signature": c["signature"], "kind": c["kind"], "scenarios": len(c["scenarios"])})
        rest = [c for c in cl if not c["allowed"]][a.top:]
        if rest:
            w("<details><summary>%d further clusters</summary>" % len(rest))
            w("")
            for c in rest:
                w("- %s — %s, %d scenario(s): e.g. `%s` step %d (%s)" % (c["signature"], c["kind"], len(c["scenarios"]),
                                                                       c["examples"][0]["sc"], c["examples"][0]["step"],
                                                                       (c["examples"][0].get("detail") or "").replace("|", "/")[:120]))
                all_clusters.setdefault(name, []).append({"signature": c["signature"], "kind": c["kind"], "scenarios": len(c["scenarios"])})
            w("")
            w("</details>")
            w("")
        if name == "phone":
            emu = [o for o in keys_by_target if o.startswith("farm")]
            only = [c for c in cl if not c["allowed"] and emu and not any(gate_key(c) in keys_by_target[o] for o in emu)]
            if only:
                w("### Phone-only clusters (not seen on the Gboard 18.6 emulators: per-account configuration effects?)")
                w("")
                for c in only:
                    ex = c["examples"][0]
                    w("- %s — %s, %d scenario(s); e.g. `%s` step %d: %s" % (c["signature"], c["kind"], len(c["scenarios"]),
                                                                       ex["sc"], ex["step"], (ex.get("detail") or "").replace("|", "\\|")[:160]))
                w("")
        so = speed_only(res["divergences"])
        if so:
            w("### Speed-only divergences (time-compressed replays only)")
            w("")
            w("The same human sentences replayed at 0.7x and 0.5x of the person's timing; listed when the divergence does not")
            w("occur for that sentence at real speed (decoder lag, commits racing the last letter, rollover).")
            w("")
            for sg, where in sorted(so.items(), key=lambda kv: -len(kv[1])):
                k, sig = sg.split("|", 1)
                w("- %s — %s; %d sentence(s): %s" % (sig, k, len(where), ", ".join(
                    "`human-sentence-x%g/%s`" % (sp, base) for sp, base in sorted(where)[:3])))
            w("")
        if wins:
            w("### pboard matched the intended text, Gboard did not (wins, not bugs)")
            w("")
            w("Human items have known targets (and fuzz boundary taps a known intended letter); these divergences are where")
            w("only pboard produced what the person meant.")
            w("")
            for c in wins:
                ex = c["examples"][0]
                w("- %s — %d scenario(s); e.g. `%s` step %d: text before `%s`; %s" % (
                    c["signature"], len(c["scenarios"]), ex["sc"], ex["step"], (ex.get("prevText") or "").replace("\n", "\\n"),
                    (ex.get("detail") or "").replace("|", "\\|")))
            w("")
        if allowed:
            w("### Allowlisted (tools/conformance/allowlist.yaml)")
            w("")
            for c in allowed:
                w("- %s — %d scenario(s) — allowlist `%s`" % (c["signature"], len(c["scenarios"]), c["allowed"]))
            w("")
    for d in [t.split("=", 1)[1] for t in a.target]:
        for ime in ("gboard", "pboard"):
            p = os.path.join(d, ime, "crash.txt")
            if os.path.exists(p) and "FATAL EXCEPTION" in open(p).read():
                txt = open(p).read()
                n = txt.count("FATAL EXCEPTION")
                first = [l.split("AndroidRuntime: ", 1)[-1] for l in txt.splitlines() if "AndroidRuntime" in l][:6]
                w("### Crashes logged during the %s batch of %s (%d)" % (ime, os.path.basename(d), n))
                w("")
                w("```")
                w("\n".join(first))
                w("```")
                w("")
    if a.notes and os.path.exists(a.notes):
        w(open(a.notes).read())
    if a.clusters:
        json.dump(all_clusters, open(a.clusters, "w"), indent=1, ensure_ascii=False)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    open(a.out, "w").write("\n".join(L) + "\n")
    print("report -> %s" % a.out)


if __name__ == "__main__":
    main()
