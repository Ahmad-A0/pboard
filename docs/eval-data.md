# Human evaluation data

pboard's decoder and benchmark were evaluated on synthetic taps and swipes. This document covers the
**real human typing data** that replaces or calibrates them: which public datasets exist, what they really
contain, which were adopted and under what licence, how they are converted into our trace format on the
Pixel-8 Gboard geometry, the fixed v1 evaluation splits, and a typist model fitted from the data.

Code: `tools/data/human/`. Downloads: `tools/data/raw/<source>/` (gitignored, treated as untrusted).
Verified 2026-11-08.

```sh
python3 -I tools/data/human/fetch.py        # ~0.7 GB into tools/data/raw/, sha256-pinned where it matters
python3 -I tools/data/human/build.py        # ~4 min: splits/v1 (committed) + out/v1 (local), manifest
python3 -I tools/data/human/fit_typist.py   # tools/data/human/typist_model.json (tune splits only)
python3 -I tools/data/human/validate.py     # projection fidelity + no participant in both splits
```

**Rule: `*.test.*` files are held out.** They are split by participant (or session or sender), never by
sample. Nothing that is tuned (decoder weights, thresholds, LM interpolation, generator parameters) may look at
them. Use `*.tune.*` for development and report final numbers on `*.test.*`.

## 1. Datasets adopted

| Source | What it is | Touch data | Layout / device recorded | Size used | Licence | Eval / tune | Training |
|---|---|---|---|---|---|---|---|
| **swipe.futo.org** (FUTO 2025, arXiv:2606.25247; HF `futo-org/swipe.futo.org`, config swipe-1) | Volunteers on their own phones swiped prompted words (Common Voice / Wikipedia sentences) on a web QWERTY canvas with 3 letter rows | Raw stroke, normalised x/y + ms timestamps at the browser's native rate (45-125 Hz) | Layout file (`swipe-5/layouts/qwerty.json`); canvas width × height in CSS px per swipe; orientation. No screen size or demographics | dev 54,269 + test 49,970 swipes (from 1.04 M; 12,278 sessions overall) | **MIT** | yes | yes (keep the copyright notice with redistributed data) |
| **How We Swipe** (Leiva, Kim, Cui, Bi, Oulasvirta, MobileHCI 2021; OSF `sj67f`) | 1,338 volunteers (TypingMaster users + social media) swiped 16 sentences each in a mobile web app: 1/3 EnronMobile memorable sentences, 2/3 random 4-word sequences (frequent / common / rare / out-of-vocabulary words) | Raw stroke in canvas px + ms timestamps (~60 Hz), touch radius and angle | Keyboard canvas width/height per event; layout reconstructed from the app source (`swipetest` js/keyboard-impl.js: 10 pitches across, rows h, h, h, 0.7h). Demographics, screen size, vendor, swipe finger/hand in `metadata.tsv` | 109,338 gestures, of which 85,417 accepted (dedup'd) | **MIT** (OSF node licence) | yes | yes |
| **Google TSI** (Lertvittayakumjorn, Cai, Dou, Ho, Zhai, UIST 2024; GitHub `google-research-datasets/tap-typing-with-touch-sensing-images`) | 16 regular smartphone typists copy-typed 80 MacKenzie-Soukoreff phrases + 40 random strings each on a **custom Gboard build on a Pixel 6 Pro** (auto-correct, prediction, key feedback disabled) | Touch-down centroid x/y (keyboard px), timestamp, touch ellipse, capacitive image; intended character aligned by the authors, deleted taps included | Key centres and sizes (`keyboard_data.json`), screen 1440×3120, keyboard 1440×854 at y=2098 | 43,735 taps → 6,665 word traces (phrases only) | **CC BY 4.0** | yes | yes, with attribution |
| **Vertanen & Kristensson noisy typing** (IUI 2023; OSF `5xwng`), phone part from VelociTap (CHI 2015) | Sentence-at-a-time touchscreen typing for a sentence decoder; participants typed fast without correcting. We use only `vt_exp2` *space* (Nexus 4, full-size keyboard, key pop-ups, spaces typed, 24 people) and `vt_exp3` *normal* (Nexus 4 60×40 mm keyboard, 12 people) | Every touch sample from down to up (x, y, ms); authors' forced alignment to reference words | Key centres/sizes per keyboard (`keyboards.json`) | 8,260 word traces from 36 participants | **CC BY 4.0** | yes (stress set: noisier than everyday typing) | yes, with attribution |
| **Wikipedia: Lists of common misspellings / For machines** | 4,310 `typo->fix` lines of frequent misspellings fixed by editors | none (text) | – | 4,266 usable pairs | **CC BY-SA 4.0** | yes | only if the derived artefact is itself CC BY-SA; do not bundle as-is |
| **EnronMobile** (Vertanen & Kristensson, MobileHCI 2011) | 2,239 genuine sentences e-mailed from BlackBerry devices by Enron employees, hand-corrected; the build files keep the raw messages next to the corrected ones | none (text) | – | 2,239 sentences (88 senders), 193 genuine typo pairs in context | **none stated** (Enron corpus is public record; the derived set has no licence) | **local only** (built into gitignored `out/`, never committed) | no |

Attribution text for everything committed is in `tools/data/human/splits/v1/ATTRIBUTION.txt`. If a model is
ever *trained* on TSI or the Vertanen data, add the CC BY attribution to `NOTICE`.

### Facts worth knowing about each adopted set

- **FUTO**: the publisher's splits are session-disjoint (session = short-lived anonymous ID; one person can
  donate in several sessions, so cross-split person leakage cannot be ruled out). We map *their dev → our
  tune* and *their test → our held-out test*, so anyone who later trains on FUTO's train split still has a
  clean test. Sentences are encyclopaedic (Wikipedia), so word choice is formal. FUTO removed swipes that
  failed its validity checks (~5 %, e.g. swipe and target word did not match), so very sloppy swipes are
  under-represented. 3.8 % of swipes are landscape and are dropped.
