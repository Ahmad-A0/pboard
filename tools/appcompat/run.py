#!/usr/bin/env python3
"""App-compatibility matrix: plays the scenarios of tools/appcompat/scenarios.py in real apps' text fields through one
IME on one device and records every step (bench/.../AppCompat.kt).

  run.py --serial S --ime gboard|pboard --out DIR [--fields id,id] [--scenarios name,name] [--no-prepare]
  run.py --serial S --list                       the fields and their scenarios

Devices: farm pool instances of the golden gb18-1 (Gboard 18.6), leased with tools/farm/pool.sh; Gboard and pboard
run on separate leased instances of the same image (byte-identical state), each prepared here: settings, test apps
(webfield, F-Droid APKs from tools/appcompat/fetch_apks.py), pboard release + bench APKs, the IME selected.
APK locations: APPCOMPAT_APKS (F-Droid dir, default tools/appcompat/apks), APPCOMPAT_PBOARD (pboard release APK),
APPCOMPAT_BENCH, APPCOMPAT_WEBFIELD.
Output: DIR/obs.jsonl (one row per observed step), DIR/crashes.txt (crash/ANR buffers after each field),
DIR/info.json.
"""
import argparse
import json
import os
import shlex
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import apps as A  # noqa: E402
import scenarios as SC  # noqa: E402

IMES = {"gboard": ("com.google.android.inputmethod.latin", "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"),
        "pboard": ("dev.pboard", "dev.pboard/.ime.PboardIme")}
APKS = os.environ.get("APPCOMPAT_APKS", os.path.join(HERE, "apks"))
PBOARD = os.environ.get("APPCOMPAT_PBOARD", os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk"))
BENCH = os.environ.get("APPCOMPAT_BENCH", os.path.join(ROOT, "bench/build/outputs/apk/debug/bench-debug.apk"))
WEBFIELD = os.environ.get("APPCOMPAT_WEBFIELD", os.path.join(HERE, "webfield/build/webfield.apk"))
DEV = "/data/local/tmp/appcompat"


def log(*a):
    print(*a, file=sys.stderr, flush=True)


class Dev:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *a, check=True, timeout=None, input=None):
        r = subprocess.run(["adb", "-s", self.serial] + list(a), capture_output=True, timeout=timeout, input=input)
        if check and r.returncode != 0:
            raise RuntimeError("adb %s: %s" % (" ".join(a), (r.stderr or b"").decode(errors="replace")[:400]))
        return (r.stdout or b"").decode(errors="replace")

    def sh(self, cmd, **kw):
        return self.adb("shell", cmd, **kw)

    def installed_sha(self, pkg):
        return self.sh("cat %s/sha.%s 2>/dev/null" % (DEV, pkg), check=False).strip()

    def install(self, apk, pkg):
        import hashlib
        h = hashlib.sha256(open(apk, "rb").read()).hexdigest()[:16]
        if self.installed_sha(pkg) == h and pkg in self.sh("pm list packages %s" % pkg, check=False):
            return False
        log("== installing %s (%s)" % (pkg, os.path.basename(apk)))
        r = subprocess.run(["adb", "-s", self.serial, "install", "-r", "-g", apk], capture_output=True, timeout=600)
        if r.returncode != 0 and (b"UPDATE_INCOMPATIBLE" in (r.stderr + r.stdout) or b"VERSION_DOWNGRADE" in (r.stderr + r.stdout)):
            # a build signed elsewhere or a newer one is on the instance (read-only instances: nothing of this persists)
            self.adb("uninstall", pkg, check=False)
            self.adb("install", "-r", "-g", apk, timeout=600)
        elif r.returncode != 0:
            raise RuntimeError("install %s: %s" % (apk, (r.stderr or b"").decode(errors="replace")[:400]))
        self.sh("mkdir -p %s && echo %s > %s/sha.%s" % (DEV, h, DEV, pkg))
        return True


def prepare(d, ime):
    d.adb("wait-for-device")
    for s in ["settings put secure show_ime_with_hard_keyboard 1", "settings put secure stylus_handwriting_enabled 0",
              "settings put global stay_on_while_plugged_in 7", "settings put system screen_off_timeout 1800000",
              # password fields expose their text to accessibility (the runner reads fields that way)
              "settings put secure speak_password 1",
              # the clipboard overlay (bottom-left thumbnail) would cover keys after a paste
              "device_config put systemui clipboard_overlay_enabled false",
              "settings put system accelerometer_rotation 0", "settings put system user_rotation 0",
              "input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard",
              # Chrome without its first-run screens (command-line file is read for the debug app)
              "echo '_ --disable-fre --no-default-browser-check --no-first-run' > /data/local/tmp/chrome-command-line",
              "am set-debug-app --persistent com.android.chrome"]:
        d.sh(s, check=False)
    d.install(BENCH, "dev.pboard.bench")
    d.install(WEBFIELD, "dev.pboard.webfield")
    d.install(PBOARD, "dev.pboard")
    man = json.load(open(os.path.join(APKS, "manifest.json"))) if os.path.exists(os.path.join(APKS, "manifest.json")) else {}
    for pkg, m in sorted(man.items()):
        p = os.path.join(APKS, m["file"])
        if os.path.exists(p):
            d.install(p, pkg)
    if "com.termux" in man and d.sh("cat %s/termux.boot 2>/dev/null" % DEV, check=False).strip() != "1":
        # first start unpacks Termux's bootstrap (tens of seconds); do it once per device
        log("== first Termux start (bootstrap)")
        d.sh("am start -W -n com.termux/.app.TermuxActivity", check=False)
        time.sleep(45)
        d.sh("am force-stop com.termux; echo 1 > %s/termux.boot" % DEV, check=False)
    serve_page(d)
    pkg, comp = IMES[ime]
    if ime == "pboard":
        d.sh("pm clear dev.pboard", check=False)
    d.sh("ime enable %s" % comp)
    d.sh("ime set %s" % comp)
    time.sleep(2)


