#!/usr/bin/env python3
"""Device regression check: one-handed mode's side buttons (exit, swap, resize) and settings applied while shown.

Bug (main 69195b1): in one-handed mode the exit button "does nothing". With the keyboard on the
left edge (after a swap) the button column was laid out in the keyboard's 4 dp edge margin instead of the free
column on the right: the buttons were drawn over the keys and a tap on them typed a letter.

Every case starts from a fresh full-width keyboard, enters one-handed mode from the toolbar's "more" grid (keyboard
on the right), presses side buttons where they are drawn, and checks the stored mode (run-as, debug build) after
each step and that the keys are full width again after the exit. The other cases change the theme and key borders
while the keyboard is shown (in-process, through the debug build's PrefsReceiver, as the Settings screens write them)
and flip the night mode under System Auto: the keyboard must follow at once, without a hide/show.

  tools/dev/onehanded_check.py --serial emulator-5586 [--out DIR] [--only CASES]
  (a remote pool emulator: ADB_SERVER_SOCKET=tcp:<host>:5037; a debug pboard installed and enabled)

Coordinates are for the pool's Pixel 8 profile (1080x2400 @ 420 dpi, default density, toolbar on, default keyboard
height); the script refuses other screens. It resets pboard's data (pm clear). Exit status 1 if any case fails.
"""
import argparse
import os
import re
import subprocess
import sys
import time

PBOARD = "dev.pboard/.ime.PboardIme"
GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
FIELD = (540, 190)            # PlaygroundActivity's text field
MORE = (60, 1572)             # toolbar: "more" grid
TILE_ONE_HANDED = (182, 1796)  # "more" grid: One-handed
# Side buttons (Gboard 15.1 geometry at 420 dpi): centres 189.5 / 361.5 / 533.5 px below the keyboard's top, in the
# free column: x 0..206 with the keyboard on the right, 874..1080 with it on the left.
SIDE_Y = {"exit": 1706, "swap": 1878, "resize": 2050}
SIDE_X = {"right": 103, "left": 977}
RESIZE_DONE = (760, 1896)     # resize overlay: Done
Q_KEY = (40, 1740)            # first key of the top row on the full-width keyboard
P_KEY = (1040, 1740)          # last key of the top row
BAND = (540, 2330)            # keyboard background under the keys (navigation band)
BLACK_KEY = "161616"          # Black theme's letter key (dev.pboard.ime.theme.KeyboardTheme)
SETTINGS_TILE_SYSTEM_AUTO = (540, 1185)
SETTINGS_TILE_DYNAMIC = (214, 1185)
SHEET_APPLY = (899, 2243)
PREFS = "/data/user_de/0/dev.pboard/shared_prefs/pboard_settings.xml"
OUT = "."
SETTLE_S = 4


class Dev:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *a, out=False):
        r = subprocess.run(["adb", "-s", self.serial, *a], capture_output=True, check=False)
        return r.stdout.decode(errors="replace") if out else r.stdout

    def sh(self, cmd):
        return self.adb("shell", cmd, out=True)

    def tap(self, xy, wait=1.6):
        self.sh("input tap %d %d" % xy)
        time.sleep(wait)

    def key(self, code, wait=1.5):
        self.sh("input keyevent %d" % code)
        time.sleep(wait)

    def shot(self, name):
        path = os.path.join(OUT, name.replace("/", "_") + ".png")
        with open(path, "wb") as f:
            f.write(self.adb("exec-out", "screencap", "-p"))
        return path


def pixel(png, xy):
    r = subprocess.run(["magick", png, "-format", "%%[hex:p{%d,%d}]" % xy, "info:"], capture_output=True, check=True)
    return r.stdout.decode().strip()[:6]


def ui_nodes(d):
    """(text, content-desc, [x0, y0, x1, y1]) of every node on screen."""
    d.sh("uiautomator dump /sdcard/ui.xml")
    xml = d.sh("cat /sdcard/ui.xml")
    nodes = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        b = [int(v) for v in re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n).group(1))]
        nodes.append((re.search(r' text="([^"]*)"', n).group(1), re.search(r'content-desc="([^"]*)"', n).group(1), b))
    return nodes


def stored(d, key):
    m = re.search(r'name="%s">([^<]*)<' % key, d.sh("run-as dev.pboard cat " + PREFS))
    return m.group(1) if m else None


def mode(d):
    m = re.search(r'name="one_handed_mode">(\w+)<', d.sh("run-as dev.pboard cat " + PREFS))
    return m.group(1) if m else "off"


def ime_shown(d):
    return "mInputShown=true" in d.sh("dumpsys input_method | grep mInputShown")


def playground_focused(d):
    return "PlaygroundActivity" in d.sh("dumpsys window | grep mCurrentFocus")