- **How We Swipe**: every word had to pass a lenient check (start/end within 1.25 key sizes of the first/last
  letter, every letter visited); failed attempts (18,283) had to be redone and are excluded, so this is the
  "accepted swipe" distribution. The random 4-word phrases are poor language-model context (`phraseSet` field:
  `enron` vs `random`). Some event batches were uploaded twice (5,638 duplicate gestures removed) and some
  words' events are missing; context is rebuilt from the sentence.
- **TSI**: the closest thing to "Gboard on a Pixel" that exists publicly. Only the first touch frame is
  published (no hold durations, no touch-up position). Taps not aligned to any prompt character (insertion
  errors) are not in the data. No shift taps either: targets keep the prompt's case, compare case-insensitively.
  Per-key visual and haptic feedback was off, which may slightly change behaviour.
- **Vertanen phone**: participants knew a sentence decoder would fix errors and were asked to be fast, so offsets
  are larger and heavier-tailed than everyday typing. 5 % of words have a tap count ≠ letter count (those are
  projected through the grid map instead of key-relatively; `alignedTaps=false`). Word groups exclude the
  delimiter taps, so a synthetic space tap ends each trace.

## 2. Candidates checked and rejected

| Candidate | What it actually contains | Why not adopted |
|---|---|---|
| Aalto **Typing37k** (Palin et al., MobileHCI 2019; userinterfaces.aalto.fi/typing37k) | 37,370 volunteers, web transcription task on their **own native keyboards**; keystroke press/release times, key value, input-field states, screen W/H, keyboard app, use of autocorrect/prediction; 4.7 GB raw SQL | **No touch coordinates** (browsers do not expose them for the system keyboard). Typed text already passed through each participant's own autocorrect. **No data licence** (code is MIT; consent text only says data "can be made publicly available as part of our scientific work"). Candidate for IKI statistics or "errors that survived autocorrect" only if the authors grant a licence. |
| **How We Type** (Jiang et al., CHI 2020) | 30 participants, Finnish QWERTY (å ö ä), tap x/y + key + timestamps, gaze and finger motion capture; ~32 k events | Finnish layout and text, tiny; licence conflicts (web page "open for anyone to use", README "non-commercial use"); key-coordinate frame looks offset from touch frame |
| **HMOG** (Sitová et al., TIFS 2016) | 100 users, touch events with x/y and key-press events on a phone keyboard, plus IMU | Non-commercial research licence, no redistribution, no keyboard geometry |
| **BB-MAS** (Belman et al. 2019, IEEE DataPort) | 117 users, desktop/tablet/phone keystrokes and swipes, CC BY | Phone keystrokes have key + timing only; x/y only for scroll swipes |
| **Yandex Cup 2023 NeuroSwipe** | Millions of Russian ЙЦУКЕН swipes with per-record key hit boxes | Russian layout; **no licence found** |
| **Invisible Mobile Keyboard (IMK)** (MIT) and **I-Keyboard** (no licence) | Tap coordinates on an *invisible* imagined keyboard | Not representative of typing on visible keys |
| **HuMIdb** (BiDA Lab) | 600 users, touch + keystroke (key and pressure only) | Signed licence agreement, no tap coordinates on keys |
| Buschek et al. (ResearchIME, CHI 2018), Weir et al. (UIST 2012), Azenkot & Zhai (MobileHCI 2012), Findlater & Wobbrock (CHI 2011/12), Henze et al. (CHI 2012, 47.8 M keystrokes) | Touch-typing studies with tap data | **No data released** (Findlater's were tablet ten-finger anyway) |
| Vertanen **eyes-free**, **VelociTap pilots**, `vt_exp1`, small/tiny keyboards, watch / VR / desktop parts of the IUI 2023 set | Blindfolded or no-feedback typing, unusual keyboard sizes, other devices | Not representative of phone typing with feedback |
| **GitHub Typo Corpus** (Hagiwara & Mita, LREC 2020) | 350 k typo edits from commits, per-repo (permissive) licences | Only download (S3) returns **404**; desktop keyboards and technical text anyway |
| **Birkbeck spelling error corpus** (Mitton; OTA 0643) and Norvig's `spell-errors.txt` | 36 k misspellings, mostly handwritten/cognitive errors | Licence not stated / OTA behind an access wall; cognitive spelling errors, not touch slips |
| **MacKenzie & Soukoreff 500 phrases** | Memorable phrases for text-entry studies | No licence; not mobile-representative (they appear as context in the TSI traces anyway) |
| Leiva & Sanchis-Trilles "representatively memorable" phrase sets | Phrase sets sampled from EnronMobile | Download URL returns 403 |
| Vertanen **mobile text dataset** (forum posts written on mobiles, 624 MB) | Mobile-written forum text with device metadata | No licence for the text (the LMs are CC BY); possible future larger target set |

## 3. Trace format and projection onto the Pixel-8 Gboard geometry

Every trace file is gzip JSONL in the benchmark's format:

```json
{"context": "My ", "target": "preferred", "kind": "tap", "points": [[x, y, tMs], ...], "id": "...",
 "src": "tsi", "participant": "user01", "split": "test", ...annotations}
```

`points` are **screen pixels of the target geometry** (`tools/bench/geometry/gboard_pixel8.json`, Gboard on a
Pixel 8, 1080×2400 @ 420 dpi; a byte-identical snapshot is vendored as `tools/data/human/geometry/` until the
benchmark branch lands, sha256 in the manifest). `t` is ms from the item's first touch (original timing kept).
For taps every point is a touch-down and the last point ends the word (`terminator`: `space`, `period` or
`synthetic` = added by us, at the space-bar centre 250 ms after the last tap). For swipes the points are one
continuous stroke. Annotations: `intended` (keys meant), `literal` (keys actually touched on the participant's
own keyboard), `offsetsKu` (per-tap offset from the intended key, key units), `holdMs` (Vertanen only),
`correctedInWord`/`finalLiteral`/`terminatorKeyOnTarget` (TSI), `clampedPts`, `srcKeyAspect`, `srcCanvasPx`,
`phraseSet` (HWS).

**Key units.** All source layouts are QWERTY with the standard stagger (row 2 offset ½ key, row 3 offset 1½).
Positions are expressed in key pitches horizontally (centre-to-centre of adjacent letters = hit-box width) and
row pitches vertically. Source and target pitches are fitted from the letter centres (target: 107 × 154.75 px).

- **Taps are projected key-relatively**: offset from the *intended* key's centre on the participant's keyboard,
  in that keyboard's pitches, re-applied around the same key on Gboard. Space-bar taps are mapped as a fraction
  of the bar width (TSI's bar is 5 pitches wide, Gboard's 4), the period key like a letter. Check: the key under
  the projected point equals the key the participant touched for 99.9 % (TSI) / 99.4 % (Vertanen) of taps.
- **Swipes are projected through the per-axis affine map** between the two letter grids (no single intended key
  per sample; this map equals the key-relative map at every key centre, so shapes stay continuous).
- Points that fall outside the Gboard keyboard window after projection are clamped into it (`clampedPts`).

**Caveats.**

1. *Key aspect ratio.* Row pitch / key pitch is 1.45 on Gboard-Pixel 8, 1.53 on TSI (Pixel 6 Pro), 1.51 on
   Nexus 4, 1.40 median on FUTO canvases (p05-p95 1.16-1.60, set by phone width) and 1.58 on How We Swipe
   canvases (1.39-1.70). Mapping per-axis rescales x and y independently, so stroke slopes change by up to
   ~20 % (typically < 10 %) and a circle becomes a slight ellipse. Distances in key widths are preserved
   horizontally and in key heights vertically. `srcKeyAspect` is stored per swipe so results can be stratified.
2. *Physical size.* Expressing noise in key units assumes people's spread scales with the key size they see.
   Physical pitches are similar (Pixel 8 6.5 × 9.4 mm, Pixel 6 Pro 6.7 × 10.2 mm, Nexus 4 6.1 × 9.2 mm; web
   canvases 35-49 CSS px ≈ 5.6-7.8 mm wide), so the error of this assumption is small, but it is not zero.
3. *Edges and special keys.* Source keyboards differ in side margins (TSI 45 px each side, canvases none, Gboard
   5 px) and in which special keys exist. A tap far left of `a` stays far left on Gboard; the few that land on a
   Gboard-only key (shift, delete, comma, emoji) are kept as is: that is what would happen on Gboard.
4. *Swipe canvases had no space row/suggestion strip* (FUTO: 3 letter rows only); strokes leaving the canvas
   are clamped to the Gboard window (only 2-7 of 3,000 swipes per core file have any clamped sample).
   Browsers deliver coalesced pointer events at 45-125 Hz; Android delivers MotionEvents at up to the display
   rate (120 Hz on a Pixel 8) with batched historical samples. No resampling is done.
5. *Feedback differs.* None of the swipe sources showed live suggestions, and TSI had no key pop-ups; people may
   behave differently with Gboard's live preview. Vertanen participants typed for a sentence decoder (sloppier).
6. *Replay.* Both sets can be replayed on pboard (JVM, feed points into `suggestForTaps/suggestForSwipe`) and on
   real Gboard (emulator, `adb shell input motionevent`). For TSI there are no hold times; use the fitted hold
   distribution (`typist_model.json → tap.vtap.timing.holdMs`, median 45 ms). About 7 % of real TSI word
   terminators land above the space bar on the bottom letter row, as they did for the participant.
   `terminatorKeyOnTarget` says where each one lands on Gboard. To measure word decoding only, snap those taps
   to the space-bar centre; to measure end-to-end behaviour, keep them.

## 4. Evaluation splits v1

All in `tools/data/human/`; `splits/v1/manifest.json` lists every file with item and participant counts and
sha256 (also for the local files, so a rebuild can be verified).

| Set (`<set>.tune` / `<set>.test`) | Kind | Source | Tune: items / participants | Test: items / participants | Where |
|---|---|---|---|---|---|
| `tap_tsi` | tap words (first pass + terminator) | TSI | 3,321 / 8 | 3,344 / 8 | `splits/v1`, committed |
| `tap_vtap` | tap words, stress set | Vertanen | 4,442 / 18 | 3,818 / 18 | `splits/v1`, committed |
| `swipe_futo` | swipe, core subset (≤ 6 per session) | FUTO | 3,000 / 601 sessions | 3,000 / 613 sessions | `splits/v1`, committed |
| `swipe_hws` | swipe, core subset (≤ 6 per user) | How We Swipe | 3,000 / 618 users | 3,000 / 606 users | `splits/v1`, committed |
| `swipe_futo_full` | swipe, everything | FUTO | 50,979 / 650 | 46,810 / 653 | `out/v1`, local |
| `swipe_hws_full` | swipe, everything | How We Swipe | 40,436 / 650 | 41,698 / 656 | `out/v1`, local |
| `text_typos` | `{context, typed, target, error}` | TSI + Vertanen taps | 7,689 (1,925 misspelt) / 26 | 7,053 (2,111 misspelt) / 26 | `splits/v1`, committed |
| `text_wikipedia` | `{typed, target, targets}`, no context | Wikipedia | 2,105 | 2,161 | `splits/v1`, committed (CC BY-SA) |
| `text_enron_typos` | genuine mobile typos in context | EnronMobile | 80 / 16 senders | 113 / 21 senders | `out/v1`, local |
| `sentences_enronmobile` | target sentences (`.jsonl.gz` + `.txt`) | EnronMobile | 1,093 / 43 senders | 1,146 / 45 senders | `out/v1`, local |

Split units: TSI and Vertanen are split exactly half/half by participant (ranked by a salted hash). How We Swipe
is split by user hash. FUTO follows the publisher's session-disjoint dev/test. Wikipedia is split by correct
word and EnronMobile by sender. The core swipe subsets (6,000 swipes per source, ~1,500 distinct words each) are
sized for replay on the Gboard emulator; use the `_full` sets for decoder-only runs on the JVM. `validate.py`
confirms that no participant appears on both sides and that ids are unique.

Small-sample warning: the 8 + 8 TSI participants differ a lot. 20 % of tune words but 30 % of test words have a
first-pass key sequence that misspells the target. Report TSI results per participant as well as pooled.

**Text-level autocorrect sets.** `text_typos` holds what the participants' fingers actually spelled (keys
touched, decoded on their own keyboard, before any correction) with sentence context, for every word, so it
measures both fixing errors and not breaking correct words (`error` flag). `text_wikipedia` adds frequent
editor-fixed misspellings without context (all spellings of one correct word on the same side).
`text_enron_typos` (local) are genuine mobile typos in context. **Prediction / keystroke savings**:
`sentences_enronmobile` (local; `.txt` copies use the benchmark's `# --- section:` format). EnronMobile is not in
the LM corpus list used on the LM branch (OASST, Topical-Chat, Taskmaster, MultiWOZ, SGD, SMS Spam, FineWeb,
Tatoeba); FineWeb could in principle contain Enron text, so check n-gram overlap before quoting perplexities.

## 5. Typist model (`tools/data/human/typist_model.json`)

Fitted by `fit_typist.py` on the **tune** splits only (TSI 8 participants / 14.5 k letter taps; Vertanen 18 /
16.1 k; FUTO 20 k swipes / 626 sessions; HWS 20 k swipes / 646 users). Tap offsets are key-relative on the
participant's own keyboard (x in key pitches, y in row pitches); swipe values are on the Gboard geometry in key
widths (kw = 107 px) and rows (154.75 px). `genTracesArgs` maps the fit onto `tools/bench/gen_traces.py` flags
and lists what that generator cannot express yet (`extensions`).

In the table, "Synthetic default" means `gen_traces.py` on the benchmark branch with its default flags. Its
swipe values were measured by running the same analysis code on 669 swipes from that generator.

| Quantity | Human | Synthetic default |
|---|---|---|
| Tap offset sd, letters (TSI) | x 0.25 kw, y 0.167 row (= 0.24 kw, so isotropic in mm); per person x 0.21-0.28 kw | isotropic, U(0.2, 0.4) kw per word |
| Tails | MAD σ 0.23 / 0.16, excess kurtosis 1.3 / 1.7 (TSI); 80 / 12 (Vertanen) | Gaussian |
| Taps outside the intended key | 5.7 % (TSI), 10.2 % (Vertanen) | 12.2 % |
| Mean offset | (-0.03 kw, -0.04 row) on Pixel 6 Pro/Gboard (TSI); (0.00, +0.13 row) on Nexus 4 | (0, +0.05 kw) |
| Per-key mean offsets (TSI) | up to 0.19 kw: `a` -0.19 kw (towards the screen edge), `z` +0.17 kw (away from shift), middle/bottom rows -0.07/-0.09 row (high) | none |
| Per-person bias | sd of participant means 0.06 kw / 0.05 row (6 % / 10 % of the variance) | none |
| Space taps (TSI) | +0.31 of the bar width right of centre (+1.2 kw on Gboard), sd 0.08 bar (0.34 kw); -0.22 row (upper half) | centred, sd 0.6 kw |
| Word terminator lands on a letter (TSI) | 6.9 % (above the bar, bottom letter row) | 0 % (clamped into the bar) |
| Words whose touched keys misspell the target | 20 % TSI tune (30 % test), 30 % Vertanen; 6 % corrected mid-word (TSI) | |
| Inter-key interval (TSI) | median 183 ms, lognormal σ 0.54, p05-p95 79-439 ms | 150 + 45·log2(1+d) ms, σ 0.18, clamp 110-420 |
| Distance effect / thumbs (TSI) | none (-4 ms per bit); next key on the other half of the keyboard 141 ms vs same half 214 ms; double letter 172 ms | Fitts slope 45 ms per bit, no hand model |
| Same, Vertanen (Nexus 4, 2015) | 63 ms per bit; cross-half slower (287 vs 215 ms): mostly one-finger typing | |
| Hold (touch down-up, Vertanen) | median 45 ms, lognormal σ 0.45, p05-p95 24-99 ms | N(75, 15) ms |
| Within-word typing rate (TSI) | 63 wpm median per participant (50-81); excludes the pause after each space | |
| Swipe duration, median | 797 ms (FUTO), 1,114 ms (HWS) | 542 ms |
| Swipe duration model (ms) | -18 + 183·segments + 19.7·kw (FUTO); 271 ms/segment (HWS) | 157 + 35·segments + 28·kw |
| Mean swipe speed | 1.57 kw/100 ms median (FUTO; HWS 1.22); per person p05-p95 0.79-2.33 | 1.64 (IQR only 1.40-1.84) |
| Speed over the stroke (20 bins) | slow start 0.38 → early peak 1.30 → mid-word dip 0.86 → 0.94 → slow end 0.43 | 0.13 → plateau 1.26-1.31 → 0.36 |
| Path length / key-centre polyline | 1.07 median, p95 1.34 (FUTO); 1.13 / 1.53 (HWS) | 0.92 (spline cuts corners) |
| Corner cut (closest approach pulled towards neighbours' midpoint) | median 0.03, mean 0.05, p95 0.22 | median 0.14, p95 0.31 |
| Interior keys passed > 0.5 kw away | 16 % (FUTO), 13 % (HWS) | 42 % |
| Start point | offset sd 0.28 kw × 0.17 row, +0.07 row low; reaches 0.2 kw of motion after 67 ms | sd 0.23 kw × 0.16 row; 89 ms |
| End point | sd 0.51 kw × 0.25 row (twice the start); **overshoot** along the last segment +0.12 kw median, p95 +0.91 kw | sd 0.23 × 0.15; overshoot 0 |
| Touch sampling | 45-125 Hz (FUTO median 67), ~59 Hz (HWS) | 60/90/120 Hz |

The `genTracesArgs` block holds these values: `sigma 0.21:0.28`, `bias_y -0.055`, `iki_base 183`,
`iki_slope 0`, `iki_jitter 0.54`, `iki_min 79`, `iki_max 439`, `hold_mean 45`, `hold_sd 20`,
`space_sigma_x 0.34`, `swipe_sigma 0.19:0.29`, `corner_cut 0.10`, `seg_base 230`, `swipe_speed 2.14:4.99` and
`swipe_dwell 10`. With them the generator comes close to the human data:

| Measure | Generator with `genTracesArgs` | Human |
|---|---|---|
| Taps outside their key | 4.7 % | 5.7 % (TSI) |
| Median swipe | 784 ms, 162 ms per extra segment | 797 ms / 183 ms (FUTO) |
| Corner-cut mean | 0.059 | 0.049 |
| Missed interior keys | 12 % | 16 % |

The generator still cannot produce the end overshoot, the wider end spread, the speed profile, per-key offsets,
per-person bias, the space-bar shift or thumb alternation (listed under `genTracesArgs.extensions`).

### How real typing differs from Gaussian noise around key centres

1. **Synthetic taps are too noisy on average and too uniform in shape.** Human letter taps on Gboard/Pixel
   spread 0.25 kw in x and 0.24 kw in y (the same in mm), so 5.7 % land on another key; the default generator
   puts 12 % on another key. Humans have moderately heavy tails instead (kurtosis 1.3-1.7, far heavier for
   Vertanen's fast typists), so rare far-off taps matter more than a Gaussian predicts.
2. **Errors are systematic, not only random.** Each key has its own mean offset (edge keys `a` and `z` by about
   0.2 kw, bottom rows hit high on the Pixel), and each person has a bias of about 0.06 kw. A decoder tuned on
   zero-mean noise cannot learn these, and evaluating on such noise overstates how much per-key priors help.
3. **The vertical bias depends on the device.** On the Pixel 6 Pro (Gboard build) taps sit 0.04 row *above*
   the key centre; on the Nexus 4 they sit 0.13 row *below*. The generator's fixed +0.05 kw downward bias has
   the wrong sign for Gboard on a Pixel. Do not hard-code a vertical bias in the decoder; learn it per user on
   the device.
4. **Timing comes from thumb alternation, not Fitts' law.** Two-thumb typists (TSI) show no distance effect.
   An interval is about 70 ms shorter when the next key is on the other half of the keyboard. Intervals vary
   three times more (σ 0.54 vs 0.18 log units), with a p05 of 79 ms that the generator's 110 ms clamp forbids.
   Holds are about 45 ms, not 75. Check timing-based features (e.g. "fast tap means less precise") on human
   data before trusting them.
5. **The space bar is hit off-centre.** Taps land about a third of the bar width right of centre (right
   thumb) and in its upper half.
6. **Real swipes are slower and dominated by corners.** Each additional segment costs ~180-270 ms, while path
   length costs only ~20 ms per key width. Synthetic swipes cost 35 ms per segment, so they are ~1.5× too fast
   for long words. Speed rises quickly, dips mid-word and tails off; it is not a symmetric plateau. Per-person
   speed varies 3× (0.8-2.3 kw/100 ms).
7. **Real swipes hug the interior keys but are sloppy at the ends.** Real paths are *longer* than the key-centre
   polyline (1.07-1.13×) and pass within 0.5 kw of 84-87 % of interior keys. The generator's spline and corner
   pull make paths shorter than the polyline and miss 42 % of interior keys, so synthetic swipes are harder in
   the middle than real ones. At the end the finger keeps going: the median overshoot is +0.12-0.19 kw past the
   last key (p95 +0.9 kw), and the end point spreads twice as much as the start. 28-30 % of real swipes start
   or end nearest a key other than the intended first/last letter, so decoders must not anchor hard on the last
   sample.
8. **Real errors are word-level events.** In TSI, typists corrected 6 % of words mid-word, and the first-pass
   key sequences of 20-30 % of words misspell the target. These are real slips (`trrat`, `xoubtry`, `peodit`).
   Of the misspelt TSI tune words, 74 % have a single neighbouring-key substitution, 15 % several
   substitutions, 6 % an omission and 3 % a transposition; substitutions by a non-adjacent key are rare (0.5 %).
   For Vertanen the shares are 63 / 17 / 4 / 1 %, plus 4 % insertions; 5 % of Vertanen words have more or fewer
   taps than letters (mostly extra taps). Per word, humans show about 1.2 % omissions, 0.3-0.7 %
   transpositions and ~1.2 % insertions (Vertanen). The generator's per-character slip probabilities (1.5 %
   omission, 1 % transposition, 1 % doubling) give roughly 6 %, 4 % and 4 % of words, which is 3-6× too many.
   Its substitutions come only from Gaussian noise, so they are not concentrated on neighbouring keys.
9. **The space bar is missed too.** 6.9 % of TSI word-terminating taps land above the space bar on the bottom
   letter row, which glues words together on a real keyboard. The generator never misses the space bar.

## 6. Limitations and next steps

- Only 16 TSI participants (8 held out): tap-level per-key statistics are solid, per-person variation is not.
  The 36 Vertanen participants are noisier than everyday typing. TSI has no insertion errors (extra taps);
  Vertanen's appear only in the 5 % of words whose taps could not be aligned one-to-one.
- All swipe sources are browser canvases without a live decoder; none is Gboard itself.
- Text sets are small for mobile in-context typos (193 Enron pairs). Typing37k would be the large source if its
  authors confirm a licence; ask before using.
- `gen_traces.py` should gain: per-key mean offsets, a per-participant bias, thumb-alternation timing
  instead of a Fitts slope, lognormal holds, space taps biased to the right of the bar (and occasionally above
  it), lower slip rates, swipe end overshoot and wider end spread, per-corner time cost and a non-constant speed
  profile.
- Rebuilding requires the pinned downloads; FUTO's and TSI's files are hashed in `fetch.py`/the manifest. The
  Wikipedia list is fetched live (its hash for v1 is in the manifest), so a later rebuild of `text_wikipedia`
  may differ. Keep the committed v1 files as the reference.
