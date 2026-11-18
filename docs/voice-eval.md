# Offline voice typing: speech-engine evaluation (phase 1)

Goal: choose the on-device speech recogniser for pboard's voice typing. It should feel like Gboard's: words
stream into the field as you speak, with punctuation, capitals and written numbers. It must run fully on-device,
ship as a separately installed pack, and need no network permission. Phase 1 (this evaluation) chose the engine;
the first cut of phase 2 is built (see "Phase 2: what is built"). Measured 2026-11-08 on the dev phone (arm64, Snapdragon 732G: 2× Cortex-A76 + 6× A55,
Android 16).

Everything here is reproducible with `tools/voice/` (see "Reproduce"). The raw numbers behind the tables are in
`tools/voice/results/`.

## Recommendation

**Primary engine: X-ASR streaming Zipformer transducer, 480 ms chunks, with punctuation and casing**
(`sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05`).
- Model: Apache-2.0, 148M parameters, 169 MB int8, run through sherpa-onnx (Apache-2.0) and ONNX Runtime (MIT).
- It is the only candidate with all four of: native streaming, built-in punctuation and casing, an open licence,
  and comfortable phone speed.
- Phone speed: RTF 0.17 on one thread, 0.22 CPU-s per audio second, 288 MB RSS.
- Accuracy: best open streaming model on conversational (AMI 12.1 %) and number-heavy (Earnings-22 14.4 %) speech;
  3.1 / 7.7 % on LibriSpeech; 18.7 % on Common Voice, where accented read speech is hard for every streaming model
  here.
- Partials:
  - fairly stable: 0.19 revised words per final word;
  - first text about 1.0-1.2 s after you start speaking;
  - the last word is on screen about 0.7 s after you stop, on the phone, in real time.
- Known issues to handle in phase 2:
  - It is a zh-en model and emits Chinese on about 1.3 % of English utterances; mask or filter CJK output.
  - It transcribes fillers ("um", "uh").
  - It spells numbers out, so number formatting needs inverse text normalisation (ITN).
  - Its ONNX graph takes 10 s to load on the phone; preload it, or convert the pack to ORT format.

**Optional higher-accuracy pack: NVIDIA parakeet-tdt_ctc-110m (TDT head) as a final pass**
(`sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8`).
- 137 MB, CC-BY-4.0 (attribution in the pack). Offline, with punctuation and casing.
- How it works: X-ASR keeps drawing the live partials. At each endpoint the buffered utterance is re-decoded by
  parakeet, and its text is committed.
- Accuracy: 2.5 / 5.4 % LibriSpeech, 11.1 % CV, 13.7 % AMI, 13.1 % E22. Raw formatted WER is 16 % on CV against
  31 % for X-ASR, so it fixes most of X-ASR's CV gap and formats better.
- Cost: about 0.4 s of extra decode per typical utterance on the phone (RTF 0.066).
- The bigger parakeet-tdt-0.6b-v2 (661 MB, CV 8.8 %, AMI 10.3 %, E22 12.1 %; RTF 0.15 on 2 threads, ~1 GB RSS) is
  the ceiling if a user wants the most accurate option.

Not chosen, and why:
- **nemotron-3.5-asr-streaming-0.6b** (OpenMDW): streaming with punctuation, phone RTF 0.37 on 2 threads, and the
  most stable, lowest-lag partials. But it is 4× the size and 1.6× the CPU for no gain on CV (18.2 %). It also
  needs an explicit `en-US` prompt: in auto mode it transliterated accented English into Devanagari.
- **Kroko** (fastest, most stable): unclear licence (CC-BY-SA, version unstated, empty LICENSE file), weaker on
  AMI and E22, and the slowest words to appear (lag50 0.94 s).
- **icefall zipformers** (LibriSpeech-trained): 3.1 % on LibriSpeech but 34-59 % on CV, AMI and E22.
- **NVIDIA FastConformer streaming exports**: NGC terms of use, and 32 % on AMI.
- **Moonshine Streaming**: Tiny is fast but 34 % on CV, with hallucinations and repeat loops. Small cannot keep
  up in real time on the 732G (RTF 1.25 on 1 thread, 0.86 on all cores at 3.9 CPU-s/s).
- **Whisper / FUTO ACFT**: offline only. Base takes 2.5 s after you stop with the 30 s window; ACFT's dynamic
  window cuts that to 0.7 s, but there are still no live partials.
- **Vosk small**: 37 % on CV, with no punctuation.

Compute: three rented CPU boxes over about 1.3 box-hours (details under "Reproduce").


## Results

WER is in %, phone latencies in ms. Definitions are in "Method". Moonshine-small's phone figures are
with the library's default threading.

### Main comparison

