#!/usr/bin/env python3
"""Soak test: hours of randomised, realistic input through pboard across the app matrix on one device, watching for
crashes, ANRs, memory growth and thread / view / wake-lock leaks.

  soak.py --serial S --hours H --out DIR [--fields id,id] [--seed N] [--steps-per-field N] [--ime pboard|gboard]

Input: for each field in turn (tools/appcompat/apps.py), a long random scenario played by the app-compatibility runner
(bench/.../AppCompat.kt): sentences typed word by word (tools/bench/sentences), glides, single and held backspaces,
cursor moves, select-and-replace, pastes, the emoji panel, the symbols page, enter, app switches, rotations and the
app replacing the text. Fields cycle until the time is up.
Watch (host side, every --sample seconds): pboard's pid (a change = the process died), `dumpsys meminfo dev.pboard`
(PSS, Java/native heap, Views, ViewRootImpl, Activities, binders), threads (/proc/<pid>/status), wake locks naming
pboard (`dumpsys power`); at the end the crash buffer and the dropbox (data_app_crash / data_app_anr /
data_app_native_crash) entries for dev.pboard.
Output: DIR/samples.tsv, DIR/obs.jsonl (every step), DIR/events.txt, DIR/summary.md (tools/soak/soak_summary.py).
"""
import argparse
import json
import os
import random
import re
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "tools", "appcompat"))
import apps as A  # noqa: E402
import run as R  # noqa: E402
import scenarios as SC  # noqa: E402

PKG = "dev.pboard"


def sentences():
    p = os.path.join(ROOT, "tools", "bench", "sentences", "heldout_v1.txt")
    out = [l.strip() for l in open(p) if l.strip() and not l.startswith("#")]
    return [re.sub(r"[^a-z ]", "", s.lower()) for s in out]


def random_scenario(f, rng, n, sents):
    """One long random scenario on field f (n steps)."""
    b = SC.B()
    kind = f["kind"]
    texty = kind in ("text", "multiline")
    length = 0  # rough text length, for cursor moves
    words = []
    for _ in range(n):
        r = rng.random()
        if kind == "terminal":
            choice = "word" if r < 0.6 else ("delete" if r < 0.8 else "enter")
        elif r < 0.45:
            choice = "word"
        elif r < 0.55 and texty:
            choice = "glide"
        elif r < 0.65:
            choice = "delete"
        elif r < 0.70:
            choice = "hold_delete"
        elif r < 0.76 and f.get("clear"):
            choice = "cursor"
        elif r < 0.80 and f.get("clear"):
            choice = "select"
        elif r < 0.83 and f.get("clear"):
            choice = "paste"
        elif r < 0.86 and texty:
            choice = "emoji"
        elif r < 0.89:
            choice = "symbols"
        elif r < 0.91:
            choice = "enter"
        elif r < 0.93:
            choice = "appswitch"
        elif r < 0.95:
            choice = "rotate"
        elif r < 0.97 and f.get("clear"):
            choice = "settext"
        else:
            choice = "word"
        if choice == "word":
            if not words:
                words = rng.choice(sents).split()
            w = words.pop(0)
            if kind == "password":
                w = w[:6]
            b.word(w + (" " if rng.random() < 0.85 else ""))
            length += len(w) + 1
        elif choice == "glide":
            if not words:
                words = rng.choice(sents).split()
            w = words.pop(0)
            if len(w) >= 2:
                b.glide(w)
                length += len(w) + 1
        elif choice == "delete":
            for _ in range(rng.randint(1, 3)):
                b.key("delete")
            length = max(0, length - 2)
        elif choice == "hold_delete":
            b._s.hold("delete", rng.choice([600, 1200, 2000]))
            b._take("hold delete")
            length = max(0, length - 10)
        elif choice == "cursor":
            p = rng.randint(0, max(0, length))
            b.op("setsel", "cursor to %d" % p, start=p, end=p)
        elif choice == "select":
            s = rng.randint(0, max(0, length))
            e = min(length, s + rng.randint(1, 8))
            b.op("setsel", "select %d-%d" % (s, e), start=s, end=e)
            if rng.random() < 0.7:
                b.word(rng.choice(["yes", "no", "okay", "there"]))
        elif choice == "paste":
            b.op("paste", "paste", text=rng.choice(["hello", "see you soon", "https://example.org/a?b=c", "42"]))
            length += 8
        elif choice == "emoji":
            SC.emoji_steps(b)
            length += 2
        elif choice == "symbols":
            b.key("symbols", "symbols page")
            b._s.sym(rng.choice(["1", "2", "?", "!", "@", "("]))
            b._take("symbol")
            b._s.sym("abc")
            b._take("letters")
        elif choice == "enter":
            b.key("enter", "enter")
        elif choice == "appswitch":
            b.op("key", "home", code="KEYCODE_HOME", settle=500)
            b.op("resume", "back to the app", pkg=f["app"], settle=500)
            b.op("refocus", "tap the field", ifHidden=True)
        elif choice == "rotate":
            b.op("rotate", "landscape", rot=1)
            b.op("rotate", "portrait", rot=0)
            b.op("refocus", "tap the field", ifHidden=True)
        elif choice == "settext":
            b.op("settext", "app replaces the text", text=rng.choice(["", "Draft", "Reply to: hi"]))
            length = 6
    setup = list(f["open"]) + [dict({"op": "focus", "sel": f["sel"]}, **f.get("focusOpts", {}))]
    if f.get("clear"):
        setup.append({"op": "settext", "text": ""})
    sc = {"id": "%s/soak" % f["id"], "field": f["id"], "scenario": "soak", "app": f["app"], "ime": PKG,
          "setup": setup, "steps": b.steps, "sel": f["sel"]}
    if f.get("probe"):
        sc["probe"] = f["probe"]
    return sc


