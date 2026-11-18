# Stability: soak test and idle check

```
tools/farm/pool.sh lease 1 -j soak -- python3 -I tools/soak/soak.py --serial @DEVICES@ --hours 3 --out ~/soak/run1
python3 -I tools/soak/soak_summary.py <out>          # summary.md (also written at the end of a run)
tools/phone/guard.sh --headless --wait 3600 -- tools/soak/idle_check.sh --serial <phone-serial> --minutes 15
```

## soak.py

Hours of randomised, realistic input through pboard across the app matrix of [tools/appcompat](../appcompat/README.md)
(every field except the number pads), on one leased pool device. Per field a long random scenario (default 60
steps) is played by the app-compatibility runner: sentences from `tools/bench/sentences/heldout_v1.txt` typed word by
word, glides, backspaces (taps and holds), cursor moves, select-and-replace, pastes, the emoji panel, the symbols page,
Enter, app switches (home and back), rotations, and the app replacing the text; fields cycle until the time is up
(seeded: `--seed`).

Watched every `--sample` seconds (host side): pboard's pid (a change means the process died), `dumpsys meminfo
dev.pboard` (PSS, Java and native heap, Views, ViewRootImpl, Activities, binders), threads, wake locks held by pboard.
After every field the crash buffer is collected; at the end the dropbox (`data_app_crash`, `data_app_anr`,
`data_app_native_crash`). `soak_summary.py` reports restarts, crashes, ANRs, trends after warm-up (first quarter left
out) and steps whose keyboard did not come up.

## idle_check.sh

Reads system state only (no input, no screenshots), so on the dev phone it runs under `guard.sh --headless`: over a
window with the keyboard hidden it samples pboard's CPU time (`/proc/<pid>/stat`), threads, RSS, whether the keyboard
is shown and wake locks held by pboard (`dumpsys power`), and snapshots `dumpsys batterystats --checkin` (diffed per
uid: wake locks, jobs, syncs, wakeup alarms, network, process CPU), alarms, jobs and services at both ends.
