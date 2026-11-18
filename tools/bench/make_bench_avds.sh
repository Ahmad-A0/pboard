#!/usr/bin/env bash
# Make benchmark emulators that all hold the very same clean IME state, for sharded runs.
#
#   tools/bench/make_bench_avds.sh --golden <avd> --count N [--first-index 1] [--first-port 5570]
#                                  [--avd-dir DIR] [--snapshot gboard_bench_base] [--no-start]
#   tools/bench/make_bench_avds.sh --read-only <avd> --count N [--first-port 5570] [--snapshot S]
#   tools/bench/make_bench_avds.sh --provision --count N [...]      (not recommended, see below)
#
# --read-only (preferred where the emulator supports it, e.g. on the emulator farm): start N `-read-only`
# instances of one golden AVD on first-port + 2*k. They share the golden's disk (zero extra space),
# are byte-identical by construction, load its snapshot, and discard all writes on exit; the
# golden must itself be stopped or running with -read-only. Nothing is ever saved into it.
#
# Default (--golden): clone one prepared AVD - a "golden image" whose snapshot was made by
# make_base_snapshot.sh (e.g. gb18-1 from tools/emulator/setup_gboard18.sh) - into bench-<i>. Disk
# images and snapshot are copied byte for byte, so every shard runs an identical Gboard (same remote
# configuration and experiment arms). Separately provisioned AVDs re-sync their configuration and were seen to differ
# on a few percent of hard items (docs/bench/gboard-baseline.md). The golden AVD must be stopped
# while it is copied. Clones go to --avd-dir (default: the golden AVD's own directory's parent, e.g.
# /var/tmp/pboard-avd); their .ini files go to $ANDROID_AVD_HOME (default ~/.android/avd), like the
# golden's. Each clone needs about as much disk as the golden (~7 GB); the script refuses to start a
# copy that would leave less than 3 GB free.
# --provision: create fresh AVDs and run make_base_snapshot.sh on each (each re-syncs its own configuration).
# Unless --no-start, each device is started headless from the snapshot on first-port + 2*k and
# checked with device.sh. For a remote farm, run this on the farm host; see device.sh.
set -euo pipefail
# Start a long-lived process without inheriting our open file descriptors (a pool lease is an
# open flock fd; an emulator started while one is open would hold that lease for its whole life).
detached() {
  ( for f in /proc/self/fd/*; do n="${f##*/}"; [ "$n" -gt 2 ] 2>/dev/null && eval "exec $n>&-" 2>/dev/null; done
    exec nohup "$@" ) &
}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! command -v emulator >/dev/null 2>&1; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
GOLDEN=""; PROVISION=0; RO=""; COUNT=1; I0=1; PORT0=5570; DIR=""; SNAP=gboard_bench_base; START=1
while [ $# -gt 0 ]; do
  case "$1" in
    --golden) GOLDEN="$2"; shift 2;; --provision) PROVISION=1; shift;; --read-only) RO="$2"; shift 2;;
    --count) COUNT="$2"; shift 2;; --first-index) I0="$2"; shift 2;; --first-port) PORT0="$2"; shift 2;;
    --avd-dir) DIR="$2"; shift 2;; --snapshot) SNAP="$2"; shift 2;; --no-start) START=0; shift;;
    *) sed -n '2,24p' "$0"; exit 2;;
  esac
done
[ -n "$GOLDEN" ] || [ -n "$RO" ] || [ "$PROVISION" = 1 ] || { echo "!! need --read-only <avd>, --golden <avd> or --provision"; exit 2; }
INI_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
IMG="${BENCH_SYSTEM_IMAGE:-system-images;android-36;google_apis_playstore;x86_64}"

