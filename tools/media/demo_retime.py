#!/usr/bin/env python3
"""Pick the frames of the README demo from a constant-rate recording (tools/media/demo_gif.sh).

Usage: demo_retime.py <frames.rgb> <width> <height> --fps 30 --field-rows N --panel-rows N  > frame-numbers.txt

Input: small raw RGB frames, top to bottom: the text field band (`--field-rows` rows), the part of the app above the
keyboard that the taller emoji panel covers when it is open (`--panel-rows` rows), the keyboard.
Output: 1-based source frame numbers, one per output frame (a held frame is repeated).

The recording has long waits in it: the injector lets the field settle after every word, and that is also when the
host changes the theme. Typing inside a word, every glide and everything on the emoji panel keep their recorded timing;
only the waits are shortened:
 - the demo starts `--lead` seconds before the first change in the text field;
 - a stretch where nothing moves is cut down to `--pause` seconds, except while the emoji panel is open (the pauses
   there are part of the scripted taps). The blinking cursor does not count as movement;
 - the last frame is held for `--last` seconds.
"""
import argparse


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("width", type=int)
    ap.add_argument("height", type=int)
    ap.add_argument("--fps", type=float, default=30)
    ap.add_argument("--field-rows", type=int, required=True)
    ap.add_argument("--panel-rows", type=int, required=True)
    ap.add_argument("--lead", type=float, default=0.5)
    ap.add_argument("--pause", type=float, default=0.17)
    ap.add_argument("--last", type=float, default=1.3)
    a = ap.parse_args()
    w, h = a.width, a.height
    size = w * h * 3
    with open(a.raw, "rb") as f:
        data = f.read()
    frames = [data[i:i + size] for i in range(0, len(data) - size + 1, size)]
    n = len(frames)

    def changed(p, q, r0, r1):
        """Pixels (as column numbers) in rows r0..r1 where two frames differ visibly."""
        lo, hi = r0 * w * 3, r1 * w * 3
        p, q = p[lo:hi], q[lo:hi]
        if p == q:
            return []
        return [(k // 3) % w for k, (x, y) in enumerate(zip(p, q)) if abs(x - y) > 24]

    def typed(i):
        # More than the cursor (which blinks, at most two columns wide here) changed in the text field.
        cols = changed(frames[i - 1], frames[i], 0, a.field_rows)
        return bool(cols) and max(cols) - min(cols) > 1

    def moved(i):
        return typed(i) or bool(changed(frames[i - 1], frames[i], a.field_rows, h))

    def panel_open(i):
        r0, r1 = a.field_rows, a.field_rows + a.panel_rows
        return len(changed(frames[0], frames[i], r0, r1)) > 0.3 * (r1 - r0) * w * 3

    first_key = next(i for i in range(1, n) if typed(i))
    last_move = max(i for i in range(1, n) if moved(i))
    out = list(range(max(0, first_key - int(a.lead * a.fps)), first_key))
    cap, still = int(a.pause * a.fps + 0.5), 0
    for i in range(first_key, last_move + 1):
        still = 0 if moved(i) else still + 1
        if still <= cap or panel_open(i):
            out.append(i)
    out += [last_move] * int(a.last * a.fps)
    for i in out:
        print(i + 1)


if __name__ == "__main__":
    main()
