# Text intelligence: grammar check and Smart Compose (on device)

pboard's two writing aids beyond autocorrect, both fully offline (no INTERNET permission, nothing learned from what is
checked, nothing leaves the device):

1. **Grammar check**: a blue underline on likely grammar mistakes in the app's field, and a single fix chip in the
   strip when the cursor rests in a marked word.
2. **Smart Compose**: grey inline completion of the phrase being typed, accepted by a tap or swipe on it or a
   rightward swipe on the space bar.

Code: engine `dev.pboard.engine.textintel` (`GrammarChecker`, `InlineCompleter`, `EnglishMorphology`, `TextTokens`);
IME `dev.pboard.ime.textintel` (`PboardSpellCheckerService`, `GrammarAssist`, `ComposeController`, `GhostTextView`,
`TextIntelIme`, `TextIntelModels`, `TextIntel`). Tools: `tools/textintel/`. Settings: `Prefs.grammarCheck`,
`Prefs.spellCheck`, `Prefs.smartCompose`. Tests: `engine/src/test/kotlin/dev/pboard/engine/textintel/`.
All numbers below use the shipped 768-unit LSTM (`en.nnlm`, alpha 0.7).

## 0. Summary

* **Grammar engine: pboard's own candidate rules, verified by the n-gram + LSTM language model already on the device**
  (`GrammarChecker`). A learned ELECTRA-small token tagger trained on a rented GPU on licence-clean synthetic errors
  reached similar precision but lower recall (0.73 vs 0.91 on held-out synthetic chat errors) at 5-10x the
  compute, so it was not adopted. LanguageTool, the rule-based alternative, is LGPL and measured 47 % precision on the
  same native text.
* **Precision and false alarms** (the default-on gate: precision ≥ ~85 % and ≤ ~5 false alarms per 1,000 correct
  sentences):
  * BEA-2019 dev (W&I+LOCNESS): detection P 0.84 / R 0.015, correction P 0.62 / R 0.011 (gold-based; recall is low
    by design, the scope is narrow). CoNLL-2014: detection P 0.89, correction P 0.69.
  * Held-out native chat, DailyDialog, SMS and web text (14,269 sentences), every flag read by hand: **89 % right fixes,
    91 % real errors, 0.8 false alarms per 1,000 sentences** (11 in total). Most flags there are real mistakes in the
    text (`your all set`, `its a job`), so the raw rate of flagged sentences is higher (3-54 per 1,000; 2-20 without
    the lone `i`).
  * The prompts of the human tap/swipe TEST splits (2,331 correct sentences: MacKenzie, VelociTap, FUTO, How We Swipe
    Enron): 7 flagged sentences (3.0 per 1,000), of which 2-3 false alarms (**about 1 per 1,000**); the rest are real
    errors in the prompts (`You will loose your voice`, `Its a primary school`). Identical in en-GB and en-US. (The
    How We Swipe Enron prompts were lower-cased by the dataset, so 27 lone `i` flags there come on top.)
  * en-GB vs en-US: the same flags except 6 sentences (5 `its`→`it's` only in en-US, 1 `we're`→`were` only in en-GB);
    no rule ever touches a dialect spelling.
  * → **Grammar check stays ON by default.**
* **Smart Compose** (threshold 0.7, conservative; by default only in message-like fields, like Gboard's messaging-app
  allow-list but decided from the field type; setting: messages only / everywhere / off): in those fields a ghost text
  appears about 4 times per 100 words typed (2.4 % of
  mid-word positions, 0.16 % after a space) and **84 % of the time it is exactly what the user goes on to type**
  (held-out chat, en-US and en-GB alike); 76 % on DailyDialog, 60 % on SMS. 3.2 % of keystrokes saved on top of the
  strip. Stays ON.
* **Latency**: grammar check screens with the n-gram and runs the LSTM only for the 16 % of candidates within 4 nats
  of their threshold: desktop mean 1.8 ms per sentence (was 15 ms); phone (Snapdragon 732G) p50 38 ms, p99 122 ms per
  sentence (p99 236 ms without screening), on a background-priority binder thread after a space or full stop. Smart
  Compose on the phone: p50 6.4 ms, p99 20 ms per edit, on its own background worker. **Neither runs on the input
  path**: keystroke and commit p99 on the phone are unchanged with both features on (keystroke p99 5.03 / 5.04 / 5.05
  ms off / grammar / grammar + Smart Compose; section 6.1).
* **UI matches Gboard 18.6** (measured on the emulator): the mark is the same `SuggestionSpan` (flags 8, `#33B5E5`,
  2 dp), the chip and the tint are pixel-identical in colour (`#DCE2F9` fill, `#E3E7F7` tinted word) and size;
  Smart Compose draws an overlay at the cursor in the field's own text appearance, as Gboard does.
* **System-wide scope and privacy**: the spell checker service runs only if the user picks pboard in Android's
  Settings › System › Languages › Spell checker (Android binds only the selected checker); it checks the text the app
  sends in memory and keeps, logs and learns nothing from it (no user model in the grammar LM, no storage calls in the
  package).
* **Tagger training compute**: a 5-minute benchmark of RTX 3060 / 3090 / 4090 (9.3k / 12.9k / 17.7k sentences/s; GPU
  use 72 / 30 / 21 %), then 3 epochs in 17 minutes on the 4090 (fastest time-to-result).

