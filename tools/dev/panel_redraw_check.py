#!/usr/bin/env python3
"""Device regression check: the keys must be drawn after any panel replaced them.

The key surface (KeyboardView) caches its key caps in a RenderNode of its own. HWUI deletes a node's display list
when the node leaves the drawn tree for a frame (the keys view detached while the "more" grid, emoji, clipboard or
text-editing panel shows) or when the window's hardware resources are destroyed; if the view then draws without
re-recording, the keyboard comes back blank while touches still type (bug in build 95d628b).

For every way of leaving a panel (its own close button, an item, system back then a tap into the field, home then
back to the app), this opens the panel, leaves it, screenshots, and checks that the key rows are drawn: the
standard deviation of the key-row pixels must stay near that of a freshly shown keyboard (a blank keyboard is one
flat colour).

  tools/dev/panel_redraw_check.py --serial emulator-5586 [--out DIR]
  (a remote pool emulator: ADB_SERVER_SOCKET=tcp:<host>:5037; pboard installed and enabled)

Coordinates are for the pool's Pixel 8 profile (1080x2400 @ 420 dpi, default density, toolbar on); the script
refuses other screens. Exit status 1 if any case leaves the keys undrawn.
"""
import argparse
import os
import subprocess
import sys
import time

PBOARD = "dev.pboard/.ime.PboardIme"
GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
FIELD = (540, 190)       # PlaygroundActivity's text field
MORE = (60, 1572)        # toolbar: "more" grid (becomes the panel's back arrow while open)
CLIPBOARD = (300, 1572)  # toolbar: clipboard
PANEL_BACK = (60, 1572)  # clipboard / text-editing panel: back arrow
EMOJI_KEY = (325, 2180)  # emoji key left of space
EMOJI_ABC = (80, 2212)   # emoji panel: ABC
TILE_TEXT_EDIT = (417, 1800)  # "more" grid: Text editing
TILE_EMOJI = (183, 1990)      # "more" grid: Emoji
KEY_ROWS = (0, 1660, 1080, 580)  # x, y, w, h: the four key rows
OUT = "."
SETTLE_S = 6


class Dev:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *a, out=False):
        r = subprocess.run(["adb", "-s", self.serial, *a], capture_output=True, check=False)
        return r.stdout.decode(errors="replace") if out else r.stdout

    def sh(self, cmd):
        return self.adb("shell", cmd, out=True)

    def tap(self, xy, wait=1.3):
        self.sh("input tap %d %d" % xy)
        time.sleep(wait)

    def key(self, code, wait=1.5):
        self.sh("input keyevent %d" % code)
        time.sleep(wait)

    def shot(self, path):
        with open(path, "wb") as f:
            f.write(self.adb("exec-out", "screencap", "-p"))


def rows_stddev(png):
    x, y, w, h = KEY_ROWS
    r = subprocess.run(["magick", png, "-crop", "%dx%d+%d+%d" % (w, h, x, y), "-colorspace", "Gray",
                        "-format", "%[fx:standard_deviation]", "info:"], capture_output=True, check=True)
    return float(r.stdout.decode().strip())


def ime_shown(d):
    return "mInputShown=true" in d.sh("dumpsys input_method | grep mInputShown")


def playground_focused(d):
    return "PlaygroundActivity" in d.sh("dumpsys window | grep mCurrentFocus")


def show_keyboard(d):
    """Taps the field until the keyboard is up (a loaded host can drop the first tap after an IME switch)."""
    for _ in range(4):
        if not playground_focused(d):
            d.sh("am start -n dev.pboard/.PlaygroundActivity")
            time.sleep(2)
        d.tap(FIELD, 2)
        if ime_shown(d):
            return
    sys.exit("keyboard did not come up")


def fresh(d):
    """A new IME service (switching away and back destroys it) with the keyboard shown on the letters."""
    d.sh("ime set " + GBOARD)
    time.sleep(1)
    d.sh("ime set " + PBOARD)
    d.sh("am start -n dev.pboard/.PlaygroundActivity")
    time.sleep(2)
    show_keyboard(d)
    # The new service loads its language model in the app's process (the playground's too): a back press during
    # that GC churn can miss the IME's 2.5 s key-dispatch window on a loaded host and finish the activity instead.
    time.sleep(SETTLE_S)


