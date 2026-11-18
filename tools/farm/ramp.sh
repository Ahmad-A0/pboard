#!/usr/bin/env bash
# Measure the farm's real emulator ceiling: add pool instances one at a time under a representative
# workload (Gboard Replay of human TSI taps on EVERY pool device, settle=compare so each item also
# reports Gboard's decode latency) and record memory pressure, swap-in, load and latency per level.
#
#   tools/farm/ramp.sh <from> <to> [dwell-s=240] [out=tools/bench/out/ramp]
#
# Run inside `nix develop` on the emulator farm, from a checkout with the harness built (bench-debug.apk), when no
# benchmark is running (it uses every pool device). The pressure gate is bypassed for the ramp only
# (POOL_CAP=<to> stays a hard ceiling). Safety stop: PSI memory "full avg10" > 20 % or MemAvailable
# < 3 GB ends the ramp at once. The added instances keep running afterwards: `pool.sh down` stops them.
# Output: <out>/samples.tsv (5 s samples), <out>/items.tsv (per replayed item), <out>/summary.txt.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
FROM="${1:?from}"; TO="${2:?to}"; DWELL="${3:-240}"; OUT="${4:-tools/bench/out/ramp}"
mkdir -p "$OUT/res"; rm -f "$OUT"/res/* "$OUT/stop"
GB=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
HARNESS=bench/build/outputs/apk/debug/bench-debug.apk
APP="${APP_APK:?APP_APK (pboard release apk, provides the Playground)}"
SET=tools/bench/out/ramp_set.jsonl
# 25 human TSI items (user01), already in gboard_pixel8 coordinates
RAMP_SET="${RAMP_SET:-$(ls tools/bench/out/*_sub_gboard18_tap_tsi/groups/000.jsonl 2>/dev/null | head -1)}"
head -25 "${RAMP_SET:?RAMP_SET (a group file of a Gboard tap run, e.g. <out>/groups/000.jsonl)}" > "$SET"
POOL=(env POOL_CAP="$TO" POOL_MAX_SWAPIN=100000000 POOL_MAX_PSI=101 POOL_LOAD_FACTOR=1000 tools/farm/pool.sh)

prep() {  # device: harness + Playground + Gboard selected
  local A=(adb -s "$1")
  "${A[@]}" install -r -g "$HARNESS" >/dev/null 2>&1
  "${A[@]}" uninstall dev.pboard >/dev/null 2>&1; "${A[@]}" install -r "$APP" >/dev/null 2>&1
  "${A[@]}" shell ime set "$GB" >/dev/null
  "${A[@]}" shell mkdir -p /data/local/tmp/bench
  "${A[@]}" push "$SET" /data/local/tmp/bench/ramp.jsonl >/dev/null
  "${A[@]}" shell am start -W -n dev.pboard/.PlaygroundActivity >/dev/null
}
load_loop() {  # device: replay the 25 items in a loop until $OUT/stop; tag each pass with the level
  local s="$1" n=0 lvl
  while [ ! -e "$OUT/stop" ]; do
    lvl="$(cat "$OUT/level")"; n=$((n + 1))
    adb -s "$s" shell am instrument -w -e mode replay -e trace /data/local/tmp/bench/ramp.jsonl -e out ramp.jsonl \
      -e settle compare dev.pboard.bench/.Replay > /dev/null 2>&1
    adb -s "$s" shell run-as dev.pboard.bench cat files/ramp.jsonl 2>/dev/null > "$OUT/res/$s.$n.jsonl"
    echo "$lvl $(cat "$OUT/level")" > "$OUT/res/$s.$n.lvl"   # level at start and end of the pass
  done
}
sample() {  # level -> one samples.tsv line
  local si0 si1 t
  si0=$(awk '/^pswpin/ {print $2}' /proc/vmstat); sleep 5; si1=$(awk '/^pswpin/ {print $2}' /proc/vmstat)
  t=$(date +%s)
  echo -e "$t\t$1\t$(pgrep -c qemu-system)\t$(( (si1 - si0) / 5 ))\t$(awk '/^some/ {sub("avg10=","",$2); print $2}' /proc/pressure/memory)\t$(awk '/^full/ {sub("avg10=","",$2); print $2}' /proc/pressure/memory)\t$(cut -d' ' -f1 /proc/loadavg)\t$(awk '/MemAvailable/ {printf "%.1f", $2/1048576}' /proc/meminfo)\t$(free -g | awk '/Swap/ {print $3}')" >> "$OUT/samples.tsv"
}
unsafe() { awk -v f="$(awk '/^full/ {sub("avg10=","",$2); print $2}' /proc/pressure/memory)" \
  -v m="$(awk '/MemAvailable/ {print $2/1048576}' /proc/meminfo)" 'BEGIN {exit !(f > 20 || m < 3)}'; }

echo -e "t\tlevel\tinstances\tswapin_per_s\tpsi_some10\tpsi_full10\tload1\tmemavail_gb\tswap_used_gb" > "$OUT/samples.tsv"
declare -A LOOPING=()
for ((lv = FROM; lv <= TO; lv++)); do
  "${POOL[@]}" up "$lv" | grep -v "^started" || true
  echo "$lv" > "$OUT/level"
  for s in $(ls /var/tmp/emupool/devices); do
    [ -n "${LOOPING[$s]:-}" ] && continue
    prep "$s"; load_loop "$s" & LOOPING[$s]=$!
  done
  echo "== level $lv: $(ls /var/tmp/emupool/devices | wc -l) pool devices loaded, $(pgrep -c qemu-system) instances ($(date -u +%H:%M:%S))"
  end=$(( $(date +%s) + DWELL )); stop=0
  while [ "$(date +%s)" -lt "$end" ]; do
    sample "$lv"
    if unsafe; then echo "!! safety stop at level $lv: $(tail -1 "$OUT/samples.tsv")"; stop=1; break; fi
  done
  [ $stop = 1 ] && break
done
touch "$OUT/stop"; wait
python3 -I tools/farm/ramp_report.py "$OUT" | tee "$OUT/summary.txt"