SNAPSHOT = os.environ.get("POOL_SNAPSHOT", "gboard_bench_base")


def device_alive(d):
    try:
        return d.adb("get-state", check=False, timeout=20).strip() == "device"
    except subprocess.TimeoutExpired:
        return False


def restart_read_only(d):
    """Restarts a dead pool instance the way the pool / conformance harness do: the same port, read-only, from the
    golden snapshot (the instance count does not change; nothing is saved). Only for the device this job leased."""
    port = d.serial.split("-")[-1]
    avd = os.environ.get("POOL_AVD", "gb18-1")
    subprocess.Popen(["bash", "-c", "nohup emulator -avd %s -read-only -port %s -no-window -no-audio -no-boot-anim "
                      "-gpu swiftshader_indirect -no-snapshot-save -snapshot %s > /tmp/soak-emu-%s.log 2>&1 &" % (avd, port, SNAPSHOT, port)])
    t = time.time()
    while time.time() - t < 300:
        time.sleep(5)
        try:
            if d.sh("getprop sys.boot_completed", check=False, timeout=10).strip() == "1":
                time.sleep(5)
                return True
        except subprocess.TimeoutExpired:
            pass
    return False


class Watcher(threading.Thread):
    def __init__(self, d, out, every):
        super().__init__(daemon=True)
        self.d, self.out, self.every = d, out, every
        self.halt = threading.Event()
        self.path = os.path.join(out, "samples.tsv")
        if not os.path.exists(self.path):
            open(self.path, "w").write("time\tpid\tpss_kb\tjava_kb\tnative_kb\tviews\tviewroots\tactivities\t"
                                       "local_binders\tproxy_binders\tthreads\twakelocks\n")

    def sample(self):
        d = self.d
        pid = d.sh("pidof %s" % PKG, check=False, timeout=30).strip().split(" ")[0] or "-"
        mem = d.sh("dumpsys meminfo %s" % PKG, check=False, timeout=60)

        def num(pat):
            m = re.search(pat, mem)
            return m.group(1) if m else "-"
        pss = num(r"TOTAL PSS:\s+(\d+)") if "TOTAL PSS" in mem else num(r"TOTAL\s+(\d+)")
        java = num(r"Java Heap:\s+(\d+)")
        native = num(r"Native Heap:\s+(\d+)")
        views = num(r"\bViews:\s+(\d+)")
        roots = num(r"ViewRootImpl:\s+(\d+)")
        acts = num(r"Activities:\s+(\d+)")
        lb = num(r"Local Binders:\s+(\d+)")
        pb = num(r"Proxy Binders:\s+(\d+)")
        thr = "-"
        if pid != "-":
            st = d.sh("cat /proc/%s/status" % pid, check=False, timeout=30)
            m = re.search(r"Threads:\s+(\d+)", st)
            thr = m.group(1) if m else "-"
        uid = getattr(self, "uid", None)
        if uid is None:
            m = re.search(r"(?:userId|appId)=(\d+)", d.sh("dumpsys package %s" % PKG, check=False, timeout=60))
            self.uid = uid = m.group(1) if m else "?"
        power = d.sh("dumpsys power", check=False, timeout=60)
        block = power.split("Wake Locks:", 1)[1].split("\n\n", 1)[0] if "Wake Locks:" in power else ""
        wl = sum(1 for l in block.splitlines() if ("uid=%s " % uid) in l or PKG in l)
        with open(self.path, "a") as f:
            f.write("\t".join(str(x) for x in [int(time.time()), pid, pss, java, native, views, roots, acts, lb, pb, thr, wl]) + "\n")

    def run(self):
        while not self.halt.is_set():
            try:
                self.sample()
            except Exception as e:  # noqa: BLE001  (a sample lost is not a reason to stop the soak)
                with open(os.path.join(self.out, "events.txt"), "a") as f:
                    f.write("%s sample failed: %s\n" % (time.strftime("%H:%M:%S"), e))
            self.halt.wait(self.every)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--hours", type=float, default=2.0)
    ap.add_argument("--out", required=True)
    ap.add_argument("--fields", default="")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--steps-per-field", type=int, default=60)
    ap.add_argument("--sample", type=int, default=60)
    ap.add_argument("--no-prepare", action="store_true")
    ap.add_argument("--restart-lost", action="store_true",
                    help="if the emulator dies, restart this (leased) pool instance read-only from its snapshot and go on")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = R.Dev(a.serial)
    if not a.no_prepare:
        R.prepare(d, "pboard")
    d.adb("logcat", "-c", "-b", "crash", check=False)
    fields = [A.BY_ID[x] for x in a.fields.split(",") if x] or [f for f in A.FIELDS if f["kind"] not in ("number", "phone")]
    rng = random.Random(a.seed)
    sents = sentences()
    w = Watcher(d, a.out, a.sample)
    w.start()
    end = time.time() + a.hours * 3600
    rounds = 0
    info = {"serial": a.serial, "start": time.strftime("%Y-%m-%d %H:%M:%S"), "hours": a.hours, "seed": a.seed,
            "fields": [f["id"] for f in fields],
            "pboard": [l.strip() for l in d.sh("dumpsys package dev.pboard", check=False).splitlines() if "versionName" in l or "lastUpdateTime" in l][:2]}
    json.dump(info, open(os.path.join(a.out, "info.json"), "w"), indent=1)
    def event(msg):
        with open(os.path.join(a.out, "events.txt"), "a") as fh:
            fh.write("%s %s\n" % (time.strftime("%H:%M:%S"), msg))

    while time.time() < end:
        for f in fields:
            if time.time() >= end:
                break
            if not device_alive(d):
                # the emulator itself died (host side): not pboard's doing, but the soak goes on from a fresh instance
                event("DEVICE LOST: %s is gone (emulator process ended)" % a.serial)
                if not a.restart_lost or not restart_read_only(d):
                    event("stopping: device not restarted")
                    end = 0
                    break
                R.prepare(d, "pboard")
                event("DEVICE RESTARTED from %s; pboard prepared again" % SNAPSHOT)
            sc = random_scenario(f, rng, a.steps_per_field, sents)
            path = os.path.join(a.out, "s.jsonl")
            open(path, "w").write(json.dumps(sc) + "\n")
            t = time.time()
            try:
                d.sh("mkdir -p %s" % R.DEV, timeout=60)
                d.adb("push", path, R.DEV + "/s.jsonl", timeout=120)
                res = d.adb("shell", "am", "instrument", "-w", "-e", "scenarios", R.DEV + "/s.jsonl", "-e", "minIdleMs", "200",
                            "-e", "idleTimeoutMs", "3000", "dev.pboard.bench/.AppCompat", check=False, timeout=1800)
                data = d.adb("exec-out", "run-as", "dev.pboard.bench", "cat", "files/appcompat/obs.jsonl", check=False, timeout=120)
            except (subprocess.TimeoutExpired, RuntimeError) as e:
                event("round %d %s: adb failed (%s)" % (rounds, f["id"], str(e)[:120]))
                continue
            with open(os.path.join(a.out, "obs.jsonl"), "a") as fh:
                for l in data.splitlines():
                    if l.strip():
                        r = json.loads(l)
                        r["round"] = rounds
                        fh.write(json.dumps(r) + "\n")
            ok = "INSTRUMENTATION_CODE: -1" in res
            with open(os.path.join(a.out, "events.txt"), "a") as fh:
                fh.write("%s round %d %s: %d steps in %.0f s%s\n" % (time.strftime("%H:%M:%S"), rounds, f["id"],
                         len(sc["steps"]), time.time() - t, "" if ok else " (runner: %s)" % res.strip()[-200:]))
            try:
                crash = d.adb("logcat", "-d", "-b", "crash", check=False, timeout=60)
            except subprocess.TimeoutExpired:
                crash = ""
            if crash.strip():
                with open(os.path.join(a.out, "crash_buffer.txt"), "a") as fh:
                    fh.write("===== round %d %s %s\n%s\n" % (rounds, f["id"], time.strftime("%H:%M:%S"), crash))
                d.adb("logcat", "-c", "-b", "crash", check=False)
        rounds += 1
    w.halt.set()
    w.sample()
    try:
        drop = d.sh("dumpsys dropbox --print data_app_crash data_app_anr data_app_native_crash system_app_anr", check=False, timeout=120)
    except subprocess.TimeoutExpired:
        drop = ""
    open(os.path.join(a.out, "dropbox.txt"), "w").write(drop)
    info["end"] = time.strftime("%Y-%m-%d %H:%M:%S")
    info["rounds"] = rounds
    json.dump(info, open(os.path.join(a.out, "info.json"), "w"), indent=1)
    subprocess.run([sys.executable, "-I", os.path.join(HERE, "soak_summary.py"), a.out])


if __name__ == "__main__":
    main()
