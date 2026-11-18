#!/usr/bin/env python3
"""Device regression check: a word re-opened at the cursor is not marked as composing until it is edited.

Gboard 18.6 (measured on a farm pool device) shows suggestions for the word at the cursor when a field opens or a tap
puts the cursor at the end of a word, but sets no composing region (composingStart -1) until the word is edited;
then typing, backspace, a strip pick and space edit it as usual. pboard used to mark the word as composing at once.
This checks both the editor state and the text after each editing flow, for pboard (and, with --ime gboard, the
reference).

  tools/dev/reopen_word_check.py --serial emulator-5586 [--ime pboard|gboard]
  (a remote pool emulator: ADB_SERVER_SOCKET=tcp:<host>:5037; pboard installed and enabled)

Key coordinates are for the pool's Pixel 8 profile (1080x2400 @ 420 dpi, default density). Exit status 1 on a
mismatch.
"""
import argparse
import json
import re
import subprocess
import sys
import time

IMES = {"pboard": "dev.pboard/.ime.PboardIme",
        "gboard": "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"}
ROWS = [("qwertyuiop", 54, 1714), ("asdfghjkl", 110, 1870), ("zxcvbnm", 216, 2024)]
KEYS = {c: (x0 + 108 * i, y) for row, x0, y in ROWS for i, c in enumerate(row)}
KEYS.update({" ": (594, 2180), "<": (1026, 2024), "^": (540, 1572)})  # space, backspace, strip centre

# (name, field text, cursor offset to tap first or None, keys, expected text, expected composingStart or None)
FLOWS = [
    ("open: no composing", "hello wonderful world", None, "", "hello wonderful world", -1),
    ("tap word end: no composing", "hello wonderful world", 5, "", "hello wonderful world", -1),
    ("tap inside word: no composing", "hello wonderful world", 9, "", "hello wonderful world", -1),
    ("open, type", "hello wor", None, "ld", "hello world", None),
    ("open, pick centre", "hello wor", None, "^", "hello world ", None),
    ("open, backspace x2", "hello wor", None, "<<", "hello w", None),
    ("open, space", "hello wor", None, " ", "hello wor ", None),
    ("tap word end, type", "abc def", 3, "d", "abcd def", None),
    ("tap word end, backspace", "abc def", 3, "<", "ab def", None),
]


class Dev:
    def __init__(self, serial):
        self.serial = serial

    def sh(self, cmd):
        r = subprocess.run(["adb", "-s", self.serial, "shell", cmd], capture_output=True, check=False)
        return r.stdout.decode(errors="replace")

    def playground(self, cmd, extra=""):
        out = self.sh("am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd %s %s" % (cmd, extra))
        m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
        return json.loads(m.group(1)) if m else {}

    def tap(self, x, y, wait=0.7):
        self.sh("input tap %d %d" % (x, y))
        time.sleep(wait)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--ime", default="pboard", choices=sorted(IMES))
    a = ap.parse_args()
    d = Dev(a.serial)
    if "1080x2400" not in d.sh("wm size") or "Override density" in d.sh("wm density"):
        sys.exit("refusing: key coordinates are for 1080x2400 at the default density")
    d.sh("ime set " + IMES[a.ime])
    d.sh("am start -n dev.pboard/.PlaygroundActivity")
    time.sleep(2)
    failed = 0
    for name, text, tap_at, keys, want_text, want_comp in FLOWS:
        d.playground("reset", "--es variant nocap --es text '%s'" % text)
        time.sleep(2)
        if tap_at is not None:
            p = d.playground("pos", "--ei offset %d" % tap_at)
            d.tap(int(p["x"]), int(p["y"]), wait=1.0)
        for k in keys:
            d.tap(*KEYS[k])
        time.sleep(1)
        st = d.playground("get")
        ok = st.get("text") == want_text and (want_comp is None or st.get("composingStart") == want_comp)
        failed += not ok
        print("%-30s text=%-24r composingStart=%-3s %s" % (name, st.get("text"), st.get("composingStart"),
                                                              "ok" if ok else "MISMATCH (want %r, %s)" % (want_text, want_comp)))
    print("FAIL: %d flow(s)" % failed if failed else "PASS: %d flows" % len(FLOWS))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
