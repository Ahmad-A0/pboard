#!/usr/bin/env python3
"""Device regression check: inline settings sliders follow a finger that grabs the thumb at either end of the range.

Bug: dragging Settings > Clipboard > "Keep clipboard history for" did not move the slider. At the
default (1 hour) the thumb sits at the start of the track, 24 dp from the screen edge: half of it lay outside the
slider's touch area and the rest inside the gesture-navigation back zone, so a finger on the thumb started a back
swipe (or touched nothing) instead of a drag. The same held for the last value at the right edge.

Each case puts the slider at one end with a tap on the track, presses ON the thumb, drags towards the other end in
small steps and checks, while the finger is still down, that the thumb is drawn further along (screenshot) and the
stored value changed (run-as, debug build), and afterwards that the page is still Settings (no back navigation).

  tools/dev/settings_slider_check.py --serial emulator-5556 [--out DIR]
  (a remote pool emulator: ADB_SERVER_SOCKET=tcp:<host>:5037; a debug pboard installed)

Coordinates are for the pool's Pixel 8 profile (1080x2400 @ 420 dpi, default density); the script refuses other
screens. Exit status 1 if any case fails.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time

ROUTE_EXTRA = "dev.pboard.extra.SETTINGS_ROUTE"
TRACK_Y = 1312            # Clipboard page: the retention slider's track (third row)
TRACK_X = (63, 1017)      # track ends = thumb centres at the first and last value (24 dp page inset)
STEPS = [1, 2, 3, 6, 12, 18, 24]  # Prefs.CLIPBOARD_RETENTION_STEPS
PREFS_XML = "/data/user_de/0/dev.pboard/shared_prefs/pboard_settings.xml"


class Device:
    def __init__(self, serial, out):
        self.serial, self.out = serial, out

    def adb(self, *a, out=None):
        cmd = ["adb", "-s", self.serial, *a]
        if out:
            with open(out, "wb") as f:
                subprocess.run(cmd, stdout=f, check=True)
            return ""
        return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout

    def motion(self, kind, x, y=TRACK_Y):
        self.adb("shell", "input", "motionevent", kind, str(x), str(y))

    def shot(self, name):
        path = os.path.join(self.out, name + ".png")
        self.adb("exec-out", "screencap", "-p", out=path)
        return path

    def retention(self):
        xml = self.adb("shell", "run-as", "dev.pboard", "cat", PREFS_XML)
        m = re.search(r'name="clipboard_retention_hours" value="(\d+)"', xml)
        return int(m.group(1)) if m else 1

    def top_activity(self):
        dump = self.adb("shell", "dumpsys", "activity", "activities")
        m = re.search(r"topResumedActivity=\S+ \S+ (\S+)", dump)
        return m.group(1) if m else "?"


def thumb_x(png):
    """Centre of the thumb: the dark (primary) pixels on a row through the circle, 4 px above the 2 dp track."""
    row = subprocess.run(["magick", png, "-crop", "1080x1+0+%d" % (TRACK_Y - 10), "+repage", "-colorspace", "gray",
                          "-depth", "8", "gray:-"], capture_output=True, check=True).stdout
    xs = [x for x, v in enumerate(row) if v < 140]
    return sum(xs) / len(xs) if xs else None


def case(dev, name, start_tap, grab_x, direction):
    dev.adb("shell", "input", "tap", str(start_tap), str(TRACK_Y))
    time.sleep(0.8)
    before_value = dev.retention()
    before_x = thumb_x(dev.shot(name + "_0"))
    dev.motion("DOWN", grab_x)
    x = grab_x
    for _ in range(60):  # 360 px in 6 px steps: about two and a half values
        x += 6 * direction
        dev.motion("MOVE", x)
    time.sleep(0.5)
    mid_x = thumb_x(dev.shot(name + "_mid"))
    mid_value = dev.retention()
    dev.motion("UP", x)
    time.sleep(0.8)
    top = dev.top_activity()
    problems = []
    if before_x is None or mid_x is None or (mid_x - before_x) * direction < 100:
        problems.append("thumb did not follow the finger (x %s -> %s)" % (before_x, mid_x))
    if mid_value == before_value:
        problems.append("stored value unchanged (%d)" % before_value)
    if "SettingsActivity" not in top:
        problems.append("left the settings page (top: %s)" % top)
    print("%-22s %s  value %d -> %d, thumb x %s -> %s" % (
        name, "FAIL" if problems else "ok", before_value, mid_value, before_x, mid_x))
    for p in problems:
        print("    " + p)
    return not problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    out = args.out or tempfile.mkdtemp(prefix="slider_check_")
    os.makedirs(out, exist_ok=True)
    dev = Device(args.serial, out)
    size = dev.adb("shell", "wm", "size")
    density = dev.adb("shell", "wm", "density")
    densities = re.findall(r"density: (\d+)", density)
    if "1080x2400" not in size or not densities or densities[-1] != "420":
        sys.exit("needs the Pixel 8 profile (1080x2400 @ 420 dpi): %s / %s" % (size.strip(), density.strip()))
    dev.adb("shell", "am", "start", "-S", "-W", "-n", "dev.pboard/.SettingsActivity", "--es", ROUTE_EXTRA, "clipboard")
    time.sleep(1.5)
    ok = True
    # Grab the thumb a few px outside the track end, where a finger centred on it lands half the time.
    ok &= case(dev, "first-value-right", start_tap=100, grab_x=TRACK_X[0] - 8, direction=1)
    ok &= case(dev, "last-value-left", start_tap=1000, grab_x=TRACK_X[1] + 8, direction=-1)
    print("screenshots:", out)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
