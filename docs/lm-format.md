# pboard language packs (`.lm` base packs, `.lmo` dialect overlays)

A language pack holds everything the keyboard needs about one language's words: the lexicon (spellings,
canonical capitalisation, flags), a quantised back-off n-gram model, and the locale's case and folding
rules. A *dialect overlay* adjusts a base pack for one regional variety. English ships as one shared base,
`en.lm`, plus `en_GB.lmo` and `en_US.lmo`.

Writer: `tools/lm/binfmt.py`. Reader: `engine/src/main/kotlin/dev/pboard/engine/lm/LmPack.kt` and
`DialectOverlay.kt`. Build: `tools/lm/build.sh <locale>`.

## Design goals

- **Zero-copy load.** The file is memory-mapped, or wrapped as a `ByteBuffer`, and queried in place.
  Opening a pack reads only the header, the section table, two 1 KB quantisation tables per order and the
  small locale-rule tables. The only eager work is the lowercase and fold tables (0x3000 entries), and
  reading the 0.67 MB overlay into memory. On a quiet 16 vCPU host, open + overlay + first query measured
  44 ms on a cold JVM and 21 ms warm; under heavy CPU contention the cold figure rose to 150–290 ms. The
  target was under 300 ms.
- **Locale-agnostic.**
  - Words are UTF-8 with full Unicode.
  - Hashing is FNV-1a over UTF-16 code units, which is what a JVM `String` holds, so a key never needs
    re-encoding.
  - Sorting is by UTF-16 code-unit order of the fold key (= Kotlin `String.compareTo`).
  - Case mapping is per code point: pack-supplied overrides (e.g. Turkish `I→ı`, `İ→i`) first, then
    Unicode simple lowercase (`Character.toLowerCase(int)`).
  - Prefix folding (diacritics, apostrophes, `ß→ss`, …) comes from a table stored in the pack.
  - Nothing assumes ASCII.
- **Space-sensitive languages.**
  - Words carrying the `NO_SPACE_AFTER` flag are elided clitics such as French `l'` or `qu'`. The tokeniser
    splits them off and the IME commits them without a trailing space.
  - The `COMPOUNDING` flag plus the linking-morpheme list (German `s`, `es`, `n`, `en`, `e`, `er`) let the
    model decompose closed compounds that are not in the lexicon (`NgramLanguageModel.decompose`). Such
    compounds are then known and scored instead of being treated as typos.
- **Fast queries.** `logProb` takes at most order × (order−1)/2 binary searches over child ranges.
  `predict` scans the explicit continuations of each context, then a unigram or prefix fallback; see below.

## File layout (version 1.1)

A minor version adds optional sections only. A reader of the same major version must ignore unknown
sections and fall back when an optional one is absent.

Little-endian. The 1024-byte header is followed by sections. Each section starts on an 8-byte boundary
and is followed by at least 8 zero bytes, so a reader may always load a 64-bit word at any record's byte
offset.

### Header

| offset | type | field |
|---|---|---|
| 0 | char[4] | magic: `PBLM` for a base pack, `PBLO` for an overlay |
| 4 | u16, u16 | version major (1), minor (0) |
| 8 | u32 | header size (1024) |
| 12 | u32 | flags: bit 0 `COMPOUNDING`, bit 1 `CLITICS` |
| 16 | char[16] | locale tag, ASCII, NUL-padded (`en`, `en_GB`, `de_DE`) |
| 32 | u32 | n-gram order N (base only) |
| 36 | u32 | nIds: n-gram vocabulary size including the 6 specials (≤ 65536) |
| 40 | u32 | nLex: lexicon entries (in an overlay: the length of its arrays) |
| 44 | u32[8] | count[k] for k = 1..8: number of k-grams |
| 76+k | u8 | record bits of order k, for k = 2..8 |
| 88 | f32 | base: OOV penalty, log P(a specific unknown word \| `<unk>`). Overlay: log Z, the normaliser |
| 92 | f32 | log marginal probability of a sentence start (informational) |
| 96 | u64 | build id. An overlay stores the build id of the base it was made for, and the loader rejects a mismatch |
| 124 | u32 | number of sections |
| 128 | 24 bytes each | section table: `u32 id, u32 reserved, u64 offset, u64 length` |

### Base-pack sections