def show_keyboard(d):
    for _ in range(4):
        if not playground_focused(d):
            d.sh("am start -n dev.pboard/.PlaygroundActivity")
            time.sleep(2)
        d.tap(FIELD, 2)
        if ime_shown(d):
            return
    sys.exit("keyboard did not come up")


def fresh(d):
    """Default settings, a new IME service and the keyboard shown full width."""
    d.sh("am force-stop dev.pboard")
    d.sh("pm clear dev.pboard")
    d.sh("ime enable " + PBOARD)
    d.sh("ime set " + GBOARD)
    time.sleep(1)
    d.sh("ime set " + PBOARD)
    d.sh("am start -n dev.pboard/.PlaygroundActivity")
    time.sleep(2)
    show_keyboard(d)
    time.sleep(SETTLE_S)


class Case:
    def __init__(self, d, name):
        self.d, self.name, self.errors = d, name, []

    def expect_mode(self, want, step):
        got = mode(self.d)
        if got != want:
            self.errors.append("%s: mode %s, want %s" % (step, got, want))
        return got == want

    def side(self, button, keyboard_on):
        self.d.tap((SIDE_X[keyboard_on], SIDE_Y[button]))


def enter(c):
    c.d.tap(MORE)
    c.d.tap(TILE_ONE_HANDED, 2)
    c.expect_mode("right", "enter")


def exit_from(c, side):
    c.side("exit", side)
    if c.expect_mode("off", "exit (%s)" % side):
        png = c.d.shot(c.name + "-after-exit")
        if pixel(png, Q_KEY) != REF["key"] or pixel(png, P_KEY) != REF["key"]:
            c.errors.append("exit (%s): keys not full width (Q %s, P %s, key %s)" % (
                side, pixel(png, Q_KEY), pixel(png, P_KEY), REF["key"]))


def case_exit(c):
    enter(c)
    exit_from(c, "right")


def case_swap_exit(c):
    enter(c)
    c.side("swap", "right")
    c.expect_mode("left", "swap")
    c.d.shot(c.name + "-left")
    exit_from(c, "left")


def case_swap_swap_exit(c):
    enter(c)
    c.side("swap", "right")
    c.expect_mode("left", "swap")
    c.side("swap", "left")
    c.expect_mode("right", "swap back")
    exit_from(c, "right")


def case_resize_exit(c, side):
    enter(c)
    if side == "left":
        c.side("swap", "right")
        c.expect_mode("left", "swap")
    c.side("resize", side)
    c.d.shot(c.name + "-resize")
    c.d.tap(RESIZE_DONE)
    c.expect_mode(side, "resize done")
    exit_from(c, side)


def broadcast_prefs(d, args):
    d.sh("am broadcast -n dev.pboard/.debug.PrefsReceiver " + args)
    time.sleep(2)


def case_theme_while_shown(c):
    """Theme and key borders written while the keyboard is up: it re-themes at once and stays up."""
    d = c.d
    broadcast_prefs(d, "--es theme color_black")
    png = d.shot(c.name + "-black")
    if not ime_shown(d):
        c.errors.append("keyboard hidden by the theme change")
    bg, key = pixel(png, BAND), pixel(png, Q_KEY)
    if (bg, key) != ("000000", BLACK_KEY):
        c.errors.append("black theme not applied while shown: background #%s key #%s" % (bg, key))
    broadcast_prefs(d, "--ez key_borders false")
    png = d.shot(c.name + "-borderless")
    if pixel(png, Q_KEY) != "000000":
        c.errors.append("key borders still drawn while shown: #%s" % pixel(png, Q_KEY))
    broadcast_prefs(d, "--es theme dynamic --ez key_borders true")
    png = d.shot(c.name + "-dynamic")
    if pixel(png, Q_KEY) != REF["key"]:
        c.errors.append("Dynamic Color not back while shown: key #%s, want #%s" % (pixel(png, Q_KEY), REF["key"]))


def labels_differ(a, b):
    """Pixels that differ between two screenshots in the top key row (the letter labels)."""
    r = subprocess.run(["magick", "compare", "-metric", "AE", "-fuzz", "8%", a, b, "-crop", "1080x120+0+1680", "null:"],
                       capture_output=True, check=False)
    return int(float(r.stderr.decode().split()[0]))


def case_font_while_shown(c):
    """Preferences › Font › System default while the keyboard is up: the labels change at once, and change back."""
    d = c.d
    pboard = d.shot(c.name + "-pboard-font")
    broadcast_prefs(d, "--ez use_system_font true")
    system = d.shot(c.name + "-system-font")
    broadcast_prefs(d, "--ez use_system_font false")
    back = d.shot(c.name + "-pboard-font-again")
    changed, restored = labels_differ(pboard, system), labels_differ(pboard, back)
    print("    label pixels changed %d, after switching back %d" % (changed, restored))
    if changed < 500:
        c.errors.append("labels unchanged with the system font (%d px)" % changed)
    if restored > 50:
        c.errors.append("labels not restored with pboard's font (%d px)" % restored)


