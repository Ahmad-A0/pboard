#!/usr/bin/env python3
"""Reads the strip's emoji for common words on a device, per keyboard, and compares top-1 picks.

For each word: a fresh "nocap" playground field, the word typed key by key, then the strip read from the
accessibility tree (`uiautomator dump --windows`): the emoji while the word is typed and after a space. Both
keyboards expose their strip there (Gboard: content-desc "🍕 emoji", pboard: "🍕").

  tools/dev/emoji_top1.py --serial emulator-5586 --ime gboard --out g.jsonl
  tools/dev/emoji_top1.py --serial emulator-5586 --ime pboard --out p.jsonl
  tools/dev/emoji_top1.py --compare g.jsonl p.jsonl
  (a remote pool emulator: ADB_SERVER_SOCKET=tcp:<host>:5037; key coordinates for 1080x2400 @ 420 dpi)
"""
import argparse
import html
import json
import re
import subprocess
import sys
import time

WORDS = ("love happy sad cat dog pizza coffee car home fire heart sun moon star rain snow beer wine cake birthday "
         "party music money phone book tree flower rose baby kiss laugh cry angry cool sleep tired sick hot cold "
         "thanks hello bye good beautiful food water ball gift christmas ghost rocket plane train dance smile "
         "wow lol omg").split()
IMES = {"pboard": "dev.pboard/.ime.PboardIme",
        "gboard": "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"}
ROWS = [("qwertyuiop", 54, 1714), ("asdfghjkl", 110, 1870), ("zxcvbnm", 216, 2024)]
KEYS = {c: (x0 + 108 * i, y) for row, x0, y in ROWS for i, c in enumerate(row)}
KEYS[" "] = (594, 2180)
STRIP_Y = (1500, 1650)


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, check=False).stdout.decode(errors="replace")


def is_emoji(s):
    return any(ord(ch) >= 0x2190 for ch in s) and not any(ch.isalpha() and ord(ch) < 0x2190 for ch in s.replace(" emoji", ""))


def strip(serial):
    adb(serial, "shell", "uiautomator dump --windows /sdcard/w.xml")
    xml = adb(serial, "shell", "cat /sdcard/w.xml")
    items = {}
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        d = html.unescape(re.search(r'content-desc="([^"]*)"', n).group(1))
        if not b or not d or not (STRIP_Y[0] <= int(b.group(2)) <= STRIP_Y[1]):
            continue
        d = d[:-len(" emoji")] if d.endswith(" emoji") else d
        items[int(b.group(1))] = d
    return [items[x] for x in sorted(items)]


def field_text(serial):
    out = adb(serial, "shell", "am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd get")
    m = re.search(r'"text":"([^"]*)"', out)
    return m.group(1) if m else None


def measure(serial, ime, out, words=WORDS):
    adb(serial, "shell", "ime set " + IMES[ime])
    adb(serial, "shell", "am start -n dev.pboard/.PlaygroundActivity")
    time.sleep(2)
    with open(out, "w") as f:
        for w in words:
            for attempt in range(3):  # a loaded host can drop a tap: retype the word until the field has it
                adb(serial, "shell", "am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd reset "
                                     "--es variant nocap")
                time.sleep(2)
                for c in w:
                    adb(serial, "shell", "input tap %d %d" % KEYS[c])
                    time.sleep(0.6)
                time.sleep(1.2)
                if field_text(serial) == w:
                    break
            typing = strip(serial)
            adb(serial, "shell", "input tap %d %d" % KEYS[" "])
            time.sleep(1.5)
            after = strip(serial)
            rec = {"word": w, "text": field_text(serial), "typing": typing, "after": after,
                   "emojiTyping": [s for s in typing if is_emoji(s)], "emojiAfter": [s for s in after if is_emoji(s)]}
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()
            print("%-10s typing %-28s after %s" % (w, " ".join(rec["emojiTyping"]), " ".join(rec["emojiAfter"])))


def top1(rec):
    e = rec["emojiTyping"] or rec["emojiAfter"]
    return e[0] if e else None


def compare(g_path, p_path):
    g = {r["word"]: r for r in map(json.loads, open(g_path, encoding="utf-8"))}
    p = {r["word"]: r for r in map(json.loads, open(p_path, encoding="utf-8"))}
    words = [w for w in g if w in p and top1(g[w])]
    same = 0
    for w in words:
        a, b = top1(g[w]), top1(p[w])
        same += a == b
        anyset = b in (g[w]["emojiTyping"] + g[w]["emojiAfter"])
        print("%-10s gboard %-4s pboard %-4s %s" % (w, a, b or "-", "=" if a == b else ("~" if anyset else "")))
    print("top-1 agreement: %d / %d words where Gboard shows an emoji" % (same, len(words)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--ime", choices=sorted(IMES))
    ap.add_argument("--out")
    ap.add_argument("--compare", nargs=2)
    ap.add_argument("--words", help="comma-separated subset of the word list")
    a = ap.parse_args()
    if a.compare:
        compare(*a.compare)
    elif a.serial and a.ime and a.out:
        measure(a.serial, a.ime, a.out, a.words.split(",") if a.words else WORDS)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