| engine | params | size MB | licence | stream | P+C | WER clean | other | CV | AMI | E22 | raw WER CV / E22 | phone RTF | cpu/s | RSS MB | first partial ms | final / visible ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| zipformer-20M | 20M | 45 | Apache-2.0 | yes | no | 23.86 | 31.65 | 57.99 | 79.62 | 64.32 | 101.0 / 100.6 | 0.07 (t1) | 0.09 | 122 | 1431 | 277 |
| zipformer-0626 | 66M | 72 | Apache-2.0 | yes | no | 3.11 | 7.83 | 34.13 | 59.31 | 52.00 | 103.4 / 103.1 | 0.14 (t1) | 0.17 | 175 | 1055 | 620 |
| kroko-en | ~70M | 71 | CC-BY-SA (version unstated; unclear) | yes | no | 4.04 | 9.47 | 18.09 | 17.35 | 18.73 | 27.8 / 34.8 | 0.07 (t1) | 0.10 | 193 | 1502 | 991 |
| xasr-160-punct | 148M | 169 | Apache-2.0 | yes | yes | 3.46 | 8.71 | 21.86 | 13.30 | 15.87 | 35.2 / 33.1 | 0.36 (t1) | 0.46 | 290 | 1005 | 617 |
| xasr-480-punct | 148M | 169 | Apache-2.0 | yes | yes | 3.09 | 7.70 | 18.65 | 12.05 | 14.37 | 30.9 / 31.3 | 0.17 (t1) | 0.22 | 288 | 1210 | 741 |
| fastconf-480ms | 114M | 137 | NGC Terms of Use (not open) | yes | no | 2.48 | 6.16 | 18.12 | 32.35 | 24.48 | 42.4 / 42.0 | 0.15 (t1) | 0.19 | 233 | 1223 | 786 |
| nemotron35-560ms | 600M | 682 | OpenMDW-1.1 | yes | yes | 3.45 | – | 18.23 | – | – | 31.0 / – | 0.37 (t2) | 1.23 | 788 | 1296 | 329 |
| moonshine-s-tiny | 34M | 45 | MIT | yes | yes | 7.37 | 16.81 | 34.42 | 28.67 | 26.57 | 41.3 / 39.7 | 0.43 (t1) | 0.45 | 174 | 1031 | 611 |
| moonshine-s-small | 123M | 142 | MIT | yes | yes | – | – | – | 20.60 | – | – / – | 1.25 (t1) | 1.30 | 397 | 1384 | 4911 |
| vosk-small | (Kaldi) | 71 | Apache-2.0 | yes | no | – | – | 36.78 | 33.42 | – | 57.1 / – | 0.29 (t1) | 0.31 | 186 | 852 | 248 |
| moonshine-tiny | 27M | 44 | MIT | no | yes | – | – | – | – | – | – / – | 0.06 (t1) | 0.06 | 178 | – | 285 |
| parakeet-110m-tdt | 114M | 136 | CC-BY-4.0 | no | yes | 2.51 | 5.40 | 11.08 | 13.72 | 13.09 | 16.0 / 27.7 | 0.07 (t1) | 0.07 | 334 | – | 382 |
| parakeet-0.6b-v2 | 600M | 661 | CC-BY-4.0 | no | yes | – | – | 8.81 | 10.28 | 12.14 | 12.9 / 25.9 | 0.15 (t2) | 0.31 | 982 | – | 867 |
| canary-180m | 182M | 207 | CC-BY-4.0 | no | yes | – | – | 9.40 | 12.80 | 14.13 | 13.8 / 27.9 | 0.22 (t2) | 0.43 | 485 | – | 1122 |
| whisper-tiny.en-q8 | 39M | 44 | MIT | no | yes | – | – | – | – | – | – / – | 0.18 (t2) | 0.34 | 119 | – | 1238 |
| whisper-base.en-q5 | 74M | 60 | MIT | no | yes | – | – | – | – | – | – / – | 0.71 (t2) | 1.39 | 156 | – | 5016 |
| whisper-base.en-q8 | 74M | 82 | MIT | no | yes | – | – | 20.41 | – | 14.90 | 25.9 / 26.4 | 0.36 (t2) | 0.69 | 177 | – | 2457 |
| whisper-base.en-q8-dynctx | 74M | 82 | MIT | no | yes | – | – | – | – | – | – / – | 0.32 (t2) | 0.56 | 196 | – | 794 |
| futo-acft-tiny.en-dynctx | 39M | 44 | Apache-2.0 (FUTO ACFT) | no | yes | – | – | – | – | – | – / – | 0.07 (t2) | 0.12 | 103 | – | 419 |
| futo-acft-base.en-dynctx | 74M | 82 | Apache-2.0 (FUTO ACFT) | no | yes | – | – | – | 18.12 | – | – / – | 0.11 (t2) | 0.20 | 159 | – | 664 |

### WER detail (norm / lc / raw, %) and number formatting

| engine | ls-clean | ls-other | cv | ami | e22 | num CV / E22 |
|---|---|---|---|---|---|---|
| canary-180m | – | – | 9.40 / 9.75 / 13.80 | 12.80 / 16.49 / 27.02 | 14.13 / 20.80 / 27.89 | – / 0 |
| fastconf-480ms | 2.48 / 2.64 / 24.51 | 6.16 / 6.38 / 28.90 | 18.12 / 18.46 / 42.42 | 32.35 / 35.56 / 45.02 | 24.48 / 30.66 / 41.99 | – / 0 |
| fastconf-480ms+punct | 2.48 / 2.64 / 15.97 | 6.16 / 6.38 / 20.68 | 18.12 / 18.46 / 29.88 | 32.35 / 35.56 / 49.54 | 24.48 / 30.66 / 43.08 | – / 0 |
| futo-acft-base.en-dynctx | – | – | – | 18.12 / 22.89 / 32.00 | – | – / – |
| kroko-en | 4.04 / 4.32 / 14.11 | 9.47 / 9.88 / 19.19 | 18.09 / 18.41 / 27.84 | 17.35 / 21.69 / 32.52 | 18.73 / 25.46 / 34.76 | – / 0 |
| moonshine-s-small | – | – | – | 20.60 / 22.06 / 38.97 | – | – / – |
| moonshine-s-tiny | 7.37 / 8.10 / 19.59 | 16.81 / 17.46 / 29.32 | 34.42 / 34.48 / 41.29 | 28.67 / 29.97 / 45.33 | 26.57 / 28.85 / 39.69 | – / 56 |
| nemotron35-560ms | 3.45 / 3.62 / 15.88 | – | 18.23 / 18.49 / 31.03 | – | – | – / – |
| parakeet-0.6b-v2 | – | – | 8.81 / 9.18 / 12.90 | 10.28 / 12.13 / 20.41 | 12.14 / 16.82 / 25.89 | – / 32 |
| parakeet-110m-tdt | 2.51 / 2.67 / 11.15 | 5.40 / 5.62 / 13.72 | 11.08 / 11.35 / 16.03 | 13.72 / 18.30 / 26.73 | 13.09 / 20.16 / 27.66 | – / 0 |
| vosk-small | – | – | 36.78 / 36.76 / 57.14 | 33.42 / 35.39 / 47.53 | – | – / – |
| whisper-base.en-q8 | – | – | 20.41 / 20.84 / 25.89 | – | 14.90 / 18.44 / 26.38 | – / 74 |
| xasr-160-punct | 3.46 / 3.59 / 15.54 | 8.71 / 8.91 / 21.22 | 21.86 / 22.19 / 35.18 | 13.30 / 15.55 / 29.09 | 15.87 / 21.69 / 33.09 | – / 0 |
| xasr-480-punct | 3.09 / 3.23 / 14.02 | 7.70 / 7.92 / 18.97 | 18.65 / 18.94 / 30.87 | 12.05 / 14.20 / 27.28 | 14.37 / 20.34 / 31.28 | – / 0 |
| zipformer-0626 | 3.11 / 3.23 / 99.00 | 7.83 / 7.94 / 99.08 | 34.13 / 34.29 / 103.36 | 59.31 / 59.72 / 99.84 | 52.00 / 54.13 / 103.10 | – / 0 |
| zipformer-0626+punct | 3.11 / 3.23 / 16.46 | 7.83 / 7.94 / 22.03 | 34.13 / 34.29 / 43.96 | 59.31 / 59.72 / 68.67 | 52.00 / 54.13 / 62.38 | – / 0 |
| zipformer-20M | 23.86 / 23.72 / 99.36 | 31.65 / 31.59 / 99.29 | 57.99 / 57.96 / 100.98 | 79.62 / 79.79 / 99.80 | 64.32 / 65.71 / 100.63 | – / 0 |

