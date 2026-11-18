# Conformance report: pboard vs Gboard, keystroke by keystroke

Generated 2026-11-08 23:15 by `tools/conformance/run.sh` (see tools/conformance/README.md). Every scenario is played identically on both keyboards and everything observable is compared after every step once the keyboard is idle: field text, selection, composing span, the strip's words (OCR) and layout, the keyboard page and shift state, and timing. The first divergence of each kind per scenario is clustered by what each keyboard did; clusters are ranked by scenarios affected x user impact.

Targets, in order of authority: **farm-440** and **farm-420** = Gboard 18.6.3 (x86_64) on read-only emulator-farm instances of the golden gb18-1 (verified per instance: synced look, `becuase` -> `because`, glide, main LM 2026092216), at 440 dpi (the dev phone's 350-399 dp size set) and 420 dpi (the >= 400 dp set); each batch restarts the instance from snapshot `gboard_bench_base` and pboard is `pm clear`ed. **emulator-15.1** = Gboard 15.1 on the local AVD (secondary; its pboard build is older, see its section). **phone** = Gboard 18.6 on the dev phone with its real account configuration (cross-check; nothing is reset there): clusters only seen there are listed separately.

## Harness accuracy

- **Strip OCR**, checked by eye against contact sheets (`tools/conformance/sheet.py`) of 100 distinct suggestion strips: emulator Gboard 15.1 25/30 strips exactly right, emulator pboard 28/30, phone Gboard 18.6 (dark theme) 19/20, phone pboard 18/20: **90/100 strips, about 96 % of words**. Remaining errors: a lost space in a two-word candidate (`this old` read `thisold`), a doubled letter (`k` read `Kk`), the first of three ellipsised words in a strip of truncated candidates, and chips with icons (the `Undo` / deleted-text chip). Case is decided from glyph heights (c o s u v w x z), `i` vs `l` from the gap above the stem: no case errors left in the sample after that fix.
- **Bold (the autocorrection mark)**: stroke width from a distance transform, relative to the other words of the strip: 11/11 bold candidates in the sample found, no false positives on word strips (one on an icon chip).
- **Emoji** are counted per slot from saturated blobs or OCR junk, not identified; a divergence in emoji means a different number of emoji slots.
- **Keyboard state** (page, shift, popups) by nearest reference image from each run's own calibration steps: every observation in the checked runs matched what the step must produce (CAP_SENTENCES empty field: shift on; after a letter: off; ?123: symbols; password: digit-row layout; Done: hidden); identical renders give distance 0, popups/panels far from every reference are flagged. References are validated (an image claimed by two labels keeps the label most calibration steps gave it; each label keeps its most frequent image) and missing labels come from a library of earlier validated calibrations of the same IME and geometry: in the full emulator run Gboard ignored the calibration's ?123 and emoji taps (host load ~40-55), which this caught. Letter case comes from the whole keyboard, one-shot vs caps lock from the shift key.
- **Idle detection**: a step is observed once the IME area and the field are unchanged for 3 samples spanning >= 250 ms (sampling every ~80 ms after a 150 ms minimum); per-target idle rates and settle times are in each target section. Steps start >= 1.2 s after the previous step so the idle wait cannot change behaviour; behaviour inside shorter windows is tested in real-time bursts.
- **Timing fidelity**: events are injected with exact timestamps through UiAutomation (as tools/bench); real-time hold stretches are recorded per step.

## Positive control (TF-01, tap-time key resolution)

Run on emulator-5562 (Gboard 15.1 from the clean snapshot; pboard debug build of c94ddf2, i.e. before the TF-01 fix
landed on main) with `tools/conformance/run.sh --control`: 45 scenarios / 690 steps from human taps only (6 TSI/vtap
sentences tap by tap, 10 sentences as real-time word bursts) and seeded fuzzing (6 x 20 steps); there is no hand-written
TF-01 scenario in that set. Host load was high (40-100) during that run; the same cluster had already appeared in the
first quick run at load ~25 (`human-steps/tsi/user12/task4/9` step 23: "for th" + a tap 10.7 % inside `r` gives "the" on
Gboard, "thr" on pboard). To repeat: build the pre-fix app from c94ddf2 in a separate checkout and run
`CONFORM_APP=<that apk> tools/conformance/run.sh --control`. (The raw data of this run was lost in a cleanup of a shared
temp directory; the result below is from the report generated at the time.)
- **detected**: "letter tap: G types a neighbouring key's letter (tap-time key resolution), P types the key under the finger" in 18 scenario(s); occurrences by source: human 22, fuzz 3
  - `fuzz/5/1` step 10 (tap 4% of a key width inside `h`, next to `g`): text before ` You  `; G +'g', P +'h'
  - `fuzz/5/2` step 4 (tap 5% of a key width inside `w`, next to `e`): text before `Wh`; G +'e', P +'w'
  - `fuzz/5/5` step 5 (tap 10% of a key width inside `r`, next to `e`): text before `someh`; G +'e', P +'r'
  - `human-steps/tsi/user12/task2/8` step 6 (tap 7% of a key width inside `a`, next to `s`): text before `The la`; G +'s', P +'a'
  - `human-steps/tsi/user12/task4/27` step 21 (tap 14% of a key width inside `r`, next to `e`): text before `The imagination of th`; G +'e', P +'r'
- Also found without being looked for, the same mechanism across key types (pre-fix build): a tap on `b` next to the
  space bar ending "Never" gives a space on Gboard and `b` on pboard; a tap on the delete key's edge while typing "all"
  gives `l` on Gboard while pboard deletes; a tap on the space bar's top edge after "ba" gives `c` ("back") on Gboard and
  a space on pboard.

## Target: farm-440

- Gboard: versionName=18.6.3.1002251336-release-x86_64, main LM 2026092216; pboard: versionName=0.1.1-52-gdbe4302352 lastUpdateTime=2026-11-08 22:06:03 (apk a01e7525f4f4)
- pboard build: commit dbe4302, appSameAsMain True, apkSha a01e7525f4f4
- State: Gboard restored from snapshot `gboard_bench_base` before its batch; pboard `pm clear`ed
- Scenarios: 264 (1371 steps) from qol 178, human 70, fuzz 16
- gboard observations: 1393 steps, idle reached in 1393 (100.0%), settle median 181 ms (p95 296 ms), 0 scenarios aborted; farm host load (1 min) median 28, max 54 on 48 cores (1393 of 1393 steps sampled)
- pboard observations: 1393 steps, idle reached in 1393 (100.0%), settle median 181 ms (p95 464 ms), 0 scenarios aborted; farm host load (1 min) median 28, max 58 on 48 cores (1393 of 1393 steps sampled)

Wall-clock by scenario class (runner clock, per keyboard):

| class | scenarios | steps | keystrokes | Gboard min | pboard min | steps/min | keystrokes/min |
|---|---|---|---|---|---|---|---|
| calib | 8 | 22 | 16 | 0.6 | 0.6 | 37 | 27 |
| fuzz | 16 | 397 | 396 | 6.5 | 6.6 | 61 | 61 |
| human-sentence-x0.5 | 16 | 16 | 505 | 2.1 | 2.1 | 8 | 239 |
| human-sentence-x0.7 | 16 | 16 | 505 | 2.5 | 2.6 | 6 | 199 |
| human-sentence-x1 | 32 | 32 | 978 | 6.0 | 6.0 | 5 | 162 |
| human-steps | 6 | 186 | 186 | 2.4 | 2.4 | 77 | 77 |
| qol | 178 | 724 | 736 | 18.4 | 18.5 | 39 | 40 |

### Divergence counts by kind (not allowlisted)

| kind | clusters | scenario-divergences | distinct scenarios |
|---|---|---|---|
| strip-content | 16 | 411 | 219 |
| strip-layout | 16 | 223 | 129 |
| text | 18 | 56 | 56 |
| timing | 3 | 6 | 6 |
| keyboard | 3 | 3 | 3 |
| editor-events | 1 | 1 | 1 |
| *allowlisted* | 6 | 175 | |
| *pboard wins (matched the intent, Gboard did not)* | 10 | 27 | |

### Top divergences (ranked by scenarios x impact)

**1. strip mid-word: candidates differ** — strip-content/candidates, 140 scenario(s), 506 occurrence(s), sources: fuzz, human, qol; owner: engine

- Input: `nocap` field (-), field focused — G ['hel', 'hello', 'hell'] \| P ['hel', 'hell', 'hello']; text before: `hel`
- Gboard: text `hel` sel 3; strip suggestions [hel \| hello \| hell]; keyboard letters/off
- pboard: text `hel` sel 3; strip suggestions [hel \| hell \| hello]; keyboard letters/off
- Confirmation: host load when first observed 27
- Ground truth (known intended text): both 50, neither 23
- Repro: scenario `qol/CX-08/mid-word` up to step -1: `tools/conformance/run.sh --repro-scenario qol/CX-08/mid-word --steps 0 --mode full`
- Also in: qol/ED-01/flick-150#-1, qol/CO-04/tap-into-word#-1, qol/TP-16/delete-hold-250#-1

**2. letter tap: P types a neighbouring key's letter, G types the key under the finger [Gboard matched the intended text]** — text/inserted text, 11 scenario(s), 11 occurrence(s), sources: human; owner: engine (tap-time key resolution)

- Input: `default` field (-), real-time burst of 24 taps — real-time burst, keystroke 6 (o): G +'o', P +'i'; text before: ``
- Gboard: text `I donnot fare id you do ` sel 24; strip suggestions [it \| a \| not]; keyboard letters/off
- pboard: text `I don't care if you do ` sel 23; strip suggestions [not \| that \| it]; keyboard letters/off
- Confirmation: reproduced in a fresh run (host load 45.4); host load when first observed 28
- Ground truth (known intended text): only Gboard 11
- Minimal repro (1 step, prefill-step): `tools/conformance/run.sh --repro docs/conformance/repros/farm-440-02.json`
- Also in: human-sentence-x1/tsi/user03/task1/5#0, human-sentence-x1/tsi/user03/task2/22#0, human-sentence-x0.7/tsi/user13/task1/4#0

**3. strip at after space: predictions differ** — strip-content/predictions, 107 scenario(s), 132 occurrence(s), sources: fuzz, human, qol; owner: engine

- Input: `plain` field (inputType=8193), field focused — G ['You', 'I', '', '<emoji>'] \| P ['I', 'That', '<emoji>']; text before: `i think `
- Gboard: text `i think ` sel 8; strip suggestions [You \| I \| ∅ \| <emoji>]; keyboard letters/on
- pboard: text `i think ` sel 8; strip suggestions [I \| That \| <emoji>]; keyboard letters/on
- Confirmation: host load when first observed 46
- Repro: scenario `fuzz/1/3` up to step -1: `tools/conformance/run.sh --repro-scenario fuzz/1/3 --steps 0 --mode full`
- Also in: qol/SU-02/predictions#-1, qol/CX-08/see-you-at-the#-1, qol/TP-02/pick-prediction#-1

**4. strip at mid-word: fewer word slots on G than on P** — strip-layout/slots, 52 scenario(s), 135 occurrence(s), sources: fuzz, human, qol; owner: ime-ui

- Input: `default` field (-), tap on `h` (50% of a key width inside it, next to `j`) — G ['H', 'Hello', '<emoji>', '<emoji>'] \| P ['H', 'Hello', 'How']; text before: ``
- Gboard: text `H` sel 1; strip suggestions [H \| Hello \| <emoji> \| <emoji>]; keyboard letters/off
- pboard: text `H` sel 1; strip suggestions [H \| Hello \| How]; keyboard letters/off
- Confirmation: did not reproduce in a fresh run (host load 45.4): treat as unconfirmed; host load when first observed 28
- Repro: scenario `qol/TP-05/empty` up to step 0: `tools/conformance/run.sh --repro-scenario qol/TP-05/empty --steps 1 --mode full` (did not reproduce in isolation: depends on state built earlier)
- Also in: qol/TP-04/double-space#0, qol/TP-04/after-period#0, qol/TP-16/delete-hold-250#0

**5. strip at mid-word: P marks an autocorrection (bold), the other does not** — strip-layout/bold, 33 scenario(s), 44 occurrence(s), sources: fuzz, human, qol; owner: engine

- Input: `default` field (-), tap on `t` (50% of a key width inside it, next to `y`) — G ['t', 'this', 'the'] \| P ['t', 'Hit', 'there']; text before: `Hi, `
- Gboard: text `Hi, t` sel 5; strip suggestions [t \| this \| the]; keyboard letters/off
- pboard: text `Hi, t` sel 5; strip suggestions [t \| Hit** \| there]; keyboard letters/off
- Confirmation: did not reproduce in a fresh run (host load 45.4): treat as unconfirmed; host load when first observed 28
- Repro: scenario `qol/TP-05/after-comma` up to step 0: `tools/conformance/run.sh --repro-scenario qol/TP-05/after-comma --steps 1 --mode full` (did not reproduce in isolation: depends on state built earlier)
- Also in: qol/CO-07/learn#1, qol/TP-04/then-letter#1, qol/SU-06/emoji-pizza#1

**6. space tap: P autocorrects the word, G keeps it** — text/punctuation, 7 scenario(s), 7 occurrence(s), sources: qol; owner: engine

- Input: `nocap` field (-), tap on `space` — G +' ', P 'id'->"I'd "; text before: `id`
- Gboard: text `id ` sel 3; strip suggestions [like \| be \| <emoji> \| <emoji>]; keyboard letters/off
- pboard: text `I'd ` sel 4; strip suggestions [love \| like \| be]; keyboard letters/off
- Confirmation: did not reproduce in a fresh run (host load 45.4): treat as unconfirmed; host load when first observed 21
- Repro: scenario `qol/TP-08/id` up to step 2: `tools/conformance/run.sh --repro-scenario qol/TP-08/id --steps 3 --mode full` (did not reproduce in isolation: depends on state built earlier)
- Also in: qol/CO-01/teh#3, qol/TP-08/were#4, qol/CX-02/capwords-mutli#5

**7. letter tap: G types a neighbouring key's letter (tap-time key resolution), P types the key under the finger [Gboard matched the intended text]** — text/inserted text, 5 scenario(s), 5 occurrence(s), sources: fuzz, human; owner: engine (tap-time key resolution)

- Input: `default` field (-), real-time burst of 17 taps — real-time burst, keystroke 14 (y): G +'t', P +'y'; text before: ``
- Gboard: text `We have the data ` sel 17; strip suggestions [and \| for \| to]; keyboard letters/off
- pboard: text `We have the data ` sel 17; strip suggestions [that \| N \| <emoji> \| <emoji>]; keyboard letters/off
- Confirmation: did not reproduce in a fresh run (host load 45.4): treat as unconfirmed; host load when first observed 30
- Ground truth (known intended text): only Gboard 5
- Repro: scenario `human-sentence-x1/vtap/vt_exp3/p1/mobile521.77` up to step 0: `tools/conformance/run.sh --repro-scenario human-sentence-x1/vtap/vt_exp3/p1/mobile521.77 --steps 1 --mode full` (did not reproduce in isolation: depends on state built earlier)
- Also in: fuzz/1/14#5, fuzz/1/3#13, human-steps/tsi/user05/task2/0#16

**8. strip at after space: fewer word slots on G than on P** — strip-layout/slots, 23 scenario(s), 32 occurrence(s), sources: fuzz, human, qol; owner: ime-ui

- Input: `nosuggest` field (-), field focused — G ['you', 'I', '<emoji>', '<emoji>'] \| P ['I', 'that', 'Q']; text before: `i think `
- Gboard: text `i think ` sel 8; strip suggestions [you \| I \| <emoji> \| <emoji>]; keyboard letters/off
- pboard: text `i think ` sel 8; strip suggestions [I \| that \| Q]; keyboard letters/off
- Confirmation: host load when first observed 41
- Repro: scenario `fuzz/1/2` up to step -1: `tools/conformance/run.sh --repro-scenario fuzz/1/2 --steps 0 --mode full`
- Also in: fuzz/1/7#-1, qol/TP-05/after-comma#-1, qol/TP-16/hold-delete-3000#-1

**9. strip at mid-word: more word slots on G than on P** — strip-layout/slots, 22 scenario(s), 39 occurrence(s), sources: fuzz, human, qol; owner: ime-ui

- Input: `nocap` field (-), field focused — G ['abcdef..parst', '<emoji>', ' ABdef..Imost'] \| P ['abcde...grst']; text before: `abcdefghijklmnopqrst`
- Gboard: text `abcdefghijklmnopqrst` sel 20; strip suggestions [abcdef..parst \| <emoji> \|  ABdef..Imost]; keyboard letters/off
- pboard: text `abcdefghijklmnopqrst` sel 20; strip suggestions [abcde...grst]; keyboard letters/off
- Confirmation: host load when first observed 33
- Repro: scenario `qol/ED-01/drag-234` up to step -1: `tools/conformance/run.sh --repro-scenario qol/ED-01/drag-234 --steps 0 --mode full`
- Also in: qol/ED-01/drag-right#-1, qol/ED-01/drag-right#0, fuzz/1/3#2

**10. strip at after space: G marks an autocorrection (bold), the other does not** — strip-layout/bold, 21 scenario(s), 22 occurrence(s), sources: qol; owner: engine

- Input: `nocap` field (-), real-time burst of 8 taps — G ['you', 'I', "it's O"] \| P ['I', 'of', 'the']; text before: ``
- Gboard: text `because ` sel 8; strip suggestions [you \| I \| it's O**]; keyboard letters/off
- pboard: text `because ` sel 8; strip suggestions [I \| of \| the]; keyboard letters/off
- Confirmation: reproduced in a fresh run (host load 45.4); host load when first observed 19
- Minimal repro (1 step, prefill-step): `tools/conformance/run.sh --repro docs/conformance/repros/farm-440-10.json`
- Also in: qol/TP-15/very-fast-space#0, qol/CX-02/nosuggest-i#1, qol/CO-05/post-correction#1

**11. letter tap: P types a neighbouring key's letter, G types the key under the finger** — text/inserted text, 4 scenario(s), 4 occurrence(s), sources: human, qol; owner: engine (tap-time key resolution)

- Input: `nocap` field (-), tap on `y` (0% of a key width inside it, next to `t`) — G +'y', P +'t'; text before: ``
- Gboard: text `y` sel 1; strip suggestions [y \| I \| t]; keyboard letters/off
- pboard: text `t` sel 1; strip suggestions [t \| the \| yes]; keyboard letters/off
- Confirmation: reproduced in a fresh run (host load 45.4); host load when first observed 21
- Ground truth (known intended text): neither 1
- Minimal repro (1 step, prefill-step): `tools/conformance/run.sh --repro docs/conformance/repros/farm-440-11.json`
- Also in: human-sentence-x1/vtap/vt_exp3/p4/mobile515.58#0, human-sentence-x1/vtap/vt_exp2/p21/mobile1061.14#0, human-sentence-x0.5/vtap/vt_exp2/p21/mobile1061.14#0

**12. drag from t: inserted text differs** — text/inserted text, 4 scenario(s), 4 occurrence(s), sources: qol; owner: ime-ui

- Input: `nocap` field (-), drag from `t` by +55 px over 150 ms — G +'ty', P +'t'; text before: ``
- Gboard: text `ty` sel 2; strip suggestions [th \| try \| to]; keyboard letters/off
- pboard: text `t` sel 1; strip suggestions [t \| the \| yes]; keyboard letters/off
- Confirmation: reproduced in a fresh run (host load 45.4); host load when first observed 21
- Minimal repro (1 step, prefix): `tools/conformance/run.sh --repro docs/conformance/repros/farm-440-12.json`
- Also in: qol/TF-10/slide-60#0, qol/TF-10/slide-80#0, qol/TF-10/slide-110#0

**13. strip at mid-word: emoji suggestions on P only** — strip-content/emoji, 35 scenario(s), 48 occurrence(s), sources: fuzz, human, qol; owner: engine

- Input: `nocap` field (-), tap on `i` (50% of a key width inside it, next to `o`) — G ['i', 'I', ''] \| P ['i', 'I', '<emoji>']; text before: ``
- Gboard: text `i` sel 1; strip suggestions [i \| I** \| ∅]; keyboard letters/off
- pboard: text `i` sel 1; strip suggestions [i \| I** \| <emoji>]; keyboard letters/off
- Confirmation: did not reproduce in a fresh run (host load 45.4): treat as unconfirmed; host load when first observed 21
- Ground truth (known intended text): both 8
- Repro: scenario `qol/TP-08/id` up to step 0: `tools/conformance/run.sh --repro-scenario qol/TP-08/id --steps 1 --mode full` (did not reproduce in isolation: depends on state built earlier)
- Also in: qol/CX-06/done#1, qol/TP-08/youre#1, qol/TP-04/then-letter#1

**14. strip at mid-word: emoji suggestions on G only** — strip-content/emoji, 33 scenario(s), 36 occurrence(s), sources: fuzz, human, qol; owner: engine

- Input: `nocap` field (-), field focused — G ['abcdef..parst', '<emoji>', ' ABdef..Imost'] \| P ['abcde...grst']; text before: `abcdefghijklmnopqrst`
- Gboard: text `abcdefghijklmnopqrst` sel 20; strip suggestions [abcdef..parst \| <emoji> \|  ABdef..Imost]; keyboard letters/off
- pboard: text `abcdefghijklmnopqrst` sel 20; strip suggestions [abcde...grst]; keyboard letters/off
- Confirmation: host load when first observed 33
- Ground truth (known intended text): both 5
- Repro: scenario `qol/ED-01/drag-234` up to step -1: `tools/conformance/run.sh --repro-scenario qol/ED-01/drag-234 --steps 0 --mode full`
- Also in: qol/ED-01/drag-right#-1, qol/TP-05/empty#0, qol/LT-06/hold-n#0

**15. glide: capitalisation differs** — text/capitalisation, 4 scenario(s), 4 occurrence(s), sources: fuzz, qol; owner: ime-ui

- Input: `nocap` field (-), glide `hello` — G +'hello', P +'Hello'; text before: ``
- Gboard: text `hello` sel 5; strip suggestions [∅ \| ∅ \| <emoji> \| <emoji>]; keyboard letters/off
- pboard: text `Hello` sel 5; strip suggestions [Helo \| <emoji> \| Help]; keyboard letters/off
- Confirmation: reproduced in a fresh run (host load 45.4); host load when first observed 31
- Minimal repro (1 step, prefill-step): `tools/conformance/run.sh --repro docs/conformance/repros/farm-440-15.json`
- Also in: qol/SU-05/glide-candidates#0, qol/TP-01/glide-then-letter#0, fuzz/1/13#2

**16. suggestion pick: different word inserted (strips differ)** — text/inserted text, 3 scenario(s), 3 occurrence(s), sources: qol; owner: ime-ui

- Input: `nocap` field (-), tap the centre suggestion — G +'I ', P +'not '; text before: `i am `
- Gboard: text `i am I ` sel 7; strip suggestions [going \| to \| the]; keyboard letters/off
- pboard: text `i am not ` sel 9; strip suggestions [a \| sure \| going]; keyboard letters/off
- Confirmation: reproduced in a fresh run (host load 45.4); host load when first observed 33
- Minimal repro (1 step, prefill-step): `tools/conformance/run.sh --repro docs/conformance/repros/farm-440-16.json`
- Also in: qol/CO-04/tap-into-word#1, qol/TP-02/pick-inside-word#1

**17. space tap: G autocorrects the word, P keeps it** — text/inserted text, 3 scenario(s), 3 occurrence(s), sources: human, qol; owner: engine

- Input: `default` field (-), real-time burst of 34 taps — real-time burst, keystroke 7 (space): G 'Soon we'->'Good we ', P +' '; text before: ``
- Gboard: text `Good we will return from the city.` sel 34; strip toolbar; keyboard letters/off
- pboard: text `Soon we will return from the city.` sel 34; strip toolbar; keyboard letters/off
- Confirmation: did not reproduce in a fresh run (host load 45.4): treat as unconfirmed; host load when first observed 27
- Ground truth (known intended text): both 2
- Repro: scenario `human-sentence-x1/tsi/user05/task1/23` up to step 0: `tools/conformance/run.sh --repro-scenario human-sentence-x1/tsi/user05/task1/23 --steps 1 --mode full` (did not reproduce in isolation: depends on state built earlier)
- Also in: human-sentence-x1/vtap/vt_exp3/p10/mobile611.4#0, qol/CX-02/nosuggest#7

**18. space tap: P autocorrects the word, G keeps it [Gboard matched the intended text]** — text/inserted text, 3 scenario(s), 3 occurrence(s), sources: human; owner: engine

- Input: `default` field (-), real-time burst of 35 taps — real-time burst, keystroke 20 (space): G +' ', P 'etd'->'ted '; text before: ``
- Gboard: text `One hour is allotetd for questions.` sel 35; strip toolbar; keyboard letters/off
- pboard: text `One hour is allotted for questions.` sel 35; strip toolbar; keyboard letters/off
- Confirmation: did not reproduce in a fresh run (host load 45.4): treat as unconfirmed; host load when first observed 21
- Ground truth (known intended text): only Gboard 3
- Repro: scenario `human-sentence-x1/tsi/user14/task1/20` up to step 0: `tools/conformance/run.sh --repro-scenario human-sentence-x1/tsi/user14/task1/20 --steps 1 --mode full` (did not reproduce in isolation: depends on state built earlier)
- Also in: human-sentence-x0.7/tsi/user14/task1/20#0, human-sentence-x0.5/tsi/user14/task1/20#0

**19. suggestion pick: no suggestions showing on G (tap skipped), suggestions on the other** — text/inserted text, 3 scenario(s), 3 occurrence(s), sources: fuzz; owner: ime-ui

- Input: `default` field (-), tap the centre suggestion — G no change, P +'I '; text before: ` `
- Gboard: text ` ` sel 1; strip toolbar; keyboard letters/on
- pboard: text ` I ` sel 3; strip suggestions [have \| don't \| think]; keyboard letters/off
- Confirmation: host load when first observed 37
- Repro: scenario `fuzz/1/9` up to step 1: `tools/conformance/run.sh --repro-scenario fuzz/1/9 --steps 2 --mode full`
- Also in: fuzz/1/8#19, fuzz/1/1#20

**20. strip at after space: more word slots on G than on P** — strip-layout/slots, 14 scenario(s), 15 occurrence(s), sources: fuzz, human, qol; owner: ime-ui

- Input: `plain` field (inputType=8193), field focused — G ['You', 'I', '', '<emoji>'] \| P ['I', 'That', '<emoji>']; text before: `i think `
- Gboard: text `i think ` sel 8; strip suggestions [You \| I \| ∅ \| <emoji>]; keyboard letters/on
- pboard: text `i think ` sel 8; strip suggestions [I \| That \| <emoji>]; keyboard letters/on
- Confirmation: host load when first observed 46
- Repro: scenario `fuzz/1/3` up to step -1: `tools/conformance/run.sh --repro-scenario fuzz/1/3 --steps 0 --mode full`
- Also in: human-sentence-x1/tsi/user13/task2/9#0, human-sentence-x1/tsi/user02/task3/27#0, human-sentence-x0.7/tsi/user13/task2/9#0

**21. strip at after space: emoji suggestions on G only** — strip-content/emoji, 27 scenario(s), 36 occurrence(s), sources: fuzz, human, qol; owner: engine

- Input: `nosuggest` field (-), field focused — G ['you', 'I', '<emoji>', '<emoji>'] \| P ['I', 'that', 'Q']; text before: `i think `
- Gboard: text `i think ` sel 8; strip suggestions [you \| I \| <emoji> \| <emoji>]; keyboard letters/off
- pboard: text `i think ` sel 8; strip suggestions [I \| that \| Q]; keyboard letters/off
- Confirmation: host load when first observed 41
- Repro: scenario `fuzz/1/2` up to step -1: `tools/conformance/run.sh --repro-scenario fuzz/1/2 --steps 0 --mode full`
- Also in: fuzz/1/7#-1, fuzz/1/10#-1, qol/TP-05/after-comma#-1

**22. strip at mid-word: G marks an autocorrection (bold), the other does not** — strip-layout/bold, 13 scenario(s), 25 occurrence(s), sources: fuzz, human, qol; owner: engine

- Input: `default` field (-), tap on `e` (50% of a key width inside it, next to `r`) — G ['Be', 'Because', 'We'] \| P ['Be', 'Because', 'He']; text before: `B`
- Gboard: text `Be` sel 2; strip suggestions [Be \| Because** \| We]; keyboard letters/off
- pboard: text `Be` sel 2; strip suggestions [Be \| Because \| He]; keyboard letters/off
- Confirmation: host load when first observed 33
- Repro: scenario `qol/CX-02/default` up to step 1: `tools/conformance/run.sh --repro-scenario qol/CX-02/default --steps 2 --mode full`
- Also in: qol/CX-02/incognito#1, qol/CX-02/shortmessage#1, qol/CX-02/nosuggest#2

**23. strip at after symbol: suggestions on G, toolbar on P** — strip-layout/mode, 11 scenario(s), 14 occurrence(s), sources: fuzz, human, qol; owner: ime-ui

- Input: `default` field (-), real-time burst of 34 taps — after real-time burst: G ['B c3 e S'] \| P []; text before: ``
- Gboard: text `Soon we will return from the city.` sel 34; strip suggestions [B c3 e S**]; keyboard letters/off
- pboard: text `Soon we will return from the city.` sel 34; strip toolbar; keyboard letters/off
- Confirmation: host load when first observed 27
- Repro: scenario `human-sentence-x0.7/tsi/user05/task1/23` up to step 0: `tools/conformance/run.sh --repro-scenario human-sentence-x0.7/tsi/user05/task1/23 --steps 1 --mode full`
- Also in: human-sentence-x0.5/tsi/user05/task1/23#0, qol/TP-10/open-paren#1, qol/TF-08/paren-space#1

**24. strip at mid-word: suggestions on G, toolbar on P** — strip-layout/mode, 10 scenario(s), 11 occurrence(s), sources: human, qol; owner: ime-ui

- Input: `nocap` field (-), field focused — after focus: G ['', 'AAAAA.AAAA', 'AAAA..AAAA'] \| P []; text before: `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`
- Gboard: text `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` sel 120; strip suggestions [∅ \| AAAAA.AAAA \| AAAA..AAAA]; keyboard letters/off
- pboard: text `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` sel 120; strip toolbar; keyboard letters/off
- Confirmation: host load when first observed 19
- Repro: scenario `qol/TP-16/hold-delete-1400` up to step -1: `tools/conformance/run.sh --repro-scenario qol/TP-16/hold-delete-1400 --steps 0 --mode full`
- Also in: qol/LT-05/flag#0, qol/ED-01/drag-234#0, qol/LT-05/skin-tone#0

**25. glide: different word decoded** — text/inserted text, 2 scenario(s), 2 occurrence(s), sources: fuzz; owner: engine

- Input: `uri` field (-), glide `did` — G +'did', P +'Did '; text before: `by. `
- Gboard: text `by.did ` sel 6; strip suggestions [dis \| for \| food]; keyboard field/uri
- pboard: text `by. Did ` sel 7; strip suggestions [Duff \| Dogs \| Diff]; keyboard field/uri
- Confirmation: host load when first observed 49
- Repro: scenario `fuzz/1/12` up to step 4: `tools/conformance/run.sh --repro-scenario fuzz/1/12 --steps 5 --mode full`
- Also in: fuzz/1/2#21

<details><summary>32 further clusters</summary>

- strip mid-word: the typed word is shown on G only — strip-content, 16 scenario(s): e.g. `qol/TF-02/left-edge` step 0 (G ['q', 'h', 'I'] / P ['g', 'a', 'what'])
- strip at sentence start: toolbar on G, suggestions on P — strip-layout, 7 scenario(s): e.g. `fuzz/1/5` step 0 (after space tap: G [] / P ['The', 'I', 'Becuase'])
- strip at after space: emoji suggestions on P only — strip-content, 13 scenario(s): e.g. `human-sentence-x1/tsi/user13/task2/9` step 0 (G ['the', 'to', 'into'] / P ['away', 'the', '<emoji>', '<emoji>'])
- . tap: P autocorrects the word, G keeps it — text, 1 scenario(s): e.g. `qol/CO-01/becuase-period` step 7 (G +'.', P 'uase'->'ause.')
- space tap: different text — text, 1 scenario(s): e.g. `human-sentence-x1/vtap/vt_exp3/p3/mobile1542.30` step 0 (real-time burst, keystroke 27 (space): G +' ', P 'h'->' ')
- letter tap: different text — text, 1 scenario(s): e.g. `human-sentence-x0.5/tsi/user13/task1/4` step 0 (real-time burst, keystroke 7 (t): G +'o', P +'it')
- letter tap: different letter — text, 1 scenario(s): e.g. `fuzz/1/4` step 5 (G +'%', P +'5')
- suggestion pick: no suggestions showing on P (tap skipped), suggestions on the other — text, 1 scenario(s): e.g. `fuzz/1/15` step 9 (G +' be ', P no change)
- strip mid-word: the typed word is shown on P only — strip-content, 8 scenario(s): e.g. `qol/SU-06/emoji-pizza` step 0 (G ['O', 'please', 'probably'] / P ['P', 'please', 'okay'])
- strip at after space: toolbar on G, suggestions on P — strip-layout, 4 scenario(s): e.g. `fuzz/1/9` step 0 (after space tap: G [] / P ['The', 'I', 'Becuase'])
- strip at after space: suggestions on G, toolbar on P — strip-layout, 4 scenario(s): e.g. `human-sentence-x1/tsi/user13/task2/13` step 0 (after real-time burst: G ['and', 'to', '<emoji>', '<emoji>'] / P [])
- letter tap at mid-word: spacing differs (G +'x', P +' x') — text, 1 scenario(s): e.g. `qol/TP-07/slide-shift-to-z` step 1 (G +'x', P +' x')
- space tap at mid-word: capitalisation of the committed word differs — text, 1 scenario(s): e.g. `human-sentence-x0.5/vtap/vt_exp3/p1/mobile402.74` step 0 (real-time burst, keystroke 9 (space): G +' ', P 'brian'->'Brian ')
- strip at after space: more emoji suggestions on G — strip-content, 7 scenario(s): e.g. `qol/TP-01/glide-after-space` step -1 (G ['there', 'my', '<emoji>', '<emoji>'] / P ['there', 'how', '<emoji>'])
- strip at mid-word: toolbar on G, suggestions on P — strip-layout, 3 scenario(s): e.g. `fuzz/1/8` step 11 (after letter tap: G [] / P ['t', 'the', 'that'])
- strip at sentence start: suggestions on G, toolbar on P — strip-layout, 3 scenario(s): e.g. `qol/TP-05/abbreviation` step -1 (after focus: G ['N', 'is', 'on'] / P [])
- delete tap: DEL key events on G only — editor-events, 1 scenario(s): e.g. `qol/CO-02/revert` step 8 (G 1 ['key:0:67'] / P 0 [])
- strip at mid-word: more emoji suggestions on P — strip-content, 5 scenario(s): e.g. `qol/TP-08/well` step 2 (G ['wel', 'well', '<emoji>'] / P ['wel', 'well', '<emoji>', '<emoji>'])
- strip at mid-word: more emoji suggestions on G — strip-content, 5 scenario(s): e.g. `qol/TP-01/glides` step 0 (G ["He'll", 'Help', '<emoji>', '<emoji>'] / P ['Helo', '<emoji>', 'Help'])
- shift tap at empty field: shift state differs (G on, P off) — keyboard, 1 scenario(s): e.g. `qol/ED-03/shift-cycles-case` step 1 ()
- suggestion pick at after space: shift state differs (G on, P off) — keyboard, 1 scenario(s): e.g. `fuzz/1/9` step 1 ()
- glide at mid-word: shift state differs (G on, P off) — keyboard, 1 scenario(s): e.g. `fuzz/1/13` step 2 ()
- strip mid-word: candidates differ [Gboard matched the intended text] — strip-content, 4 scenario(s): e.g. `human-steps/vtap/vt_exp2/p15/mobile2112.3` step 1 (G ['Ho', 'How', 'Hope'] / P ['Ho', 'However', 'Hi'])
- strip at after space: more emoji suggestions on P — strip-content, 4 scenario(s): e.g. `human-sentence-x1/vtap/vt_exp3/p1/mobile582.41` step 0 (G ['you', 'and', '<emoji>'] / P ['to', 'and', '<emoji>', '<emoji>'])
- strip at after symbol: more word slots on G than on P — strip-layout, 2 scenario(s): e.g. `qol/TP-06/cap-characters` step 3 (G ['', 'AB 1ST', 'AB 10'] / P ['ABT1'])
- strip at after symbol: predictions differ — strip-content, 3 scenario(s): e.g. `qol/TP-06/cap-characters` step 3 (G ['', 'AB 1ST', 'AB 10'] / P ['ABT1'])
- glide: the text changes later on G — timing, 3 scenario(s): e.g. `qol/TP-01/glides` step 0 (text changes 329 ms after the touch on G, 64 ms on P)
- strip at mid-word: emoji suggestions on P only [Gboard matched the intended text] — strip-content, 2 scenario(s): e.g. `human-steps/vtap/vt_exp2/p17/mobile489.13` step 6 (G ['Anythin', 'Anything'] / P ['Anythin', '<emoji>', "Anything's"])
- strip at mid-word: suggestions on G, other on P — strip-layout, 1 scenario(s): e.g. `qol/TP-08/ill` step 1 (after letter tap: G ['I', "I'll", 'IL'] / P [])
- drag from t: the keyboard settles later on G — timing, 2 scenario(s): e.g. `qol/TF-10/slide-55` step 0 (settles after 1089 ms on G, 180 ms on P)
- strip at empty field: emoji suggestions on G only — strip-content, 2 scenario(s): e.g. `fuzz/1/3` step 3 (G ['Undo <emoji>'] / P ['s Undo'])
- drag from shift: the keyboard settles later on P — timing, 1 scenario(s): e.g. `qol/TP-07/slide-shift-to-z` step 0 (settles after 187 ms on G, 1072 ms on P)

</details>

### Speed-only divergences (time-compressed replays only)

The same human sentences replayed at 0.7x and 0.5x of the person's timing; listed when the divergence does not
occur for that sentence at real speed (decoder lag, commits racing the last letter, rollover).

- letter tap: P types a neighbouring key's letter, G types the key under the finger — text; 3 sentence(s): `human-sentence-x0.5/tsi/user05/task1/23`, `human-sentence-x0.7/tsi/user05/task1/23`, `human-sentence-x0.7/vtap/vt_exp3/p1/mobile402.74`
- strip at after symbol: suggestions on G, toolbar on P — strip-layout; 2 sentence(s): `human-sentence-x0.5/tsi/user05/task1/23`, `human-sentence-x0.7/tsi/user05/task1/23`
- strip at after space: emoji suggestions on G only — strip-content; 2 sentence(s): `human-sentence-x0.5/vtap/vt_exp3/p3/mobile1542.30`, `human-sentence-x0.7/vtap/vt_exp3/p3/mobile1542.30`
- strip at after space: fewer word slots on G than on P — strip-layout; 2 sentence(s): `human-sentence-x0.5/vtap/vt_exp3/p3/mobile1542.30`, `human-sentence-x0.7/vtap/vt_exp3/p3/mobile1542.30`
- letter tap: different text — text; 1 sentence(s): `human-sentence-x0.5/tsi/user13/task1/4`
- space tap at mid-word: capitalisation of the committed word differs — text; 1 sentence(s): `human-sentence-x0.5/vtap/vt_exp3/p1/mobile402.74`

### pboard matched the intended text, Gboard did not (wins, not bugs)

Human items have known targets (and fuzz boundary taps a known intended letter); these divergences are where
only pboard produced what the person meant.

- letter tap: P types a neighbouring key's letter, G types the key under the finger [pboard matched the intended text] — 6 scenario(s); e.g. `human-sentence-x1/tsi/user13/task2/9` step 0: text before ``; real-time burst, keystroke 4 (b): G +'b', P +'n'
- . tap: different text [pboard matched the intended text] — 3 scenario(s); e.g. `human-sentence-x1/tsi/user13/task2/13` step 0: text before ``; real-time burst, keystroke 26 (.): G +' ', P +'.'
- letter tap: G types a neighbouring key's letter (tap-time key resolution), P types the key under the finger [pboard matched the intended text] — 3 scenario(s); e.g. `human-sentence-x1/tsi/user02/task3/27` step 0: text before ``; real-time burst, keystroke 10 (d): G +'s', P +'d'
- space tap: P autocorrects the word, G keeps it [pboard matched the intended text] — 2 scenario(s); e.g. `human-sentence-x1/tsi/user13/task4/11` step 0: text before ``; real-time burst, keystroke 28 (space): G +' ', P 'oextacular'->'pectacular '
- tap on a letter key near another key: P resolves it to a space, G executes the key under the finger [pboard matched the intended text] — 1 scenario(s); e.g. `human-sentence-x1/tsi/user11/task2/24` step 0: text before ``; real-time burst, keystroke 17 (n): G +'n', P +' '
- strip mid-word: candidates differ [pboard matched the intended text] — 4 scenario(s); e.g. `human-steps/tsi/user05/task2/0` step 1: text before `P`; G ['al', 'P', 'Pi'] \| P ['P', 'Please', 'Okay']
- strip at mid-word: emoji suggestions on P only [pboard matched the intended text] — 4 scenario(s); e.g. `human-steps/tsi/user12/task4/9` step 3: text before `Exe`; G ['Exer', 'Exe', 'Exec'] \| P ['Exer', 'Exercise', '<emoji>', '<emoji>']
- strip at mid-word: more emoji suggestions on G [pboard matched the intended text] — 2 scenario(s); e.g. `qol/TP-01/glides` step 1: text before `Hello`; G ['works', 'would', '<emoji>', '<emoji>'] \| P ['worlds', '<emoji>', 'World']
- strip mid-word: the typed word is shown on P only [pboard matched the intended text] — 1 scenario(s); e.g. `human-steps/tsi/user05/task2/0` step 7: text before `Please `; G ['O', 'please', 'put'] \| P ['P', 'provide', 'put']
- strip at mid-word: emoji suggestions on G only [pboard matched the intended text] — 1 scenario(s); e.g. `human-steps/vtap/vt_exp2/p15/mobile2112.3` step 27: text before `Hope your week is going w`; G ['we', 'wel', '', '<emoji>'] \| P ['we', 'well', 'week']

### Allowlisted (tools/conformance/allowlist.yaml)

- shift tap: SHIFT key events on G only — 17 scenario(s) — allowlist `farm-shift-key-events`
- composing region differs (G none, P set) — 146 scenario(s) — allowlist `composing-span`
- real-time burst: SHIFT key events on G only — 8 scenario(s) — allowlist `farm-shift-key-events`
- hold shift: SHIFT key events on G only — 2 scenario(s) — allowlist `farm-shift-key-events`
- drag from shift: SHIFT key events on G only — 1 scenario(s) — allowlist `farm-shift-key-events`
- chord: SHIFT key events on G only — 1 scenario(s) — allowlist `farm-shift-key-events`

## About the 18:23 farm pass (how to read it)

- **Gboard 18.6.3 on the emulator farm** (read-only `gb18-1` instances from `gboard_bench_base`, LM 2026092216, fully provisioned state
  checked per batch) at density 440 (the phone's size set) and 420. pboard: main 63da4c3's release (apk
  4ff730d2a288; includes the IME conformance fixes of 04abadd).
- **Learning history differs in this pass.** Each Gboard leg ran all 258 scenarios in order on one instance. pboard
  ran in 3 shards per density, which this pass needed to finish in time. Both keyboards learn from what is typed,
  so a scenario late in Gboard's run can see words that pboard's shard never saw. The clearest case: after
  `qol/TP-14/composing` (types `becuase` and leaves it in the field when the session ends), Gboard 18.6 stopped
  correcting `becuase` for the rest of its run. That covers `CO-01`, `CX-02` (all variants) and `TP-15`: it kept
  `becuase `, as in "space tap: P autocorrects the word, G keeps it". pboard's shard 2 ran the same TP-14 then
  CX-02/incognito and still corrected, so pboard does not learn a word abandoned in the field (Gboard does).
  `qol/SU-09/abandoned-misspelling` now tests this from a clean state. From now on, sharded runs give both keyboards
  the same shard files (same order, same history). Any cluster that does not reproduce in the shrink pass (fresh
  state on both) should be treated as history-dependent.
- **Host load.** The emulator farm had been at a load of about 30. From about 18:50 to 19:00 it went to 80-350, when 26 emulators
  were running. The tail of both Gboard legs (human sentences at 0.5x and fuzz) and the start of the pboard shards
  ran in that window. Gboard's decoder lags at that load. The legs sampled the build host's load, not the farm's. Load is now
  re-attached from the farm's samples (from 18:55:45; earlier steps have none), and the sampler reads the emulator host's
  load. Shrink rounds wait for a farm load below 48 (one per core) before confirming.
- **Phone cross-check: incomplete.** Gboard batch: adb went offline after 12 of 204 scenarios. pboard batch
  (959831a): paused for two phone benchmarks after 72 scenarios; on resume the guard refused, because the
  foreground was not a pboard test screen. It was not retried (a refused guard is never retried). The phone has no usable
  phone-only differences from this pass. It was left on main's release with pboard selected (see
  `out/<phone run>/phone_left_on.json`).

## Baseline decisions

- **Accepted trade-off of e9cf5ef/15ae32f** (engine tap resolution; maintainer's decision, 2026-11-08, after the release
  gate on main 2efbacc). In the quick gate, Gboard resolved a near-border tap to the intended neighbouring letter where
  pboard now types the key under the finger (#1: `human-steps/tsi/user12/task4/9` step 5, `vt_exp3/p1/mobile402.74`
  keystroke 12 at x1 and x0.7). In one tap the reverse happens, and pboard matched the intent (#3: `mobile402.74` x0.5
  keystroke 2). The change cut human TEST WER (VTAP 4.90 -> 3.72) with no false-correction increase, so both clusters
  are in `clusters-farm-440-quick.json`, which carries the same note.
- **Open:** "glide: different word decoded" (#2, `fuzz/1/1` step 11: Gboard `For`, pboard `Four`) is deliberately left
  out of the quick baseline, so the gate keeps flagging it until it is fixed.
- **Composition:** since 2efbacc pboard never composes, like Gboard 18.6. The composing-span allowlist entry is
  removed, and any composing region pboard sets is a divergence again.

## Findings outside the step comparison

**Status in the 18:23 farm pass (pboard 63da4c3):**
- No pboard crash: no FATAL EXCEPTION in any batch; the IME-switch crash below was fixed in c8a604c.
- The caps-lock leak is fixed: `qol/LT-11/caps-lock-new-field` matches Gboard at both densities.
- The PopupWindow focus loss after a tap into a word is still there: the same three scenarios fail at both densities.

**pboard crashes whenever another keyboard is selected (P0, ime-ui; fixed in c8a604c).** Switching from pboard to any other IME
(`adb shell ime set <gboard>`, or the system IME picker) kills the `dev.pboard` process with an "pboard keeps
stopping" dialog (and takes down anything else in that process, e.g. the playground). Seen on emulator-5562 on
every switch while setting up this harness:

```
java.lang.RuntimeException: Unable to stop service dev.pboard.ime.PboardIme: RejectedExecutionException:
  Task EngineRunner$$ExternalSyntheticLambda4 rejected from ThreadPoolExecutor[Terminated, ...]
    at EngineRunner.run(EngineRunner.kt:100)
    at PboardIme.saveLearned(PboardIme.kt:487)
    at PboardIme.onFinishInput(PboardIme.kt:500)
    at InputMethodService.doFinishInput / onDestroy
    at PboardIme.onDestroy(PboardIme.kt:156)
```

`onDestroy` shuts the engine executor down and then `super.onDestroy()` runs `onFinishInput`, whose
`saveLearned()` submits to the terminated executor. A second variant crashes from `loadLanguage`'s posted
`pushGeometry` when the service is destroyed soon after creation. Repro: select pboard, open any text field,
select another keyboard.

**pboard kept caps lock across input sessions (P0, ime-ui; fixed in 04abadd).** In the full emulator run (pboard 9c82772), after
`qol/TP-07/double-tap-lock` every later scenario started in caps lock on pboard (89 of 228; each scenario begins with
the app restarting input on the field), and pboard typed capitals mid-word, while Gboard started every field unshifted
or auto-capitalised. Caps-lock scenarios now release the lock at the end so the leak cannot cascade, and
`qol/LT-11/caps-lock-new-field` tests it directly: double-tap shift, the app restarts input, tap `a`.

**A popup took the focus after a tap into a word: harness timing artefact, fixed in the harness.** In
`qol/CO-04/tap-into-word`, `qol/CO-04/tap-end-of-word` and `qol/ED-01/drag-right` (cluster "P: focus went to
PopupWindow after tap in text"), a tap into existing text opened the TextView's spell-check suggestion popup. It
opens with either keyboard once the system spell checker has underlined the word. The checker is Gboard's service, and
it answers late (> 10 s) while Gboard's process is busy after an IME restart, so with Gboard as the keyboard the
underline was not there yet when the step tapped. Measured locally with the new playground `spell` command: one marked
word at the tap with pboard, none with Gboard. The harness now turns the system spell checker off on the test
emulators (`spell_checker_enabled 0`, recorded per batch as `spellCheckerEnabled`). Before every tap into text, the
runner also waits until the checker has settled (`awaitSpellCheck`, at most 20 s; recorded as `spellWaitMs` and
`spellMarked`); that is what applies on the phone, where settings are not changed. With both, the three scenarios keep
the focus on both keyboards. This exposed a real difference the popup had hidden: after a tap into `their`, Gboard
offers candidates for the touched word (picking one turns it into `there`), while pboard shows the toolbar. Seen
locally on Gboard 15.1; still to be confirmed on the farm's 18.6.

**Keyboard size on the phone: fixed.** With main 9342f69 (Gboard 18.6 size sets) pboard's key rectangles on the
dev phone (1080x2400 @ 440 dpi) match Gboard 18.6's to the pixel (both: strip 1564-1696, `q` 12,1710-106,1821, space
384,2126-800,2241; measured 2026-11-08 17:33 with `tools/conformance/geometry.py`). Before that, pboard's keyboard was
92 px taller there (128 px keys from y=1475). On the emulator (>= 400 dp) both were already identical.

## P0/P1 quality-of-life items not exercised (and why)

| item | why not |
|---|---|
| CO-08 remove a suggestion | long-press drag to a drop target; destructive to the shared phone's learned data |
| ED-04 text-editing panel, ED-06 clipboard chip, ED-08 clipboard panel | reached through toolbar buttons whose positions differ between the keyboards (pboard's default toolbar differs on purpose); the clipboard needs a copy source |
| CX-09 landscape, CX-10 one-handed | change device or keyboard settings that other users of the devices rely on |
| CX-12 physical keyboard, CX-14 autofill, CX-17 lock screen, SU-08 contacts, SU-10 personal dictionary | need system setup (hardware keys, an autofill service, a PIN, contacts, dictionary entries) |
| TF-04 globe key | needs a second language enabled |
| TF-09 key preview with glide typing off | needs a settings change on both keyboards |
| TF-12 haptics and sound | not observable from the screen or the field (dumpsys vibrator_manager would be the next observable) |
| LT-01, LT-02, LT-03 emoji variants, recents, search | the emoji panel's buttons differ between the keyboards and versions (TP-11 opens the panel and inserts two emoji) |
| LT-08 comma long-press menu | its pre-selected item opens settings |
| LT-09 TalkBack, LT-10 `.com` key | need TalkBack; Gboard 15.1 shows no `.com` key in URL fields |