## 1. What Gboard 18.6 does

Observed on the emulator farm (`gb18-1` read-only instance, Gboard 18.6.3 x86_64, fully provisioned, 420 dpi, en-US, light theme),
with the playground's new `spans` command (every span on the field: class, range, flags, colours, SuggestionSpan
flags/suggestions/underline colour) plus Gboard's own dump and logcat.
Reference screenshots: `docs/ref/gboard/textintel/` (gitignored). [V] = seen on the device; statements without
the mark were not verified on the device.

### 1.1 Grammar check

* **On device, in the spell checker.** It works in airplane mode [V]. The check runs inside Gboard's *system spell
  checker service* (per logcat a native TFLite model downloaded from gstatic, CPU), not in
  the IME: the app's `TextView` asks the service about each sentence and **Android itself draws the underline**. The
  keyboard only adds the strip chip and a tint. Setting default: on, but only on a fully provisioned install
  (a fresh install without its remote configuration has the feature off).
* **The mark** is a `SuggestionSpan` with `FLAG_GRAMMAR_ERROR` only (flags = 8: no `FLAG_EASY_CORRECT`, so the text
  view's own suggestion popup never opens), exactly one suggestion, underline `#33B5E5` about 2 dp thick (Android's
  `TextAppearance.GrammarErrorSuggestion`; spelling: flags = 2, `#FF4444`) [V].
* **When.** On the space or "." after the error is followed by a finished word, 20-40 ms after that keystroke; a pause
  alone does nothing; the word under the cursor is never checked (Android). Some only once the sentence ends with "."
  (`it's tail`, `They was`, `I seen`) [V].
* **Range.** Often the phrase being rewritten: `of done → have done`, `go to → goes to`, `a apple → an apple`,
  `The the → The`, `am more taller → am taller`; otherwise the word (`their → there`, `was → were`) [V].
* **What it flagged (en-US):** their→there, Their going→They're, it's tail→its, could/should of→have, He go→goes,
  She have→has, They/We was→were, I has→have, a apple→an apple, an dog→a dog, the the→The, more taller, I seen→saw,
  taller then→than, loose→lose, effect→affect, There is many→are, He walk→walked, can sees→see, I am agree→I agree,
  a honest→an honest, I have went→I went, informations are. **Not flagged:** Your welcome, less people, Me and him,
  Who's book, She don't like it, Can you borrow me a pen. `its a` and `i think` are fixed by autocorrect first; `alot` is
  a spelling mark; colour/organise/color/organize: nothing [V].
* **Cursor in or at the end of a marked word** (≈210 ms after the tap): the word is reopened as composing text with a
  `BackgroundColorSpan #99DCE2F9` tint (full line height), and the strip shows **one pill chip** with the fix: 59.4 ×
  33.9 dp (34 dp tall, fully rounded), centred on the toolbar's centre line, fill `#DCE2F9` (the shift / ?123 key
  colour), text `#404659` at ≈14 sp (0.8 × candidate size), 12.5-13 dp side padding, no icon [V].
* **Tapping the chip** replaces the mark in one edit, cursor at the end of the replacement, no extra space; mark and
  tint go; the strip then shows alternatives for the word at the cursor (`there | there's | three`) [V].
* The mark stays while typing on and when tapping elsewhere (only tint and chip go); backspacing inside the word removes
  it [V].

### 1.2 Smart Compose

* **Not in ordinary fields.** It never appeared in the playground (16 prefixes, online and offline) [V]. At each start
  of input Gboard turns inline suggestions off unless: the setting is on, the field is not a password/sensitive field,
  no magnification, **the app is on an allow-list** (Messages, Chat, Hangouts, WhatsApp, Telegram, LINE, Airbnb,
  Verizon Messages, Slack, Discord), and **exactly one language is enabled and it is en-US**. Apps can opt out
  with `disableSmartCompose` in `privateImeOptions`.
* **Rendering**: its own text overlay in the (tall) IME window at the app's cursor (from `CursorAnchorInfo`), not
  composing text and not a span; clipped at 0.85-0.98 × screen width per app; text size per app (16 sp default).
  Accept: "Tap or swipe on the inline suggestion to accept", "Swipe on space bar to accept" (tooltip at most 3 times).
* **Thresholds**: multi-word completions need a score ≥ −0.52 (−0.4 without remote configuration), i.e. a joint probability of
  about 0.59 if the score is a natural-log probability; single words −8.0 and ≥ 9 characters. Completions come from the
  decoder's own on-device LM.

### 1.3 Local vs server

| Feature | Where | Evidence |
|---|---|---|
| Grammar check (marks) | **on device** (spell checker service, TFLite model downloaded from gstatic) | works offline [V]; logcat [V] |
| Grammar chip and tint | on device, IME | [V] |
| Spell check | on device | works offline [V] |
| Smart Compose | on device (decoder LM), allow-listed apps, en-US only | gating as in 1.2 |
| Proofread / "Fix it" / Writing tools | **server** ("The text that's proofread will be sent to Google…") or Gemini Nano via AICore | absent on the emulator [V] |
| LLM post-correction | on-device Gemma model, switched off | - |

pboard matches the UI of the on-device features with its own models; the server proofread/writing tools are omitted.

