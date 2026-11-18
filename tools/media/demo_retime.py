#!/usr/bin/env python3
"""Cut the README demo out of a constant-rate recording and check that it plays in real time (tools/media/demo_gif.sh).

Usage: demo_retime.py <frames.rgb> <width> <height> --fps 30 --field-rows N --timing trace.jsonl.timing.json
           > frame-numbers.txt        (the timing table goes to stderr)

Input: small raw RGB frames, the text field band (`--field-rows` rows) on top of the keyboard. Output: 1-based source
frame numbers, one per output frame. Nothing inside the performance is retimed: the frames from `--lead` seconds before
the first touch to the last frame in which anything moves are kept as recorded, then the last
frame is held for `--last` seconds.

The check: every touch-up of the trace that changes the text (a tapped key, the end of a glide) should show in the
text field at the same time after the first one as in the trace. For each step the table gives the trace's duration
and its end time, and when the recording shows the text change of its last touch-up that has one (a space after a
word changes nothing visible, the cursor being hidden; a glide's word shows once it is decoded, shortly after the
touch-up). For glides it also counts the frames during the stroke in which the keyboard did not change at all: with a
finger moving there should be none, more than one or two is a recording that stalled (run it again).
"""
import argparse
import json
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("width", type=int)
    ap.add_argument("height", type=int)
    ap.add_argument("--fps", type=float, default=30)
    ap.add_argument("--field-rows", type=int, required=True)
    ap.add_argument("--timing", required=True)
    ap.add_argument("--lead", type=float, default=0.4)
    ap.add_argument("--last", type=float, default=1.2)
    a = ap.parse_args()
    w, h = a.width, a.height
    size = w * h * 3
    with open(a.raw, "rb") as f:
        data = f.read()
    frames = [data[i:i + size] for i in range(0, len(data) - size + 1, size)]
    n = len(frames)

    def changed(i, r0, r1):
        """Columns in rows r0..r1 where frame i differs visibly from frame i-1."""
        lo, hi = r0 * w * 3, r1 * w * 3
        p, q = frames[i - 1][lo:hi], frames[i][lo:hi]
        if p == q:
            return []
        return [(k // 3) % w for k, (x, y) in enumerate(zip(p, q)) if abs(x - y) > 24]

    def typed(i):
        return bool(changed(i, 0, a.field_rows))  # the recording has no cursor: only text changes the field

    text_changes = [i for i in range(1, n) if typed(i)]
    first = text_changes[0]
    last_move = max(i for i in range(1, n) if typed(i) or changed(i, a.field_rows, h))
    with open(a.timing, encoding="utf-8") as f:
        steps = json.load(f)
    ms = 1000.0 / a.fps
    # The first text change is the first touch-up that types something: the demo starts before that touch went down.
    start = first - int((steps[0]["ups"][0] - steps[0]["down"]) / ms + a.lead * a.fps)
    out = list(range(max(0, start), last_move + 1)) + [last_move] * int(a.last * a.fps)
    for i in out:
        print(i + 1)

    seen = [(i - first) * ms for i in text_changes]
    t0 = steps[0]["ups"][0]
    err = sys.stderr
    print("step        kind   trace: length  ends at | recording: shown at   off   frozen frames (ms; one frame = %.0f)" % ms, file=err)
    worst = frozen_all = 0
    for s in steps:
        # The step's last touch-up that shows as a text change.
        shown = "       -      -"
        for up in reversed(s["ups"]):
            near = min(seen, key=lambda t: abs(t - (up - t0)))
            if abs(near - (up - t0)) <= (300 if s["kind"] == "glide" else 100):
                shown = "%8.0f %+6.0f" % (near, near - (up - t0))
                worst = max(worst, abs(near - (up - t0)))
                break
        frozen = ""
        if s["kind"] == "glide":
            i0, i1 = (first + int(round((t - t0) / ms)) for t in (s["down"] + 70, s["up"] - 40))
            k = sum(1 for i in range(i0, min(i1, n - 1) + 1) if frames[i][a.field_rows * w * 3:] == frames[i - 1][a.field_rows * w * 3:])
            frozen = "%4d of %d" % (k, i1 - i0 + 1)
            frozen_all += k
        print("%-11s %-5s %12d %8d | %s  %s" % (s["label"][:11], s["kind"], s["up"] - s["down"], s["up"] - t0, shown, frozen), file=err)
    print("largest offset %.0f ms over %.1f s; %d frozen frames during glides" % (worst, (steps[-1]["up"] - t0) / 1000.0, frozen_all), file=err)


if __name__ == "__main__":
    main()
