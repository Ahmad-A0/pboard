#!/usr/bin/env python3
"""Pick the frames of the README demo from a constant-rate recording (tools/media/demo_gif.sh).

Usage: demo_retime.py <frames.rgb> <width> <height> --fps 25 --field-rows N --themes N  > frame-numbers.txt

Input: small raw RGB frames (text field band on top, `--field-rows` rows high, keyboard band below). Output: 1-based
source frame numbers, one per output frame (a held frame is repeated).

 - The demo starts a moment before the first change in the text field.
 - Until the first theme change, stretches where nothing moves (waiting for the host's next command) are cut down to
   `--pause` seconds. The blinking cursor does not count as movement.
 - A theme change is a change of the keyboard's background colour (sampled at the left edge, where no key or icon
   is); the first `--skip` changes are something else (the emoji panel opening and closing). Each theme gets the
   recording's `--beat` seconds from its change, the last one `--last` seconds.
"""
import argparse
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("width", type=int)
    ap.add_argument("height", type=int)
    ap.add_argument("--fps", type=float, default=25)
    ap.add_argument("--field-rows", type=int, required=True)
    ap.add_argument("--themes", type=int, required=True, help="number of theme changes expected")
    ap.add_argument("--skip", type=int, default=0, help="background changes before the first theme change")
    ap.add_argument("--lead", type=float, default=0.6, help="seconds shown before the first key")
    ap.add_argument("--pause", type=float, default=0.55)
    ap.add_argument("--beat", type=float, default=0.8)
    ap.add_argument("--last", type=float, default=1.4)
    a = ap.parse_args()
    w, h = a.width, a.height
    size = w * h * 3
    with open(a.raw, "rb") as f:
        data = f.read()
    frames = [data[i:i + size] for i in range(0, len(data) - size + 1, size)]
    n = len(frames)

    def changed_columns(i, r0, r1):
        """Columns in rows r0..r1 where frame i differs visibly from frame i-1."""
        lo, hi = r0 * w * 3, r1 * w * 3
        p, q = frames[i - 1][lo:hi], frames[i][lo:hi]
        if p == q:
            return set()
        return {(k // 3) % w for k, (x, y) in enumerate(zip(p, q)) if abs(x - y) > 24}

    def typed(i):
        # More than the cursor (which blinks, at most two columns wide here) changed in the text field.
        cols = changed_columns(i, 0, a.field_rows)
        return bool(cols) and max(cols) - min(cols) > 1

    def moved(i):
        return typed(i) or bool(changed_columns(i, a.field_rows, h))

    def background(i):
        # Left edge, just below the top of the bottom-most third of the frame: keyboard background in every state.
        row = h - (h - a.field_rows) // 3
        o = (row * w) * 3
        return frames[i][o:o + 3]

    def differs(c, d):
        return max(abs(x - y) for x, y in zip(c, d)) > 4

    first_key = next(i for i in range(1, n) if typed(i))
    # Theme changes: frames where the background settles on a new colour.
    changes, cur = [], background(first_key)
    for i in range(first_key, n):
        b = background(i)
        if differs(b, cur) and (i + 1 >= n or not differs(background(i + 1), b)):
            changes.append(i)
            cur = b
    changes = changes[a.skip:]
    if len(changes) != a.themes:
        sys.exit("found %d theme changes, expected %d" % (len(changes), a.themes))

    out = []
    start = max(0, first_key - int(a.lead * a.fps))
    cap, still = int(a.pause * a.fps), 0
    for i in range(start, changes[0]):
        still = 0 if moved(i) else still + 1
        if still <= cap:
            out.append(i)
    for k, c in enumerate(changes):
        length = int((a.last if k == len(changes) - 1 else a.beat) * a.fps)
        end = min(changes[k + 1] if k + 1 < len(changes) else n, n)
        for j in range(length):
            out.append(min(c + j, end - 1))
    for i in out:
        print(i + 1)


if __name__ == "__main__":
    main()
