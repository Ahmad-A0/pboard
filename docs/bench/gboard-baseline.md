# Gboard baseline (and pboard on the same harness)

The bar pboard has to clear: Gboard's output on identical replayed touch input, measured with the
benchmark in [README.md](README.md). Human held-out test sets are the headline; synthetic sets are
secondary. Every number here comes from `score.txt` / `groups.json` in `tools/bench/out/<run>/`
(gitignored); `device.txt` there records IME version, Gboard main-LM version, reset policy,
devices and commits.

## Headline: pboard vs Gboard 18.6 on the human TEST sets

The run was on the emulator farm on 2026-11-08 and finished at 22:07 UTC. Gboard `18.6.3.1002251336-release-x86_64` used main LM
`2026092216` with slow settle. pboard was the `bench` build of main `b627b57` with fast settle; on this build
compare mode found 0 false-early items in 909. Every group was valid: per-participant resets, the
3.5 x load rule and the incident window for the voice-eval overload from 19:54 to 19:58.

Each item is paired: both keyboards typed the same touches. The CIs are 95 % bootstrap intervals
that resample participants with replacement. A difference is "real" only when its CI excludes 0 and
it exceeds the noise floor. The noise floor is the disagreement between two Gboard slow-settle runs
of the same 200 TSI items on different devices; correctness differed on 0 items, and the text on
2 items.

| set (items, participants) | metric | Gboard 18.6 | pboard b627b57 | pboard - Gboard [95 % CI] | real? |
|---|---|---|---|---|---|
| TSI taps (3344, 8) | word accuracy | 93.39 % [89.14, 96.65] | 92.79 % [88.28, 96.69] | -0.60 [-1.94, +0.45] | no |
| | WER | 7.03 % | 7.75 % | +0.72 [-0.45, +2.26] | no |
| | false corrections | 0.95 % | 0.62 % | -0.33 [-1.13, +0.64] | no |
| | noisy recovery | 83.74 % | 81.55 % | -2.18 [-3.85, +0.11] | no |
| | accuracy, terminator on bar | 95.86 % | 95.56 % | -0.30 [-1.90, +0.78] | no |
| | accuracy, terminator missed the bar (372 items) | 73.66 % | 70.70 % | -2.96 | - |
| VTAP taps (3818, 18) | word accuracy | 96.15 % [94.50, 97.77] | 94.11 % [91.66, 96.63] | -2.04 [-2.94, -1.06] | **yes** |
| | WER | 4.06 % | 6.31 % | +2.25 [+1.22, +3.22] | **yes** |
| | false corrections | 0.31 % | 0.39 % | +0.08 [-0.10, +0.27] | no |
| | noisy recovery | 88.72 % | 82.55 % | -6.17 [-7.51, -3.81] | **yes** |
| FUTO swipes (400, 81) | word accuracy | 86.00 % [82.89, 89.05] | 83.75 % [79.90, 87.40] | -2.25 [-6.19, +1.57] | no |
| HWS swipes (400, 81) | word accuracy | 75.75 % [71.46, 80.28] | 69.00 % [64.30, 73.41] | -6.75 [-11.75, -2.21] | **yes** |

- **TSI.** pboard is level with Gboard, and false corrections are lower but not significantly so.
  Words whose terminator missed the space bar are pboard's weakest TSI slice. On the 303-word subset
  that slice was 60.7 % vs Gboard's 71.4 %; main `9e04b23` (Gboard-like space/delete resolution)
  brings it to 71.4 %, with overall subset accuracy unchanged at 92.08 %.
- **VTAP.** Vertanen's deliberately noisy typing is a real gap: pboard recovers fewer noisy words,
  82.6 % vs 88.7 %.
- **Swipe.** HWS has a real gap of 6.8 pp. FUTO is within noise.
- **pboard behaviour found by the harness.** For TSI user10, item 278 ("you") has a tap at y = 1630,
  between the suggestion strip and the top key row. pboard then ignores taps for the next two
  items, and a later run of about 12 items does the same. Gboard types through both. The behaviour
  is deterministic: identical in two runs. It is scored as pboard's errors.

Throughput on the emulator farm: the four verdict subsets ran on both keyboards in 1368 s on a 12-device pool.
Full TSI with Gboard slow ran at 46 s per 100 items on 8 devices.

### Update: pboard main `e9cf5ef` (neural swipe decoder active on device, glide-start fix, VTAP fixes)

