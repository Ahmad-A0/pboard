#!/usr/bin/env python3
"""Device side of the conformance harness: IME state, APKs, running scenarios, pulling observations.

  device.py run --serial S --ime gboard|pboard --scenarios s.jsonl --out DIR [--snapshot NAME] [--keep-state]
  device.py verify --serial S [--snapshot NAME]      check a Gboard snapshot (synced look, autocorrect, glide)

Targets
  emulator (emulator-*): Gboard 15.1. Before every Gboard batch the emulator snapshot (default conform_base,
          made by make_snapshot.sh) is restored; never `pm clear` Gboard (that also drops its synced remote
          configuration). pboard: `pm clear dev.pboard`, then enable and select it again. Both IMEs share the
          gboard_pixel8 key geometry (measured identical).
  phone   (PBOARD_PHONE_SERIAL, Gboard 18.6): shared hardware. Every injection and screenshot runs under
          tools/phone/guard.sh (exclusive lock + "a pboard test screen is in front" check); scenarios run
          in chunks, each chunk one guarded invocation (the lock is released in between), the runner stops
          at once if anything but the playground is in front (strict mode, no recovery key presses), strip
          taps are only made while suggestions show, and scenarios touching UI outside the keys are left
          out. IME state is NOT reset (no clearing of either app on the phone). Geometry per IME is measured
          (tools/conformance/geometry/*_phone440.json). pboard is left selected afterwards.
Scenarios are projected onto each IME's geometry (project.py) before running.
"""
import argparse
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import ROOT, HERE, IMES, GEOMETRY, log, sha, read_jsonl, write_jsonl, load_json  # noqa: E402
import project as P  # noqa: E402

