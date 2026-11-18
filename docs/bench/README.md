# IME typing-intelligence benchmark

Replays identical touch input on any IME running in the emulator and scores the text that comes out.
It answers one question: does pboard's autocorrect, swipe decoding and next-word prediction match
Gboard's, on the same input?

```
tools/bench/run.sh <serial> <ime-id> <geometry.json> <set.jsonl[.gz]> [key value ...]  # one set
tools/bench/human.sh <serial> <ime-id> <geometry.json> <label> [swipe-limit] [vtap-limit]  # human test splits (headline)
tools/bench/baseline.sh <serial> <ime-id> <geometry.json> <label>                     # synthetic tap + swipe + predict
```

Two kinds of input:

- **Human** (headline): the held-out *test* splits in `tools/data/human/splits/v1/` (real people's
  taps from Google TSI and Vertanen, real swipes from FUTO and How We Swipe), already projected onto
  `gboard_pixel8.json`; see `docs/eval-data.md`. `human.sh` replays `tap_tsi` (all), the first N
  items of `swipe_futo` and `swipe_hws`, and `tap_vtap`. Taps keep each person's real word terminator
  (about 10 % of TSI terminators land on the bottom letter row instead of the bar; `score.py` reports
  those items separately). TSI has no hold times, so taps are held 45 ms (fitted human median).
- **Synthetic** (secondary): `tools/bench/sets/<geometry>/{tap,swipe,predict}_v1.jsonl` from
  `gen_traces.py`, whose defaults are the human fit in `tools/data/human/typist_model.json`
  (`--typist-model` reloads it). Synthetic sets also carry the next-word prediction probes.

Results go to `tools/bench/out/<...>/` (gitignored): `results.jsonl` / `predict.jsonl` (raw per-item
field text), `score.json` + `score.txt`, `instrument.txt`, `device.txt` (build fingerprint, IME version).
`tools/bench/report.py A=a/score.json B=b/score.json` prints a side-by-side table.

### Sharding across emulators (and an emulator farm)

`run.sh` and `compare.sh` take a comma-separated device list as the first argument
(`tools/bench/run.sh emulator-5570,emulator-5572,emulator-5574 <ime> <geometry> <set>`). The set is cut
into *reset groups* - one per participant for the tap sets (TSI, Vertanen), consecutive chunks of
`CHUNK` (default 100) items otherwise - and every group starts from the same clean state, so a
result never depends on which device ran a group or on how many devices there were (verified item
for item, see gboard-baseline.md). Groups are spread largest-first over the devices and replayed in
parallel; the per-group results are merged in group order and scored as one set.

Devices:
- `tools/bench/make_bench_avds.sh --golden <avd> --count N [--first-port 5570] [--avd-dir DIR]`
  clones one prepared AVD (the *golden image*, e.g. `gb18-1` made by `tools/emulator/setup_gboard18.sh`,
  whose `gboard_bench_base` snapshot came from `make_base_snapshot.sh`) byte for byte into
  `bench-1..N` and starts them from the snapshot on ports 5570, 5572, ... Every shard then runs an
  identical Gboard: separately provisioned AVDs re-sync their remote configuration and were seen to differ on
  ~3 % of hard items. `--provision` (fresh AVD + `make_base_snapshot.sh` per device) exists but is not
  recommended. Pair devices deterministically anyway: the same groups on the same device list always
  land on the same devices, for every keyboard.
- Device-specific steps sit behind `tools/bench/device.sh check|info|restore|restart <device>`.
  `check` refuses anything that is not an emulator (`ro.kernel.qemu`/`ro.boot.qemu`), so a phone can
  never be driven by accident. Local emulators are restored through their console
  (`adb emu avd snapshot load`); `AUTO_RESTART=1` restarts a crashed local AVD from the snapshot.