Only pboard was re-run, on the same items; the Gboard 18.6 results are the ones above. pboard used
the `bench` build of `e9cf5ef`. Swipes and the TSI subset used compare mode (0 false-early items in
1103); VTAP used fast settle. The new per-group geometry check passed on every group (0 px).

| set (items, participants) | metric | Gboard 18.6 | pboard e9cf5ef | pboard - Gboard [95 % CI] | real? |
|---|---|---|---|---|---|
| FUTO swipes (400, 81) | word accuracy | 86.00 % | 92.75 % [90.43, 94.94] | **+6.75 [+3.93, +9.71]** | yes (pboard better) |
| HWS swipes (400, 81) | word accuracy | 75.75 % | 79.25 % [75.54, 82.90] | +3.50 [0.00, +7.04] | no |
| VTAP taps (3818, 18) | word accuracy | 96.15 % | 95.81 % [94.25, 97.44] | -0.34 [-0.82, +0.04] | no |
| | noisy recovery | 88.72 % | 87.66 % | -1.06 [-2.65, +0.14] | no |
| | false corrections | 0.31 % | 0.31 % | 0.00 [-0.21, +0.21] | no |
| TSI subset (303, 8) | word accuracy | 92.41 % | 93.07 % | +0.66 [-2.33, +2.99] | no |
| | noisy recovery | 81.98 % | 81.08 % | -0.90 [-6.19, +5.75] | no |
| | accuracy, terminator missed the bar (28) | 71.43 % | 57.14 % | - | - |

The real VTAP and HWS gaps of `b627b57` are gone. FUTO swipes are now significantly better than
Gboard. On the TSI subset, the 28 words whose terminator missed the space bar fell back from 71.4 %
(`9e04b23`) to 57.1 %, while words with the terminator on the bar rose to 96.7 %. That slice is
small, so the full TSI set should be re-run before calling it a regression.

### Secondary: Gboard 15.1 (emulator image), the build host, TSI TEST

Gated per-participant run on 3344 words, LM `2025031300`, all 8 groups valid:

| metric | value |
|---|---|
| word accuracy | 93.87 % |
| WER | 6.79 % |
| false corrections | 20/2108 = 0.95 % |
| noisy recovery | 85.03 % |
| accuracy, terminator on bar | 95.69 % |
| accuracy, terminator missed the bar | 79.30 % |

Per participant: user01 97.84, user04 97.52, user06 86.99, user07 96.45, user08 85.41, user10 94.29,
user15 95.94, user16 96.67.

### Settle check and determinism (emulator farm, Gboard 18.6)

- **Gboard slow vs fast.** 195/200 items gave identical text; slow vs slow on swapped devices gave
  198/200. Gboard is nondeterministic on about 1 % of items even on byte-identical instances, so
  Gboard stays on slow settle.
- **pboard.** Release vs bench gave 200/200 identical items, and bench slow vs fast 200/200. Compare
  mode had 0 false-early items in 909 on `b627b57`. Median settle was 175 ms (fast) vs 797 ms (slow)
  for taps, and 1142 ms vs 2059 ms for swipes.
- **Load.** Groups re-run at calm load (max load 63) gave the same output as under 2.8-3.4 x
  overload: pboard VTAP, 1740 items identical. Gboard user10 differed on 4-7 of 420 items between
  runs, within its own nondeterminism. This evidence set the invalidation threshold at 3.5 x cores.


## Validity rules (what counts)

Only *gated* runs are reported as results:

- **Clean state per reset group.** Every group (one per participant for tap sets, 50-item chunks
  for swipes) starts from the emulator snapshot `gboard_bench_base` (Gboard clean, remote configuration
  synced, downloaded main LM); pboard is installed fresh (sha-verified) and `pm clear`ed. Gboard is
  never `pm clear`ed (that wipes its synced configuration, and the re-sync can fail or leave it on the
  older LM bundled in the APK).
- **Load gate.** A group starts only when the host's 1-minute load average is <= 1.5 x cores; load
  at start and end is recorded per group. On an oversubscribed host Gboard's decoder lags behind the
  replayed taps and commits literal words (seen as an uncorrected "becuase" probe), which biases
  results in pboard's favour.