### Phone detail

| engine@threads | rtf | cpu/s | RSS MB | load ms | final ms (unpaced) | first ms | visible ms | p90 | paced rtf |
|---|---|---|---|---|---|---|---|---|---|
| canary-180m@t2 | 0.216 | 0.43 | 485 | 2531 | 1122 | – | – | – | – |
| canary-180m@t4 | 0.245 | 0.99 | 487 | 2532 | 1237 | – | – | – | – |
| fastconf-480ms@t1 | 0.150 | 0.19 | 233 | 3228 | 349 | 1223 | 786 | 1094 | 0.42 |
| futo-acft-base.en-dynctx@t2 | 0.113 | 0.20 | 159 | 233 | 664 | – | – | – | – |
| futo-acft-tiny.en-dynctx@t2 | 0.070 | 0.12 | 103 | 148 | 419 | – | – | – | – |
| kroko-en@t1 | 0.074 | 0.10 | 193 | 7778 | 207 | 1502 | 991 | 1405 | 0.21 |
| kroko-en@t1.big | – | – | 196 | 7087 | – | 1354 | 905 | 1319 | 0.13 |
| moonshine-s-small@t1 | 1.249 | 1.30 | 397 | 646 | 455 | 1384 | 4911 | 10486 | 1.54 |
| moonshine-s-small@tdef | 0.861 | 3.89 | 397 | 926 | 324 | 1173 | 1444 | 4930 | 1.03 |
| moonshine-s-tiny@t1 | 0.425 | 0.45 | 174 | 149 | 153 | 1031 | 611 | 1418 | 0.57 |
| moonshine-s-tiny@t1.big | – | – | 172 | 141 | – | 919 | 608 | 1433 | 0.53 |
| moonshine-s-tiny@tdef | 0.299 | 1.51 | 177 | 165 | 115 | 1008 | 580 | 849 | 0.53 |
| moonshine-tiny@t1 | 0.056 | 0.06 | 178 | 795 | 285 | – | – | – | – |
| moonshine-tiny@t2 | 0.047 | 0.10 | 178 | 766 | 258 | – | – | – | – |
| nemotron35-560ms@t2 | 0.369 | 1.23 | 788 | 5542 | 842 | 1296 | 329 | 757 | 0.49 |
| nemotron35-560ms@t4 | 0.476 | 3.08 | 788 | 4353 | 965 | 1376 | 429 | 728 | 0.63 |
| parakeet-0.6b-v2@t2 | 0.145 | 0.31 | 982 | 4782 | 867 | – | – | – | – |
| parakeet-0.6b-v2@t4 | 0.161 | 0.70 | 982 | 4774 | 959 | – | – | – | – |
| parakeet-110m-tdt@t1 | 0.066 | 0.07 | 334 | 2750 | 382 | – | – | – | – |
| parakeet-110m-tdt@t2 | 0.048 | 0.11 | 335 | 2178 | 283 | – | – | – | – |
| vosk-small@t1 | 0.293 | 0.31 | 186 | 967 | 32 | 852 | 248 | 1140 | 0.49 |
| vosk-small@t1.big | – | – | 179 | 678 | – | 820 | 191 | 1098 | 0.39 |
| whisper-base.en-q5@t2 | 0.708 | 1.39 | 156 | 136 | 5016 | – | – | – | – |
| whisper-base.en-q8-dynctx@t2 | 0.325 | 0.56 | 196 | 467 | 794 | – | – | – | – |
| whisper-base.en-q8@t2 | 0.358 | 0.69 | 177 | 415 | 2457 | – | – | – | – |
| whisper-tiny.en-q8@t2 | 0.178 | 0.34 | 119 | 148 | 1238 | – | – | – | – |
| xasr-160-punct@t1 | 0.363 | 0.46 | 290 | 10209 | 755 | 1005 | 617 | 731 | 0.48 |
| xasr-160-punct@t1.big | – | – | 288 | 9502 | – | 1002 | 612 | 726 | 0.43 |
| xasr-160-punct@t2 | 0.350 | 1.57 | 288 | 9901 | 724 | – | – | – | – |
| xasr-480-punct@t1 | 0.169 | 0.22 | 288 | 10232 | 340 | 1210 | 741 | 962 | 0.44 |
| xasr-480-punct@t1.big | – | – | 289 | 10069 | – | 1116 | 670 | 896 | 0.30 |
| zipformer-0626@t1 | 0.137 | 0.17 | 175 | 7293 | 271 | 1055 | 620 | 737 | 0.49 |
| zipformer-0626@t1.big | – | – | 179 | 6999 | – | 981 | 543 | 669 | 0.28 |
| zipformer-0626@t2 | 0.146 | 0.64 | 176 | 7355 | 255 | 987 | 560 | 675 | 0.33 |
| zipformer-20M@t1 | 0.067 | 0.09 | 122 | 1429 | 131 | 1431 | 277 | 590 | 0.42 |

### Partial results (host, audio time)