start_avd() {  # name port
  local name="$1" port="$2"
  echo "== starting $name on emulator-$port from $SNAP"
  detached emulator -avd "$name" -port "$port" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
    -no-snapshot-save ${3:-} > "${DIR:-/tmp}/$name.bench.log" 2>&1
  sleep 10
  adb -s "emulator-$port" wait-for-device
  until [ "$(adb -s "emulator-$port" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  "$ROOT/tools/bench/device.sh" check "emulator-$port"
}

if [ -n "$RO" ]; then
  if pgrep -af -- "-avd $RO( |$)" | grep -v -- "-read-only" | grep -q qemu; then
    echo "!! $RO is running without -read-only; read-only instances cannot share it"; exit 2
  fi
  DEVS=""
  for k in $(seq 0 $((COUNT - 1))); do
    p=$((PORT0 + 2 * k))
    if ! adb -s "emulator-$p" get-state >/dev/null 2>&1; then
      start_avd "$RO" "$p" "-read-only -snapshot $SNAP"
    fi
    [ "$(adb -s "emulator-$p" emu avd name 2>/dev/null | tr -d '\r' | head -1)" = "$RO" ] || { echo "!! emulator-$p is not $RO"; exit 2; }
    DEVS="${DEVS:+$DEVS,}emulator-$p"
    echo "== emulator-$p: read-only instance of $RO"
  done
  echo "DEVICES=$DEVS"
  exit 0
fi

if [ -n "$GOLDEN" ]; then
  GPATH="$(sed -n 's/^path=//p' "$INI_HOME/$GOLDEN.ini")"
  [ -d "$GPATH/snapshots/$SNAP" ] || { echo "!! $GOLDEN ($GPATH) has no snapshot $SNAP"; exit 2; }
  if pgrep -f -- "-avd $GOLDEN( |$)" >/dev/null; then echo "!! $GOLDEN is running; stop it before cloning"; exit 2; fi
  DIR="${DIR:-$(dirname "$GPATH")}"
  NEED_KB=$(du -sk "$GPATH" | cut -f1)
  for k in $(seq 0 $((COUNT - 1))); do
    i=$((I0 + k)); NAME="bench-$i"
    if [ -e "$INI_HOME/$NAME.ini" ]; then echo "== $NAME exists, keeping it (delete it to re-clone)"; continue; fi
    FREE_KB=$(df -Pk "$DIR" | awk 'NR==2 {print $4}')
    [ $((FREE_KB - NEED_KB)) -gt 3000000 ] || { echo "!! not enough disk in $DIR for $NAME"; exit 3; }
    echo "== cloning $GOLDEN -> $DIR/$NAME.avd"
    cp -a "$GPATH" "$DIR/$NAME.avd"
    rm -f "$DIR/$NAME.avd"/*.lock "$DIR/$NAME.avd"/emulator-*.log "$DIR/$NAME.avd"/emu-launch-params.txt
    printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-36\n' "$DIR/$NAME.avd" "$NAME" > "$INI_HOME/$NAME.ini"
    sed -i -e "s|^AvdId=.*|AvdId=$NAME|" -e "s|^avd.ini.displayname=.*|avd.ini.displayname=$NAME|" "$DIR/$NAME.avd/config.ini"
  done
  if [ "$START" = 1 ]; then
    for k in $(seq 0 $((COUNT - 1))); do
      i=$((I0 + k)); p=$((PORT0 + 2 * k))
      adb -s "emulator-$p" get-state >/dev/null 2>&1 || start_avd "bench-$i" "$p" "-snapshot $SNAP"
      echo "== bench-$i ready on emulator-$p (clone of $GOLDEN)"
    done
  fi
  exit 0
fi

# --provision
for k in $(seq 0 $((COUNT - 1))); do
  i=$((I0 + k)); NAME="bench-$i"; PORT=$((PORT0 + 2 * k)); SERIAL="emulator-$PORT"
  if [ ! -f "$INI_HOME/$NAME.ini" ]; then
    echo "== creating $NAME"
    echo no | avdmanager -s create avd -n "$NAME" -k "$IMG" -d pixel_8 ${DIR:+-p "$DIR/$NAME.avd"} >/dev/null
    CFG="$(sed -n 's/^path=//p' "$INI_HOME/$NAME.ini")/config.ini"
    for kv in hw.ramSize=2G hw.cpu.ncore=2 hw.keyboard=no hw.gpu.enabled=no PlayStore.enabled=no \
              disk.dataPartition.size=6G fastboot.forceColdBoot=no; do
      key="${kv%%=*}"; grep -v "^$key=" "$CFG" > "$CFG.tmp" || true; mv "$CFG.tmp" "$CFG"; echo "$kv" >> "$CFG"
    done
  fi
  adb -s "$SERIAL" get-state >/dev/null 2>&1 || start_avd "$NAME" "$PORT" "-no-snapshot-load"
  [ -x "$ROOT/tools/bench/make_base_snapshot.sh" ] || { echo "!! --provision needs tools/bench/make_base_snapshot.sh, which this tree does not have"; exit 2; }
  LM_TRIES="${LM_TRIES:-200}" "$ROOT/tools/bench/make_base_snapshot.sh" "$SERIAL" "$SNAP"
  echo "== $NAME ready on $SERIAL with its own snapshot $SNAP"
done
