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