| engine | first partial ms | word lag p50 / p90 ms | words only at final % | revisions / word | incl. case+punct |
|---|---|---|---|---|---|
| fastconf-480ms | 1060 | 620 / 900 | 5.7 | 0.161 | 0.161 |
| kroko-en | 1260 | 940 / 1520 | 11.7 | 0.059 | 0.095 |
| moonshine-s-tiny | 720 | 480 / 1180 | 4.4 | 0.780 | 1.087 |
| nemotron35-160ms | 860 | 320 / 560 | 1.5 | 0.447 | 0.500 |
| nemotron35-560ms | 1060 | 440 / 760 | 2.9 | 0.107 | 0.143 |
| vosk-small | 720 | 280 / 820 | 9.2 | 0.374 | 0.374 |
| xasr-160-punct | 980 | 580 / 720 | 5.3 | 0.520 | 0.576 |
| xasr-480-punct | 1040 | 700 / 940 | 7.0 | 0.188 | 0.231 |
| zipformer-0626 | 960 | 520 / 720 | 4.6 | 0.308 | 0.308 |


## Findings worth knowing for the build

- **One decode thread.** On the 732G, 2 ORT threads were no faster for models up to 150M (zipformer 0.137 →
  0.146, X-ASR 0.36 → 0.35) but used 3.4-3.6× the CPU, because the thread pools spin while waiting. Use
  `num_threads = 1` for the streaming model. The 0.6B models do gain from 2 threads, but not from 4.
- **Big cores matter when paced.** Released in real time, each 100 ms chunk decodes 2-3× slower than when
  decoding flat out:
  - the scheduler parks the idle shell process on the A55s at low clock;
  - pinned to the A76s, X-ASR-480's paced RTF drops from 0.45 to 0.30.
  The IME's decode thread should be a foreground, high-priority thread.
- **Load time.** int8 ONNX models take 7-10 s to load on the phone (graph optimisation at every load). Moonshine's
  pre-optimised ORT-format files load in 0.15-0.4 s. Convert packs to ORT format, or cache the optimised model,
  and load on mic-key touch-down.
- **Tail flush.** Large-chunk models need about 1-2 s of trailing audio to emit the last word and its full stop:
  X-ASR-480 and Kroko dropped the final word with only 0.6 s. In the app, the endpoint silence provides it.
- **Multilingual models need a language prompt.** nemotron-3.5 in "auto" wrote about 20 % of accented CV clips in
  Devanagari, and X-ASR (zh-en) writes Chinese for about 1-3 % of noisy English utterances.
- **LibriSpeech alone is misleading.** Models trained only on LibriSpeech score about 3 % there and 34-59 % on
  CV, AMI and E22.
- **Formatting.** Raw WER (case- and punctuation-sensitive) is 8-15 points above normalised WER even for models
  with punctuation, and no candidate writes numbers reliably (see the "num" column). The Edge-Punct-Casing
  post-processor (Apache-2.0, 7.5 MB, about 5 ms per utterance) brings a lower-case model to 16-22 % raw WER on
  LibriSpeech.
- **zipformer-20M** drops the first 1-2 s of most utterances in sherpa-onnx 2.0.1, even fp32 and on its own test
  wav, so its numbers are not representative.
- **Coverage gaps.** The evaluation was stopped early.
  - Whisper, vosk, Moonshine-small, canary, parakeet-0.6b and nemotron-3.5 have only some sets ("–").
  - nemotron-3.5's LibriSpeech and partial-result numbers are from "auto" language mode.
  - Published LibriSpeech clean/other for the missing cells: Whisper base.en 4.3 / 10.3, parakeet-0.6b-v2
    1.7 / 3.2, canary-180m 1.9 / 3.8.


## Candidates and licences

Licences were verified from the model cards, LICENSE files and the HF/GitHub/NGC APIs on 2026-11-08. "Ship?"
means whether the weights can go into an open-source keyboard as a separately installed pack.

| engine (id in tools/voice/engines.py) | model | params | streaming | punct + case | weights licence | ship? |
|---|---|---|---|---|---|---|
| zipformer-20M / -0621 / -0626 | icefall streaming Zipformer transducers (LibriSpeech; 0621 adds GigaSpeech), sherpa-onnx exports | 20M / 70M / 66M | yes, 320 ms chunks | no (lower/upper case) | Apache-2.0 | yes |
| kroko-en | Banafo Kroko streaming Zipformer (sherpa-onnx export) | ~70M | yes | partial (some commas) | "CC-BY-SA", version unstated, empty LICENSE file | unclear: ask Banafo |
| xasr-160-punct / xasr-480-punct | X-ASR zh-en streaming Zipformer transducer with punctuation, 160 / 480 ms chunks | 148M | yes | yes | Apache-2.0 | yes |
| fastconf-80ms / -480ms / -1040ms | NVIDIA FastConformer hybrid large streaming, sherpa-onnx exports of the NGC checkpoints | 114M | yes (cache-aware) | no | NGC Terms of Use | **no** (the same architecture is CC-BY-4.0 on HF as `stt_en_fastconformer_hybrid_large_streaming_multi`, which we would have to export ourselves) |
| nemotron-160ms / -560ms | NVIDIA nemotron-speech-streaming-en-0.6b | 600M | yes | yes | NVIDIA Open Model License (revocable, guardrail clause) | only with care, not OSI |
| nemotron35-160ms / -560ms | NVIDIA nemotron-3.5-asr-streaming-0.6b (multilingual) | 600M | yes | yes | OpenMDW-1.1 (permissive) | yes |
| parakeet-unified-560ms | NVIDIA parakeet-unified-en-0.6b | 600M | yes | yes | NVIDIA Open Model License | dropped: RTF ~3 on the host |
| moonshine-s-tiny / -small / -medium | Moonshine Streaming (v2) | 34M / 123M / 245M | yes (sliding-window encoder, decoder re-run every 0.5 s) | yes | MIT (code and English models) | yes |
| moonshine-tiny / -base | Moonshine v1 via sherpa-onnx | 27M / 61M | no | yes | MIT | yes |
| vosk-small / vosk-lgraph | Kaldi online models | – | yes | no | Apache-2.0 | yes |
| parakeet-110m-tdt / -ctc | NVIDIA parakeet-tdt_ctc-110m | 114M | no | yes | CC-BY-4.0 | yes (attribution) |
| parakeet-0.6b-v2 | NVIDIA parakeet-tdt-0.6b-v2 | 600M | no | yes | CC-BY-4.0 | yes (attribution) |
| canary-180m | NVIDIA canary-180m-flash | 182M | no | yes | CC-BY-4.0 | yes (attribution) |
| whisper-tiny.en / base.en (q5_1, q8_0) | OpenAI Whisper via whisper.cpp | 39M / 74M | no (30 s window) | yes | MIT | yes |
| futo-acft-tiny.en / base.en | FUTO's ACFT fine-tunes of Whisper (trained for a dynamic audio context) | 39M / 74M | no | yes | Apache-2.0 (HF card) | yes |

