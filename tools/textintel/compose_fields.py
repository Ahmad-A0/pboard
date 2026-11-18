#!/usr/bin/env python3
"""How often Smart Compose shows in each playground field type (on an emulator/device with pboard as the IME).

Types held-out sentences character by character (`adb shell input text`, about one character per adb round trip,
roughly typing pace) into PlaygroundActivity in each field variant, and counts the ghost texts the keyboard showed
(debug log `PboardTextIntel`, counts only: `adb shell setprop log.tag.PboardTextIntel DEBUG` is set by this script):
words during which a ghost was shown at least once, and ghost updates (a ghost is recomputed at every keystroke).

    python3 -I tools/textintel/compose_fields.py --serial emulator-5560 --sentences heldout/chat.txt --n 12
"""

import argparse
import re
import subprocess
import time

VARIANTS = ["default", "shortmessage", "nocap", "plain", "search", "nosuggest", "email", "uri", "incognito"]


def adb(serial, *args, check=False):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, check=check).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--sentences", required=True)
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--variants", default=",".join(VARIANTS))
    a = ap.parse_args()
    sents = []
    for line in open(a.sentences, encoding="utf-8"):
        s = line.strip()
        # letters and spaces only (the shell would need escapes for the rest); 4-12 words
        if re.fullmatch(r"[A-Za-z ]+", s) and 4 <= len(s.split()) <= 12:
            sents.append(s)
        if len(sents) >= a.n:
            break
    words = sum(len(s.split()) for s in sents)
    adb(a.serial, "shell", "setprop", "log.tag.PboardTextIntel", "DEBUG")
    adb(a.serial, "shell", "am", "force-stop", "dev.pboard")
    adb(a.serial, "shell", "ime", "set", "dev.pboard/.ime.PboardIme")
    adb(a.serial, "shell", "am", "start", "-n", "dev.pboard/.PlaygroundActivity")
    time.sleep(3)
    print(f"{len(sents)} sentences, {words} words per field type")
    for v in a.variants.split(","):
        adb(a.serial, "logcat", "-c")
        adb(a.serial, "shell", "am", "broadcast", "-a", "dev.pboard.bench.PLAYGROUND", "-p", "dev.pboard",
            "--es", "cmd", "reset", "--es", "variant", v)
        time.sleep(1.5)
        for s in sents:
            for ch in s + " ":
                adb(a.serial, "shell", "input", "text", "%s" if ch == " " else ch)
                if ch == " ":
                    adb(a.serial, "shell", "log", "-p", "d", "-t", "PboardTextIntel", "wordmark")
            adb(a.serial, "shell", "am", "broadcast", "-a", "dev.pboard.bench.PLAYGROUND", "-p", "dev.pboard",
                "--es", "cmd", "settext", "--es", "text", "''")
            time.sleep(0.3)
        log = adb(a.serial, "logcat", "-d", "-s", "PboardTextIntel:D")
        active = re.findall(r"compose active=(\w+)", log)
        shown = len(re.findall(r"compose shown", log))
        # words during which a ghost text was on screen at least once (a ghost updates at every keystroke)
        with_ghost = sum(1 for chunk in re.split(r"wordmark", log)[:-1] if "compose shown" in chunk)
        print(f"{v:13s} active={active[-1] if active else '?':5s} words with a ghost {with_ghost:3d} = "
              f"{100 * with_ghost / words:5.1f} per 100 words; ghost updates {shown:3d} = {100 * shown / words:5.1f} per 100 words")


if __name__ == "__main__":
    main()