| id | content |
|---|---|
| 1 | STRINGS: UTF-8 of every lexicon word, concatenated |
| 2 | STR_OFFSETS: `u32[nLex+1]` byte offsets into STRINGS |
| 3 | LEX_NGRAM: `u16[nLex]` n-gram id of each word's class |
| 4 | LEX_INCLASS: `i16[nLex]` log P(word \| its class) in milli-nats (0 for single-word classes) |
| 5 | LEX_FLAGS: `u8[nLex]`: bit 0 `OFFENSIVE`, bit 1 `NO_SPACE_AFTER` |
| 7 | HASH_LOWER: `u32[2^b]` open-addressing table with linear probing. Key: FNV-1a 32 over the UTF-16 code units of the lowercased word. Value: lexId+1 of the first entry of that lowercase group; 0 = empty |
| 8 | CLASS_START: `u32[nIds+1]` start of each n-gram id's members in CLASS_MEMBERS |
| 9 | CLASS_MEMBERS: `u32[]` lexIds of the words of each class, most frequent first. Specials and `<rare>` have no members |
| 11 | CASE_TABLE: UTF-8 lines `from<TAB>to`, the locale's lowercase overrides |
| 12 | FOLD_TABLE: UTF-8 lines `char<TAB>folded`, the fold key of every lexicon character and its uppercase form (empty = deleted) |
| 13 | UNIGRAM: `nIds+1` records of `i16 logp, i16 backoff` (milli-nats) `, u32 first child` (index into order 2). The last record is a sentinel |
| 14 | LOCALE_RULES: UTF-8 lines `clitics<TAB>l' d' …`, `linking<TAB>s es …` |
| 15 | LEX_LOGFREQ (v1.1): `i16[nLex]` log relative frequency of each word in the training mix, in milli-nats. This is the context-free prior a decoder wants. The n-gram unigram is a Kneser–Ney *continuation* probability and badly underrates words that follow few contexts, such as "Francisco" |
| 16 | VARIANTS (v1.1): `u32` pairs `(lexId, representative lexId)`, sorted by lexId, for every word in a VarCon spelling class: colour → color, realise → realize. These include pairs outside the n-gram vocabulary. The representative is the American spelling |
| 20+k | ORDER k (k = 2..N): bit-packed records, see below |
| 40+k | QUANT k: `f32[256]` probability centres, then `f32[256]` back-off centres (natural log) |

**Lexicon order.** Entries are sorted by (fold key, lowercase key) in UTF-16 order, then by decreasing
frequency.
- The completions of a prefix are therefore one contiguous lexId range, found with two binary searches
  that fold the stored words on the fly.
- The case variants of a word ("us", "US") are adjacent, and the first of them is the most frequent.

**N-gram vocabulary.**
- Fixed specials: 0 `<unk>`, 1 `<s>`, 2 `</s>`, 3 the clause token `,`, 4 `<num>`, 5 `<rare>`.
- Then one id per *class*, by decreasing frequency, up to 65,536 ids:
  - a class is usually one word;
  - a VarCon spelling pair such as {color, colour} or {realize, realise} forms one class, so the shared
    model pools the statistics of both spellings and the dialect overlay picks the spelling.
- Words beyond the vocabulary map to `<rare>`, and are scored as
  `P(<rare> | context) × P(word | <rare>)` (the in-class term).

**Records of order k.** The fields, from the least significant bit:

| field | bits |
|---|---|
| word id | 16 |
| probability code | 8 |
| back-off code | 8 (absent at order N) |
| first-child pointer into order k+1 | ⌈log₂(count[k+1]+1)⌉ (absent at order N) |

A record is at most 57 bits, so one 64-bit load at byte `(i·bits) >> 3`, shifted right by
`(i·bits) & 7`, reads it whole. Orders below N carry one extra sentinel record holding the end pointer.
Children of a node are sorted by word id and binary-searched.

**Probabilities.**
- Natural logs.
- Orders ≥ 2 are quantised to 256 equal-population bins per order, KenLM-style.
- Back-off weights are those of the unquantised model, quantised separately (as in KenLM).
  - They are deliberately *not* recomputed from the quantised probabilities. Doing so charges a context's
    whole rounding error to its back-off mass.
  - For `<s>`, which is followed by almost every word, that mass is only 0.6%. In the build of b80e983 the
    rounding exceeded it: the back-off weight of `<s>` collapsed from −3.7 to −14.7, and uncommon words lost
    about 11 nats at the start of a sentence. This was fixed in a800149.
  - `tools/lm/lmcheck.py` now gates every build against this. `ShippedPackTest` checks every word at
    sentence start and mid-sentence.
