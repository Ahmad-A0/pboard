#!/usr/bin/env bash
# Device-specific steps of the benchmark, behind one small interface (used by run.sh):
#
#   device.sh check   <device>              exit 0 iff <device> is an emulator (never a phone)
#   device.sh info    <device>              print the AVD name (local emulators) or "-"
#   device.sh restore <device> <snapshot>   load the emulator snapshot, wait until booted
#   device.sh restart <device> <snapshot>   (re)start the emulator from the snapshot, wait until booted
#
# <device> is an adb serial: a local emulator ("emulator-5570"; its console is used for snapshot
# loads) or a remote emulator reached over adb TCP ("10.0.0.7:5555", after `adb connect`). Remote
# devices have no local console, so restore/restart are delegated to BENCH_DEVICE_HOOK, an
# executable called as `$BENCH_DEVICE_HOOK restore|restart <device> <snapshot>` (e.g. a wrapper that
# runs `ssh farm tools/bench/device.sh restore emulator-55xx <snapshot>` on the farm host, where the
# device is local). The hook is also used for local devices when set, so a farm can override all.
# AVD names and start flags (-read-only) for restarts come from `device.sh info` (cached in
# $BENCH_DEVICE_CACHE if set). Snapshots are only ever loaded, never saved, by the harness.
set -euo pipefail
# Start a long-lived process without inheriting our open file descriptors (a pool lease is an
# open flock fd; an emulator started while one is open would hold that lease for its whole life).
detached() {
  ( for f in /proc/self/fd/*; do n="${f##*/}"; [ "$n" -gt 2 ] 2>/dev/null && eval "exec $n>&-" 2>/dev/null; done
    exec nohup "$@" ) &
}
cmd="${1:?command}"; dev="${2:?device}"; snap="${3:-}"
ADB=(adb -s "$dev")

is_local_emulator() { case "$dev" in emulator-[0-9]*) return 0;; *) return 1;; esac; }

wait_boot() {
  local t=0
  "${ADB[@]}" wait-for-device
  until [ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    sleep 1; t=$((t + 1)); [ $t -lt 300 ] || return 1
  done
}

avd_name() {
  local cache="${BENCH_DEVICE_CACHE:-}/avd_$dev"
  if [ -n "${BENCH_DEVICE_CACHE:-}" ] && [ -s "$cache" ]; then cat "$cache"; return; fi
  local n
  n="$("${ADB[@]}" emu avd name 2>/dev/null | tr -d '\r' | head -1)"
  [ -n "$n" ] && [ "$n" != OK ] || return 1
  [ -n "${BENCH_DEVICE_CACHE:-}" ] && echo "$n" > "$cache"
  echo "$n"
}

# Extra emulator flags the running instance was started with that a restart must repeat
# (-read-only: several instances of one golden AVD sharing its disk; never saves into it).
emu_flags() {
  local cache="${BENCH_DEVICE_CACHE:-}/emuflags_$dev" port="${dev#emulator-}"
  if [ -n "${BENCH_DEVICE_CACHE:-}" ] && [ -f "$cache" ]; then cat "$cache"; return; fi
  local f=""
  pgrep -af -- "-port $port( |$)" 2>/dev/null | grep -q -- "-read-only" && f="-read-only"
  [ -n "${BENCH_DEVICE_CACHE:-}" ] && echo "$f" > "$cache"
  echo "$f"
}

case "$cmd" in
  check)
    # Emulators report ro.kernel.qemu=1 (older) or ro.boot.qemu=1; physical phones do not.
    q="$("${ADB[@]}" shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu' 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
    case "$q" in *1*) exit 0;; *) echo "!! $dev is not an emulator (qemu props: '$q'); refusing" >&2; exit 1;; esac ;;
  info)
    if is_local_emulator; then emu_flags > /dev/null; avd_name || echo "-"; else echo "-"; fi ;;
  restore|restart)
    [ -n "$snap" ] || { echo "!! $cmd needs a snapshot name" >&2; exit 2; }
    if [ -n "${BENCH_DEVICE_HOOK:-}" ]; then exec "$BENCH_DEVICE_HOOK" "$cmd" "$dev" "$snap"; fi
    is_local_emulator || { echo "!! $dev is remote; set BENCH_DEVICE_HOOK to restore/restart it" >&2; exit 2; }
    if [ "$cmd" = restore ]; then
      "${ADB[@]}" emu avd snapshot load "$snap" 2>&1 | tr -d '\r' | grep -q '^OK' || exit 1
      sleep 3
      wait_boot
    else
      avd="$(avd_name)" || { echo "!! unknown AVD for $dev" >&2; exit 1; }
      port="${dev#emulator-}"
      echo "== restarting emulator $avd on port $port from snapshot $snap" >&2
      pkill -f -- "-avd $avd .*-port $port" 2>/dev/null || true
      sleep 3
      detached emulator -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
        -no-snapshot-save $(emu_flags) -snapshot "$snap" > "${BENCH_DEVICE_CACHE:-/tmp}/emulator_$dev.log" 2>&1
      sleep 10
      wait_boot
    fi ;;
  *) echo "usage: device.sh check|info|restore|restart <device> [snapshot]" >&2; exit 2 ;;
esac
