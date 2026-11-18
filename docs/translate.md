# On-device translation (toolbar › Translate)

pboard's Translate works like Gboard 18.6's toolbar Translate: the strip area grows a translate band with
source/target language chips and an input box. You type in the source language and the translation is written live
into the app's field. Gboard sends the text to Google's servers. pboard translates **on the device**, has no
INTERNET permission, and stores nothing you type.

| | |
|---|---|
| Engine | Own pure-Kotlin inference (`:translate`, no NDK) for **Firefox Translations (Bergamot) models**: Marian transformer encoder + SSRU decoder, int8 weights, lexical shortlist, greedy search; own SentencePiece port |
| Models | Mozilla's released `base-memory` students (31.2 M parameters per direction), MPL-2.0, used unmodified |
| Quality (FLORES-200 devtest, 10 directions) | BLEU 27.0-50.1, chrF 55.9-71.9; identical to Firefox's own engine within ±0.5 BLEU (table below) |
| Speed (dev phone, Cortex-A76) | 49 ms per sentence (12 words avg, 247 words/s, one core); 29 ms per live-typing update; model load 0.15 s from an imported pack, 0.45 s from a pack APK |
| Memory | +40 MB Java heap while the bar is open (released when it closes) |
| Pack size | 36.8 MB per direction unpacked; an en↔es pack (both directions) is a 51.6 MB zip / 54 MB pack APK |
| APK cost | ~0.1 MB of code in pboard's APK; no model is bundled |

---------------------------------------------------------------------------------------------------------------------

## 1. Engine choice

### 1.1 Candidates (licences checked at the source, 2026-11-08)

| candidate | runtime (licence) | model licence | params / direction | int8 size | notes |
|---|---|---|---|---|---|
| **Firefox Translations `base-memory`** (chosen) | bergamot-translator (MPL-2.0) + Marian (MIT); or **our Kotlin port** | **MPL-2.0** ("The model files are distributed under the MPL 2.0 license", github.com/mozilla/translations README) | 31.2 M | 31.6 MB + 4.2 MB shortlist + 0.8 MB vocab | the Firefox (desktop and Android) release models for es/fr/de/it/pt/nl/pl/...; CPU-optimised students |
| Firefox Translations `tiny` | same | MPL-2.0 | 16.9 M | 17 MB | older generation; no current tiny release for es/fr/de/it/pt (only e.g. ru-en) |
| Firefox Translations `base` | same | MPL-2.0 | 42.7 M | 43 MB | desktop-only releases for some pairs, +0-1 chrF |
| OPUS-MT base (Helsinki-NLP) | CTranslate2 (MIT) or Marian | CC-BY-4.0 (project README; HF cards say Apache-2.0) | 74-85 M | 78-91 MB | standard self-attention decoder, ~3× our compute per token |
| OPUS-MT tiny students (Helsinki-NLP/opus-mt_tiny_*) | CTranslate2 / Marian | Apache-2.0 (card); training data Tatoeba (CC-BY-NC-SA) | 17.2 M | 19.8 MB | no Portuguese; 1.6-4.7 chrF below the Firefox models |
| quickmt | CTranslate2 | CC-BY-4.0 | 175-200 M | 188-211 MB | same quality as the Firefox models at 6× the size |
| Argos Translate packages | CTranslate2 | CC-BY-4.0 (converted OPUS-MT) or unstated | 75-161 M | 75-160 MB | several packages state no licence |
| NLLB-200-distilled-600M | CT2 | **CC-BY-NC-4.0** | 600 M | 600 MB | non-commercial: excluded |
| M2M100-418M / SMaLL-100 / MADLAD-400 | CT2 | MIT / MIT / Apache-2.0 | 0.33-3 B | | far too big and slow for a keyboard |
| browsermt/students (Bergamot project) | Marian | CC-BY-SA-4.0 | ~16 M | | predecessors of the Firefox models, share-alike |