- Unigram values are 16-bit fixed point.

### Overlay sections

| id | content |
|---|---|
| 60 | ADJUST: `i16[nLex]` milli-nat log-probability adjustment per lexicon entry (base lexId order) |
| 61 | FLAGS_OR: `u8[nLex]` flags OR-ed onto the base flags (e.g. a dialect-specific offensive word) |

## Scoring

For a word w of class g after context h:

    log P(w | h) = log P_ngram(g | h) + inclass(w) + adjust_dialect(w) − logZ_dialect

The terms:
- `P_ngram` is standard Katz back-off over the trie. Starting from the longest context: if `(h, g)`
  exists, return its probability plus the back-offs accumulated so far. Otherwise add `bow(h)`, when the
  context node exists, and drop the oldest word.
- Without an overlay, `adjust` and `logZ` are 0.
- An unknown word scores `log P(<unk> | h) + oovPenalty`.
- A number scores as `<num>`.
- The user model, when it has evidence, is linearly interpolated on top (see `UserNgramModel`).

**Context handling.**
- Context words may carry punctuation. `.`, `!` and `?` end a sentence, so the next word is predicted
  after `<s>`. `,`, `;` and `:` become the clause token.
- A context shorter than N−1 words is taken to start at the beginning of the field.
- Each word is truecased: the exact spelling if it is a lexicon word, otherwise the most frequent case
  variant. A capitalised sentence-initial word maps to its lowercase form when that is the more frequent
  variant.

**`predict(context, prefix, k)`** is exact top-k without scanning the vocabulary:
1. For each context suffix, longest first, visit its explicit children. A class not yet seen scores
   `(back-offs accumulated so far) + its probability`.
2. Every remaining class scores `(all back-offs) + unigram`. With no prefix, walk the classes in
   decreasing unigram order and stop once nothing can enter the top-k. With a prefix, scan the prefix's
   contiguous lexId range.
3. Class members are expanded with their in-class and overlay terms.
4. Offensive words are skipped unless the prefix is the whole word.

Desktop JVM timings are in `docs/lm-eval.md`. The heaviest case is a one-letter prefix after a very
common context; it stays well under 2 ms.

## Size budget (en, version 1.1, measured)

| part | MB |
|---|---|
| lexicon of 223,270 words: strings 1.76, offsets 0.89, class ids 0.45, in-class 0.45, flags 0.22 | 3.77 |
| lowercase hash table (2^18 slots, load ≈ 0.78) | 1.05 |
| class start + members (n-gram id → its 66,435 member words) | 0.53 |
| unigram table (65,536 ids) | 0.52 |
| 2-grams: 1,822,423 × 52 bits | 11.85 |
| 3-grams: 759,574 × 51 bits | 4.84 |
| 4-grams: 269,182 × 24 bits | 0.81 |
| word frequencies (v1.1) + 3,964 variant pairs | 0.48 |
| **en.lm** | **23.85** |
| en_GB.lmo / en_US.lmo | 0.67 each (dense, compress well) |
| en.nnlm (neural rescorer, below) | 7.20 |

The entropy-pruning threshold is chosen by binary search to fill the byte budget (`langs.py: target_bytes`).

An order-3 pack of the same size holds 2.15M bigrams and 1.23M trigrams. It has slightly lower perplexity
on dev text, but lower top-1/top-3 next-word accuracy on conversation (see `docs/lm-eval.md`), so English
ships with order 4.

**Overlays rather than two full packs.**
- Two complete packs would put about 48 MB in the APK and duplicate all n-gram statistics.
- Dialects differ in about 2k spelling classes and a few thousand lexical preferences. A 0.67 MB overlay
  expresses that exactly, switches instantly at runtime (`NgramLanguageModel.setDialect`), and keeps both
  dialects' words known, so neither dialect's spelling is ever treated as a typo.

## Neural rescorer (`en.nnlm`, magic "PBNN")

This is an optional, separately toggled asset: a word-level LSTM over the base pack's n-gram ids.
- **Vocabulary:** ids below `nv` (16,384). Higher ids are `<rare>`, and spelling classes stay classes, so
  the dialect overlays work unchanged.
- **Weights:** int8 with per-row fp32 scales; activations are fp32.
- **Inference:** `NeuralLm`, hand-written Kotlin with no runtime dependency.

