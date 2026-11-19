# FUTO Keyboard vs pboard

Same harness, same items, same build host, run 2026-11-19. Both keyboards on Android 16 emulators
(Pixel 8 profile, 1080x2400 @ 420 dpi), default settings.

- FUTO Keyboard 0.1.30 (`org.futo.inputmethod.latin`), layout geometry `tools/bench/geometry/futo_pixel8.json`.
- pboard 0.1.7-12-ga504a65, layout geometry `tools/bench/geometry/pboard_pixel8.json`.

Top-1 word accuracy:

| Set | Items | FUTO Keyboard 0.1.30 | pboard |
|---|---|---|---|
| FUTO swipe (test, first 400) | 400 | 93.00% | 93.25% |
| How We Swipe (test, first 400) | 400 | 79.25% | 78.75% |
| VTAP taps (test, all) | 3818 | 93.01% | 95.94% |
| TSI taps (verdict subset) | 303 | 79.21% | 93.07% |

Files: `<keyboard>_<set>.score_all.txt` (per participant / per slice), `.score_all.json`, `.groups.json`.

## TSI

FUTO Keyboard's TSI gap comes from noisy taps:

- Mistyped words recovered: 54/109 (49.5%) vs 90/111 (81.1%).
- Words whose space tap landed on a letter key (28 items): 0/28 vs 16/28.
- False corrections of correctly typed words: 8/194 vs 0/192.
- Two participants (user06, user08) account for most of the gap.

About 9 of the extra misses are taps recorded more than half a key above a top-row letter. On FUTO
Keyboard that point is the suggestion bar, so the tap inserts a suggestion.

Timing check: the TSI items replayed with every inter-tap interval tripled
(`slow3_futo_tap_tsi.*`) score the same, 79.21%. Replay speed is not the cause.

## Gboard

The Gboard 18.6 figures in the top-level README come from an earlier run on a different host. pboard
scored within 0.6 points of its figures there on every set in this run.