Not run, with the reason:
- FUTO asr4all-s/m/l (2026; streaming CTC plus a punctuation model). Licence: FUTO Model Weights License
  (Restricted Use) 1.0. Benchmarking is explicitly allowed, but its field-of-use limits make it non-OSI, so it
  cannot be a pboard pack. The runtime is ExecuTorch `.pte`, which our harness does not run. Published: s (28M)
  3.71 / 10.07 LibriSpeech clean/other, 9.73 Open ASR average; first result after 1.40 s at its lowest-latency
  setting.
- NVIDIA parakeet_realtime_eou_120m-v1 (NVIDIA Open Model License, no punctuation, no sherpa-onnx export) and
  `stt_en_fastconformer_hybrid_medium_streaming_80ms_pc` (32M, CC-BY-4.0, punctuation; no export). The latter is
  worth a NeMo→ONNX export in phase 2 if we want a tiny CC-BY streaming model with punctuation.
- Kyutai STT 1B, Voxtral Realtime 4B, Granite speech 470M, Qwen3-ASR 0.6B: offline or too large for a 732G.
- Distil-Whisper small.en (166M), SenseVoice (custom FunASR licence), Paraformer-en: offline, no better than the
  above, or unsuitable licences.
- Google/Gboard models: excluded by rule.


## Method

### Test sets (evaluation only; nothing derived from them is shipped)

| set | source | licence | utterances | hours | references |
|---|---|---|---|---|---|
| ls-clean | LibriSpeech test-clean (OpenSLR 12), all | CC BY 4.0 | 2620 | 5.40 | upper-case original for norm/lc WER; **LibriSpeech-PC** (NVIDIA, OpenSLR 145, CC BY 4.0) punctuated and cased text for raw WER (2417 utterances) |
| ls-other | LibriSpeech test-other, all | CC BY 4.0 | 2939 | 5.34 | same; PC for 2856 utterances |
| cv | Common Voice 9 English test (ESB copy in `hf-audio/open-asr-leaderboard`), 1500 sampled with seed 0, clips > 30 s dropped | CC0 | 1497 | 2.5 | original punctuated, cased sentences |
| ami | AMI meeting corpus, individual headset mics, test split, ESB "cleaned" segmentation, 1500 sampled | CC BY 4.0 | 1500 | 1.55 | punctuated, cased; conversational, with disfluencies |
| e22 | Earnings-22 test segments (ESB), 600 sampled, > 30 s dropped | CC BY-SA 4.0 | 596 | 1.2 | punctuated, cased; spontaneous speech with many numbers, the dictation-like stress set |

Downloads are SHA-256-recorded in `tools/voice/work/raw/SHA256SUMS` (gitignored). The sets are rebuilt
deterministically by `tools/voice/prepare.py`: two hosts produced byte-identical manifests.

### Scoring (`tools/voice/score.py`, corpus-level WER)
- **norm**: Whisper's `EnglishTextNormalizer` on reference and hypothesis. This is the Open ASR Leaderboard
  convention and is comparable with published numbers. It is the main accuracy number.
- **lc**: lower-case, punctuation stripped, no other normalisation. "10" and "ten" differ, so spoken-form engines
  are not penalised for the normaliser's choices but are penalised for format.
- **raw**: case- and punctuation-sensitive against the punctuated references (LibriSpeech-PC, CV, AMI, E22), as a
  user would see the text. "Hello," against "hello" is a substitution. This is the Gboard-likeness number.
- **num**: the share of digit-bearing reference tokens ("2019", "$12.5", "10%") that the hypothesis reproduces
  exactly. It tests whether numbers come out written.

### Host decoding (`tools/voice/run_host.py`)
Every engine decodes 16 kHz mono audio as the keyboard would.
- Streaming engines get 100 ms chunks, decoding after each one, then 2 s of silence and input-finished. The 2 s
  flushes the right context of the large-chunk models: x-asr-480 and kroko lost the last word or its final full
  stop with 0.6 s.
- Moonshine Streaming's WER runs use its whole-utterance path. On 60 LS-other utterances that gave
  11.6 % WER against 11.3 % for the 0.5 s-update streaming final, which is within noise. Partial-result runs use
  the streaming path.
- Whisper models go through our own whisper.cpp 1.9.5 harness: greedy, English, no timestamps. "dynctx" sizes the
  encoder window to the utterance (50 frames/s + 1 s margin) instead of 30 s.
- The runs used a shared host at low priority. All engines decode with 1 ORT thread per process.

### Phone (`tools/voice/phone.py` + `tools/voice/android/*_bench.cc`)
Hardware and binaries:
- A 1080x2400, 440 dpi arm64 phone: SD732G with 2× A76 at 2.3 GHz and 6× A55 at 1.8 GHz, 5.6 GB RAM, Android 16,
  on USB power.
- Our own C/C++ harnesses, built with NDK r29 for arm64 (`armv8.2-a+dotprod+fp16` for ggml):
  - sherpa-onnx 2.0.1 C API, with onnxruntime 1.28.2;
  - whisper.cpp 1.9.5;
  - libvosk 0.3.75 from the vosk-android AAR;
  - libmoonshine 0.1.6 from the moonshine-voice AAR.
