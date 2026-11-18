#!/usr/bin/env bash
# Run a command against the dev phone only while one of pboard's own test screens is in the foreground,
# holding an exclusive lock so only one job drives the phone at a time.
#
#   tools/phone/guard.sh [--open] [--wait SECS] -- <command...>
#
#   --open      launch PlaygroundActivity first (then verify it is in front)
#   --headless  take the lock but skip the foreground check. ONLY for commands that neither inject input nor read the
#               screen: adb install, app_process/JVM benchmarks, dumpsys of system state. Works while the phone is locked.
#   --wait N    wait up to N seconds for the lock (default 900)
#
# Inside <command>, $PHONE is the adb serial. Typical use:
#   tools/phone/guard.sh --open -- bash -c 'tools/phone/guard.sh --shot shot.png'
#
# Rules: never inject input unless this guard passed; take EVERY screenshot with
# `tools/phone/guard.sh --shot out.png` (checks foreground before and after; discards on mismatch) - never raw
# screencap; call `tools/phone/guard.sh --check` before each injected step; leave pboard as the default IME afterwards.
# Any screen read, even a checksum, goes through the guard.
set -euo pipefail
# The phone's adb serial is machine-local: PBOARD_PHONE_SERIAL in the environment or in local.env.
source "$(dirname "${BASH_SOURCE[0]}")/../dev/local_env.sh"
pboard_require PBOARD_PHONE_SERIAL
export PHONE="$PBOARD_PHONE_SERIAL"
export PBOARD_GUARD_HELD=1
LOCK=/tmp/pboard-phone.lock
ALLOWED='dev\.pboard/(\.PlaygroundActivity|dev\.pboard\.PlaygroundActivity|\.debug\.FieldsActivity|dev\.pboard\.debug\.FieldsActivity|\.calibration\.[A-Za-z]+)|dev\.pboard\.fieldzoo/|dev\.pboard\.bench(\.test)?/'

# Capture the whole dump first: piping straight into `grep -m1` under `set -o pipefail` let an early-exiting grep
# SIGPIPE adb, which (via set -e) silently killed the script. Never fails; prints "" when unknown.
foreground() {
  local dump
  dump="$(adb -s "$PHONE" shell dumpsys activity activities 2>/dev/null || true)"
  grep -m1 -E 'topResumedActivity|mResumedActivity' <<<"$dump" | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+' | head -1 || true
}
check() {
  local fg; fg="$(foreground)" || fg=""
  if [[ "$fg" =~ $ALLOWED ]]; then return 0; fi
  echo "guard: refusing - foreground is '${fg:-unknown}', not a pboard test screen" >&2
  return 3
}

# Safe screenshot: foreground checked immediately before AND after the capture; the image is discarded (never
# written to the destination) if a non-pboard screen was in front at either point. Always use this for screenshots
# instead of a raw `adb exec-out screencap`, which can race with another app coming to the front.
shot() {
  local out="$1" tmp; tmp="$(mktemp --suffix=.png)"
  check || { rm -f "$tmp"; return 3; }
  adb -s "$PHONE" exec-out screencap -p > "$tmp"
  if check; then mv "$tmp" "$out"; else rm -f "$tmp"; echo "guard: screenshot discarded (foreground changed)" >&2; return 3; fi
}

open=0; wait=900; headless=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) check; exit $? ;;
    --shot) shot "$2"; exit $? ;;
    --open) open=1; shift ;;
    --headless) headless=1; shift ;;
    --wait) wait="$2"; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
[[ $# -gt 0 ]] || { echo "usage: $0 [--open] [--wait N] -- <command...> | --check" >&2; exit 2; }

exec 9>"$LOCK"
flock -w "$wait" 9 || { echo "guard: phone busy (lock held > ${wait}s)" >&2; exit 4; }
adb -s "$PHONE" get-state >/dev/null
if [[ $open == 1 ]]; then
  adb -s "$PHONE" shell input keyevent KEYCODE_WAKEUP >/dev/null
  adb -s "$PHONE" shell am start -W -n dev.pboard/.PlaygroundActivity >/dev/null
  sleep 1
fi
if [[ $headless == 1 ]]; then
  echo "guard: headless mode - no input injection or screen reads allowed in this command" >&2
else
  check
fi
"$@"
