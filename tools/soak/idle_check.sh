#!/usr/bin/env bash
# Idle / battery check: does pboard hold wake locks or do background work while the keyboard is hidden?
# Only reads system state (dumpsys, /proc): no input, no screen reads. On the dev phone run it through the guard:
#   tools/phone/guard.sh --headless --wait 900 -- tools/soak/idle_check.sh --serial <phone-serial> --minutes 15
# On an emulator: tools/soak/idle_check.sh --serial emulator-5570 --minutes 10
# Writes <out>/{start,end}_*.txt and samples.tsv, then prints a summary (tools/soak/idle_summary.py).
set -euo pipefail
SERIAL=""; MIN=15; INT=60; PKG=dev.pboard
OUT="$(cd "$(dirname "$0")" && pwd)/out/idle_$(date +%Y%m%d-%H%M%S)"
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;; --minutes) MIN="$2"; shift 2 ;; --interval) INT="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;; --package) PKG="$2"; shift 2 ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
done
[ -n "$SERIAL" ] || { echo "--serial required"; exit 2; }
mkdir -p "$OUT"
A=(adb -s "$SERIAL")
# (dumps are captured before grepping: an early-exiting grep would SIGPIPE adb and, under pipefail, end the script)
pkgdump="$("${A[@]}" shell dumpsys package "$PKG" || true)"
uid="$(grep -oE '(userId|appId)=[0-9]+' <<<"$pkgdump" | head -1 | cut -d= -f2 || true)"
echo "== idle check of $PKG (uid $uid) on $SERIAL for $MIN min, sample every ${INT}s -> $OUT"

snap() {  # $1 = start|end
  local p="$OUT/$1"
  "${A[@]}" shell dumpsys batterystats "$PKG" > "${p}_batterystats.txt" 2>&1 || true
  "${A[@]}" shell dumpsys batterystats --checkin > "${p}_checkin.txt" 2>&1 || true
  "${A[@]}" shell dumpsys power > "${p}_power.txt" 2>&1 || true
  "${A[@]}" shell dumpsys alarm > "${p}_alarm.txt" 2>&1 || true
  "${A[@]}" shell dumpsys jobscheduler "$PKG" > "${p}_jobs.txt" 2>&1 || true
  "${A[@]}" shell dumpsys activity services "$PKG" > "${p}_services.txt" 2>&1 || true
  "${A[@]}" shell dumpsys input_method > "${p}_ime.txt" 2>&1 || true
  "${A[@]}" shell dumpsys meminfo "$PKG" > "${p}_meminfo.txt" 2>&1 || true
  "${A[@]}" shell dumpsys netstats detail > "${p}_netstats.txt" 2>&1 || true
}

sample() {
  local pid stat cpu thr rss shown wl
  pid="$("${A[@]}" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}' || true)"
  if [ -n "$pid" ]; then
    stat="$("${A[@]}" shell cat "/proc/$pid/stat" 2>/dev/null | tr -d '\r')"
    cpu="$(awk '{print $14 + $15}' <<<"$stat")"   # utime + stime, clock ticks
    local status
    status="$("${A[@]}" shell cat "/proc/$pid/status" 2>/dev/null || true)"
    thr="$(awk '/^Threads:/ {print $2}' <<<"$status")"
    rss="$(awk '/^VmRSS:/ {print $2}' <<<"$status")"
  else cpu=-; thr=-; rss=-; fi
  local imed powd
  imed="$("${A[@]}" shell dumpsys input_method || true)"
  shown="$(grep -oE 'mInputShown=(true|false)' <<<"$imed" | head -1 | cut -d= -f2 || true)"
  powd="$("${A[@]}" shell dumpsys power || true)"
  wl="$(sed -n "/Wake Locks: size/,/^$/p" <<<"$powd" | grep -c -E "uid=$uid[^0-9]|$PKG[^.a-z]" || true)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%s)" "${pid:--}" "$cpu" "$thr" "$rss" "${shown:-?}" "$wl" >> "$OUT/samples.tsv"
}

printf 'time\tpid\tcpu_ticks\tthreads\trss_kb\tinput_shown\twakelocks\n' > "$OUT/samples.tsv"
snap start
end=$(( $(date +%s) + MIN * 60 ))
while :; do
  sample
  [ "$(date +%s)" -lt "$end" ] || break
  sleep "$INT"
done
snap end
echo "$uid" > "$OUT/uid.txt"
python3 -I "$(dirname "$0")/idle_summary.py" "$OUT" | tee "$OUT/summary.md"