- Run as shell binaries from `/data/local/tmp/voice` under `tools/phone/guard.sh --headless`, with no input
  injection and no screen access, and removed afterwards.

Measurements:
- **rtf**: compute / audio on 30 utterances (20 LS-clean + 10 CV, 3-15 s, 2.6 min). Streaming engines decode
  each 100 ms chunk and read the partial, so partial generation is included.
- **cpu/s**: process CPU-seconds per audio second, excluding model load (getrusage). This is the energy proxy.
- **rss**: peak RSS.
- **load**: model load time.
- **Paced runs** (8 LS-clean utterances): audio is released on a real-time clock in 100 ms pieces, followed by
  1 s of real-time silence, so the CPU idles between chunks as it would in the IME.
  - **first**: when the first partial appears after the onset of speech.
  - **visible**: when the shown partial already equals the final text, measured after the last spoken word
    ends. Onset and end come from parakeet-0.6b-v2 timestamps.
  - **final** (offline engines): decode time of the whole utterance after it ends.

### Partial-result stability (`tools/voice/stability.py`, host)
- Set: 400 utterances (200 LS-clean, 200 CV).
- Measured in audio time, i.e. latency inherent to the model with instant compute:
  - first partial after speech onset;
  - lag50/lag90: when each final word becomes stably visible, relative to the end of that word in the
    parakeet-0.6b-v2 alignment;
  - final%: the share of words that only settle at finalisation.
- Flicker:
  - **rev/w**: already-shown words that a later partial changed or removed, per final word, ignoring case and
    punctuation;
  - **rev_raw/w**: the same, counting case and punctuation changes.


## Phase 2: what is built (first cut)

- **Voice pack** `dev.pboard.pack.voice.en` (module `:voicepack`, 182 MB arm64 release APK). It contains the X-ASR 480 ms
  model and sherpa-onnx's official 2.0.1 Android AAR, both fetched and sha256-checked by
  `tools/voice/fetch_voicepack.sh`, and its entry point `dev.pboard.pack.voice.VoiceEngine` (API 1: load, begin,
  accept, endpoint, finish, release). As with the scan pack, pboard loads it with its own class loader, only when the
  pack is signed with pboard's key (`dev.pboard.ime.voice.VoicePack`). Models are read straight from the pack's
  assets (stored uncompressed), so nothing is copied.
- **Mic key**: with the pack installed it opens pboard's voice panel; without it, it still hands over to another
  voice IME if one is enabled, as before. It stays hidden in password fields.
- **Voice panel** (`VoicePanelView`, Gboard-style), in place of the keys:
  - a large round mic button, filled with the accent colour while listening, with a level ring;
  - a state line: "Getting ready…", "Listening…", "Tap to speak";
  - a keyboard button back to the keys, and a repeating delete key.
- **Streaming into the field** (`VoiceSession`):
  - `AudioRecord` (VOICE_RECOGNITION, 16 kHz float) on an urgent-audio thread, 100 ms pieces, one decode thread;
  - the running hypothesis is shown as composing text and committed at each endpoint (1.0 s of silence after
    speech), or when the user taps stop;
  - the session stops itself after 8 s without speech.
- **Punctuation and casing** (`VoiceText`, unit-tested):
  - the model's own punctuation and capitals are kept;
  - CJK characters and full-width punctuation that the bilingual model sometimes emits are dropped, and so are
    fillers;
  - each utterance is fitted to the text before the cursor: a separating space, a capital at sentence starts, a
    lower-case continuation mid-sentence after a pause (except "I" forms and all-caps words), and no
    auto-capitals in URL and e-mail fields.
- **Microphone permission**: RECORD_AUDIO is declared and explicitly allowlisted in the build's permission gate,
  which still fails on any network permission.
  - It is requested only from the mic key, through `VoicePermissionActivity`: one sentence of explanation, then
    the system dialog. After a permanent denial, the same screen leads to the app's settings instead.
  - Once granted, listening starts the next time the keyboard shows.
- **Debug hook** (debuggable builds only): `am broadcast -a dev.pboard.DEBUG -p dev.pboard --es voiceWav <file>`
  feeds a 16 kHz WAV in real time instead of the microphone, for device checks.

Device check (2026-11-08, emulator `pboard5` with x86_64 debug builds of app and pack):
- Fed LibriSpeech 1089-134686-0000 through the debug hook into PlaygroundActivity's field.
- The panel showed "Listening…" with the level ring.
- The words streamed in as underlined composing text ("He hoped there would be stew for dinner, turnips and car…").
- The field ended with "He hoped there would be stew for dinner, turnips and carrots and bruised potatoes and fat
  mutton pieces to be ladled out in thick peppered flower fattened sauce", and the panel went back to "Tap to speak".
- The flush tail is now 2 s, as in the evaluation, so the final full stop comes out.

### Follow-ups built

- **Load time.** The pack's models are ORT format, pre-optimised for ARM by `tools/voice/to_ort.sh` (ONNX Runtime's
  own converter, 1.28 to match the runtime).
  - Measured on the dev phone, X-ASR's first load fell from 9.6 s to 4.9 s; parakeet-110m's from 2.2 s to 1.4 s.
  - Peak RSS rose from 289 to 396 MB, because ORT keeps the model bytes.
  - The microphone opens before the model loads, with an 8 s capture buffer. Whatever is said during "Getting
    ready…" (an arc circles the mic) is recognised once the model is up, so the first tap loses nothing.
  - Nothing loads at IME start. After use the model stays loaded for 3 minutes (a second dictation starts at once),
    then it is released.