APP_RELEASE = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
# pboard under test: the release build (debug-signed, so :bench can instrument it), as users run it;
# CONFORM_APP overrides (e.g. an older build for the positive control).
APP = os.environ.get("CONFORM_APP") or (APP_RELEASE if os.path.exists(APP_RELEASE) else
                                        os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
BENCH = os.path.join(ROOT, "bench", "build", "outputs", "apk", "debug", "bench-debug.apk")
DEV_DIR = "/data/local/tmp/conform"
sys.path.insert(0, os.path.join(ROOT, "tools", "dev"))
import local_env  # noqa: E402

# adb serial of the dev phone (machine-local: environment or local.env); None when no phone is configured
PHONE = local_env.get("PBOARD_PHONE_SERIAL", required=False)
GUARD = os.path.join(ROOT, "tools", "phone", "guard.sh")


def is_phone(serial):
    return serial == PHONE or not serial.startswith("emulator-")


def geometry_for(serial, ime):
    # explicit geometry per IME (e.g. Gboard 18.6 emulators on the emulator farm: CONFORM_GEO_GBOARD / CONFORM_GEO_PBOARD)
    env = os.environ.get("CONFORM_GEO_" + ime.upper())
    if env:
        return env
    if not is_phone(serial):
        return GEOMETRY
    name = {"gboard": "gboard186_phone440.json", "pboard": "pboard_phone440.json"}[ime]
    return os.path.join(HERE, "geometry", name)


def emulator_host():
    """The host the emulators run on: '' for this machine, else the host of ADB_SERVER_SOCKET=tcp:<host>:<port>."""
    sock = os.environ.get("ADB_SERVER_SOCKET", "")
    host = sock.split(":")[1] if sock.startswith("tcp:") else ""
    return "" if host in ("", "localhost", "127.0.0.1") else host


_CORES = {}


def host_load():
    """(1-minute load, cores) of the emulator host (over ssh when the emulators are remote)."""
    host = emulator_host()
    if not host:
        return os.getloadavg()[0], os.cpu_count() or 8
    r = subprocess.run(["ssh", "-o", "ConnectTimeout=10", host, "cat /proc/loadavg; nproc"], capture_output=True, text=True)
    try:
        lines = r.stdout.split("\n")
        _CORES[host] = int(lines[1])
        return float(lines[0].split()[0]), _CORES[host]
    except (IndexError, ValueError):
        return float("nan"), _CORES.get(host, 8)


class LoadSampler(threading.Thread):
    """Samples the emulator host's 1-minute load average (every 2 s locally, 10 s over ssh) into <out>/load.jsonl
    while a batch runs (a loaded host changes how the emulated keyboards behave, so every observation carries the
    load it was made at)."""

    def __init__(self, out_dir):
        super().__init__(daemon=True)
        self.path = os.path.join(out_dir, "load.jsonl")
        self.halt = threading.Event()

    def run(self):
        with open(self.path, "a") as f:
            while not self.halt.is_set():
                load, cores = host_load()
                if load == load:  # not NaN
                    f.write(json.dumps({"t": round(time.time(), 2), "load1": load, "cores": cores,
                                        "host": emulator_host() or "local"}) + "\n")
                    f.flush()
                self.halt.wait(10.0 if emulator_host() else 2.0)


def wait_for_quiet(max_load=None, max_wait_s=1200):
    """Waits (up to max_wait_s) until the emulator host's 1-minute load is below max_load (default 2 x cores
    locally; 1 x cores on a remote farm host, whose emulators already fill it)."""
    load, cores = host_load()
    max_load = max_load or float(os.environ.get("CONFORM_MAX_LOAD", (1 if emulator_host() else 2) * cores))
    t = time.time()
    while not load <= max_load and time.time() - t < max_wait_s:
        time.sleep(15)
        load, cores = host_load()
    if load > max_load:
        log("!! host still loaded (%.0f > %.0f) after %d s; running anyway (results flagged)" % (load, max_load, max_wait_s))
    return load


def runner_args(geo_path, phone):
    g = load_json(geo_path)
    st = g["suggestionStrip"]
    dens = g["screen"].get("density", 420)
    top = int(st["top"] - 220 * dens / 420)
    tx = st.get("textX", [105, 975])
    a = {"regionTop": top, "stripTop": int(round(st["top"])), "stripBottom": int(round(st["bottom"])),
         "stripTextX": "%d,%d" % (tx[0], tx[1]), "density": dens,
         # idle sampling every 80 ms (not 40): fewer screenshots, less load on a shared host
         "frameGapMs": 80}
    # geometry check against pboard's key nodes (Gboard has none: its typed-key calibration covers it)
    keys = {k["name"]: k for k in g["keys"]}
    a["keyCheck"] = ";".join("%s:%.0f,%.0f" % (n if n != "space" else "space", keys[n]["cx"], keys[n]["cy"])
                             for n in ("q", "p", "a", "l", "z", "m", "space") if n in keys)
    if phone:
        a["strict"] = 1
    return a


class Device:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, check=True, timeout=None, input=None):
        r = subprocess.run(["adb", "-s", self.serial] + list(args), capture_output=True, timeout=timeout, input=input)
        if check and r.returncode != 0:
            raise RuntimeError("adb %s failed: %s" % (" ".join(args), (r.stderr or b"").decode(errors="replace")))
        return (r.stdout or b"").decode(errors="replace")

    def sh(self, cmd, **kw):
        return self.adb("shell", cmd, **kw)

    def restart_read_only(self, snap):
        sock = os.environ.get("ADB_SERVER_SOCKET", "")
        host = sock.split(":")[1] if sock.startswith("tcp:") else ""
        avd = os.environ.get("CONFORM_AVD", "gb18-1")
        port = self.serial.split("-")[-1]
        script = ("cd projects/pboard; nix develop -c adb -s {s} emu kill >/dev/null 2>&1; "
                  "for i in $(seq 1 40); do pgrep -f 'port {p} ' >/dev/null || break; sleep 1; done; "
                  "(nohup nix develop -c emulator -avd {a} -read-only -port {p} -no-window -no-audio -no-boot-anim "
                  "-gpu swiftshader_indirect -no-snapshot-save -snapshot {n} > /tmp/conform-emu-{p}.log 2>&1 &)").format(
            s=self.serial, p=port, a=avd, n=snap)
        subprocess.run(["ssh", host, script] if host else ["bash", "-c", script], capture_output=True)
        time.sleep(10)
        for _ in range(120):
            if self.sh("getprop sys.boot_completed", check=False).strip() == "1":
                break
            time.sleep(3)
        else:
            raise SystemExit("!! %s did not come back after a restart" % self.serial)
        time.sleep(3)

    def emu(self, *args):
        """Emulator console command. `adb emu` connects to the console from the client side, so for emulators on
        another host driven through ADB_SERVER_SOCKET=tcp:<host>:5037 (the emulator farm) it runs there over ssh."""
        sock = os.environ.get("ADB_SERVER_SOCKET", "")
        host = sock.split(":")[1] if sock.startswith("tcp:") else ""
        if host and host not in ("localhost", "127.0.0.1"):
            cmd = "cd projects/pboard && nix develop -c adb -s %s emu %s 2>/dev/null" % (self.serial, " ".join(shlex.quote(a) for a in args))
            r = subprocess.run(["ssh", host, cmd], capture_output=True, text=True)
            return r.stdout
        return self.adb("emu", *args, check=False)

    def boot_wait(self):
        self.adb("wait-for-device")
        for _ in range(300):
            if self.sh("getprop sys.boot_completed", check=False).strip() == "1":
                return
            time.sleep(1)
        raise RuntimeError("device did not boot")

    def restore(self, snap):
        log("== restoring snapshot %s" % snap)
        t = time.time()
        out = self.emu("avd", "snapshot", "load", snap)
        if "read-only" in out:
            # a read-only instance of a golden AVD (the emulator farm) cannot load snapshots at runtime, only at boot: restart it
            self.restart_read_only(snap)
            log("   restarted read-only from %s in %.0f s" % (snap, time.time() - t))
            return
        if "OK" not in out:
            raise SystemExit("!! snapshot %s not loaded: %s (run tools/conformance/make_snapshot.sh)" % (snap, out.strip()))
        time.sleep(2)
        self.boot_wait()
        log("   restored in %.0f s" % (time.time() - t))

    def ensure_apks(self, app=APP, bench=BENCH):
        want = "%s %s" % (sha(app), sha(bench))
        have = self.sh("cat %s/apk.sha 2>/dev/null" % DEV_DIR, check=False).strip()
        if have != want:
            log("== installing app + bench APKs")
            self.adb("install", "-r", "-g", app)
            self.adb("install", "-r", bench)
            self.sh("mkdir -p %s" % DEV_DIR)
            self.adb("shell", "cat > %s/apk.sha" % DEV_DIR, input=want.encode())
            return True
        return False

    def settings(self):
        self.sh("settings put secure show_ime_with_hard_keyboard 1")
        self.sh("settings put secure stylus_handwriting_enabled 0")
        self.sh("settings put global hide_error_dialogs 1")
        # The system spell checker (Gboard's service, whichever keyboard is active) red-underlines words in the field,
        # and a tap on one opens the TextView's suggestion popup with either keyboard. It answers late (> 10 s) while
        # Gboard's process is busy after an IME restart, so whether a tap into text met the popup depended on timing.
        # It is app-side, not keyboard behaviour: off on the test emulators (the phone keeps the user's setting; the
        # runner also waits for it to settle before tapping into text). The playground (pboard's process) restarts
        # in prepare, so its field picks this up.
        self.sh("settings put secure spell_checker_enabled 0")
        self.sh("input keyevent KEYCODE_WAKEUP")
        self.sh("wm dismiss-keyguard", check=False)

    def screenshot(self, path):
        with open(path, "wb") as f:
            f.write(subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)

    def density(self):
        """Display density override (CONFORM_DENSITY), e.g. 440 to put a 1080x2400 emulator in the phone's size set."""
        d = os.environ.get("CONFORM_DENSITY")
        cur = self.sh("wm density", check=False)
        if d and cur.split()[-1] != d:
            self.sh("wm density %s" % d)
            time.sleep(3)
        elif not d and "Override" in cur:  # a shared (pool) instance left with another job's override
            self.sh("wm density reset")
            time.sleep(3)

    def keyboard_up(self):
        self.sh("am start -n dev.pboard/.PlaygroundActivity")
        time.sleep(2.5)
        for _ in range(5):
            self.sh("am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd reset --es variant default")
            time.sleep(2)
            if "mInputShown=true" in self.sh("dumpsys input_method"):
                time.sleep(1)
                return True
        return False

    def state_check(self, out_dir, geo_path=GEOMETRY):
        """Gboard's synced look (secondary-container ?123 and Enter, no tonal toolbar button), at the probe points
        of the geometry, or at the same places relative to its keys when it has none (other size sets)."""
        png = os.path.join(out_dir, "state.png")
        self.screenshot(png)
        g = load_json(geo_path)
        if not g.get("stateCheck"):
            k = {x["name"]: x for x in g["keys"]}
            sy, en = k["symbols"]["visual"], k["enter"]["visual"]
            st = g["suggestionStrip"]
            g["stateCheck"] = [
                {"name": "symbols key (?123) colour", "point": [sy[0] + 28, sy[1] + 32], "rgb": [220, 226, 249], "tol": 12},
                {"name": "enter key colour", "point": [en[2] - 34, en[1] + 32], "rgb": [220, 226, 249], "tol": 12},
                {"name": "toolbar grid button not tonal", "point": [30, st["top"] + 0.35 * (st["bottom"] - st["top"])],
                 "rgb": [238, 237, 244], "tol": 12}]
            geo_path = os.path.join(out_dir, "statecheck_geometry.json")
            json.dump(g, open(geo_path, "w"))
        r = subprocess.run([sys.executable, "-I", os.path.join(ROOT, "tools", "bench", "state_check.py"), geo_path, png],
                           capture_output=True, text=True)
        return r.returncode == 0, r.stdout.strip()

    def versions(self, info):
        for pkg in ("com.google.android.inputmethod.latin", "dev.pboard"):
            v = [l.strip() for l in self.sh("dumpsys package %s" % pkg, check=False).splitlines() if "versionName" in l or "lastUpdateTime" in l]
            info[pkg] = v[:2]
        info["fingerprint"] = self.sh("getprop ro.build.fingerprint", check=False).strip()

    def prepare(self, ime, out_dir, snapshot="conform_base", keep_state=False):
        """Emulator only: clean IME state, APKs, IME selected, keyboard up."""
        os.makedirs(out_dir, exist_ok=True)
        info = {"ime": ime, "snapshot": None, "target": "emulator"}
        if ime == "gboard":
            if not keep_state:
                self.restore(snapshot)
                info["snapshot"] = snapshot
            self.density()
            if self.ensure_apks() and not keep_state and snapshot == "conform_base":  # never write a golden snapshot
                # keep the snapshot current with the APKs (nothing typed yet, Gboard untouched); a read-only
                # emulator instance (emulator farm clones of a golden AVD) cannot save: then APKs are reinstalled per batch
                log("== re-saving snapshot %s with the new APKs" % snapshot)
                out = self.emu("avd", "snapshot", "save", snapshot)
                if "OK" not in out:
                    log("   (snapshot not saved: %s)" % out.strip()[:80])
                    self.sh("rm -f %s/apk.sha" % DEV_DIR, check=False)
            self.settings()
            self.sh("am force-stop dev.pboard")  # the playground restarts with the settings above
            self.sh("ime enable %s" % IMES["gboard"])
            self.sh("ime set %s" % IMES["gboard"])
            if not self.keyboard_up():
                raise SystemExit("!! Gboard keyboard did not come up")
            if os.environ.get("CONFORM_STATE_CHECK", "1") != "0":
                ok, msg = self.state_check(out_dir, geometry_for(self.serial, "gboard"))
                info["stateCheck"] = msg
                if not ok:
                    raise SystemExit("!! Gboard is not in its synced state:\n" + msg)
            else:
                info["stateCheck"] = "skipped (snapshot verified elsewhere; typed-key calibration checks the keyboard)"
            lm = [l for l in self.adb("logcat", "-d", check=False).splitlines() if "loadLanguageModel() : 1, version [" in l]
            info["gboardMainLm"] = lm[-1].split("version [")[1].split("]")[0] if lm else "not logged"
        else:
            if emulator_host() and not keep_state:
                # a shared farm instance: start pboard's batch from the golden snapshot too (no other job's state)
                self.restore(snapshot)
                info["snapshot"] = snapshot
            self.density()
            self.ensure_apks()
            self.settings()
            if not keep_state:
                self.sh("pm clear dev.pboard")
                info["pmClear"] = True
            self.sh("ime enable %s" % IMES["pboard"])
            self.sh("ime set %s" % IMES["pboard"])
            if not self.keyboard_up():
                raise SystemExit("!! pboard keyboard did not come up")
        self.versions(info)
        info["apk"] = sha(APP)[:12]
        info["spellCheckerEnabled"] = self.sh("settings get secure spell_checker_enabled", check=False).strip()
        info["density"] = self.sh("wm density", check=False).strip().splitlines()[-1] if self.sh("wm density", check=False).strip() else "?"
        vcs = ["gi" + "t", "-C", ROOT]
        head = subprocess.run(vcs + ["rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
        same = subprocess.run(vcs + ["diff", "--quiet", "main", "--", "app", "engine"]).returncode == 0
        info["pboardBuild"] = {"commit": head, "appSameAsMain": same, "apkSha": sha(APP)[:12]}
        return info

    def instrument(self, rows, out_dir, args, append):
        tmp = os.path.join(out_dir, "scenarios.device.jsonl")
        write_jsonl(tmp, [{k: v for k, v in r.items() if k not in ("item",)} for r in rows])
        self.sh("mkdir -p %s" % DEV_DIR)
        self.adb("push", tmp, DEV_DIR + "/s.jsonl")
        cmd = ["shell", "am", "instrument", "-w"]
        for k, v in args.items():
            cmd += ["-e", k, shlex.quote(str(v))]  # adb shell joins words: quote ; and the like
        cmd += ["-e", "scenarios", DEV_DIR + "/s.jsonl", "dev.pboard.bench/.Conform"]
        # anchor device uptime (the runner's clock) to host time, so steps can be matched with the host load
        reply = self.sh("am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd get", check=False)
        m_ = re.search(r'"uptime":(\d+)', reply)
        if m_:
            with open(os.path.join(out_dir, "anchors.jsonl"), "a") as f:
                f.write(json.dumps({"host": round(time.time(), 2), "devUptimeMs": int(m_.group(1))}) + "\n")
        sampler = LoadSampler(out_dir)
        sampler.start()
        t = time.time()
        try:
            out = self.adb(*cmd, check=False)
        finally:
            sampler.halt.set()
        summ = " ".join(l for l in out.splitlines() if "summary" in l or "CODE" in l)
        log("   instrumentation: %.0f s; %s" % (time.time() - t, summ[:400]))
        got = self.pull(out_dir, append=append)
        return out, got

    def run(self, rows, out_dir, args):
        """Emulator: runs Conform over all scenarios; resumes after an instrumentation failure."""
        start = 0
        self.adb("logcat", "-c", check=False)
        for attempt in range(3):
            out, got = self.instrument(rows[start:], out_dir, args, append=attempt > 0)
            if "INSTRUMENTATION_CODE: -1" in out:
                break
            log("!! instrumentation did not finish (attempt %d): %s" % (attempt + 1, out[-500:]))
            ids = [r["id"] for r in rows]
            last = got[-1]["sc"] if got else None
            start = ids.index(last) if last in ids else start
            self.trim(out_dir, last)
            self.sh("am start -n dev.pboard/.PlaygroundActivity", check=False)
            time.sleep(3)
        open(os.path.join(out_dir, "crash.txt"), "w").write(self.adb("logcat", "-d", "-b", "crash", check=False))

    def trim(self, out_dir, sc_id):
        p = os.path.join(out_dir, "obs.jsonl")
        if not os.path.exists(p) or sc_id is None:
            return
        write_jsonl(p, [r for r in read_jsonl(p) if r["sc"] != sc_id])

    def pull(self, out_dir, append=False):
        data = subprocess.run(["adb", "-s", self.serial, "exec-out", "run-as", "dev.pboard.bench", "tar", "cf", "-",
                               "-C", "files", "conform"], capture_output=True, check=True).stdout
        img_dir = os.path.join(out_dir, "img")
        os.makedirs(img_dir, exist_ok=True)
        new_rows = []
        with tarfile.open(fileobj=io.BytesIO(data)) as tf:
            for m in tf.getmembers():
                if not m.isfile():
                    continue
                name = m.name.split("/", 1)[1] if "/" in m.name else m.name
                f = tf.extractfile(m).read()
                if name.startswith("img/"):
                    dst = os.path.join(img_dir, os.path.basename(name))
                    if not os.path.exists(dst):
                        open(dst, "wb").write(f)
                elif name == "obs.jsonl":
                    new_rows = [json.loads(l) for l in f.decode().splitlines() if l.strip()]
        p = os.path.join(out_dir, "obs.jsonl")
        old = read_jsonl(p) if (append and os.path.exists(p)) else []
        write_jsonl(p, old + new_rows)
        return new_rows


# ------------------------------------------------------------------ phone

def phone_chunk(ime, chunk_path, out_dir, args_json, append):
    """Runs inside tools/phone/guard.sh (lock held, playground checked in front)."""
    d = Device(os.environ.get("PHONE", PHONE))
    args = json.loads(args_json)
    # pboard under test: a release built from a main checkout (CONFORM_PHONE_APP, default this tree's
    # release when its app/engine/bench equal main). Other jobs may install branch builds inside their own lock
    # (and put a main release back), so every chunk checks the installed APK's hash against the batch's build and
    # reinstalls it when it differs, recording the event: builds are never mixed within a batch.
    apk = os.environ.get("CONFORM_PHONE_APP") or APP_RELEASE
    vcs = ["gi" + "t", "-C", ROOT]
    if not os.environ.get("CONFORM_PHONE_APP"):
        if subprocess.run(vcs + ["diff", "--quiet", "main", "--", "app", "engine"]).returncode != 0:
            raise SystemExit("!! this tree's app differs from main: set CONFORM_PHONE_APP to a main release build")
    if not os.path.exists(apk):
        raise SystemExit("!! no pboard release APK (%s)" % apk)
    want = sha(apk)

    def device_sha():
        path = [x[len("package:"):] for x in d.sh("pm path dev.pboard", check=False).split()
                if x.startswith("package:") and x.endswith("base.apk")]
        return d.sh("sha256sum %s" % path[0], check=False).split()[0] if path else "?"

    bp = os.path.join(out_dir, "pboard_build.json")
    ident = load_json(bp) if (append and os.path.exists(bp)) else {
        "apkSha": want[:12], "commit": os.environ.get("CONFORM_PHONE_COMMIT") or
        subprocess.run(vcs + ["rev-parse", "--short", "main"], capture_output=True, text=True).stdout.strip(),
        "events": []}
    have = device_sha()
    if have != want:
        log("== phone pboard is %s, not this batch's build %s: installing ours" % (have[:12], want[:12]))
        d.adb("install", "-r", apk)
        ident["events"].append("%s: chunk at %s found pboard %s, reinstalled %s" % (
            time.strftime("%H:%M:%S"), os.path.basename(out_dir), have[:12], want[:12]))
        d.sh("am start -W -n dev.pboard/.PlaygroundActivity")
        time.sleep(2)
        if device_sha() != want:
            raise SystemExit("!! could not install the batch's pboard build")
    for _ in range(4):  # the playground may still be starting
        reply = d.sh("am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd hist --el since 999999999999", check=False)
        if "data=" in reply:
            break
        time.sleep(1)
    if '"hist"' not in reply:
        raise SystemExit("!! the pboard build's playground has no change history (build from main >= ffe85d2)")
    json.dump(ident, open(bp, "w"))
    bench_sha = sha(BENCH)
    if d.sh("cat %s/bench.sha 2>/dev/null" % DEV_DIR, check=False).strip() != bench_sha:
        log("== installing bench on the phone")
        d.adb("install", "-r", BENCH)
        d.sh("mkdir -p %s" % DEV_DIR)
        d.adb("shell", "cat > %s/bench.sha" % DEV_DIR, input=bench_sha.encode())
    d.sh("ime set %s" % IMES[ime])
    time.sleep(1.5)
    # switching IMEs can take the playground (same process as pboard) down: bring it back
    d.sh("am start -W -n dev.pboard/.PlaygroundActivity")
    time.sleep(2)
    if subprocess.run([GUARD, "--check"]).returncode != 0:
        raise SystemExit(3)
    rows = read_jsonl(chunk_path)
    out, got = d.instrument(rows, out_dir, args, append)
    stopped = None
    for l in out.splitlines():
        if '"stopped":"' in l:
            stopped = l.split('"stopped":"', 1)[1].split('"', 1)[0]
    if stopped:
        log("!! runner stopped: %s" % stopped)
        raise SystemExit(5)
    if "INSTRUMENTATION_CODE: -1" not in out:
        log("!! instrumentation failed: %s" % out[-400:])
        raise SystemExit(6)


def run_phone(ime, rows, out_dir, args, chunk=6):
    info = {"ime": ime, "target": "phone", "serial": PHONE, "stateReset": False}
    for i in range(0, len(rows), chunk):
        part = os.path.join(out_dir, "chunk.jsonl")
        write_jsonl(part, rows[i:i + chunk])
        log("== phone %s: scenarios %d-%d of %d" % (ime, i, min(len(rows), i + chunk) - 1, len(rows)))
        r = subprocess.run([GUARD, "--open", "--wait", "3600", "--", sys.executable, "-I", os.path.abspath(__file__),
                            "phone-chunk", "--ime", ime, "--chunk", part, "--out", out_dir, "--args", json.dumps(args),
                            "--append", "1" if i > 0 else "0"])
        if r.returncode != 0:
            info["stoppedAt"] = i
            log("!! phone batch stopped (exit %d)" % r.returncode)
            break
        # between chunks (lock released): the guard's own check must pass, else stop
        if subprocess.run([GUARD, "--check"]).returncode != 0:
            info["stoppedAt"] = i + chunk
            log("!! guard check failed between chunks; stopping")
            break
    if os.path.exists(os.path.join(out_dir, "pboard_build.json")):
        info["pboardBuild"] = load_json(os.path.join(out_dir, "pboard_build.json"))
    # leave pboard as the phone's IME
    subprocess.run([GUARD, "--wait", "3600", "--", "bash", "-c", 'adb -s "$PHONE" shell ime set %s' % IMES["pboard"]])
    d = Device(PHONE)
    d.versions(info)
    return info


# ------------------------------------------------------------------ batches

def run_batch(serial, ime, scenarios, out_dir, snapshot=None, keep_state=False, extra=None):
    snapshot = snapshot or os.environ.get("SNAPSHOT", "conform_base")  # the emulator farm: gboard_bench_base (the golden's)
    phone = is_phone(serial)
    geo = geometry_for(serial, ime)
    rows = P.project(read_jsonl(scenarios), geo, phone_safe=phone or os.environ.get("CONFORM_PHONE_SAFE") == "1")
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)
    write_jsonl(os.path.join(out_dir, "scenarios.jsonl"), rows)
    args = runner_args(geo, phone)
    args.update(extra or {})
    t = time.time()
    if phone:
        info = run_phone(ime, rows, out_dir, args)
    else:
        d = Device(serial)
        info = d.prepare(ime, out_dir, snapshot, keep_state)
        log("== %s prepared (%.0f s); running %s" % (ime, time.time() - t, os.path.basename(scenarios)))
        d.run(rows, out_dir, args)
    info["geometry"] = geo
    info["regionTop"] = args["regionTop"]
    info["serial"] = serial
    info["elapsedS"] = round(time.time() - t)
    json.dump(info, open(os.path.join(out_dir, "info.json"), "w"), indent=1)
    return info


def verify(serial, snapshot):
    """Restores the snapshot, checks the synced look and that autocorrect and glide work, restores again."""
    import scenarios as S
    import tempfile
    tmp = tempfile.mkdtemp()
    s1 = S.S("verify/becuase", "nocap", src="verify").word("becuase ")
    s2 = S.S("verify/glide", "nocap", src="verify").glide("hello")
    p = os.path.join(tmp, "verify.jsonl")
    write_jsonl(p, [s1.to_json(), s2.to_json()])
    out = os.path.join(tmp, "out")
    info = run_batch(serial, "gboard", p, out, snapshot)
    final = {r["sc"]: r.get("text") for r in read_jsonl(os.path.join(out, "obs.jsonl"))}
    want_lm = os.environ.get("CONFORM_EXPECT_LM")
    ok = final.get("verify/becuase") == "because " and (final.get("verify/glide") or "").strip().lower() == "hello" \
        and "MISMATCH" not in info.get("stateCheck", "") and (not want_lm or info.get("gboardMainLm") == want_lm)
    print("== verify %s on %s (density %s): state %s; becuase -> %r, glide hello -> %r, LM %s%s; Gboard %s" % (
        snapshot, serial, info.get("density"), "ok" if "MISMATCH" not in info.get("stateCheck", "") else "MISMATCH",
        final.get("verify/becuase"), final.get("verify/glide"), info.get("gboardMainLm"),
        " (expected %s)" % want_lm if want_lm else "", (info.get("com.google.android.inputmethod.latin") or ["?"])[0]))
    json.dump(dict(info, verify={"becuase": final.get("verify/becuase"), "glide": final.get("verify/glide"), "ok": ok}),
              open(os.path.join(os.environ.get("CONFORM_VERIFY_OUT", tmp), "verify_%s.json" % serial), "w"), indent=1)
    Device(serial).restore(snapshot)
    if not ok:
        print("!! snapshot %s failed verification" % snapshot)
        sys.exit(1)
    print("== snapshot %s verified" % snapshot)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["run", "verify", "phone-chunk"])
    ap.add_argument("--serial")
    ap.add_argument("--ime", choices=["gboard", "pboard"])
    ap.add_argument("--scenarios")
    ap.add_argument("--out")
    ap.add_argument("--snapshot", default="conform_base")
    ap.add_argument("--keep-state", action="store_true")
    ap.add_argument("--chunk")
    ap.add_argument("--args")
    ap.add_argument("--append", default="0")
    ap.add_argument("-e", nargs=2, action="append", default=[], metavar=("KEY", "VALUE"), help="extra runner args")
    a = ap.parse_args()
    if a.cmd == "verify":
        verify(a.serial, a.snapshot)
    elif a.cmd == "phone-chunk":
        phone_chunk(a.ime, a.chunk, a.out, a.args, a.append == "1")
    else:
        run_batch(a.serial, a.ime, a.scenarios, a.out, a.snapshot, a.keep_state, dict(a.e))


if __name__ == "__main__":
    main()