Measured quality of the alternatives on the same test set is in §1.3. The licence audit (exact statements and
URLs; checked at the source) is summarised in this table. Two caveats on training data:

- the OPUS-MT tiny / tc-big models are trained on Tatoeba-Challenge data released as CC-BY-NC-SA;
- quickmt's training sets mostly carry no licence tag.

Whether data terms carry over to weights is unsettled. The same applies to every OPUS-trained model, Mozilla's
included.

### 1.2 Runtime: pure Kotlin, int8, no NDK

The Firefox models need a Marian-compatible runtime with the SSRU decoder (CTranslate2 cannot run them). The two
options were cross-compiling bergamot-translator/Marian with the NDK, or porting the inference. A micro-benchmark
settled it: on the dev phone, ART compiles the plain Kotlin/Java loop `s += a[i] * b[j]` over `ByteArray`s into
SDOT/SMLAL vector code, reaching **9.6 G int8 MAC/s on one Cortex-A76 core** (fp32 code reaches 2.3 G MAC/s at
best). That is within a small factor of hand-written native GEMM for these small matrices. So pboard runs the models
in ~1,500 lines of Kotlin:

- `marian/MarianBinary.kt`: Marian's binary model format (`*.intgemm.alphas.bin`): int8 weights stored transposed
  (one row of K inputs per output) plus their quantisation multiplier, kept as is (1 byte per parameter in memory).
- `nn/Ops.kt`: per-row absmax quantisation of the activations, int8 × int8 → int32 dot products, layer norm,
  softmax, multi-head attention. Dynamic per-row activation scales instead of Marian's precomputed per-tensor
  alphas: slightly more precise, and the quality matches the reference engine.
- `nn/SsruTransformer.kt`: post-norm transformer encoder; decoder layers SSRU → cross-attention → FFN, with the
  cross-attention keys/values cached per sentence; output layer tied to the embeddings and restricted to the
  lexical shortlist (`marian/Shortlist.kt`); greedy search, max length 2 × source, like Firefox.
- `spm/SentencePiece.kt`: the `.spm` protobuf, the NFKC charsmap normaliser (Darts double-array), the unigram
  Viterbi, byte fallback and decoding. Unit tests check its token ids against google/sentencepiece.
- `SentenceSplitter.kt`, `MarianTranslator.kt`: sentence split that keeps the whitespace and line breaks, a
  per-sentence cache (while typing, only the sentence being typed is decoded again), long input chunked at 128 tokens.
- `LiveTranslation.kt`: translate-as-you-type scheduling. One worker thread, the newest request wins, and a
  superseded request is cancelled between decoder steps.

Benefits: the same code runs on the phone, the x86 emulator and in JVM unit tests. There is no native library,
no flake/NDK dependency and no per-ABI build. Every model file is parsed with bounds checks, so a damaged pack fails
with an error instead of crashing the keyboard.

### 1.3 Quality: FLORES-200 devtest (1012 sentences, sacreBLEU defaults, each line translated on its own)