- Remote farm: connect its emulators over adb TCP (`adb connect host:port`, serial `host:port`) and
  set `BENCH_DEVICE_HOOK=<script>`; `device.sh` then calls `<script> restore|restart <device> <snapshot>`
  instead of the local console (e.g. a two-line wrapper doing `ssh farm tools/bench/device.sh restore
  emulator-55xx <snapshot>` on the host where that emulator is local). Everything else (installs,
  instrumentation, screenshots) is plain `adb -s <serial>` and works unchanged over TCP. Run
  `make_bench_avds.sh` on the farm host to provision it.

Farm (a big remote host, e.g. 48 vCPU / 96 GB):
- `tools/bench/farm/bootstrap.sh <ssh-host> [--count N]`: `check_host.sh` fails fast unless `/dev/kvm`
  is usable (nested virtualisation) and sizes the farm (2 vCPU / ~3 GB RAM / ~7 GB disk per emulator,
  at most 20); installs Nix with flakes if missing; rsyncs this checkout, the prebuilt APKs
  (`tools/bench/farm/apks/`, gitignored) and the stopped golden AVD (`/var/tmp/pboard-avd/gb18-1.avd`);
  then `make_bench_avds.sh --golden` makes N byte-identical clones started from the snapshot.
- `tools/bench/farm/run_remote.sh <host> <name> <tools/bench script> [args with DEVICES]` runs the
  harness *on* the farm (no adb traffic over the network, so injection timing and snapshot loads
  stay local) under nohup, streams the log and pulls `tools/bench/out/` back into
  `tools/bench/out/farm/<name>/`. The load gate scales with the host (1.5 x its cores).

How the project's own farm (a 48-core host provisioned this way) is run:
- **Read-only instances.** Shards are `-read-only` instances of the golden `gb18-1` (Gboard 18.6,
  snapshot `gboard_bench_base`): zero extra disk, byte-identical by construction, every write
  discarded. `make_bench_avds.sh --read-only gb18-1 --count N` starts them; nothing is ever saved into
  the golden. pboard is installed per group (dev.pboard is uninstalled first if the golden holds a
  copy signed with another key).
- **Shared pool.** `tools/farm/pool.sh up|status|lease|hold|down`: registered devices, one flock per
  device. `run.sh pool ...` leases a device per reset group, so any number of runs (benchmark legs,
  conformance) share the emulators without fixed reservations; remote clients (another machine over
  `ADB_SERVER_SOCKET`) hold devices with `ssh <farm-host> 'tools/farm/pool.sh hold N'` for as long as the ssh
  session stays open.
- **Verdict.** Per commit: its own worktree on the farm, release +
  bench APKs built, then in parallel the conformance quick gate and the four verdict subsets
  (`tools/bench/sets/verdict/`: 303 TSI + 203 VTAP tap words, 200 FUTO + 200 HWS swipes, stratified over
  every test participant; `make_verdict_sets.sh`) on Gboard 18.6 and pboard, fast done-detection.
  PASS/FAIL against the last main verdict (`--baseline` records one): accuracy may not drop by more
  than max(1 pp, 2 SE); false corrections are reported with 95 % Wilson intervals, not gated (a
  300-word subset has ~200 clean words). `subset_track.py` checks a subset against full-run results.
- **Memory and admission.** Instances need 2.7-4.2 GB RSS each (the emulator raises guest RAM to
  2560 MB for this image whatever `-memory` says; KSM finds nothing to merge because the emulator does
  not mark guest RAM mergeable). Swap thrash was seen at 24-26 instances. `pool.sh` therefore admits a
  new instance or lease only while swap-in < 1000 pages/s, PSI memory "some avg10" < 10 % and load <
  1.5 x cores (`pool.sh pressure`), with a hard ceiling of 30. CPU binds first:
  15 instances that all replay hold the 1-min load at 75-100 on 48 vCPU while using ~63 GB.
- **Overload invalidates.** `run.sh` samples load and PSI every 5 s while a group runs. A group is
  invalid if the load exceeded 3.5 x cores or PSI memory exceeded 25 %, or if the group overlaps an
  incident listed in `tools/bench/out/overload_windows.txt` (`<start-epoch> <end-epoch> <reason>`).
  Re-run invalid groups with `RESUME=1` and the same `OUT`. Do not grow the pool while groups are
  running: three emulators booting at once pushed the load to ~140.