Not yet observed (needs a pool lease): dark theme colours of chip/tint, en-GB as the only language, Smart Compose in
Google Messages (overlay position/colour, accept gestures), chip size at 440 dpi, the multi-word chip tap.

## 2. pboard's design

### 2.1 Grammar check

Same split as Gboard:

* **Marks: `PboardSpellCheckerService`** (Settings › System › Languages › Spell checker → pboard; the Corrections
  page links there). The app's text view sends sentences; the service returns
  `RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR | RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS` with the fix as the single
  suggestion, so Android draws exactly Gboard's span (flags 8, `#33B5E5`, 2 dp) — verified on the emulator with the
  playground's `spans` dump. The range follows Gboard: `a apple → an apple`, `of done → have done`,
  `the the → the`, `more taller → taller`, otherwise the word (`GrammarChecker.displayEdit`). The service also marks
  spelling (`RESULT_ATTR_LOOKS_LIKE_TYPO`, red) for words the vocabulary, the learned words and the personal dictionary
  do not know (never acronyms, words with digits or inner capitals), so choosing pboard as the spell checker loses
  nothing; corrections for those come from the strip as usual.
* **Chip: `GrammarAssist`** (in `InputController`). When the cursor settles in or at the end of a word the field has
  marked (any spell checker's `SuggestionSpan` with `FLAG_GRAMMAR_ERROR`, read back with `GET_TEXT_WITH_STYLES`), the
  strip shows one pill chip with the fix (Gboard's measured geometry: 34 dp tall, fully rounded, 12.75 dp padding,
  functional-key fill, 0.8 × candidate text size, medium weight), and a word ending at the cursor is reopened as
  composing text tinted with the functional-key colour at 60 % (`#99DCE2F9` in the default light theme) and still
  underlined. Tapping the chip replaces the mark in one edit, cursor at its end, and the strip then shows the
  alternatives for that word (`there | three | the`), as Gboard does.
* Privacy: the checker reads only the text the app sends to the spell checker (or the text around the cursor); it
  learns nothing from it and keeps nothing.

### 2.2 Smart Compose

* `ComposeController` + `GhostTextView`: after every edit (selection update) with the cursor at the end of the field,
  the sentence so far and the letters of the current word go to `InlineCompleter` on a background thread; a confident
  completion is drawn as grey text after the cursor in the keyboard's full-height window (Gboard's method: an overlay,
  not composing text, so nothing reaches the app until it is accepted). Position from `CursorAnchorInfo`
  (insertion marker × matrix), text size/font/colour from `TextAppearanceInfo` (Android 14+), faded to 45 %, clipped
  at the editor bounds or 0.9 × width.
* Accept: tap or swipe on the ghost text (its rectangle joins the window's touchable region while shown), or a
  rightward swipe on the space bar (the cursor-control drag accepts instead of moving right; one acceptance per
  gesture). The completion plus a space is committed; the typed letters stay as typed.
* **Where (setting "Smart Compose": messages only (default) / everywhere / off).** Gboard shows inline completions
  only in an allow-list of messaging apps. pboard matches that feel without an app list by looking at the field
  (`MessageFields`): by default only *message-like* fields get completions — `TYPE_TEXT_VARIATION_SHORT_MESSAGE`
  (chat boxes), `TYPE_TEXT_VARIATION_LONG_MESSAGE` (e-mail bodies, long messages), and multi-line, sentence-capitalised
  plain text fields whose enter key inserts a new line (`IME_FLAG_NO_ENTER_ACTION`; how chat and e-mail compose boxes
  that declare no variation look). Search boxes, single-line form fields, lower-case fields, e-mail subjects, URL,
  e-mail address, password and number fields do not qualify. "Everywhere" drops the field test.
* Also always required: Android 14+, English, cursor at the end of the text, a field with suggestions and personalised
  learning (no passwords, URL/e-mail fields, incognito or `IME_FLAG_NO_PERSONALIZED_LEARNING`), and no
  `disableSmartCompose` in `privateImeOptions`. Both en-GB and en-US work (Gboard: en-US only; the dialect overlay
  picks the spelling: `col` → `colour` in en-GB, `color` in en-US).
* Rendering verified on the emulator: `Let me know if you have any qu` → grey `estions` on the field's baseline in its
  size; space-bar swipe and a tap on it both commit `questions `.

### 2.3 Engine (`:engine`, pure Kotlin)

`GrammarChecker` = pboard's own candidate rules + verification by the language model already on the device (no new
model file; one 88 KB inflection table):