- **Group validity.** A group is invalid - and excluded from the headline (`results_valid.jsonl`,
  `score.txt`; everything is still in `results.jsonl`, `score_all.txt`) - if its "becuase" probe was
  not corrected 3/3, if any item stayed timing-degraded after retries, if it is incomplete, if the
  host was overloaded while it ran (max load > 3.5 x cores or PSI memory "some avg10" > 25 %, sampled
  every 5 s), or if it overlaps a reported host incident (`tools/bench/out/overload_windows.txt`).
  Invalid groups are re-run from a fresh reset (`RESUME=1` keeps the valid ones).
- **Done detection.** Gboard: slow settle (field text stable for 250 ms after the last injected
  event, plus the stall checks). pboard: fast settle on the `bench` build (its probe reports touch
  ups processed, no engine work in flight, main thread idle), allowed because compare mode on every
  build under test shows 0 false-early items (the item's text at the fast signal equals the text at
  slow settle).
- **Determinism.** Replays are deterministic at sane load (an interrupted and a complete run of
  the same participant from the same state gave identical output for all 417 words). The gated
  check (200 TSI items, one device vs two shards on two different AVDs) is in the results below.

## Setup

| | |
|---|---|
| Devices (headline) | the emulator farm (48 vCPU, 94 GB): read-only instances of the golden AVD `gb18-1` from the shared pool (`tools/farm/pool.sh`), Pixel 8 profile, 1080x2400 @ 420 dpi, Android 16 Google Play image, headless, `swiftshader_indirect`; byte-identical state on every instance |
| Gboard (headline) | `18.6.3.1002251336-release-x86_64`, English (US) QWERTY, default height/theme; main LM `2026092216` (downloaded; bundled `2026080409`); settings at defaults |
| Devices / Gboard (secondary, Gboard 15.1) | local AVDs `pboard3` (emulator-5558) and `bench-1` (emulator-5570); Gboard `15.1.08.726012951-preload-x86_64` (the emulator image's), main LM `2025031300` (downloaded), not the bundled `2024102000`; settings at defaults (auto-correction on, next-word suggestions on, undo auto-correct on backspace off) |
| Gboard state | snapshot `gboard_bench_base`, made by `make_base_snapshot.sh`: reboot, one wipe right after boot, keyboard up, wait for the downloaded LM, synced-look check (?123 and Enter in the secondary-container colour (220,226,249), no tonal toolbar button), save |
| pboard | `bench` build type (= release + the done-detection probe) of the commit under test, built in its own worktree on the emulator farm, installed per group; geometry `pboard_pixel8.json` (identical key rectangles to Gboard's, suggestion slots within 1 px), re-measured on every build under test (0 px delta) |
| Field | `PlaygroundActivity` `EditText`, inputType `TEXT|CAP_SENTENCES|AUTO_CORRECT|MULTI_LINE`; prediction probes in the `incognito` variant |
| Geometry | `gboard_pixel8.json`: keyboard top y = 1517, key pitch 107 px, rows 1654-1777 / 1808-1932 / 1963-2087 / 2118-2243, slots x 538 (best) / 245 / 833 at y 1578 |
| Host | the emulator farm is shared (conformance and other jobs lease pool devices); 15.1 runs were on a 16-vCPU host |

### Autocorrect works through the injection path

`run.sh` probes every reset group ("becuase" + space on key centres, three times). The longer
probe `sets/gboard_pixel8/sanity_autocorrect.jsonl` (ten misspellings, context "I saw ") on the
snapshot state: recieve, becuase, thier, wierd, adn, definately, goign, yuo, teh, hte - all ten
corrected. With the older bundled LM (`2024102000`, what Gboard runs right after a mid-session
`pm clear`) `teh`, `adn`, `hte` stay uncorrected (offered in the strip only), which is why the
LM version is pinned and recorded. Gboard 15.1 never sets a composing region (it commits each
letter and applies corrections on space), so the harness reads the final field text.

## Method

Reproduce (devices = any emulators holding the snapshot; pboard APK = a release build + its commit):

```
tools/bench/gated_tsi.sh emulator-5570 emulator-5558,emulator-5570 <pboard.apk>:<commit> [...]   # determinism + TSI, both keyboards
AVD=pboard3 tools/bench/compare.sh emulator-5558,emulator-5570 swipe vtap synthetic               # the other sets, both keyboards
```

**Replay.** Per item the field is set to the item's `context` (unless it already holds it, i.e. the
previous word of the same sentence came out right, in which case typing simply continues), the
item's touches are injected with their own timestamps, and the field text is read once it has been
stable for 250 ms. Taps are DOWN/UP pairs held `holdMs` (45 ms when the trace has none, e.g. TSI);
swipes are one DOWN/MOVE.../UP stroke at the trace's sample times. Items whose real-time delivery
was stretched by host load (tap hold or swipe gap > 150 ms beyond the trace) are redone from a
reset, up to 3 times; if still stretched they are flagged timing-degraded (and their group invalid).

**Scoring** (`tools/bench/score.py`, case-insensitive, edge punctuation ignored): produced words =
field words after the item minus the context words. *Word accuracy* = produced exactly the target.
*WER* = word edit distance / target words. *False corrections* = among *clean* tap words (every letter
tap inside the intended key's hit box on this geometry, terminator on the space bar or period, no
apostrophe in the target), share the keyboard changed into something else. *Noisy recovery* = among
the other tap words, share it still turned into the target. *Missed space* = words whose recorded
terminator landed on the bottom letter row instead of the bar (reported separately).
*Next-word hit@k* = the target was what the best slot (k=1) or any of the three slots (k=3) inserted.

**Human sets** (`tools/data/human/splits/v1/*.test`, see `docs/eval-data.md`): `tap_tsi` - all 3,344
words of the 8 TSI test participants (Pixel 6 Pro taps re-projected key-relative onto this geometry),
including each person's real word terminator (~11 % end on the bottom letter row); `swipe_futo`,
`swipe_hws` - the first 400 swipes of each core test subset (<= 6 per person); `tap_vtap` -
Vertanen's noisy-typing participants (Nexus 4, a deliberately sloppy stress set).

**Synthetic sets** (`tools/bench/sets/gboard_pixel8/`, from `tools/bench/sentences/heldout_v1.txt`,
sentences written for this benchmark): the typist is the human fit from
`tools/data/human/typist_model.json` (the generator defaults): per-word isotropic tap noise sd
U(0.21, 0.28) key widths, mean offset 0.055 kw upward, lognormal inter-key interval (median 183 ms,
sigma 0.54, clamp 79-439 ms), holds lognormal around 45 ms, slips per letter: omission 0.3 %,
transposition 0.2 %, doubling 0.2 %; space bar hit 1.23 kw right of centre (sd 0.34 kw) and
0.32 kw high, 6.85 % of terminators on the bottom letter row; taps that would land on a function
key or the suggestion strip are redrawn. Swipes: via-point sd U(0.19, 0.29) kw, corner cut <= 0.10,
segment duration 230 ms + length / U(2.1, 5.0) kw per 100 ms (median word 826 ms, FUTO humans
797 ms), overlapping minimum-jerk velocity bells on a Catmull-Rom path, 60/90/120 Hz sampling,
1.5 px sensor noise; one-letter words are tapped. Prediction probes: 2 random positions in each of
the 77 sentences, previous word typed on key centres, field in incognito mode.

**Injection timing fidelity** (`am instrument -e mode timing`, `timing_report.py`; 600 ms swipes
onto the playground window, 5 per rate, measured at load ~40 on the earlier 8-core host):

| rate | samples scheduled | delivered with exact timestamp | extra resampled | inject late p50/p95 (ms) | dispatch latency p50/p95 (ms) |
|---|---|---|---|---|---|
| 60 Hz | 185 | 185 (100 %) | 101 | 3.7 / 65.9 | 14 / 70 |
| 90 Hz | 275 | 275 (100 %) | 115 | 5.7 / 61.3 | 15 / 68 |
| 120 Hz | 365 | 365 (100 %) | 119 | 4.5 / 64.2 | 13 / 71 |

Timestamps are exact by construction; real-time lateness comes from host load and is what the
stall retry and the load gate control.

## History (superseded runs, not results)

- Morning runs used `pm clear` resets: unverified configuration sync and the bundled LM; discarded.
- `gboard_human_tap_tsi_resetPerSet` (13:00-14:55, load ~17-25, snapshot reset once per set, so
  learning carried across participants): word accuracy 95.13 %, WER 5.50 %, false corrections
  0.95 %. Superseded by the per-participant run.
- `INVALID_highload_gboard_human_tap_tsi` (15:10-16:00, load 90-130): two groups, the second with
  290 retries and 49 timing-degraded items; discarded. At that load a "becuase" probe on bench-1
  came out uncorrected once and user01's 417 words differed in 2 places from the calm run.
