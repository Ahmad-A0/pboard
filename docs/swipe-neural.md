# Neural gesture decoding

pboard decodes a glide with a small recurrent network trained on real human swipes, the approach published by
Alsharif, Ouyang, Beaufays, Zhai, Breuel and Schalkwyk ("Long short term memory neural network for keyboard
gesture decoding", ICASSP 2015) and Ouyang et al. ("Mobile keyboard input decoding with finite-state
transducers", 2017): an LSTM reads the touch sequence and emits per-frame letter posteriors trained with CTC; a
beam search constrained to the lexicon turns them into words, and the n-gram language model ranks them. Everything
here is our own implementation from those papers; nothing comes from Gboard. The SHARK² template matcher
(`GestureDecoder`) stays as the fallback (non-QWERTY layouts, no model) and as an optional second opinion.

Code: `engine/src/main/kotlin/dev/pboard/engine/swipe/` (`SwipeFeatures`, `SwipeModel`, `NeuralSwipeDecoder`),
training in `tools/swipe/`, evaluation `NeuralSwipeEval` (`./gradlew :engine:swipeEval`).

**Status: on by default** (`SwipeParams.neural`), model `qwerty.pbsw` (version 1, 0.57 MB; the layout-agnostic
version 2 is implemented and evaluated, see section 7). It beats the current template
matcher (main's CMA-ES re-fit) on both human TEST sets with the production en-GB language model and the engine's
neural-LM re-rank, and its p99 decode on the dev phone is 22 ms.

### Results (human TEST splits, never used for training or tuning)

Core TEST subsets (3,000 swipes each, `tools/data/human/splits/v1`), production configuration: en-GB n-gram LM plus
the LSTM re-rank of the final 16 candidates (`EngineConfig.neuralRerankTopN`, `--nnlm`), top-1 / top-3 (%):

| | FUTO template | FUTO **neural** | HWS template | HWS **neural** |
|---|---|---|---|---|
| all words | 89.20 / 94.40 | **92.80 / 95.77** | 79.03 / 86.40 | **81.17 / 87.50** |
| 2 letters | 96.06 / 97.93 | 97.51 / 98.34 | 87.43 / 92.08 | 83.88 / 89.89 |
| 3–4 letters | 95.26 / 98.18 | 96.57 / 98.39 | 81.83 / 89.72 | 82.11 / 90.29 |
| 5–6 letters | 84.84 / 91.68 | 91.08 / 95.69 | 73.01 / 82.79 | 80.18 / 87.09 |
| 7+ letters | 81.73 / 90.16 | 87.12 / 91.33 | 77.33 / 82.97 | 79.66 / 83.21 |
| target in the 6-word list | 95.60 | 96.73 | 88.63 | 89.30 |
| in-vocabulary targets only | 91.11 / 96.42 | 94.79 / 97.82 | 82.76 / 90.47 | 84.99 / 91.62 |

Neural column: the current default, including the two-key template weight 1.0 chosen on TUNE in the v2 round (the
first merge, with 0.5, measured 92.80 / 95.80 and 81.23 / 87.53, two-letter HWS 84.43). Out-of-vocabulary targets
(not in the production lexicon, so no lexicon decoder can produce them): FUTO 2.10 %, HWS 4.50 %. Word length = letters typed (`En.keys`). The only place the template matcher stays ahead is How We
Swipe's two-letter items, which are mostly random abbreviations from its out-of-vocabulary phrase set ("tk", "rn",
"jc", "sr"): the network's learned spelling prior pulls them to common words; the template second opinion recovers
part of that.

History of the comparison (core TEST, top-1, n-gram LM only): against the template matcher this work started from
(hand-set weights) FUTO 85.43 → 92.53 and HWS 74.47 → 80.53; against main's CMA-ES re-fit 88.10 → 92.43 and
78.90 → 81.33. On the full TEST sets (`out/v1`, FUTO 46,810 and HWS 41,698 swipes, hand-set template, n-gram only):
FUTO 85.29 / 92.11 → 91.98 / 95.61 and HWS 74.50 / 83.52 → 81.47 / 87.33 (top-1 / top-3); by length FUTO 2 / 3–4 /
5–6 / 7+ letters 98.00 / 95.24 / 88.92 / 87.16, HWS 83.42 / 83.04 / 81.09 / 78.94 top-1.

TUNE (core, production configuration): template 88.63 / 94.10 (FUTO), 77.90 / 85.93 (HWS); neural 92.07 / 95.70,
81.30 / 87.60.

**Latency on the dev phone** (arm64, Snapdragon 732G, ART via `app_process`, one thread; full
`suggestForSwipe` from the raw path: features, encoder, beam search, n-gram LM, template second opinion, neural-LM
re-rank of the top 16; context warmed by `predictNext` before each swipe as the IME does after the previous commit
(`--warm-context`); core TUNE swipes, ms):

| | p50 | p95 | p99 | warm-up (first use) |
|---|---|---|---|---|
| template matcher, FUTO / HWS | 4.8 / 4.9 | 13.7 / 13.7 | 18.4 / 17.8 | 527 / 250 |
| **neural**, FUTO / HWS | 8.3 / 9.3 | 19.8 / 20.7 | **21.9 / 22.1** | 623 / 475 |

Without the re-rank the neural decode is p99 21.3 / 21.6 ms. With the re-rank on a *cold* context (no prefetch: every
swipe in a new sentence context) both decoders pay for the LSTM steps of the context: p99 41.5 / 29.4 ms (neural)
and 36.0 / 24.1 ms (template), which is the re-rank's cost, not the decoder's.

The encoder dominates and its cost is fixed by the stroke length (p99 stroke 150 frames → 19.8 ms), so the tail
is flat. Live previews: the IME asks for a preview every 60 ms while the finger moves, each a full decode that
finishes well inside that interval; a newer request cancels an older one between encoder layer passes and every
16 frames of the beam search, so a preview never holds up the final decode for more than a few ms.

**Both Gboard size sets:** the input is in grid units, so the 350–399 dp set (row pitch 139 px, key aspect 1.30)
and the ≥ 400 dp set (Pixel 8, 1.45) give the same features up to rounding; `NeuralSwipeDecodingTest` checks a
shortened keyboard, and `--geometry phone` in `NeuralSwipeEval` replays the human sets on the phone's size set.

## On the device: the head-to-head gap and its two causes

The device head-to-head (docs/bench/gboard-baseline.md; the emulator farm, emulators, the first 400 swipes of each core TEST
subset replayed through both IMEs) measured pboard `b627b57` at 69.0 % on HWS against Gboard 18.6's 75.75 %
(FUTO 83.75 vs 86.0), while the engine alone scores 81.25 % on those very items. A paired analysis of the HWS
items (`NeuralSwipeEval --dump`, the bench's `results_valid.jsonl`):

- Gboard right and pboard wrong on 48 items, the reverse on 21. The 48 were spread over all lengths (2: 4, 3–4: 17,
  5–6: 14, 7+: 13) and phrase sets (EnronMobile 19, random words 24), so neither short words, abbreviations nor
  out-of-vocabulary targets explained them.
- On 61 items the engine was right on the JVM and the device wrong, and the device output was never the engine's
  top choice: the decoder and the language model were not the problem. The typical device error is the right shape
  with the first key replaced by its neighbour in the direction of travel ("done" → "fine", "spin" → "doin",
  "proud" → "oreos", "mary" → "nary"), from strokes that start on the right key.
- Two pipeline faults reproduce it. Replaying them on the JVM (`--hit-centres --ime-sim`) gives 71.25 % against
  the device's 69.0 % and the device's exact wrong word on 28 of the 61 items:
  1. **The neural decoder never ran on the device.** The IME describes keys by their hit boxes
     (`BuiltKeyboard.engineGeometry`); the `a` and `l` boxes run to the screen edge, so their centres sit 0.28 key
     from the drawn keys, and `SwipeFeatures.fitQwerty` (0.2-key tolerance) declared the layout non-QWERTY. Every
     swipe went to the template-matcher fallback. Fixed: the fit uses the interior letters; the row-end letters may
     be 0.45 key off horizontally (whole-key moves such as QWERTZ are still rejected).
  2. **The IME dropped the start of glides.** Before the 23 dp glide threshold, sliding onto a neighbouring key
     re-pressed that key and cleared the path (`KeyboardView`), so a glide starting near a key edge reached the
     decoder without its first letter. Fixed: the path keeps its touch-down.
- The remaining device-only failures are 12 slow starts that opened the 300 ms long-press panel (outputs "ä", "ß",
  "4"); Gboard fails several of the same items the same way, and the delay is Gboard's, so it is unchanged.

Before / after under the device's conditions (hit-box geometry, production configuration, JVM), top-1 / top-3:

| | FUTO core TEST | HWS core TEST | FUTO head-to-head 400 | HWS head-to-head 400 |
|---|---|---|---|---|
| before (as benched) | 84.13 / 91.50 | 74.57 / 83.50 | 84.75 (device 83.75) | 71.25 (device 69.0) |
| after (both fixes) | 92.90 / 95.77 | 81.03 / 87.70 | 93.25 | 81.25 |
| Gboard 18.6 on the device | | | 86.00 | 75.75 |

TUNE (chosen there first): FUTO 84.13 → 92.17, HWS 72.87 → 81.27 top-1; the fit fix alone gives 90.67 / 78.90.
Dev phone with the hit-box geometry (the neural decoder now runs), re-rank on, warm context: p50 8.4 / 9.4 ms,
p99 22.1 / 22.2 ms (FUTO / HWS TUNE), same accuracy as the JVM. A device re-run of the head-to-head will confirm.

## External reference: FUTO Swipe's released models

FUTO published a layout-agnostic CTC swipe encoder (`honorable_sturgeon`, 635k parameters, TCN), an English/QWERTY
refinement decoder (`magic_macaw`, 304k) and a context LM (FUTO Model Weights License 1.0, which permits evaluation;
anything derived from their weights or outputs would be bound by it, so nothing of theirs is shipped, distilled or
trained on). `tools/swipe/futo_bench.py` runs them on our human sets (ExecuTorch on the host; input prepared from the
model card's description: our grid units scaled to their three-row frame, 60 Hz then 64 points) and stores their
per-frame emissions; `NeuralSwipeEval --emissions` then decodes those with **our** trie beam search, lexicon,
language model and scoring, so only the encoder differs. Their context LM is not included: its hash-embedding input
is specified only in their GPL library, which we do not copy; our n-gram LM plays that role for every encoder.
Their models were trained on FUTO's train split (our TUNE/TEST are their val/test), so the comparison is clean;
the LM weight was tuned per encoder on TUNE.

TEST (core), production configuration for every encoder (our trie beam search, lexicon, n-gram LM, neural-LM
re-rank and template second opinion), top-1 / top-3 (%):

| Encoder | FUTO all | FUTO 2 / 3–4 / 5–6 / 7+ (top-1) | HWS all | HWS 2 / 3–4 / 5–6 / 7+ (top-1) |
|---|---|---|---|---|
| template matcher only (no encoder) | 89.20 / 94.40 | 96.06 / 95.26 / 84.84 / 81.73 | 79.03 / 86.40 | 87.43 / 81.83 / 73.01 / 77.33 |
| **ours** (`qwerty.pbsw`, 286k) | **92.80 / 95.80** | 97.72 / 96.47 / 91.08 / 87.12 | 81.23 / 87.53 | 84.43 / 82.11 / 80.18 / 79.66 |
| FUTO encoder (635k) | 90.97 / 94.17 | 96.47 / 96.77 / 87.67 / 83.72 | 81.33 / 87.77 | 88.80 / 83.06 / 79.40 / 77.57 |
| FUTO encoder + decoder (939k) | 92.33 / 95.80 | 97.10 / 96.37 / 89.45 / 87.24 | **83.57 / 89.20** | 87.43 / 85.73 / 82.14 / 80.39 |

Encoders alone (TUNE, our decoder without the template opinion or re-rank; best LM weight 0.8 for all three):
FUTO 91.57 (ours) / 89.27 (FUTO enc) / 91.10 (FUTO enc+dec) top-1, HWS 79.23 / 79.90 / 82.27. Lexicon only, no LM:
FUTO 88.30 / 77.07 / 86.53, HWS 72.33 / 67.23 / 75.57. A wider beam (100, no pruning) changes none of these by
more than 0.2.

Reading: on FUTO-style text our smaller model is as good or better; on How We Swipe (other people, other canvas,
random and rare words) FUTO's encoder+decoder is about 2 points better, all of it on 2–4-letter words. Their TCN
has a short receptive field and they train with heavy layout co-augmentation (flips, rotations); our BiLSTM sees
the whole word and learns a spelling prior that helps on dictionary words and hurts on abbreviations. Two cheap
attempts to weaken that prior inside our architecture did not help (time reversal with reversed targets at
p = 0.15 / 0.3, and twice the geometric augmentation: greedy accuracy 1.5–4.5 points lower, engine top-1 lower on
both sets). The likely route is FUTO's: a layout-agnostic spatial head with co-augmented layouts (section 7).
FUTO reports 92.9 top-1 on its own test split; our numbers differ because their trie contains every test word,
while ours is the production lexicon (2.1 % of FUTO targets are out of vocabulary).

## 1. Input: key-relative frames

1. **Grid units.** The 26 letter keys of the current layout are fitted (least squares, per axis) to the canonical
   staggered QWERTY grid (q = (0.5, 0.5), a = (1.0, 1.5), z = (2.0, 2.5)). Touch points become (u, v) in key
   pitches and row pitches. This is the key-relative geometry the evaluation projects every human stroke onto
   (`tools/data/human/common.py`), and it removes screen size, keyboard height and key aspect ratio. A layout whose
   letters do not sit on that grid (any key more than 0.2 pitches off) gets the template decoder instead.
2. **Frames.** The stroke is resampled along its length: a frame every 0.30 grid units of arc length, or every
   40 ms while the finger rests (dwelling on a letter still yields frames), with linear interpolation, so the
   frames do not depend on the touch sampling rate (45–125 Hz in the data, up to 120 Hz on phones). Very long
   strokes are capped at 192 frames by scaling both steps. Human strokes give a median of 46 frames, p90 110,
   p99 150.
3. **Features (33 per frame):** position, motion since the previous frame (in steps), elapsed time, first/last
   flags, and a Gaussian proximity (σ 0.55 grid units) to each of the 26 letter keys.

`tools/swipe/swipefeat.py` is the reference; `NeuralSwipeTest` checks the Kotlin port against vectors it writes.

## 2. Network

```
features [T × 33]
  → Conv1D (kernel 4, stride 2) + ReLU            halves the frame rate: [T/2 × conv]
  → BiLSTM × layers (hid units per direction)
  → Linear → log-softmax over 27 classes          CTC blank + a..z  [T/2 × 27]
```

The shipped model (`engine/src/main/resources/dev/pboard/engine/swipe/qwerty.pbsw`, 0.57 MB) has conv 96, two
BiLSTM layers of 80 units, 286k parameters: 21 M multiply-adds for a p99-length stroke (150 frames).

Architecture sweep (15 epochs each, same data and augmentation; greedy = CTC best path equals the target's key
sequence, no lexicon; dev = the core TUNE subsets; phone = encoder only on the dev phone at 150 frames):

| Model | Params | Greedy FUTO / HWS | Engine top-1 FUTO / HWS (TUNE, 12k each) | Phone encoder, 150 frames |
|---|---|---|---|---|
| conv 64, 2 × BiLSTM 64 | 178k | 79.2 / 65.1 % | 91.79 / 81.43 | 12.6 ms |
| **conv 96, 2 × BiLSTM 80 (shipped)** | 286k | 82.4 / 66.6 % | **92.13 / 81.61** | 19.8 ms |
| same, distilled from the large model (fine-tuned) | 286k | 82.4 / 67.1 % | 91.97 / 81.43 | 19.8 ms |
| conv 128, 3 × BiLSTM 256 (not deployable: 8 MB, ~140 ms) | 3.98M | 87.1 / 72.1 % | 92.17 / 80.27 ¹ | — |

¹ core TUNE (3000 each), template second opinion on. conv 64 × 3 layers and conv 96 + 1 × BiLSTM 128 were
stopped early (worse than conv 96 / 80 at equal steps). A 14× larger network gains 5 points of greedy accuracy but
almost nothing after the lexicon and language model: the decoder, not the network, is now the limit, so the
small model ships.

The engine runs it in pure Kotlin (`SwipeModel`): fp16 weights in the file, fp32 arithmetic, the convolution and
every input-to-gate product as one blocked matrix product over all frames (4 frames × 2 rows per inner loop),
only the recurrent product step by step, and table-based tanh/sigmoid (error < 3e-5). The dev phone runs it at
about 1.05 G multiply-adds per second on one core, independent of model shape.

## 3. Decoding

- **Key-sequence trie.** Every lexicon spelling is mapped to the keys a glide passes over (silent characters
  such as the apostrophe skipped, accented letters on their base key, repeated keys merged: "letter" → l-e-t-e-r,
  as a gesture cannot show a double letter). Spellings with the same key sequence share a trie node
  ("its"/"it's"); the language model separates them.
- **CTC prefix beam search** over that trie (`NeuralSwipeDecoder`): each hypothesis is a trie node with the
  probability of all alignments ending in blank or in its last letter; per frame it stays (blank or repeated
  letter) or moves to a child. Letters whose posterior at that frame is below e^−12 are not entered. Hypotheses
  for the same node merge; the beam keeps the best `neuralBeam` by log P + `neuralLookahead` × (best unigram
  prior below the node), so frequent words survive the prefix stage.
- **Ranking.** For the word spellings at terminal nodes of the final beam,
  `cost = −neuralCtcWeight · log P_ctc(keys | gesture) − neuralLmWeight · ln P(word | context)`, using the same
  language models (and multilingual priors, learned words, dialect rules, blocking) as the rest of the engine.
- **Template second opinion (optional).** With `neuralSharkWeight` > 0, the SHARK² channel cost of each
  candidate (location, shape, ordered key alignment, path fit, end points; capped at `neuralSharkCap`) is added.
  The two decoders make different mistakes.

Tuned on the core TUNE subsets with the production en-GB LM (`SwipeParams`): beam 64, prune e^−12, look-ahead 0.3,
LM weight 1.0 (0.8 is best without the engine's neural-LM re-rank; the re-rank reuses this weight for neural
hypotheses), template second opinion 0.5 (cap 30; re-tuned after main's CMA-ES re-fit of the template weights).
Sensitivity on TUNE: LM weight 0.4–1.2 moves top-1 by ≤ 0.6; the template second opinion adds +0.3 (FUTO) / +1.7
(HWS) top-1, most on 2-letter words; beam 24 → 96 and prune −6 → −12 add up to +0.4 (HWS, long words). A beam that
ends without any complete word (rare) falls back to the template matcher. `suggestForSwipePreview` (live preview)
uses the same decoder without the final re-rank.

## 4. Training data

Never any test data. Our `swipe_futo` tune/test are FUTO's dev/test splits, so FUTO's train split is free:

| Source | Strokes | Notes |
|---|---|---|
| swipe.futo.org train split (MIT) | 866,780 | same filters as the evaluation converter (portrait, plain letters, ≥ 2 letters, ≥ 3 samples); 0 sessions shared with our tune/test |
| How We Swipe (MIT) | 236 | only users in neither `swipe_hws.tune` nor `swipe_hws.test`, from the tune split (32 users); test-split users are never used |

Both are projected exactly like the evaluation sets (source layout → key units → Pixel-8 Gboard pixels,
clamped to the keyboard window) and then into grid units, which is what the engine does on any phone.
How We Swipe is therefore an out-of-domain test: its participants, canvas (key aspect 1.58) and phrase sets
(random words, out-of-vocabulary words) are essentially unseen in training.

**Augmentation** (80 % of strokes, per epoch): drop 0–50 % of samples (lower touch rates); anisotropic scale
(x 0.9–1.1, y/x ratio e^±0.15) and rotation (σ 3°, in approximately physical space), which is how a different key
aspect ratio shows up in grid units — the data span 1.16–1.70, Gboard's 350–399 dp set is 1.30 and the ≥ 400 dp
set 1.45; offset (σ 0.12 × 0.08 grid units); per-sample jitter (σ 0.02); duration scale e^±0.3.

## 5. Reproducing

```sh
curl -L -o tools/data/raw/futo_swipe/train.jsonl \
  https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/train.jsonl      # 5.2 GB, MIT
tools/swipe/py tools/swipe/prepare.py            # tools/swipe/work/{train,dev_futo,dev_hws}.npz (grid units)
tools/swipe/py tools/swipe/train.py tools/swipe/work/run --conv 96 --hid 80 --layers 2 --epochs 15   # GPU
tools/swipe/py tools/swipe/export.py tools/swipe/work/run/best.pt \
  engine/src/main/resources/dev/pboard/engine/swipe/qwerty.pbsw --vectors engine/src/test/resources/swipe/model_vectors.json
./gradlew :engine:swipeEval -PswipeArgs="--split tune"                         # template vs. neural, TUNE
tools/phone/guard.sh --headless -- tools/swipe/bench_device.sh <phone-serial> decode <model.pbsw> --split tune
```

`tools/swipe/gpu_run.sh` trains several configurations side by side on one rented GPU (data loading is
CPU-bound, small LSTMs leave the GPU mostly idle).

## 6. Training compute

All on rented GPU boxes, 2026-11-08.

1. **Sweep** (5 models side by side on one RTX 4060 Ti + 32-vCPU Threadripper PRO box, 1.7 h). The 877 MB training set went up once as a 296 MB zstd archive (50 s).
   Small models ran at 15–16 steps/s each (batch 256, ≈ 4,000 strokes/s) with 5 runs sharing the GPU (98 % busy),
   21 steps/s with 3; the 4M-parameter teacher at 9–16 steps/s; distillation runs at 20–27 steps/s.
2. **Augmentation follow-up** (time reversal, stronger geometry; one configuration per box, three boxes in
   parallel), which doubled as an instance-type comparison for this workload (batch 256, fp32, 12 loader
   workers, one run per box):

| Box | steps/s | strokes/s | GPU busy | 15 epochs |
|---|---|---|---|---|
| RTX 4080 + Core i5-14600KF (20 vCPU) | 86–92 | 23,000 | 46–50 % | 10 min |
| RTX 4070 + Ryzen 9 3900X (24 vCPU) | 40–51 | 11,500 | 33–37 % | 18 min |
| RTX 4060 Ti + Threadripper PRO (32 vCPU) | 34–36 | 9,000 | 32–42 % | 24 min |

These small LSTMs never fill a GPU: the limit is the Python training loop's single core (packing, CTC, optimizer
step), so the box with the fastest CPU core wins on both time and cost, and a 4090 would buy nothing. Next time:
pick by single-core CPU speed, run one configuration per box in parallel, and raise the batch size (fewer
Python steps per epoch) before reaching for mixed precision.

## 7. Layouts: the version 2 model

The version 1 model's classes are the 26 QWERTY letter positions and its input is in grid units, so it serves
every QWERTY geometry (both Gboard phone size sets, other heights and widths, letters within 0.2 key pitches of
the staggered grid) and nothing else. **Version 2** (`.pbsw` v2, `tools/swipe/train2.py`, `SwipeFeatures2`) takes
the layout as an input at run time:

- **Features** (`swipefeat2.py`): pixels divided by the median nearest-neighbour key distance ("key units", aspect
  kept); frames at each integer of arc / 0.5 key + time / 70 ms (vectorised, capped at 160); position in the frame
  spanned by the key centres, motion and time per frame, first/last flags and the frame's size. No feature knows
  which letter a key carries.
- **Spatial head**: per frame an intention gate g and 10 × 10 cosine-basis coefficients c; a key's logit is c
  evaluated at the key's centre, φ(x, y)[a·10 + b] = cos(πax)·cos(πby); P(blank) = 1 − σ(g),
  P(key k) = σ(g) · softmax_k(c · φ(key k)). The engine builds φ once per layout ([Encoder.setKeys]); any key set
  with two or more rows works, and the trie's labels are the layout's key indices.
- **Co-augmentation** (`--coaug p`): with probability p the stroke and the layout get the same random map in
  physical key units (flips, rotation uniform or small, anisotropic scale 0.8–1.25, shear ±0.15) plus per-key
  jitter (σ 0.05 key), then the device's normalisation. The network can then no longer tie a position to a letter,
  so it has no spelling prior and must read the geometry.

**What co-augmentation does** (TUNE, production configuration, top-1):

| Model | FUTO | HWS | HWS 2 letters | HWS 3–4 | HWS 7+ |
|---|---|---|---|---|---|
| v1 (QWERTY classes) | 92.17 | 81.57 | 83.01 | 83.83 | 78.53 |
| v2, no co-augmentation | 92.10 | 81.43 | 81.58 | 83.53 | 79.51 |
| v2, co-augmentation p = 0.5 | 91.73 | 81.63 | 85.17 | 84.13 | 77.91 |
| v2, co-augmentation p = 1 | 91.03 | 80.73 | 86.12 | 83.33 | 76.20 |
| v2, co-augmentation p = 1, 12 × 12 basis | 90.77 | 80.80 | 86.36 | 83.03 | 76.20 |
| template matcher | 88.63 | 77.90 | 86.36 | 81.24 | 73.01 |

Without co-augmentation the layout-agnostic head costs nothing. Full co-augmentation brings two-letter words (How We
Swipe's abbreviations: "tk", "rn", "sr") up to the template matcher's level, but long words lose the spelling prior
that served them, and 3–4-letter words do not improve. So the shipped file combines both:

- **Main network**: v2 without co-augmentation (all words).
- **Auxiliary network** (`SwipeModel.aux`, embedded in the same file): v2 with full co-augmentation and a 12 × 12
  basis. On strokes of at most 50 frames (every two-key word fits), candidates of at most two keys are scored by
  forced alignment under the auxiliary network instead (`neuralAuxKeys`, weight 0.9), and its 4 best short words
  join the candidate list. Longer strokes never run it, so the latency tail is that of one network.
  Blending both networks' scores for short words (α = 0.5, 0.7) was worse than replacing them.

**Result and decision.** On larger TUNE samples (12,000 swipes per set) v2 with its auxiliary network and v1 were
level (top-1 FUTO 92.42 vs 92.58, HWS 82.43 vs 82.27; HWS two-letter words 86.19 vs 84.58), so v2 went to TEST.
TEST (core, production configuration, run once), top-1 / top-3:

| | FUTO | HWS | HWS 2 letters | HWS 3–4 | HWS 5–6 | HWS 7+ | FUTO 7+ |
|---|---|---|---|---|---|---|---|
| template matcher | 89.20 / 94.40 | 79.03 / 86.40 | 87.43 | 81.83 | 73.01 | 77.33 | 81.73 |
| **v1** (default) | **92.80** / 95.77 | 81.17 / 87.50 | 83.88 | **82.11** | **80.18** | 79.66 | 87.12 |
| v2 + auxiliary | 92.47 / **96.13** | **81.37 / 87.83** | 85.25 | 81.54 | 79.92 | **80.76** | **87.47** |

v2 raises top-3 on both sets, HWS top-1 and long words, but FUTO top-1 drops 0.33, 3–4-letter words do not improve,
and two-letter words stay 2.2 points below the template matcher. That misses the bar set for it, so **v1 stays the
default**. The v2 code path (format, features, spatial head, auxiliary network, training and export tools) is in
the engine and tested with a tiny random v2 model (`v2_tiny.pbsw`); the trained v2 file is not bundled (1.16 MB).
On the dev phone (production configuration, warm context, core TUNE) v2 with its auxiliary network decodes in
p50 11.1 / 11.8 ms, p95 17.9 / 20.2 ms, p99 22.7 / 23.2 ms (FUTO / HWS), within the 30 ms budget: the auxiliary
network only runs on strokes of at most 50 frames. It is the model to ship when pboard gains a non-QWERTY layout: it decodes any key arrangement without retraining
(`NeuralSwipeDecodingTest` checks an AZERTY block). The one decoder change kept from this round: candidates of at
most two keys get template weight 1.0 instead of 0.5 (`neuralSharkWeightShort`; TUNE FUTO +0.1, HWS +0.3).

**Training compute for v2**: four configurations on four boxes in parallel, batch 768,
20 epochs, learning rate 3e-3, 16 loader workers, vectorised features (0.14 ms per stroke). Measured throughput:

| Box | steps/s (batch 768) | strokes/s | 20 epochs |
|---|---|---|---|
| RTX 3090 + Core i7-12700K | 55–63 | 45,000 | 7 min |
| RTX 3090 + Ryzen 9 5900XT | 35–52 | 35,000 | 9 min |
| RTX 4070 SUPER + Ryzen 9 5950X | 24–39 | 25,000 | 13 min |

GPU use stayed at 50–90 %: the single-threaded training loop and data loading
still decide the speed, so a fast desktop CPU with a mid-range GPU is the right shape for these models.