1. **Candidates** (`candidates()`): 43 real-word confusion sets (their/there/they're, your/you're, its/it's,
   then/than, to/too, lose/loose, affect/effect, …; none mixes dialect spellings), pronoun subject–verb agreement
   (`he go`, `they was`, `I has`, `she don't`; not after an auxiliary, a subjunctive trigger or in coordinated subjects),
   verb form after a modal or do (`can goes`, `did you saw`) and after have (`have went`), `I seen`, `could of`, a/an by
   sound (hour, university, uninformed, FBI, 8, lower-case acronyms via the lexicon's capitalisation), doubled function
   words, `more taller`, `suppose to`/`use to`, run-together words (`alot`, `aswell`) and a lone `i`. Verb forms come from
   AGID (permissive licence, attribution in NOTICE) restricted to the LM's top-40k words.
2. **Verification** (`score()`): the log-probability of the changed word plus the next three words (and the sentence
   end when the sentence is finished) with and without the fix, from the n-gram, or the n-gram + LSTM rescorer
   (`NeuralRescoringLm` with its own state cache). The n-gram screens first: a candidate it puts more than 4 nats
   below its threshold is rejected without the LSTM (84 % of candidates; dev accuracy unchanged). A small word-class
   prior (+3 nats) is added where the LM is known to underrate the fix because the error is common in its own training
   text: a possessive before an article/adverb/verb (`its a`, `your welcome`, `your going`), `their is`,
   `taller then`, `to much`.
3. **Thresholds** (`GrammarConfig.DEFAULT_THRESHOLDS`), per family with per-pair overrides for confusions whose
   members are both common in correct text (`too>to` at a sentence end, `off>of` in phrasal verbs → off,
   `an>and`, `site>sight` → off, …). Tuned on dev data only (section 3). The last word of a sentence still being typed
   needs +3 nats (it may be continued: `I went their` + ` way`).

## 3. How the grammar engines were evaluated

Everything is reproducible with `tools/textintel/` (data and outputs in the gitignored `tools/data/work/textintel/`):

```sh
python3 -I tools/textintel/gec_data.py --raw tools/data/raw/gec --heldout <LM held-out dir> --out tools/data/work/textintel
python3 -I tools/textintel/gec_synth.py --in <dev text> --out synth_chat_dev.jsonl --name synth_chat_dev --clean-out clean_chat_dev.jsonl
./gradlew :engine:gecEval -PgecArgs="cands.jsonl en-GB <set.jsonl>..."   # every candidate with its n-gram and LSTM score
python3 -I tools/textintel/gec_tune.py apply --cands cands.jsonl --thresholds cands.thresholds.json --model nn --gold <sets>
python3 -I tools/textintel/lt_bench.py --sets <sets> --mode grammar --out lt.jsonl       # LanguageTool baseline
./gradlew :engine:composeEval -PcomposeArgs="en-US <held-out .txt>..."
tools/phone/guard.sh --headless -- tools/textintel/bench_device.sh <phone-serial> 300  # ARM latency
```

**Sets.**

| set | what | sentences | use |
|---|---|---|---|
| bea_dev | BEA-2019 W&I+LOCNESS dev (learner essays A/B/C + native N), gold M2 edits | 4,384 | test (eval-only licence) |
| bea_dev_native | its LOCNESS part (native students) | 988 | test |
| conll14 | CoNLL-2014 test, 2 annotators | 1,312 | test |
| synth_chat_test | one injected error per sentence, held-out chat + DailyDialog test splits | 2,674 | test (recall per family) |
| clean_chat / dailydialog / nussms_us / web_gb / web_us | held-out native text, no gold edits | 3,000 each (web_us 2,269) | test (flags per 1,000 sentences, adjudicated) |
| synth_chat_dev, synthconf_chat_dev, synth_web_dev | injected errors in the LM's *dev* splits (synthconf: 150 per confusion pair) | 5,690 / 8,223 / 1,378 | tuning |
| clean_chat_dev, clean_tatoeba_dev, clean_wow_dev, clean_web_dev | dev-split text as negatives | 20k / 20k / 10k / 6k | tuning |

**Scoring** (`gec_score.py`, ERRANT/M2 conventions with exact edit matching): an edit is a *correct correction* when
its token span and its correction equal a gold edit, a *correct detection* when its span overlaps one; per sentence the
annotator giving the best running F0.5 is used. P/R/F0.5 are reported for both. Gold M2 sentences are detokenised
the way people type (`do n't` → `don't`), which is what the checker sees.

**Precision on native text is adjudicated by hand.** The held-out "clean" sets are ordinary chat/SMS/web text and
contain real mistakes (`your welcome`, `alot`, `its a`), so a flag there is not automatically a false alarm. Every
flag of the final configuration on the five clean test sets was read and judged *right* (a real error and a correct
fix), *wrong fix* (a real error, another fix needed) or *false alarm* (the text was fine); LanguageTool's flags were
judged on a random sample of 50. Pipeline artefacts (the LM text normaliser removed hyphens, so `half time` was
`half-time`) are excluded from both.

**Tuning used dev data only**: the thresholds were set from synthetic-error recall on dev splits and from reading the
highest-scoring candidates on the dev negatives (which are mostly real errors: `it's own`, `you're go-to` → *right*;
`six off the last ball`, `I want a cab too` → false alarms, hence `off>of` off and `too>to` at 6 nats).

## 4. Grammar check: results

### 4.1 Engine options

| option | licence | precision on native text (adjudicated) | BEA-19 dev det. / corr. P | CoNLL-14 det. / corr. P | ARM latency per sentence | verdict |
|---|---|---|---|---|---|---|
| **Rules + n-gram + LSTM verification (shipped)** | own code, CC BY-SA LM, AGID table | **89 % right fix, 91 % real error; 0.8 false alarms / 1,000 sentences** | **0.84 / 0.62** | **0.89 / 0.69** | p50 38 ms, p99 122 ms | **chosen** |
| Rules + n-gram only | same | lower: 13 false alarms vs 1 on 20k curated Tatoeba dev sentences | 0.85 / 0.61 | 0.79 / 0.71 | mean 0.24 ms, p99 0.69 ms | fallback when the LSTM is absent |
| LanguageTool 6.6 (grammar categories) | **LGPL-2.1** | 47 % (sample of 50 flags; 17 right / 36 after artefacts) | 0.75 / 0.24 | 0.81 / 0.47 | (JVM server, not on device) | baseline only |
| Learned tagger (ELECTRA-small, synthetic data, rented GPU) | Apache-2.0 weights, own data | about 95 % on chat at confidence 0.99 (1 false of 20 flags), but 16 of 43 false on SMS | 0.78 / 0.63 (R 1.0 %) | 0.89 / 0.78 (R 1.7 %) | estimated 250-550 ms in hand-written Kotlin (13.5 M parameters, 193 M multiply-adds per 20 tokens; 3 ms int8 torch on the desktop) | not adopted |

* **LanguageTool**: the rules are LGPL-2.1 data interpreted by LGPL Java (`grammar.xml` is 8.3 MB, ~1,770 rule groups,
  plus a POS dictionary and disambiguator). A "light subset" would mean porting the rule engine and shipping an
  LGPL-licensed rule file and tagger dictionary: possible as a separately licensed asset, but heavy (tens of MB), and its
  measured precision on everyday text is half ours (most of its flags are style: compounds, capitalisation, `cause`).
  Not adopted; nothing of it is in pboard.
* **Learned tagger** (`tools/textintel/tagger/`): ELECTRA-small (Apache-2.0 weights; pretraining data includes
  BooksCorpus) fine-tuned GECToR-style on 5 M sentences of the LM's licensed training text with 24 families of
  synthetic errors. At its confidence 0.99 it reached synthetic held-out chat recall 0.73 (ours 0.91) with similar
  precision, flagged deliberate repeats and SMS shorthand on SMS (16 of 43 flags false), and costs 193 M multiply-adds per
  20-token sentence (estimated 250-550 ms in hand-written Kotlin on the phone). Not adopted; a tagger proposing edits
  for the LM to verify is the natural next step if more coverage is wanted.
* **LM-based detection** is what the shipped engine is: the rules only *propose*; the LM decides. The LSTM halves the
  false alarms of the n-gram alone on curated text and adds 1-4 points of synthetic recall (confusions, verb forms).
  The n-gram screens first (`GrammarConfig.screenMargin` = 4 nats): only 16 % of candidates reach the LSTM, with the
  same dev results (synthetic recall 0.909 → 0.908, curated false alarms unchanged).

### 4.2 Held-out results of the shipped configuration (en-GB; n-gram + 768-unit LSTM, screening on)

| set | sentences | flags / 1k | correction P / R / F0.5 | detection P / R / F0.5 |
|---|---|---|---|---|
| BEA-2019 dev (W&I+LOCNESS) | 4,384 | 30.1 | 0.621 / 0.011 / 0.051 | 0.841 / 0.015 / 0.070 |
| … LOCNESS (native) part | 988 | 21.3 | 0.286 / 0.006 / 0.030 | 0.571 / 0.013 / 0.059 |
| CoNLL-2014 test | 1,312 | 19.8 | 0.692 / 0.009 / 0.043 | 0.885 / 0.012 / 0.055 |
| synthetic errors in held-out chat | 2,674 | 950 | 0.957 / 0.909 / 0.947 | 0.960 / 0.912 / 0.950 |

Sentences with any flag, per 1,000 (en-GB / en-US; "without i" leaves out the lone lower-case `i`):

| text | sentences | flagged | without i | false alarms (hand-checked) |
|---|---|---|---|---|
| held-out chat | 3,000 | 35.7 / 35.7 | 11.3 / 11.3 | 0 |
| DailyDialog (unseen corpus) | 3,000 | 3.0 / 3.0 | 2.0 / 2.0 | 2 |
| NUS SMS (unseen corpus) | 3,000 | 52.3 / 54.0 | 18.7 / 20.3 | 4 |
| British web | 3,000 | 5.0 / 5.0 | 4.0 / 4.0 | 2 |
| American web | 2,269 | 7.9 / 7.5 | 7.5 / 7.1 | 3 |
| **human typing TEST prompts** (MacKenzie, VelociTap, FUTO, HWS-Enron) | 2,331 | 14.6 / 14.6 | **3.0 / 3.0** | 2-3 (`words for desert` → dessert, a cut-off prompt `white to` → too, `an heroic` → a) |

* **Recall on the benchmarks is low by design.** Learner essays are full of edits outside the checker's scope
  (punctuation 1,090 of BEA's 7,461 gold edits, prepositions, word choice, spelling - the spell checker's and
  autocorrect's job) and the thresholds favour precision. The gold-based precision understates it: most of the "false"
  BEA flags are real errors the annotator fixed differently (`I would recommended`, `for for`, `She stay sleeping`,
  `has became`, `why did she gave`). LanguageTool's gold precision is lower on every set.
* **Synthetic recall per family** (held-out chat): a/an 98 %, comparatives 99 %, lone `i` 99 %, `could of` 100 %,
  spacing 96.5 %, after `have` 92 %, after modals/do 89 %, agreement 88 %, doubled words 88 %, confusions 81 %,
  `suppose(d) to`/`use(d) to` 54 %.
* **Adjudicated precision on native text.** All 341 flags on the five held-out sets were read. 214 are a lone
  lower-case `i` (all real). Of the other 127: 5 artefacts (the LM text normaliser had replaced numbers: `an 8` became
  `an 3`), 108 right (a real error with the right fix: `your all set`, `its a job`, `we where together`, `would you broke
  rules`, `I have sang`, `must of been`, `way to expensive`, `your piece of mind`), 3 real errors with the wrong fix
  (`will left you know` → leave, meant let; `there always a large number` → they're, meant there's; `it call tell` →
  calls, meant can), 11 false alarms: `I am really sorry too`, `oh it's the 3 April` → its, `added up to much` → too,
  `Pick of the bunch were` → where, `an ump` → a, `flying out to short right` → too, `change of led` (LED) → lead,
  `Where all y'all at` → We're, `Aight we're there` → were, `you're full awake` → your, `rehearsal from 3 to` → too.
  That is **88.5 % right fixes, 91 % real errors, 0.77 false alarms per 1,000 sentences**; with the lone `i`, 97 % of all
  flags are real errors.
* **Dialects.** No rule maps one spelling to another and the confusion sets contain no dialect pairs. en-GB and en-US
  flag exactly the same sentences on the human prompts and on British and American web text, except 6 sentences on the
  held-out sets (5 `its → it's` only in en-US, 1 `we're → were` only in en-GB), none involving a spelling variant. Unit
  test `neverFlagsEitherDialectsSpelling`: colour/color, organise/organize, travelled/traveled, centre/center,
  practise/practice, programme/program, licence/license, got/gotten - nothing flagged in either dialect.

## 5. Smart Compose: results

`InlineCompleter` follows the greedy path of the n-gram + LSTM model from the sentence so far: after a space the first
word's probability is P(w | context); mid-word it is P(w | context, w starts with the typed letters), normalised over
the model's completions of those letters; each further word multiplies in its conditional probability; the phrase
stops where a sentence end is likelier than the next word. A completion is shown when the joint probability is at
least **0.7** and it is **at least two words** (counting the word being finished) **or a single word of 9+ letters**.
Gboard's own rules are a multi-word score ≥ −0.52 (≈ probability 0.59) and single words only from 9
characters; pboard is a notch more conservative.

**How often it shows and how often it is right** (`./gradlew :engine:composeEval`): every word boundary and the first
1-2 letters of every 3+-letter word of 1,500 held-out sentences per set; "exact" = the shown text equals the next words
exactly.

| set (dialect) | shown mid-word | exact | shown after a space | exact | ghosts per 100 words | keystrokes saved* |
|---|---|---|---|---|---|---|
| held-out chat (en-US) | 2.44 % | **84.1 %** | 0.16 % | 88.9 % | 4.0 | 3.2 % |
| chat respelt British (en-GB) | 2.34 % | 84.4 % | 0.04 % | 100 % | 3.7 | 3.1 % |
| DailyDialog (en-US, unseen corpus) | 2.23 % | 76.4 % | 0.03 % | 66.7 % | 3.4 | 3.3 % |
| NUS SMS (en-US, unseen corpus) | 1.21 % | 59.9 % | 0.04 % | 100 % | 1.9 | 1.5 % |
| British web (en-GB) | 1.58 % | 76.3 % | 0.05 % | 53.3 % | 2.5 | 2.7 % |

\* a user who accepts every exact completion with one gesture (it adds the words and a space), measured against
typing every letter; on top of what the strip already saves.

The threshold trades how often it shows for how often it is right (held-out chat, mid-word / after a space):

| threshold | 0.3 | 0.4 | 0.5 | 0.6 (≈ Gboard) | **0.7** |
|---|---|---|---|---|---|
| shown | 13.6 % / 2.1 % | 8.6 % / 1.2 % | 5.6 % / 0.65 % | 3.8 % / 0.34 % | **2.4 % / 0.16 %** |
| exact | 50.4 % / 54.2 % | 61.5 % / 71.1 % | 70.2 % / 83.8 % | 76.7 % / 84.2 % | **84.1 % / 88.9 %** |
| keystrokes saved | 9.6 % | 7.6 % | 5.8 % | 4.3 % | **3.2 %** |

The n-gram alone is weaker at the same threshold (chat mid-word 1.44 % shown, 79 % exact, 2.0 % saved).

**Latency** of the path (up to 4 words): desktop JVM mean 1.5 ms, p99 5.3 ms with the LSTM (n-gram alone 0.11 /
0.37 ms); phone (Snapdragon 732G, ART): mean 6.7 ms, p50 6.4 ms, p95 17.7 ms, p99 20.0 ms (n-gram alone 0.67 /
2.0 ms). It runs on its own background thread after each edit and never delays a keystroke (section 6.1).

### 5.1 Where it shows (field types, measured on the emulator)

`tools/textintel/compose_fields.py`: held-out DailyDialog sentences typed character by character into the playground
in each field variant (pboard as the keyboard; counts from a debug log that records only that a ghost was shown, never
text). In the app a ghost is recomputed at every keystroke, so it can also appear after 3+ letters (the offline
evaluation above only scored 1-2 letters); "words with a ghost" counts each word once.

| playground field | inputType / imeOptions | Smart Compose (default "messages only") | words with a ghost | ghost updates |
|---|---|---|---|---|
| default (multi-line chat box) | text, cap sentences, auto-correct, multi-line / no enter action | **on** | 8.4 per 100 words | 26 per 100 words |
| shortmessage | `SHORT_MESSAGE`, cap sentences / send | **on** | 6.7 per 100 words | 14 per 100 words |
| nocap (multi-line, no capitals) | multi-line, no cap sentences | off | 0 | 0 |
| plain (single-line form) | text / done | off | 0 | 0 |
| search | text / search | off | 0 | 0 |
| nosuggest, email, uri, incognito | (no suggestions, address fields, no learning) | off | 0 | 0 |

25 sentences (179 words) per active variant and 12 (100 words) per inactive one. With "everywhere" the plain, search
and no-capitals fields would behave like the chat box.

## 6. Latency

| operation | desktop JVM (16 vCPU, shared host) | phone, ART (Snapdragon 732G) |
|---|---|---|
| grammar `check()` per sentence, n-gram screening + 768-unit LSTM (shipped) | mean 1.8 ms, p50 0.01 ms, p95 13.5 ms, p99 26 ms | **mean 36 ms, p50 38 ms, p95 96 ms, p99 122 ms** |
| … without screening (LSTM for every candidate) | mean 12 ms, p95 38 ms, p99 56 ms | mean 73 ms, p50 65 ms, p95 170 ms, p99 236 ms |
| grammar `check()` per sentence, n-gram only | mean 0.05 ms, p99 0.18 ms | mean 0.27 ms, p99 0.72 ms |
| Smart Compose path (up to 4 words), n-gram + LSTM | mean 1.5 ms, p99 5.3 ms | **mean 6.7 ms, p50 6.4 ms, p95 17.7 ms, p99 20.0 ms** |
| Smart Compose path, n-gram only | mean 0.11 ms, p99 0.37 ms | mean 0.67 ms, p99 2.0 ms |

Phone numbers: `tools/phone/guard.sh --headless -- tools/textintel/bench_device.sh <phone-serial> 300` (300 held-out
chat sentences + 300 with injected errors; 100 sentences for Smart Compose). The LSTM costs one step per scored
position (several ms each for the 768-unit model on this phone), which is why the n-gram screens first. The grammar
check runs on the spell checker's own thread after a space or full stop, never on the keyboard's. Gboard's marks
appeared 20-40 ms after the keystroke on the emulator; ours appear in the same spell-check round trip.

### 6.1 Off the input path

Neither feature runs on the keyboard's UI thread or its engine (decoding) thread, and neither can block a keystroke,
a commit, a decode or a prediction:

* **Grammar marks** are computed in `PboardSpellCheckerService` on the service's binder threads, at
  `THREAD_PRIORITY_BACKGROUND` for the duration of each request. Requests come from the app's text view after a space
  or full stop; each is independent and the text view ignores answers to text that has changed since.
* **The models are separate objects**: the grammar checker and the completer have their own `NgramLanguageModel` view
  of the memory-mapped pack and their own `NeuralRescoringLm` state caches; the pack and the LSTM weights are read-only,
  so there is no lock shared with the decoder's LM.
* **Smart Compose**: the UI thread only bumps a request counter and queues the work; the field is read and the model
  run on its own `SerialWorker` at background priority, and a request superseded by a newer edit is dropped before it
  reads or computes anything. The result is drawn only if no edit came in meanwhile.
* **The grammar chip** does no checking: when the cursor settles in a word (300 ms after the user moved it, not on
  keystrokes) it reads the field's existing marks (`GET_TEXT_WITH_STYLES`, two short reads, like the ones the
  keyboard already does there).
* Model loading (first use) runs on the loader thread / the service's binder thread at background priority.

**Typing latency with the features on vs off** (`InterferenceBench`, `tools/textintel/bench_interference.sh`): 300 human
tap words (TSI TUNE) replayed twice through the IME's engine configuration, with nothing else running, with a grammar
check after every committed word, and with grammar checks plus a Smart Compose completion after every keystroke, all on
a background-priority thread and with no pause between keystrokes (the worst case: the background thread is always
busy).

| mode | keystroke p50 / p99 | commit p50 / p99 | device |
|---|---|---|---|
| off | 0.96 / 2.52 ms | 2.95 / 4.78 ms | emulator (x86, 4 vCPU; relative only) |
| grammar | 0.97 / 2.68 ms | 2.90 / 4.55 ms | |
| grammar + Smart Compose | 0.98 / 2.49 ms | 2.85 / 5.25 ms | |
| off | 2.63 / 5.03 ms | 6.91 / 12.23 ms | phone (Snapdragon 732G, 8 cores) |
| grammar | 2.61 / 5.04 ms | 6.78 / 8.66 ms | |
| grammar + Smart Compose | 2.55 / 5.05 ms | 6.71 / 13.61 ms | |

The differences are within run-to-run noise: keystroke p99 does not change with the features on, and commit p99
(600 samples per mode, so set by a handful of calls) moves both ways (12.2 → 8.7 → 13.6 ms) while commit p95 stays at
7.7-7.9 ms. The background thread's own grammar checks took p50 0.06 ms / p99 0.9 ms here (short phrases, most
candidates screened out by the n-gram) and its completions p50 1.3 ms / p99 14 ms.