def serve_page(d):
    """The test page for Chrome: a local HTTP server on this host (the webfield assets, nothing else), reached from
    the device through adb reverse as http://localhost:<PAGE_PORT>/fields.html."""
    import socket
    s = socket.socket()
    busy = s.connect_ex(("127.0.0.1", A.PAGE_PORT)) == 0
    s.close()
    if not busy:
        subprocess.Popen([sys.executable, "-I", "-m", "http.server", str(A.PAGE_PORT), "--bind", "127.0.0.1",
                          "--directory", os.path.join(HERE, "webfield", "assets")],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        time.sleep(1)
    d.adb("reverse", "tcp:%d" % A.PAGE_PORT, "tcp:%d" % A.PAGE_PORT, check=False)


def collect_crashes(d, f, out):
    crash = d.adb("logcat", "-d", "-b", "crash", check=False)
    anr = d.sh("dumpsys activity anr 2>/dev/null | head -50; ls -la /data/anr 2>/dev/null", check=False)
    with open(os.path.join(out, "crashes.txt"), "a") as fh:
        fh.write("===== after %s (%s)\n%s\n--- anr\n%s\n" % (f, time.strftime("%H:%M:%S"), crash, anr))
    d.adb("logcat", "-c", "-b", "crash", check=False)


def run_field(d, ime, f, names, out, append):
    rows = SC.for_field(f, names)
    if not rows:
        return 0
    pkg = IMES[ime][0]
    for r in rows:
        r["ime"] = pkg
    path = os.path.join(out, "s.%s.jsonl" % f["id"])
    with open(path, "w") as fh:
        for r in rows:
            fh.write(json.dumps(r) + "\n")
    d.sh("mkdir -p %s" % DEV)
    d.adb("push", path, DEV + "/s.jsonl")
    t = time.time()
    cmd = ["shell", "am", "instrument", "-w", "-e", "scenarios", DEV + "/s.jsonl", "-e", "append", "0",
           "dev.pboard.bench/.AppCompat"]
    res = d.adb(*cmd, check=False, timeout=3600)
    summ = " ".join(l for l in res.splitlines() if "summary" in l or "CODE" in l or "error" in l.lower())
    log("   %s: %d scenarios in %.0f s; %s" % (f["id"], len(rows), time.time() - t, summ[:300]))
    data = d.adb("exec-out", "run-as", "dev.pboard.bench", "cat", "files/appcompat/obs.jsonl", check=False)
    with open(os.path.join(out, "obs.jsonl"), "a" if append else "w") as fh:
        fh.write(data if data.endswith("\n") or not data else data + "\n")
    collect_crashes(d, f["id"], out)
    return len(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--ime", choices=list(IMES))
    ap.add_argument("--out")
    ap.add_argument("--fields", default="")
    ap.add_argument("--scenarios", default="")
    ap.add_argument("--no-prepare", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--append", action="store_true", help="add to DIR/obs.jsonl instead of replacing it")
    a = ap.parse_args()
    fields = [A.BY_ID[x] for x in a.fields.split(",") if x] or A.FIELDS
    names = [x for x in a.scenarios.split(",") if x] or None
    if a.list:
        for f in fields:
            print(f["id"], [s["scenario"] for s in SC.for_field(f, names)])
        return
    os.makedirs(a.out, exist_ok=True)
    d = Dev(a.serial)
    if not a.no_prepare:
        prepare(d, a.ime)
    else:
        d.sh("ime set %s" % IMES[a.ime][1])
    info = {"ime": a.ime, "serial": a.serial, "start": time.strftime("%Y-%m-%d %H:%M:%S"),
            "fingerprint": d.sh("getprop ro.build.fingerprint", check=False).strip(),
            "versions": {p: [l.strip() for l in d.sh("dumpsys package %s" % p, check=False).splitlines() if "versionName" in l][:1]
                         for p in [IMES[a.ime][0], "com.android.chrome", "com.google.android.webview"] + list({f["app"] for f in fields})}}
    d.adb("logcat", "-c", "-b", "crash", check=False)
    n = 0
    for i, f in enumerate(fields):
        n += run_field(d, a.ime, f, names, a.out, append=i > 0 or a.append)
    info["end"] = time.strftime("%Y-%m-%d %H:%M:%S")
    info["scenarios"] = n
    json.dump(info, open(os.path.join(a.out, "info.json"), "w"), indent=1)
    log("== %s: %d scenarios -> %s" % (a.ime, n, a.out))


if __name__ == "__main__":
    main()