- **Numbers** (`VoiceItn`, pboard's own rules, unit-tested):
  - cardinals ("twenty five" → 25; a lone word below ten stays a word);
  - years, digit strings and ordinals;
  - dates ("March third twenty twenty four" → March 3rd, 2024);
  - times ("five thirty pm" → 5:30 pm, "seven oh five a m" → 7:05 am, "at five forty five" → at 5:45);
  - money ($25, $3.50, £10, €5, $2 million) and percent.
- **Optional accurate pack** `dev.pboard.pack.voice.en.accurate` (module `:voicepackaccurate`, 139 MB, data only).
  - Contents: parakeet-tdt_ctc-110m (TDT head, int8, ORT format) with its CC-BY-4.0 attribution in
    `assets/voice/accurate/ATTRIBUTION.txt`.
  - When it is installed, engine API 2 also loads it. Each utterance's audio is kept, and at the endpoint it is
    re-decoded; that text is committed, while X-ASR still draws the live words.
  - It is off unless installed. Utterances over 30 s, and ones in which X-ASR heard nothing, keep the streaming
    text.
  - Emulator check: the committed text came from parakeet ("…bruised potatoes, and fat mutton pieces to be ladled
    out in thick, peppered flour fattened sauce,"; X-ASR had "flower").
- **End-to-end latency on the dev phone** (headless harness, 1 thread):
  - The streamed words reach the field about 0.7-0.9 s after you stop speaking.
  - The utterance is committed at the endpoint, about 1.15 s after you stop.
  - The accurate pack adds its re-decode: a median 0.38 s for these 3-15 s utterances (RTF 0.064).
  - X-ASR-480's paced real-time factor was 0.69 in that session, against 0.45 earlier unpinned. The phone was
    warm after the 170 MB push, so the IME's foreground thread should be faster; it was not measured inside the IME.

Not built yet: voice commands, personal-dictionary biasing, spoken punctuation, and a Voice settings page for the
packs.

## Phase 2: build plan for Gboard-style voice typing

Goal: the mic key (`ToolbarItems.VOICE`, today a hand-off to another voice IME in `PboardIme.startVoiceInput`)
opens pboard's own voice panel. Words stream into the field as you speak, punctuated and cased, numbers written as
digits. Everything runs on-device and needs no network permission.

### 1. Architecture
- New module `:voice` (Android library): a JNI wrapper around sherpa-onnx's C API (Apache-2.0) and ONNX Runtime.
  Build both from source with the NDK r29 now in the devshell (this phase used prebuilt ORT 1.28.2 from
  csukuangfj/onnxruntime-libs), with an operator-reduced ORT build. Moonshine reported 6.0 MB for such a build,
  against 22 MB for stock arm64; that is the main APK-size lever. Only arm64-v8a, plus armeabi-v7a if we still
  ship it.
- The keyboard process owns one `VoiceSession`. `AudioRecord` captures 16 kHz mono PCM16
  (`VOICE_RECOGNITION` source) in 100 ms buffers on a high-priority audio thread, and the recogniser runs on one
  decode thread.
  - Use `num_threads = 1`: on the phone two threads gave no speed-up and 2-4x the CPU time.
  - Set the ORT session option `session.intra_op.allow_spinning = 0`, since spinning thread pools burnt CPU while
    waiting for audio.
- Load the model when the panel opens, and preload it on the mic key's touch-down. Convert packs to ORT format and
  keep the optimised graph: zipformer ONNX took 7 s to load on the phone, Moonshine's ORT files 0.17 s. A loaded
  model stays resident about 30 s after the panel closes, then is freed.
- Two-pass decoding (sherpa-onnx's documented "two-pass" pattern): the streaming model draws the partials. At
  each endpoint, the utterance's audio, already buffered, is re-decoded by the offline model of the pack, and that
  text is committed.
- Engine-specific work for X-ASR:
  - It is a zh-en model and emits Chinese on about 1-3 % of accented or noisy English utterances. Mask the CJK
    vocabulary in the joiner output (a logit mask before the argmax in our sherpa-onnx build, or a "blocked
    tokens" option upstreamed), so an English pack can never output them.
  - It transcribes fillers ("um", "uh"). Drop them in post-processing, as Gboard does.

### 2. Partial results in the field
- Show the partial hypothesis as composing text (`setComposingText`), underlined as Gboard does. On each partial,
  replace the composing region. When an utterance finalises (endpoint), `commitText` it and start a new composing
  region.
- Stability:
  - Commit the stable prefix early. A word that has been unchanged for N updates, and is followed by at least
    one more word, can move out of the composing span. That keeps the composing span short and cheap to rewrite.
  - Measured revision rates (the "rev/word" column) show how much flicker to expect.
- The voice panel replaces the key area, as Gboard's does:
  - a mic button with a level meter drawn from audio RMS;
  - "Listening…" or "Paused" states;
  - a backspace and an "abc" key back to the keyboard;
  - language label.
- Tap the field or the mic to stop.

### 3. Endpointing
- Use the recogniser's endpoint rules for the streaming transducer: rule1 at 2.4 s of trailing silence with
  nothing decoded, rule2 at about 0.8 s after speech (Gboard finalises in about 0.6-1.0 s; tune it on the stab
  set), rule3 at 20 s maximum.
- Add Silero VAD (MIT, 2 MB, already in sherpa-onnx) to:
  - gate decoding during silence, which saves CPU;
  - drive the panel's auto-stop after about 5-8 s of silence;
  - skip the decoder for noise-only segments.
- An endpoint finalises the utterance: run punctuation finalisation, ITN and command parsing, then commit.

### 4. Punctuation, casing, numbers
- If the primary model is lower-case (the zipformers), run the Edge-Punct-Casing CNN-BiLSTM (Apache-2.0, 7.5 MB
  int8, about 1 ms per utterance) on the final text, and optionally on long partials. Measured effect: see "raw
  WER" with and without `+punct`.
- If the model emits punctuation and casing itself (X-ASR punct, Moonshine, NVIDIA PnC), use it as is.
- Spoken punctuation ("comma", "period", "question mark", "new line", "new paragraph") is a dictation feature
  that Gboard has, so we should match it. A small rule pass on the final words does it; spoken punctuation must
  win over the model's predicted punctuation.
- Numbers: none of the streaming candidates write numbers reliably (see the "num" column). Inverse text
  normalisation (ITN) is needed:
  - Option A: NVIDIA NeMo text-processing English ITN grammars (Apache-2.0), compiled to FSTs and run through
    sherpa-onnx's `rule_fsts` (it already supports rule FSTs for ITN).
  - Option B: our own Kotlin ITN for the dictation-relevant classes (cardinals, ordinals, money, percent,
    times, dates, phone numbers), tested against Earnings-22 and CV numerals.
  - Start with B for the common classes, since it is easier to keep locale-aware for en-GB/en-US and to unit
    test, and keep A as an option.
- Capitalise after sentence ends and at field start, respecting `InputType` caps flags as the keyboard already
  does. Join lines carefully: Moonshine capitalises the start of every VAD line, so lower-case a line start when
  the previous line did not end a sentence.

### 5. Voice commands
- On the final text of an utterance, match a small grammar in the IME language:
  - "delete" / "delete last word" / "undo";
  - "send" (performs the editor action if the field has one);
  - "new line" / "new paragraph";
  - "stop listening";
  - "select all" / "clear".
- Gboard executes a command only when it forms a whole utterance, or the utterance tail after a pause. Do the
  same and keep it conservative (exact phrase, whole utterance) to avoid false triggers.

### 6. Biasing with the personal dictionary
- sherpa-onnx transducers support hotwords with `modified_beam_search`. The BPE `modeling_unit` and `bpe_vocab`
  (zipformer-0626 ships `bpe.model`) are passed as a hotwords buffer built from:
  - the user dictionary;
  - learned words above a frequency threshold;
  - contact names (only with explicit opt-in and a READ_CONTACTS permission we do not request today; default
    off).
- Rebuild the list when the dictionary changes. Cost: beam search with 4 paths is about 1.3-2x greedy. Measure on
  the phone before enabling by default.
- Moonshine streaming has the same thing built in (`moonshine_transcriber_set_keyterms`, per stream).
- Evaluate with a keyterm test set: rare words from our LM eval sets, recorded with TTS or taken from CV
  sentences that contain them. Measure keyterm recall against overall WER.
- Privacy: biasing lists live in memory only and are built from app-private data. Nothing is logged.

### 7. Microphone permission, opt-in
- Do not add RECORD_AUDIO to the manifest until the feature ships. When it does:
  - Voice typing is off by default under Settings → Voice typing (`Route.Voice` already exists).
  - The first mic-key tap with no pack installed opens settings, not a dialog over the field.
  - An IME cannot show the runtime permission dialog itself. The settings activity requests RECORD_AUDIO with a
    clear rationale ("Audio is processed on this device and never stored or sent"), then returns the user.
- While recording, the panel shows a visible indicator, and Android's own mic privacy chip appears.
- Never record in password or sensitive fields (`EditorState.voiceAllowed` already excludes passwords; add
  `IME_FLAG_NO_PERSONALIZED_LEARNING` fields to the biasing exclusion).
- Never write audio to disk; recognition results follow the existing learning rules.

### 8. Pack distribution without network
pboard has no INTERNET permission, so packs are separate:
- **Companion pack APK** (recommended): a resource-only APK per model, such as `dev.pboard.voice.en`, signed with
  the same key, installed from F-Droid or our releases.
  - pboard finds it with `PackageManager` (a `<queries>` entry in the manifest) and reads the model files through
    `createPackageContext(...).assets` without copying, using the asset fd and offset with ORT's memory-mapped
    loading.
  - Updates arrive through the store like any app.
  - Signature check: same signer as pboard.
- **Import from file**: Storage Access Framework picker for a `.pbvoice` zip (model files + manifest.json with
  name, licence, sha256 and engine type). Copy it to app-private storage after verifying the sha256 against a
  list compiled into pboard, then show licence and attribution in the licences screen.
- Licence and attribution files travel inside each pack. For CC-BY models (NVIDIA parakeet), keep the
  attribution in the pack and in pboard's licences screen.
- Size targets: primary pack ≤ 100 MB; high-accuracy pack ≤ 300 MB, preferably.

### 9. Tests and acceptance
- Port `tools/voice` as the regression harness. Every pack change re-runs:
  - WER on the five sets;
  - the stab metrics;
  - the paced phone run.
- Acceptance before shipping:
  - normalised WER ≤ the numbers below on all sets;
  - first partial ≤ 1.2 s after speech onset and last word ≤ 1.0 s after speech end on the dev phone;
  - CPU per audio second ≤ 0.35;
  - no regression in typing latency while the voice panel is closed (model unloaded).
- Gboard comparison on the phone: dictate the same TTS or recorded prompts into both keyboards in the test app
  (guarded), and diff the committed text.


## Reproduce

All code is in `tools/voice/`. Downloads, models, prepared audio and hypotheses go to the gitignored
`tools/voice/work/`.

```sh
# host side (any Linux box; on NixOS tools/voice/py adds the nix-ld libraries for the manylinux wheels)
tools/voice/setup_host.sh                    # venv, test sets (fetch_data.sh + prepare.py), models, host whisper.cpp
JOBS=8 tools/voice/run_all.sh tierA stab     # WER + partial-history runs (list=FILE runs an explicit job list)
tools/voice/py tools/voice/punct.py zipformer-0626        # +punct variants for lower-case engines
tools/voice/py tools/voice/score.py --json tools/voice/results/wer.json
tools/voice/py tools/voice/stability.py --json tools/voice/results/stab.json

# phone (inside the devshell, which now provides the NDK as ANDROID_NDK_HOME)
nix develop . --command tools/voice/android/build_sherpa.sh          # sherpa-onnx 2.0.1 for arm64
nix develop . --command tools/voice/android/build_bench.sh sherpa whisper vosk moonshine
nix develop . --command tools/phone/guard.sh --headless -- python3 -I tools/voice/phone.py ENGINE... --threads 1
nix develop . --command tools/phone/guard.sh --headless -- python3 -I tools/voice/phone.py --cleanup
tools/voice/py tools/voice/phone_report.py --json tools/voice/results/phone.json
tools/voice/py tools/voice/report.py         # the tables below
```

Engines and model files are registered in `tools/voice/engines.py`. `tools/voice/setup_box.sh` is the
setup used on the rented CPU box.

Compute used:
- Two EPYC 9V45 hosts.
- Rented CPU boxes. Two older-CPU boxes, a Xeon Gold 6152 and an EPYC 7742, were measured first and dropped:
  int8 ONNX ran 5-12× slower per process there than on Zen 5. A Ryzen 9 9950X box did the 0.6B models.