| direction | **pboard (Kotlin)** BLEU / chrF | bergamot-translator 0.4.5 (Firefox's engine) BLEU / chrF | Mozilla registry (FLORES+) BLEU / chrF |
|---|---|---|---|
| en→es | **27.39 / 55.94** | 27.25 / 55.92 | 27.52 / 56.14 |
| es→en | **26.99 / 58.00** | 26.75 / 57.97 | 26.85 / 58.06 |
| en→fr | **49.00 / 70.91** | 48.91 / 70.84 | 48.85 / 70.80 |
| fr→en | **42.49 / 67.84** | 42.81 / 67.93 | 42.95 / 68.07 |
| en→de | **39.77 / 65.92** | 39.48 / 65.77 | 39.65 / 65.94 |
| de→en | **41.37 / 67.50** | 40.88 / 67.33 | 40.85 / 67.37 |
| en→it | **29.34 / 59.09** | 29.30 / 59.04 | 29.39 / 59.14 |
| it→en | **30.69 / 60.84** | 30.58 / 60.85 | 30.65 / 60.94 |
| en→pt | **50.08 / 71.86** | 50.08 / 71.78 | 50.44 / 71.99 |
| pt→en | **47.49 / 71.14** | 47.36 / 71.02 | 47.67 / 71.22 |

Our port is as good as the original runtime. It is 0.1 BLEU better on average because of the per-row activation
scales.

**Alternatives on the same set** (chrF, greedy decoding; run with CTranslate2 int8 on the emulator farm; parameters per
direction):

| direction | **Firefox base-memory (31 M)** | OPUS-MT tiny (17 M) | OPUS-MT base (75-85 M) | Argos (75-161 M) | quickmt (175-200 M) | OPUS-MT tc-big (231-233 M) |
|---|---|---|---|---|---|---|
| en→es | **55.94** | 54.08 | 54.80 | 53.06 | 54.72 | 56.36 |
| es→en | **58.00** | 55.17 | 58.10 | 52.71 | 57.52 | |
| en→fr | **70.91** | 67.64 | 68.96 | 68.96 | 70.99 | |
| fr→en | **67.84** | 65.94 | 66.59 | 66.59 | 69.64 | 68.90 |
| en→de | **65.92** | 61.24 | 62.70 | 65.77 | 65.33 | |
| de→en | **67.50** | 64.41 | 66.35 | 68.44 | 68.19 | |
| en→it | **59.09** | 57.45 | 57.57 | 56.07 | 58.99 | |
| it→en | **60.84** | 58.32 | 59.84 | 58.28 | 61.22 | |
| en→pt | **71.86** | (none) | 68.09 | 69.15 | 70.86 | 71.23 |
| pt→en | **71.14** | (none) | 69.04 | 69.50 | 70.59 | |
| int8 size per direction | **36.6 MB** incl. shortlist and vocab | 19.8 MB | 78-91 MB | 82-164 MB | 188-211 MB | 236-238 MB |

Results:

OPUS-MT has no direct en↔pt models; those rows use its multilingual en-ROMANCE / ROMANCE-en models. Argos en↔fr are
repackaged OPUS-MT models (identical output). The alternatives were decoded in batches with 2 threads.

- The Firefox models beat OPUS-MT base in 9 of 10 directions (es→en ties) at less than half the size.
- They beat the OPUS-MT tiny students everywhere, by 1.6 to 4.7 chrF, and there is no tiny Portuguese model.
- They match quickmt, which is 6× larger.
- Beam 4 adds only 0.1-1.5 chrF to the alternatives, so greedy decoding (Firefox's setting) is kept.
- NLLB is non-commercial; M2M100, SMaLL-100 and MADLAD-400 are 0.33-3 B parameters.

The full per-system BLEU/chrF table (beam 1 and 4) and the licence notes are reproducible with
`tools/translate/score.py` on the hypothesis files.

### 1.4 Speed and memory on the dev phone (arm64, Snapdragon 732G, Android 16; headless app_process)

`tools/phone/guard.sh --headless -- tools/translate/phone_bench.sh <model> tools/translate/bench_en.txt [cpu-mask]`,
en→es base-memory, 25 chat-style sentences of 1-21 words (12.2 on average), after JIT warm-up:

| measurement | result |
|---|---|
| model load from plain files | 152-168 ms |
| model load from the deflated pack zip (= pack APK assets) | 449 ms (inflating 36 MB) |
| first translation after loading (code not yet compiled; 21 words) | 163 ms (a release install is AOT-compiled from the baseline-profile rules in `app/src/main/baselineProfiles/translate-rules.txt`) |
| Java heap for one loaded direction | +40.4 MB |
| per sentence, translated cold (no cache) | mean 49.3 ms, median 52.3 ms, p90 68.7 ms, max 71.8 ms, 247 words/s |
| live typing (51-word text, retranslated after every word; earlier sentences cached) | mean 29.1 ms, median 28.3 ms, p90 53.5 ms, max 64.4 ms |
| pinned to the two A76 cores (`taskset c0`) | identical: the scheduler already runs it on a big core |

For comparison, Gboard waits ~300 ms after the last keystroke and then does a network round trip: 334-399 ms
from key to field update on the emulator. pboard waits 200 ms and needs ~50 ms more, so it updates the field
sooner while still not flashing half-typed words. Host (x86 JVM) timings of `:translate:evalMt` are not
meaningful for the phone: HotSpot does not vectorise the int8 loops the way ART does.

Working copies of the models (Firefox release files for the 10 directions), the built Spanish pack (directory and
zip) and the evaluation notes (licence audit, per-system scores) are kept outside the repository in
`~/pboard-data/translate/` on the build machine (`models/bm/<pair>/`, `packs/`, `eval-notes/`, `models.json` = cached
registry for `build_pack.py --registry`).

---------------------------------------------------------------------------------------------------------------------

## 2. Translation packs (format 1)

pboard has no network access, so models are installed separately. Nothing large is in pboard's APK. A pack holds
one or more one-directional models and is either a **pack APK** or a **pack file**. Packs contain data only:
nothing in a pack is ever executed.

```
manifest.json
LICENSE                          (MPL-2.0 text for the Firefox models)
en-es/model.enes.intgemm.alphas.bin   en-es/vocab.enes.spm   en-es/lex.50.50.enes.s2t.bin
es-en/model.esen.intgemm.alphas.bin   es-en/vocab.esen.spm   es-en/lex.50.50.esen.s2t.bin
```

```json
{ "format": 1, "id": "es", "version": 1, "name": "Spanish", "license": "MPL-2.0",
  "attribution": "Firefox Translations models by Mozilla (...), unmodified; source: <registry URL>",
  "models": [
    { "from": "en", "to": "es", "type": "marian-ssru",
      "model": "en-es/model.enes.intgemm.alphas.bin", "vocab": "en-es/vocab.enes.spm",
      "shortlist": "en-es/lex.50.50.enes.s2t.bin", "size": 36576277,
      "upstream": { "architecture": "base-memory", "path": "models/en-es/...", "flores200-plus": { ... } } } ] }
```

- `type` `marian-ssru` = Marian transformer with SSRU decoder and `intgemm8` weights (all current Firefox models).
  Models with separate vocabularies (CJK) use `srcVocab`/`trgVocab` instead of `vocab`. Unknown keys are ignored.
- Paths are relative, `/`-separated, and may not leave the pack (`..`, absolute paths and `\` are rejected).
- `id` is a lower-case language code; `version` is an integer (a newer import replaces an older one).

**Pack APK** (`:translatepack`): a code-free APK (`android:hasCode="false"`, no permissions, no components) named
`dev.pboard.pack.translate.<id>`, with the pack under `assets/translate/`. pboard finds it through
`PackageManager` (the 51 known ids are listed in `<queries>` for Android 11+ package visibility). It **ignores packs
not signed with pboard's own key** (`checkSignatures`) and reads the assets in place with `createPackageContext`,
so nothing is copied. Build:

```
python3 tools/translate/build_pack.py es out/ [--models <cache>] [--registry models.json]
./gradlew :translatepack:assembleRelease -PtranslatePack=out/es -PtranslatePackId=es
```

`build_pack.py` takes Mozilla's released model for each direction from the public model registry
(`.../db/models.json`). It downloads the files if they are not cached, checks them against the registry's SHA-256,
copies them unmodified, and writes the manifest, the MPL-2.0 text and a zip.

**Pack file**: the same tree zipped (`pboard-translate-<id>-v<version>.zip`). In Settings › Translate › *Import a
language pack file*, the system file picker (Storage Access Framework, no storage permission) hands pboard the zip.
pboard then:

1. extracts it into `no_backup/translate/.import-*` with path checks, at most 256 entries and 1 GB;
2. parses the manifest;
3. loads every model once to prove it works;
4. atomically replaces `no_backup/translate/<id>`.

Imported packs can be removed in the same screen; pack APKs are uninstalled through the system.

Sizes (Firefox base-memory, both directions):

| pack | unpacked | zip / APK |
|---|---|---|
| es (en↔es) | 73.6 MB | 51.6 MB / 54 MB |
| per direction (any of es/fr/de/it/pt) | 36.6-36.8 MB | ~26 MB |

---------------------------------------------------------------------------------------------------------------------

## 3. UI (measured on Gboard 18.6, implemented in `dev.pboard.ime.translate`)

Reference: Gboard 18.6.3 on a read-only gb18-1 instance (the emulator farm), 1080×2400 @ 420 dpi. The screenshots are in the
gitignored `docs/ref/gboard/translate/`. pboard's code draws the same geometry in reference px scaled by `Dimens.m`.

- **Entry**: toolbar item Translate (Material Symbols `translate`), in the features panel by default; it can be
  pinned like any item. While Translate is open, a pinned item sits on a 57.9 × 32 dp pill (secondary/onSecondary).
  The item is disabled in password fields.
- **Band** above the unchanged 44.2 dp strip; the keyboard grows by 102.9 dp (portrait):
  - a 33.9 dp round back button in the 41.9 dp side slot;
  - chips 36.2 dp tall with 16 dp text padding and 14 sp medium text;
  - a 40 × 36.2 dp swap button with 4 dp gaps;
  - the chip group centred ~2 dp right of the middle and clamped 21 dp inside the row;
  - a 400 × 36.2 dp pill input box (1.1 dp outline, 16 dp text inset, hint "Type here to translate");
  - vertical spacing 12.2 / 12.2 / 6.1 dp.
  - Landscape: one 52.2 dp row: back, box, then the chips right-aligned.
- **Colours** (Material You roles, match Gboard pixel for pixel on the emulator in light and dark): band =
  surfaceContainer, chips = primaryContainer / onPrimaryContainer, box = surfaceContainerLowest with a primary
  outline and caret, hint = outline, swap icon = onSurfaceVariant. Fixed themes derive the same roles from their key
  colours.
- **Pickers**: a centred dialog with a 28 dp corner radius over a 32 % black scrim, drawn inside the IME's
  full-height window (the whole window takes touches while it is open). It has a fixed 24 sp title ("Translate
  from" / "Translate to") and 72 dp rows. The current choice comes first with a check mark on a primaryContainer
  stadium, then up to 3 recent choices, a short 128 dp divider, and every other language alphabetically.
  pboard-specific:
  - only languages with an installed model are listed;
  - the dialog wraps a short list instead of always being 680 dp;
  - a last row "Get more languages" opens Settings › Translate.
- **Behaviour** (as measured on Gboard):
  - the keys type into the box, with suggestions, autocorrect and auto-capitalisation (shift is on in the empty box);
  - the action key is a check mark;
  - the translation is written at the app's cursor as **composing text**, replaced as a whole on every update;
  - text around the cursor is not touched;
  - one separating space is committed first when the character before the cursor is a letter;
  - **Done** commits the translation, clears the box and keeps the bar open;
  - **closing** (back button, another panel, leaving the field, rotation or theme change) commits what is in the
    field;
  - if Done or close comes before the last translation has arrived, it is computed at once and committed (waiting
    at most 1.5 s);
  - an app that restarts input on the same field (`restartInput`, e.g. a chat app after Send) does **not** close
    Translate, but the box starts over empty (`TranslateLanguages.keepOnRestart`, unit-tested);
  - hiding the keyboard (back) closes Translate and keeps the translation; the keyboard comes back on its keys with
    suggestions for the last word; another app (or field) closes it the same way.
  - All three were verified against Gboard 18.6 on a pool device (2026-11-08).
- **Languages**: the target is remembered (`translate_target`). The source opens as the keyboard language (Gboard
  resets an explicit source on reopen). Swap exchanges them if the reverse model is installed; otherwise a status
  line says which model is missing. Recents: `translate_recent_sources` / `translate_recent_targets`.
- **No pack installed**: the box becomes a status pill ("Add a translation language") that opens Settings ›
  Translate (Gboard shows "Network unavailable" in the same pill when offline).
- **Deliberate differences from Gboard**:
  - no "Detect language" (it needs a language identifier; the source is explicit);
  - no consent dialog (nothing leaves the device);
  - no emoji key on the box's letters page (as in emoji search);
  - a 200 ms debounce instead of ~300 ms plus a network round trip.

Settings › Translate (new home entry in the Clipboard/Dictionary group, where Gboard 18.6 has Translate) lists the
installed languages (directions, size, source, licence) and offers the import. Gboard's own page only has the cloud
switch "Translate your replies", which pboard omits.

## 4. Privacy

- No network code and no INTERNET permission. Models come from packs the user installs or picks.
- The text typed in the box and the translation exist only in memory. The per-sentence cache is dropped together
  with the model when the bar closes. Nothing is logged except the model load time.
- The box inherits the field's `IME_FLAG_NO_PERSONALIZED_LEARNING` (no learning from incognito fields). Translate
  is unavailable in password fields.
- Pack APKs are accepted only when signed with pboard's key; packs are data only and parsed with bounds checks.
- Imported packs live in `no_backup/` (excluded from backup, like all of pboard's data).

## 5. Code map and tests

| path | what |
|---|---|
| `translate/` (`:translate`, JVM) | engine, SentencePiece, pack manifest, `LiveTranslation`; tests: SentencePiece vs reference ids, manifest/JSON, splitter, int8 ops, synthetic SSRU model, shortlist, live scheduling; `TranslatorModelTest` against real models with `-PtranslateModels=<dir>` |
| `app/.../ime/translate/` | `TranslateMode` (session), `TranslateBarView`, `LanguagePickerView`, `TranslateInputConnection`, `TranslatePacks`, `TranslateLanguages` (pure rules, unit-tested), `TranslateStyle` |
| `app/.../settings/screens/Translate.kt` | Settings › Translate |
| `translatepack/` | pack APK module |
| `tools/translate/` | `build_pack.py`, `ref_bergamot.py` (reference outputs with bergamot-translator), `score.py` (sacreBLEU), `phone_bench.sh` + `PhoneBench.kt`, `make_test_spm.py`, `bench_en.txt` |

Reproduce the quality table: get FLORES-200 devtest
(`https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz`, use `devtest/<code>.devtest` as `<code>.txt`), then:

```
./gradlew :translate:evalMt -PevalArgs="flores <modelsRoot> <floresDir> <outDir> 5 en-es es-en ..."
python3 tools/translate/score.py <floresDir> <outDir>
```

## 6. What is left

- "Detect language" as a source (Gboard's default). It needs a small on-device language identifier: the Firefox
  vocabularies are joint (English + X), so they cannot tell the two apart.
- Pivoting through English for X→Y pairs without a direct model (two models in memory: ~80 MB).
- Pack distribution: pack APKs/zips are built with `build_pack.py` and side-loaded (pboard cannot download).
  Shipping them uncompressed in the pack APK would cut the load from 0.45 s to ~0.15 s but makes the APK ~35 % larger.
- Speed headroom for long texts: run the encoder on the two A76 cores, batch sentences.
- The merge gate does not run `:translate:test` yet.
- Not measured on Gboard: ellipsis of very long chip labels, `wm density 440`, open/close animation. (The dark
  active toolbar pill was measured later: #C0C6DC / #2A3042 = secondary / onSecondary, as implemented.)