**Header** (512 bytes, little-endian):

| offset | field |
|---|---|
| 0 | magic `PBNN` |
| 4 | u16 major (1), u16 minor (0) |
| 8 | u32 header size |
| 12 | u32 nv, u32 emb, u32 hid, u32 nClusters |
| 28 | u32 cutoffs[nClusters+1]: shortlist size, then each tail cluster's end |
| 64 | u64 build id of the base pack it was trained for; checked at load |
| 72 | f32 recommended interpolation weight α (tuned on chat dev data) |
| 124 | u32 section count |
| 128 | section table, 24 bytes per entry: `u32 id, u32 kind, u32 rows, u32 cols, u64 offset` |

**Section kinds:**
- `1` (int8 matrix): `rows×cols` int8 values, then (4-aligned) `rows` f32 scales; `w[r,c] = q[r,c]·scale[r]`.
- `2` (f32 vector): `cols` f32 values.

**Section ids:**

| id | content | shape |
|---|---|---|
| 1 | embedding | nv × emb |
| 2 | LSTM input weights `W_ih` | 4·hid × emb, gate order i, f, g, o |
| 3 | LSTM recurrent weights `W_hh` | 4·hid × hid |
| 4 | bias `b_ih + b_hh` | 4·hid |
| 5, 6 | projection to the output space: `W`, `b` | emb × hid, emb |
| 7 | adaptive-softmax head | (shortlist + nClusters) × emb |
| 10 + 2i, 11 + 2i | tail cluster i: down-projection, output | (emb / 4^(i+1)) × emb, clusterSize × that |

**Computation.** One `step` per token:
- `x = E[tok]`, `g = W_ih·x + W_hh·h + b`
- `c' = σ(f)·c + σ(i)·tanh(g)`, `h' = σ(o)·tanh(c')`
- `e = W·h' + b`

Then `log P(id)` is `logsoftmax(head·e)[id]` for shortlist ids. A tail cluster k contributes
`logsoftmax(out_k·(proj_k·e))[id − start_k] + logsoftmax(head·e)[shortlist + k]`, and tail clusters are
computed lazily.

**Shipped model:** 7.03M parameters (emb 192, hid 768, cutoffs 2000/8000/16384) in a 7.20 MB asset. Each
step plus head costs about 3.5M multiply-adds. It was trained on the same chat/general streams as the
n-gram pack, sampled 0.67 : 0.33:
- a 512-unit model first: 134M tokens on 6 CPU threads, then 440M tokens on a rented GPU
  (`tools/lm/gpu_train.py`);
- that model widened to 768 units (`nnlm.py --widen`, Net2Net-style), then 1.92B more tokens. In all, that is
  about 35 passes over the 48.5M-token chat stream.

The checkpoint was chosen on chat *dev* top-1 + top-3, and α = 0.7 was tuned on the same dev sentences.
int8 export loses nothing measurable: chat-dev top-1 / top-3 is 27.2 / 41.9 for both fp32 and int8.
Results, the 512/640/768 comparison and latency are in `docs/lm-eval.md`.

**Use.** `NeuralRescoringLm(ngram, nn)` is a drop-in `LanguageModel`:
`P = α·P_nn(class)·P(word | class, dialect) + (1−α)·P_ngram`.
- `predict` rescores the n-gram's top 32 candidates, plus the LSTM's own top 8 for next-word prediction.
- `logProb` mixes for any word in the neural vocabulary; other words keep the n-gram score.
- States are cached per sentence prefix, so each committed word costs one LSTM step.

`EnglishPack.load(tag, base, overlay, neural = NeuralLm.read(bytes))` returns a `LanguagePack` whose `lm`
is the rescorer. Leaving `neural` null turns the rescorer off.

**Optional background step.** Right after a commit, the IME can call `pack.prefetch(context)` (or
`NeuralRescoringLm.prefetch`).
- The LSTM step for the new context then runs on a background daemon thread, and the call returns a `Future`
  at once.
- The next `predict` / `logProb` in that context finds the state cached and only pays for the rescoring. On
  the dev phone that is about 1.4 ms mean, instead of 3.2 ms with the step.
- A query that arrives while the step is still running waits for it rather than stepping twice.
- `LanguagePack.prefetch` does nothing when no neural LM is loaded.

