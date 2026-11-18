#!/usr/bin/env python3
"""Downloads the FOSS apps of the app-compatibility matrix from F-Droid and verifies every link of the chain.

  fetch_apks.py [--out tools/appcompat/apks] [--abi x86_64] [PACKAGE ...]

Trust chain (nothing is installed unless all of it holds):
  1. https://f-droid.org/repo/entry.jar must carry a valid JAR signature (`jarsigner -verify` says "jar verified")
     by the pinned F-Droid repository key (certificate SHA-256 FDROID_KEY, the fingerprint F-Droid publishes).
  2. index-v2.json must have the SHA-256 that the signed entry.json names.
  3. Each APK must have the SHA-256 that the index names for that version, and its v2/v3 signer certificate
     (apksigner) must be one the index lists for it (F-Droid's own key or, for reproducible builds, the developer's).
The chosen versions, hashes and signers go to <out>/manifest.json (the matrix report cites it). The APKs are not
committed (gitignored); they are third-party binaries used only on test emulators.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

REPO = "https://f-droid.org/repo"
FDROID_KEY = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
HERE = os.path.dirname(os.path.abspath(__file__))

# package -> why it is in the matrix
APPS = {
    "org.mozilla.fennec_fdroid": "Firefox (Fennec F-Droid): GeckoView, its own IME bridge",
    "com.zulipmobile": "Zulip 30.x: the Flutter app under the legacy package name (Flutter TextField)",
    "com.paperlessnative.mobile": "Paperless Native: React Native TextInput (login screen)",
    "com.adilhanney.saber": "Saber: Flutter TextField",
    "com.termux": "Termux: terminal view (TYPE_NULL-style raw key input)",
    "com.fsck.k9": "K-9 Mail: account setup (e-mail and password fields)",
    "org.fossify.notes": "Fossify Notes (successor of Simple Notes): plain multi-line EditText",
}


def fetch(url, dst):
    with urllib.request.urlopen(url, timeout=120) as r, open(dst + ".part", "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    os.replace(dst + ".part", dst)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def verify_entry(jar):
    v = subprocess.run(["jarsigner", "-verify", jar], capture_output=True, text=True)
    if "jar verified" not in v.stdout:
        raise SystemExit("!! entry.jar signature invalid:\n" + v.stdout + v.stderr)
    c = subprocess.run(["keytool", "-printcert", "-jarfile", jar], capture_output=True, text=True).stdout
    fps = {m.replace(":", "").lower() for m in re.findall(r"SHA256:\s*([0-9A-F:]+)", c)}
    if fps != {FDROID_KEY}:
        raise SystemExit("!! entry.jar is not signed by the pinned F-Droid key: %s" % fps)
    with zipfile.ZipFile(jar) as z:
        return json.loads(z.read("entry.json"))


def apksigner():
    p = shutil.which("apksigner")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    bt = os.path.join(sdk, "build-tools")
    if not p and os.path.isdir(bt):
        for v in sorted(os.listdir(bt), reverse=True):
            if os.path.exists(os.path.join(bt, v, "apksigner")):
                return os.path.join(bt, v, "apksigner")
    return p or "apksigner"


def apk_signers(apk):
    out = subprocess.run([apksigner(), "verify", "--print-certs", apk], capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit("!! apksigner rejects %s: %s" % (apk, out.stderr.strip()[:300]))
    return {m.lower() for m in re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", out.stdout)}


def pick(versions, abi):
    ok = []
    for v in versions.values():
        m = v["manifest"]
        nc = m.get("nativecode") or []
        if nc and abi not in nc:
            continue
        if m.get("usesSdk", {}).get("minSdkVersion", 1) > 36:
            continue
        ok.append(v)
    return max(ok, key=lambda v: v["manifest"]["versionCode"]) if ok else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(HERE, "apks"))
    ap.add_argument("--abi", default="x86_64")
    ap.add_argument("packages", nargs="*")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="fdroid-")
    jar = os.path.join(tmp, "entry.jar")
    fetch(REPO + "/entry.jar", jar)
    entry = verify_entry(jar)
    idx_path = os.path.join(tmp, "index-v2.json")
    fetch(REPO + entry["index"]["name"], idx_path)
    if sha256(idx_path) != entry["index"]["sha256"]:
        raise SystemExit("!! index-v2.json hash does not match the signed entry")
    index = json.load(open(idx_path))
    man_path = os.path.join(a.out, "manifest.json")
    manifest = json.load(open(man_path)) if os.path.exists(man_path) else {}
    for pkg in a.packages or list(APPS):
        p = index["packages"].get(pkg)
        if not p:
            print("!! %s is not in the F-Droid index" % pkg)
            continue
        v = pick(p["versions"], a.abi)
        if not v:
            print("!! %s: no build for %s" % (pkg, a.abi))
            continue
        name = v["file"]["name"].lstrip("/")
        dst = os.path.join(a.out, name)
        if not (os.path.exists(dst) and sha256(dst) == v["file"]["sha256"]):
            print("== downloading %s (%.0f MB)" % (name, v["file"].get("size", 0) / 1e6), flush=True)
            fetch(REPO + "/" + name, dst)
        if sha256(dst) != v["file"]["sha256"]:
            os.remove(dst)
            raise SystemExit("!! %s: SHA-256 differs from the signed index" % name)
        signers = apk_signers(dst)
        want = set(v["manifest"].get("signer", {}).get("sha256", []))
        if not signers & want:
            os.remove(dst)
            raise SystemExit("!! %s: signer %s is not the one the index lists (%s)" % (name, signers, want))
        manifest[pkg] = {"file": name, "versionName": v["manifest"]["versionName"],
                         "versionCode": v["manifest"]["versionCode"], "sha256": v["file"]["sha256"],
                         "signer": sorted(signers), "source": REPO + "/" + name,
                         "srcTarball": REPO + v["src"]["name"] if v.get("src") else None,
                         "why": APPS.get(pkg, ""), "indexTimestamp": entry["timestamp"]}
        print("ok %s %s (%s) signer %s" % (pkg, v["manifest"]["versionName"], name, sorted(signers)[0][:16]))
    json.dump(manifest, open(man_path, "w"), indent=1, sort_keys=True)
    shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
