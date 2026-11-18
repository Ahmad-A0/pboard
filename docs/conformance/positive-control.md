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