## 7. Defaults and settings

| setting | default | why |
|---|---|---|
| Grammar check (`grammar_check`) | **on** (was off "until an on-device model exists") | passes the gate (precision ≥ ~85 %, ≤ ~5 false alarms per 1,000 correct sentences): 89 % right fixes / 91 % real errors, 0.8 false alarms per 1,000 held-out sentences, about 1 per 1,000 on the human test prompts; Gboard's default is on. Marks appear only where pboard is the system spell checker; the chip reads any checker's marks. |
| Spell check (`spell_check`) | on (unchanged) | the spell checker service also marks unknown words, so selecting pboard loses nothing |
| Smart Compose (`smart_compose_scope`; the old `smart_compose` switch still turns it off) | **messages only**, threshold **0.7**, ≥ 2 words or a 9+ letter word | Gboard-like scope without an app list (section 2.2); in message fields about 4 ghost texts per 100 words typed, 84 % of them exactly right on held-out chat (76 % DailyDialog, 60 % SMS); none in search, form, address or lower-case fields (section 5.1). Options: messages only / everywhere / off. Android 14+ only (needs the app's text appearance). |

## 8. Privacy and dialects

* The spell checker service does nothing until the user selects pboard in Android's Settings › System › Languages ›
  Spell checker: the system binds only the selected checker. Selected, it checks what apps send (sentences of their
  text fields) in memory and returns marks; nothing is stored, logged or learned (the grammar LM has no user model,
  and the package has no file, preference or logging calls). Its spelling check reads the learned vocabulary and the
  personal dictionary only to avoid marking the user's own words.
* No network code; the models are the shipped LM pack, the LSTM and an 88 KB inflection table. Text sent by apps to
  the spell checker is checked in memory and dropped; grammar never learns, so a mistake the user keeps making is
  still flagged (the grammar LM has no user model). Smart Compose reads the learned model (the user's own phrases) but
  is off in fields that forbid learning.
* en-GB and en-US: no rule changes a spelling, the confusion sets contain no dialect pairs, and the flag counts on
  British and American text are identical under both dialects; Smart Compose spells completions in the active
  dialect (`col` → `colour` / `color`, unit-tested).

## 9. Changes outside `textintel` (for the IME and engine owners)

* `InputController` (IME): `val grammar = GrammarAssist()`; `offerGrammarFix()` runs first in the cursor-settle
  runnable (reopens a marked word that ends at the cursor as tinted composing text and shows the chip);
  `onSuggestion` applies the chip; `refreshStrip()` / `updateSuggestions()` call `grammar.dismiss()`;
  `acceptCompletion` + `insertCompletion(text)` for Smart Compose, and a rightward space-bar drag in `onCursorMove`
  accepts a shown completion instead of moving the cursor.
* `PboardIme`: one `TextIntelIme` with a call per lifecycle event (language load, view creation, start/finish input,
  `onUpdateSelection`, new `onUpdateCursorAnchorInfo`, `onComputeInsets` adds the ghost text to the touchable region,
  destroy); sets the chip tint colour and context from the theme.
* `TopBarView`: `StripItem.fix` and `drawFixChip` (the pill chip), a medium typeface.
* `KeyboardConfig` / `PrefsKeyboardConfig`: `grammarCheck`, `smartCompose`. `Prefs.grammarCheck` default on.
* `AndroidManifest.xml` + `res/xml/spellchecker.xml`: the spell checker service (`BIND_TEXT_SERVICE`; no new
  permission requested by the app). `PlaygroundActivity`: a `spans` command that dumps every span on the field.
* Engine: only the new package and two Gradle tasks (`gecEval`, `composeEval`); nothing in `decode/` or `Api.kt`
  changed. `NeuralRescoringLm` is reused with separate state caches (64 entries for grammar, 24 for Smart Compose), so
  the decoder's cache is never evicted.

## 10. Open items

* Gboard observations still missing (emulator farm was oversubscribed): dark-theme chip/tint colours, en-GB-only behaviour,
  Smart Compose rendering in Google Messages (overlay colour, tooltip), chip size in the 440-dpi size set, the chip for
  a multi-word fix.
* The chip is offered for a mark the cursor rests in or at the end of; a mark containing the cursor mid-word shows
  the chip without the composing tint (InputController's composing model assumes the cursor at the word's end).
* Spell-check marks carry no suggestions (the strip provides them when the cursor is put in the word, as with Gboard).
* Known false-alarm patterns worth a rule: lower-case `led` (LED), `an ump`, coordinated subjects after a comma
  (`…, addie and I are`).
* Smart Compose relies on cursor-anchor monitoring requested at the start of input; the hardware-keyboard mode's
  `requestCursorUpdates(0)` when it hands back to the on-screen keyboard turns it off until the next field.
* Right after a cold start of the process (e.g. an update) the spell checker's first request may come back before the
  models are loaded and miss the first sentence; it is checked again on the next edit. Preloading when the keyboard
  starts (already done for the keyboard's own use) usually covers it.
* Smart Compose: no tooltip ("Swipe on space bar to accept", Gboard shows it at most 3 times); Android 13 and older get
  no inline completion.