def case_retheme_while_shown(c):
    """System Auto, then the night mode flips while the keyboard is up: the keys must follow at once."""
    d = c.d
    d.sh("cmd uimode night no")
    d.sh("am start -n dev.pboard/.SettingsActivity --es dev.pboard.extra.SETTINGS_ROUTE theme")
    time.sleep(3)
    d.tap(SETTINGS_TILE_SYSTEM_AUTO)
    d.tap(SHEET_APPLY)
    d.key(4)
    d.key(4)
    show_keyboard(d)
    time.sleep(2)
    light = pixel(d.shot(c.name + "-light"), Q_KEY)
    d.sh("cmd uimode night yes")
    time.sleep(4)
    if not ime_shown(d):
        show_keyboard(d)
        c.errors.append("keyboard hidden by the night-mode change")
    dark = pixel(d.shot(c.name + "-dark"), Q_KEY)
    d.sh("cmd uimode night no")
    time.sleep(3)
    if light == dark:
        c.errors.append("key colour did not follow the night mode (%s)" % light)
    else:
        print("    light key #%s -> dark key #%s" % (light, dark))


def case_landscape_sheet(c):
    """Landscape: the theme preview sheet's Key borders switch and Apply button must be on screen and work."""
    d = c.d
    rotation = d.sh("settings get system accelerometer_rotation").strip()
    d.sh("settings put system accelerometer_rotation 0")
    d.sh("settings put system user_rotation 1")
    time.sleep(3)
    try:
        d.sh("am start -n dev.pboard/.SettingsActivity --es dev.pboard.extra.SETTINGS_ROUTE theme")
        time.sleep(3)
        tile = None
        for _ in range(6):
            tile = next((b for t, dsc, b in ui_nodes(d) if dsc == "Default Dark" and b[3] - b[1] > 150 and b[1] > 250
                         and b[3] < 1000), None)
            if tile:
                break
            d.sh("input swipe 1200 800 1200 350 500")
            time.sleep(1.5)
        if not tile:
            c.errors.append("Default Dark tile not found")
            return
        d.tap(((tile[0] + tile[2]) // 2, (tile[1] + tile[3]) // 2), 2.5)
        nodes = ui_nodes(d)
        d.shot(c.name + "-sheet")
        apply = next((b for t, dsc, b in nodes if t == "Apply"), None)
        borders = next((b for t, dsc, b in nodes if t == "Key borders"), None)
        if not apply or apply[3] > 1080 or not borders or borders[3] > 1080:
            c.errors.append("sheet controls off screen (Apply %s, Key borders %s)" % (apply, borders))
            d.key(4)
            return
        d.tap(((apply[0] + apply[2]) // 2, (apply[1] + apply[3]) // 2), 2)
        if stored(d, "theme") != "default_dark":
            c.errors.append("Apply did not store the theme (%s)" % stored(d, "theme"))
    finally:
        d.key(4)
        d.key(4)
        d.sh("settings put system user_rotation 0")
        d.sh("settings put system accelerometer_rotation " + (rotation or "1"))
        time.sleep(2)


CASES = {
    "right/exit": case_exit,
    "right/swap/exit": case_swap_exit,
    "right/swap/swap/exit": case_swap_swap_exit,
    "right/resize/done/exit": lambda c: case_resize_exit(c, "right"),
    "left/resize/done/exit": lambda c: case_resize_exit(c, "left"),
    "theme+borders/while-shown": case_theme_while_shown,
    "font/while-shown": case_font_while_shown,
    "system-auto/night-mode-while-shown": case_retheme_while_shown,
    "theme-sheet/landscape-apply": case_landscape_sheet,
}
REF = {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="onehanded_out")
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
    ref = d.shot("reference")
    REF["key"] = pixel(ref, Q_KEY)
    if pixel(ref, P_KEY) != REF["key"]:
        sys.exit("reference keyboard not full width or not shown: Q #%s P #%s" % (REF["key"], pixel(ref, P_KEY)))
    failed = []
    for name in (a.only.split(",") if a.only else list(CASES)):
        fresh(d)
        c = Case(d, name)
        CASES[name](c)
        print("%-40s %s" % (name, "ok" if not c.errors else "FAIL: " + "; ".join(c.errors)))
        if c.errors:
            failed.append(name)
    d.sh("am force-stop dev.pboard")
    d.sh("pm clear dev.pboard")
    print("FAIL: " + ", ".join(failed) if failed else "PASS: %d cases" % len(CASES if not a.only else a.only.split(",")))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