- **Resets are reboots.** A `-read-only` instance refuses `emu avd snapshot load` ("Snapshot load is
  disabled because -read-only was specified"), so every group reset restarts the emulator from the
  snapshot. That takes about 6 cores for 1-2 min, and `RESET_SLOTS` (default 3) limits how many run at
  once per host. Cheap in-place resets need writable instances, at 5.4 GB of disk each (the AVD
  directory with its 2.5 GB snapshot; the farm's ext4 has no reflinks and ~35 GB free).
- **Bad devices.** A pool worker leases only booted devices. A group that fails on a device (restore,
  restart, install) is released for another worker, up to 3 tries, and that worker avoids the device
  from then on. Before this rule, one half-booted device failed 11 groups in a row.

### IME state and gates

Every set starts from the same clean IME state. For Gboard that is the emulator snapshot
`gboard_bench_base`, made once by `tools/bench/make_base_snapshot.sh <serial>` (reboot, one wipe right
after boot so Gboard re-syncs its remote configuration and downloads its current main LM, keyboard up, wait
for the downloaded LM, verify the synced look, save) and restored by `run.sh` with
`adb emu avd snapshot load`. `pm clear` is not used for Gboard between runs: it also wipes the
synced remote configuration, and if the re-sync fails Gboard silently runs on compiled-in defaults (grey ?123,
blue Enter, tonal toolbar button, different strip and decoder behaviour), and a wipe later in a
session also leaves it on the older main LM bundled in the APK (2024102000 instead of 2025031300).

Before replaying, `run.sh` aborts unless
1. the geometry's `stateCheck` pixels match (`state_check.py`; for Gboard: ?123 and Enter in the
   synced secondary-container colour, no tonal toolbar button), and
2. "becuase" tapped on key centres + space comes out as "because" (typed three times; the last must
   be corrected).
`tools/bench/sets/gboard_pixel8/sanity_autocorrect.jsonl` (ten misspellings on key centres) is a
longer manual check. `state.txt` in each output directory records the state the result came from.

## Pieces

| piece | where | what |
|---|---|---|
| injector | `bench/` (`:bench`, APK `dev.pboard.bench`) | `Instrumentation` that reads a trace and injects `MotionEvent`s via `UiAutomation` with exact timestamps; also prediction probing and a timing self-test |
| text field | `app/.../PlaygroundActivity.kt` | plain `EditText` (normal text input type) driven by ordered broadcasts: `reset` (text/context, field variant), `get` (text, selection, composing span), `probe_*` (timing test); logs every change under logcat tag `PboardBench` |
| geometry | `tools/bench/geometry/*.json`, `measure_geometry.py` | screen-space key rects per IME (format below) |
| trace generator | `tools/bench/gen_traces.py`, `make_sets.sh` | synthetic noisy taps / swipe paths / prediction probes from held-out sentences (human-fitted typist) |
| set preparation | `tools/bench/prepare_set.py` | re-projects any shared-format set (also `.jsonl.gz`) onto the target geometry; appends a space tap to synthetic tap words that lack one |
| scoring | `tools/bench/score.py` | WER, word accuracy, false corrections, noisy-word recovery, swipe accuracy, prediction hit@k; per participant and by terminator for human sets |
| timing check | `tools/bench/timing_report.py` | summarises the injection self-test |

## Injection and its timing fidelity

`adb shell input motionevent` spawns a process per event (50-100 ms each), which destroys gesture timing.
Instead `Replay` (run with `am instrument`) builds every event itself: `eventTime = anchor + t` from
the trace, `downTime` of the stroke, finger tool type, touchscreen source. It sleeps then spins on
`System.nanoTime` to each event's deadline on an urgent-priority thread and injects it with
`UiAutomation.injectInputEvent(event, sync=false, waitForAnimations=false)` (the 3-argument TestApi
overload, via reflection; the public 2-argument overload waits for window animations after every
event on API 34+, which stalls each DOWN while the key-preview popup animates). Taps become
DOWN/UP pairs (hold from `holdMs`, default 45 ms = fitted human median); swipes become DOWN, MOVE..., UP.

So the **timestamps the IME sees are exactly the trace's**, independent of host load. Real-time
arrival (which matters to IME timers such as long-press) follows the trace up to host scheduling noise.
Every item records `stallMs`, the worst stretch of a gap between consecutive events; items above
150 ms (`stallTapMs` / `stallSwipeMs`) are redone from a reset, up to 3 times, and are flagged
`timingDegraded` if they never got under it. Items whose field did not change at all are redone too,
as are items after which the playground lost window focus (a stray touch opened something, e.g. an
IME settings screen); the harness presses back, re-launches the playground and waits for the IME.

Self-test (`am instrument -e mode timing`, summarised by `timing_report.py`): synthetic 600 ms swipes
at 60/90/120 Hz injected onto the playground window, which records every received `MotionEvent`
including historical samples. Measured on the shared dev host (load average ~42 on 8 cores, 4
emulators running), see the table in [gboard-baseline.md](gboard-baseline.md#injection-timing-fidelity):
every scheduled sample is delivered with its exact timestamp (100 %, 0 ms error); Android's input
consumer adds its usual resampled samples on top (as on a real device); the median injection call is
4-6 ms late; the one systematic real-time artefact is that the DOWN is processed slowly by the busy
system (typically 30-120 ms), after which the following MOVEs catch up. That is far below long-press
timeouts (300 ms+) and does not affect timestamps.

## Trace format (shared with the JVM engine eval)

One JSON object per line, one intended word per line:

```json
{"context": "I think we ", "target": "should", "kind": "tap",
 "points": [[x, y, tMs], ...], "holdMs": [70, ...], ...annotations}
```

- `context`: text before the word (with trailing space; "" at the start of the field). The harness
  pre-fills it unless the field already holds it (continuous typing, `chain=true` default).
- `kind: "tap"`: each point is one touch-down, held `holdMs[i]` (optional). The word's final point
  should be the spacebar (the generator does this; `prepare_set.py` adds one if missing), because a
  person commits a word with space and IMEs autocorrect on commit.
- `kind: "swipe"`: one continuous stroke; words are separated by the IME's auto-space.
- `kind: "predict"`: next-word probe. `prefix` is pre-filled, `points` type the last context word
  cleanly plus space, then each suggestion slot is tapped (retyping from scratch before each slot);
  `target` is the word that should be predicted. `baseline.sh` runs probes in the `incognito` field
  variant (IME_FLAG_NO_PERSONALIZED_LEARNING) so that tapping one slot does not teach the IME and
  reorder the strip for the next probe.
- Points are **screen pixels of a specific geometry** (keyboard-local = screen - `keyboard.x/y` of the
  geometry); `t` is ms from the item's first touch.
- Annotations (all optional, carried through to results): `id`, `sentence`, `word`, `literal` (what
  the taps hit, by hit box), `clean` (no slips and every tap inside the intended key), `slips`,
  `sigmaKw`, `hz`, `durationMs`, `pathKw`.
- A sidecar `<set>.jsonl.meta.json` embeds the full geometry (`geometry`) and generator parameters.
  Sets from elsewhere (e.g. human data under `tools/data/human/`) only need the JSONL plus either such
  a sidecar or `--source-geometry` to be re-projected.

## Geometry format (`pboard-bench-geometry/1`)

```json
{"name": "gboard_pixel8", "ime": "<ime id>", "coordinates": "screen",
 "screen": {"width": 1080, "height": 2400, "density": 420},
 "keyboard": {"x": 0, "y": 1517, "width": 1080, "height": 883},
 "keyWidth": 107.0,
 "keys": [{"name": "q", "label": "q", "row": 0, "cx": 58.5, "cy": 1715.5,
           "visual": [l, t, r, b], "hit": [l, t, r, b], "width": 112, "height": 154}, ...],
 "suggestionStrip": {"top": 1517, "bottom": 1638.5,
                     "slots": [{"cx": 538, "cy": 1577.75}, ...],          // best slot first
                     "ready": {"points": [[385, 1562], ...], "rgb": [197, 198, 208], "tol": 14}}}
```

`name` is the key letter or `shift`, `delete`, `symbols`, `emoji`, `space`, `enter`, `,`, `.`;
`label` is the produced character (`" "` for space). `visual` is the drawn key, `hit` the touch area
(visual extended halfway into the gaps), `width`/`height` the hit size; `cx/cy` the visual centre.
This mirrors the engine's `KeyGeom(label, cx, cy, width, height)` after subtracting the keyboard origin.
`ready` is an IME-specific pixel probe that holds only while the strip shows suggestions (for Gboard,
the two slot dividers), so the prediction probe never taps a toolbar button.

`gboard_pixel8.json` was measured by `measure_geometry.py` from a screenshot of Gboard 15.1's
lowercase layout in the playground (default height, no number row), plus a screenshot with
predictions showing for the strip; the screenshots stay in the gitignored `docs/ref/gboard/bench/`.

## Running on pboard (once it has an engine)

1. Geometry. pboard's layout is meant to match Gboard's, so the measurement tool should work on it
   directly (screenshot with the keyboard up in the playground, lowercase; a second one with
   predictions showing):
   ```
   adb -s emulator-5558 shell ime set dev.pboard/.ime.PboardIme
   adb -s emulator-5558 shell am start -n dev.pboard/.PlaygroundActivity
   adb -s emulator-5558 exec-out screencap -p > /tmp/pb.png         # + /tmp/pb_pred.png after typing "can "
   python3 -I tools/bench/measure_geometry.py /tmp/pb.png tools/bench/geometry/pboard_pixel8.json \
       --name pboard_pixel8 --ime dev.pboard/.ime.PboardIme --strip-png /tmp/pb_pred.png
   ```
   Alternatively have pboard export its own `KeyboardGeometry` (keyboard-local) plus its window
   origin and write the same JSON; keep `name`, `keyboard`, `keyWidth`, `keys[].{name,label,cx,cy,hit}`
   and `suggestionStrip.slots` (+ `ready` if its strip can show a toolbar).
2. Sets for that geometry (same sentences, same seeds; noise is in key-width units so the typist is
   the same): `tools/bench/make_sets.sh tools/bench/geometry/pboard_pixel8.json`
3. Run, human sets first (headline), then synthetic:
   ```
   tools/bench/human.sh    emulator-5558 dev.pboard/.ime.PboardIme tools/bench/geometry/pboard_pixel8.json pboard 400
   tools/bench/baseline.sh emulator-5558 dev.pboard/.ime.PboardIme tools/bench/geometry/pboard_pixel8.json pboard
   ```
   For pboard (no snapshot configured) `run.sh` reboots and runs `pm clear dev.pboard` before every
   set, wiping pboard's learned data (the playground lives in the
   same package and is restarted). Human sets are re-projected from `gboard_pixel8` onto pboard's
   geometry by an affine fit of the letter-key centres (`human.sh` passes `--source-geometry`).
   Before the runs, check `sanity_autocorrect.jsonl` (step above) on pboard too.
4. Compare `tools/bench/out/pboard_*/score.txt` with the Gboard numbers in
   [gboard-baseline.md](gboard-baseline.md). If the geometries are identical, the very same
   `tools/bench/sets/gboard_pixel8/*.jsonl` files can be replayed on pboard for a byte-identical input.

## Caveats

- Replay takes ~1.5 s per tapped word and ~2.5 s per swipe (settle waits dominate; more when the
  field must be reset); `human.sh` takes ~4 h, `baseline.sh` ~1.5 h.
- Gboard learns during a run (words typed); every set starts from the same clean state, so runs are
  reproducible, but later items see a slightly personalised Gboard - the same holds for pboard.
- The field is reset to the oracle context whenever the IME's output diverged, so errors do not
  compound; within a sentence typing is continuous.
- Scoring is case-insensitive (the typist never presses shift; capitalisation is left to the IME).