Keep `NeuralLm.read(bytes)` at its default of 1 thread. On the dev phone, `threads = 2` made `predict` 3–4×
slower, although the bare step got faster (`docs/lm-eval.md`).

## Kotlin API (engine, `dev.pboard.engine.lm`)

For the decoders, the entry point is `EnglishPack`. It returns a `LanguagePack`, which is both the
`LanguageModel` and the `LexiconSource`, and destructures like a pair:

```kotlin
val base = LmPack.wrap(mappedEnLm)                       // share one base between dialects
val gb = EnglishPack.load("en-GB", base, enGbLmoBytes)   // en-AU, en-NZ, en-IE, en-IN… also map to en_GB
val us = EnglishPack.load("en-US", base, enUsLmoBytes)   // en-CA and plain "en" map to en_US
engine.setLanguages(listOf(gb.spec()))                   // LanguageSpec(tag, lm, lexicon, weight)
val (lm, lexicon) = gb
lexicon.entries()   // LexiconEntry(word = "colour", logPrior = dialect-adjusted log frequency,
                    //              variantKey = "color", offensive = false); 223k entries
```

Lower-level access:

```kotlin
val pack = LmPack.open(file)                    // or LmPack.wrap(mappedAssetBuffer)
val lm = NgramLanguageModel(pack, DialectOverlay.read(gbBytes), UserNgramModel())
lm.logProb(listOf("see", "you"), "tomorrow")     // LanguageModel
lm.predict(listOf("I", "love"), "y", 3)         // LanguageModel: completions, canonical case
lm.isKnown("colour")                             // LanguageModel (case-insensitive, both dialects)
lm.isOffensive("…"); lm.attachesToNext("l'")     // extras for decoders / IME
lm.lexIdOf("london"); lm.word(id); lm.prefixRange("lon")   // lexicon access for candidate generation
lm.observe(context, committedWord)               // on-device learning (respect no-learning fields)
lm.setDialect(usOverlay)                         // switch en_GB <-> en_US instantly
```

`logProb` remembers the last resolved context per thread. A decoder that scores many candidates in one
context pays about 0.7 µs per candidate on a desktop JVM.

## Shipping packs without network access

pboard has no INTERNET permission, so additional language packs reach the device in one of three ways.
All three end in a `ByteBuffer` handed to `LmPack.wrap()`, or a file opened with `LmPack.open()`.

1. **Bundled asset** (current English packs). Ship the pack in `app/src/main/assets/lm/`; `.lm` and `.lmo`
   are in `noCompress` (`app/build.gradle.kts`). Open it with
   `assets.openFd("lm/en.lm")`, then
   `FileInputStream(fd.fileDescriptor).channel.map(READ_ONLY, fd.startOffset, fd.length)`. This maps the
   asset straight out of the APK, with no copy.
2. **Separate pack APK** (`dev.pboard.lang.de`, …). An asset-only APK signed with the same key, installed
   from F-Droid, an app store or sideloaded.
   - The keyboard enumerates installed packs with `PackageManager.queryIntentActivities`/`getInstalledPackages`
     on a `<meta-data android:name="dev.pboard.LANGUAGE_PACK" …>` marker, plus `<queries>` in the
     manifest on Android 11+.
   - It opens a pack with `createPackageContext(pkg, 0).assets.openFd("lm/de_DE.lm")` and maps it as in (1).
   - Verify `PackageManager.checkSignatures(own, pkg) == SIGNATURE_MATCH` before trusting a pack.
   - No permission or network access is involved, and updates go through the normal app-update channel.
3. **User import via the system file picker** (`ACTION_OPEN_DOCUMENT`, Storage Access Framework).
   - Copy the chosen document into `noBackupFilesDir/lm/`, validate its magic, version and size, and for
     an overlay check that its build id matches the base pack.
   - Then `LmPack.open(file)`. The pick grants access to that one file only.

Dialect overlays (`.lmo`) are small and can travel with their base in any of these channels. A
mismatched overlay is rejected through its build id.

## User model storage (not part of the pack)

`UserNgramModel.save()` writes a small versioned binary blob (magic `PBUM`): decayed counts of the
unigrams, bigrams and trigrams the user typed, plus words the user removed. Rules:
- The IME keeps it in `noBackupFilesDir`; the manifest already has `allowBackup=false`.
- It never leaves the device.
- The IME must not call `observe()` for password or sensitive fields, nor for fields that set
  `IME_FLAG_NO_PERSONALIZED_LEARNING`.
