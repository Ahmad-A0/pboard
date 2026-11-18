# Keystroke-level conformance: pboard vs Gboard

Plays identical input through both keyboards and compares everything observable after **every** step, so
behavioural differences show up without anyone having to think of them first (the harness found Gboard's
tap-time key resolution, TF-01, from human taps alone).

```
tools/conformance/run.sh --quick --pool 4 --target farm-440    # regression gate on an emulator farm's Gboard 18.6 (~5 min)
tools/conformance/run.sh --quick                 # one local emulator (emulator-5562), ~10 min
tools/conformance/run.sh --full                  # + shrinking, writes docs/conformance/report.md
tools/conformance/run.sh --full --devices a,b,c,d --target NAME   # sharded over given emulators
tools/conformance/run.sh --quick --phone         # dev phone (Gboard 18.6), through tools/phone/guard.sh
tools/conformance/run.sh --control               # positive control (human taps + fuzzing only)
tools/conformance/run.sh --repro docs/conformance/repros/<n>.json      # one minimal repro, both keyboards
tools/conformance/run.sh --repro-scenario <id> --steps N [--mode full]  # a generated scenario's prefix
```

## Sharding (emulator farm)

The emulator farm (a remote host, `PBOARD_FARM_HOST` in local.env) runs Gboard 18.6 as read-only instances of one golden AVD (`gb18-1`, snapshot `gboard_bench_base`), leased
from the shared pool (`tools/farm/pool.sh hold`, over ssh; never start instances of your own there). With
`--devices` / `--pool`, `run.sh` splits the scenarios into one shard per device pair (`shard.py split`): every
shard starts with the calibration scenarios (each batch classifies keyboard states against its own references),
the rest go in scenario order to the shard with the least estimated runner time. Shard *k* of Gboard and of
pboard get the same file, so both keyboards see the same scenarios in the same order (same learning history).
All legs run at once, each is analysed as soon as it ends, then `shard.py merge` builds one `gboard/` and one
`pboard/` directory and everything after (diff, shrink, report, gate) is unchanged. Shrink rounds run the Gboard
and pboard batches on two devices at once. Every leg starts its device from the golden snapshot (an instance
restart: read-only instances cannot load snapshots at runtime), so a leased device carries no other job's
state. `load.jsonl` samples the emulator host's load (emulator farm over ssh) and shrink waits until that host is below one
load unit per core before confirming.

## Pieces

| file | what |
|---|---|
| `scenarios.py` | scenario sources: `qol` (the P0/P1 quality-of-life behaviours, one scenario per item), `human` (tap_tsi / tap_vtap TUNE sentences: by default each sentence as one real-time burst at the person's own rhythm, also time-compressed to 0.7x and 0.5x for the fast-typing regime; a capped share tap by tap, where per-keystroke strip and keyboard observation is the point), `fuzz` (seeded edit sequences: boundary taps, space, punctuation, backspace taps and holds, spacebar drags, taps into the text, shift, symbols page, glides, strip picks, field variants), `calib` (labelled keyboard states) |
| `project.py` | maps scenarios from the reference geometry onto each device/IME geometry, keeping positions *relative to keys* |
| `geometry.py`, `phone_shots.sh` | measure key rects, strip and slots per device/IME from two screenshots (phone geometries in `geometry/`) |
| `bench/.../Conform.kt` | on-device runner (`am instrument ... dev.pboard.bench/.Conform`): injects each step with exact timing (multi-pointer capable), waits for idle, records the field (text, selection, composing span, full change history with timestamps), hashes and saves the IME area |
| `device.py` | IME state (emulator snapshot restore for Gboard, `pm clear` for pboard; nothing reset on the phone), APKs, batches; phone runs go through the guard |
| `analyze.py` | per image: strip OCR (tesseract hOCR, case from glyph heights, `i` vs `l`, emoji blobs, bold by stroke width, dividers/slots), keyboard page and shift state by nearest calibration reference |
| `diff.py` | step-by-step alignment, divergences with signatures, clusters; `allowlist.yaml` for intended differences. Kinds: text (inserted text / spacing / capitalisation / punctuation), cursor, composing, editor-events (key events and editor actions the app receives), strip-layout, strip-content, keyboard, transient (intermediate texts visible >= 40 ms), timing (latency, settle time, lag behind real-time typing), focus (the app lost focus or died). Real-time bursts are compared as sequences of field states, so a slower keyboard is not mistaken for a different one |
| `shrink.py` | delta debugging from clean state: prefill-plus-step, current word, then ddmin |
| `shard.py` | sharded runs: split scenarios over devices, merge the legs' batches |
| `report.py`, `gate.py` | `docs/conformance/report.md`; the regression gate against `docs/conformance/clusters-<target>.json` |
| `show.py`, `sheet.py` | side-by-side step dump; OCR contact sheets for checking by eye |
| `make_snapshot.sh` | creates and verifies the emulator snapshot `conform_base` (clean, fully provisioned Gboard) |

## Observation

After each step the runner waits 150 ms, then samples a screenshot of the IME area and the field every ~80 ms
until the newest sample has been unchanged for 3 samples spanning >= 250 ms (idle) or 4 s pass. Between observed
steps the gap is short (330 ms after the last event, still enough for the keyboard to go idle) unless a keyboard
time window could join the two steps: space after space (double-space period, ~1 s on Gboard), shift after shift
(double tap, ~0.3 s), the same key twice, delete after delete, after the app restarts input, and the first step;
those get 1.2 s, beyond every measured window, so idle waits never change behaviour. The fast-typing regime (decoder
lag, commits racing the last letter, rollover) is covered by the real-time sentence bursts, compared keystroke by
keystroke through the field's change history; divergences seen only in the compressed replays are reported as
"speed-only". The change history also gives transient states and text latency. The report lists wall-clock,
steps/min and keystrokes/min per scenario class. (The benchmark's explicit idle signals for pboard and frame-based
quiescence for Gboard can replace `Conform.observe()`'s screenshot sampling once validated.)

pboard is tested as its release build (debug-signed, like the phone's, so `:bench` can instrument it).

## Phone rules

The phone is shared hardware. Every chunk of scenarios runs inside `tools/phone/guard.sh --open` (exclusive
lock, playground in front); the runner in strict mode checks the foreground with the guard's rule before
every injected step and before and after every screen capture (discarding the frame and stopping on a
mismatch), never presses keys outside the app, taps the strip only while it shows suggestions, and leaves out
scenarios that use the emoji panel's buttons or long-press menus. Neither app is cleared on the phone, so
learned state carries over between runs. pboard is selected again at the end.

pboard under test on the phone is a release built from a main checkout (`CONFORM_PHONE_APP`, default this tree's
release when its app/engine/bench equal main; `CONFORM_PHONE_COMMIT` names it). Other jobs may install branch
builds inside their own lock, so every chunk checks the installed APK's hash and reinstalls the batch's build if it
changed, recording the event in the run's `pboard_build.json`: builds are never mixed within a batch.

## Caveats

- Emulator Gboard is 15.1; the phone has 18.6, the current target.
- The system spell checker is off on the test emulators (`spell_checker_enabled 0`): its underline-and-popup
  behaviour is app-side and depended on how busy Gboard's process was (its spell checker service), not on the keyboard
  under test. On the phone it stays on, and every tap into text first waits for it to settle (`awaitSpellCheck`).
- Gboard learns within a batch (the emulator snapshot resets it per batch; shrinking reruns from clean state).
- OCR: see the accuracy section of the report; strip-content clusters tolerate one OCR character error in
  words of five or more letters.