def back_hides(d):
    """System back with the keyboard up: it must hide the keyboard (not leave the app)."""
    if not ime_shown(d):
        sys.exit("keyboard not up before back")
    d.shot(os.path.join(OUT, "before-back.png"))
    d.key(4)
    for _ in range(10):  # focus can be briefly unset while the hide animates on a loaded host
        if playground_focused(d) and not ime_shown(d):
            return
        time.sleep(0.5)
    sys.exit("back did not just hide the keyboard (focus: %s)" % d.sh("dumpsys window | grep mCurrentFocus").strip())


def reshow(d):
    show_keyboard(d)


def home_and_back(d):
    d.key(3)
    d.sh("am start -n dev.pboard/.PlaygroundActivity")
    time.sleep(2)
    show_keyboard(d)


CASES = {
    # leave the panel with its own controls (the keyboard stays shown)
    "more/toggle": lambda d: (d.tap(MORE), d.tap(MORE)),
    "more/emoji-tile/abc": lambda d: (d.tap(MORE), d.tap(TILE_EMOJI), d.tap(EMOJI_ABC)),
    "emoji/abc": lambda d: (d.tap(EMOJI_KEY), d.tap(EMOJI_ABC)),
    "clipboard/back-arrow": lambda d: (d.tap(CLIPBOARD), d.tap(PANEL_BACK)),
    "text-editing/back-arrow": lambda d: (d.tap(MORE), d.tap(TILE_TEXT_EDIT), d.tap(PANEL_BACK)),
    # hide with the panel open, then show again
    "more/system-back/show": lambda d: (d.tap(MORE), back_hides(d), reshow(d)),
    "emoji/system-back/show": lambda d: (d.tap(EMOJI_KEY), back_hides(d), reshow(d)),
    "clipboard/system-back/show": lambda d: (d.tap(CLIPBOARD), back_hides(d), reshow(d)),
    "text-editing/system-back/show": lambda d: (d.tap(MORE), d.tap(TILE_TEXT_EDIT), back_hides(d), reshow(d)),
    "more/home/show": lambda d: (d.tap(MORE), home_and_back(d)),
    "emoji/home/show": lambda d: (d.tap(EMOJI_KEY), home_and_back(d)),
    # twice in a row (a second detach after the first re-record)
    "more/toggle-twice": lambda d: (d.tap(MORE), d.tap(MORE), d.tap(MORE), d.tap(MORE)),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="panel_redraw_out")
    ap.add_argument("--only", help="comma-separated case names")
    a = ap.parse_args()
    global OUT
    OUT = a.out
    os.makedirs(a.out, exist_ok=True)
    d = Dev(a.serial)
    size = d.sh("wm size")
    dens = d.sh("wm density")
    if "1080x2400" not in size or ("Override density" in dens) or "420" not in dens:
        sys.exit("refusing: coordinates are for 1080x2400 @ 420 dpi, got %r %r" % (size.strip(), dens.strip()))
    fresh(d)
    ref_png = os.path.join(a.out, "reference.png")
    d.shot(ref_png)
    ref = rows_stddev(ref_png)
    print("reference key-row stddev %.4f" % ref)
    if ref < 0.05:
        sys.exit("reference keyboard not drawn (stddev %.4f): is pboard the IME and the field focused?" % ref)
    failed = []
    names = a.only.split(",") if a.only else list(CASES)
    for name in names:
        fresh(d)
        CASES[name](d)
        png = os.path.join(a.out, name.replace("/", "_") + ".png")
        d.shot(png)
        sd = rows_stddev(png)
        ok = sd >= 0.6 * ref
        print("%-32s stddev %.4f  %s" % (name, sd, "ok" if ok else "BLANK KEYS"))
        if not ok:
            failed.append(name)
    print("FAIL: " + ", ".join(failed) if failed else "PASS: keys drawn after every panel (%d cases)" % len(names))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
