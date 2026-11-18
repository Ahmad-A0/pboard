#!/usr/bin/env bash
# Shared emulator pool on the emulator farm - the one authority for emulator instances there. Benchmark,
# conformance and other jobs lease read-only instances of the golden AVD instead of keeping idle
# emulators of their own. A new instance or lease is admitted only while the host is not under
# memory/CPU pressure (pressure gate below), with a hard ceiling POOL_CAP (default 30) as a safety net.
#
# Pressure gate (all must hold; `pool.sh pressure` prints the current values):
#   swap-in rate  < POOL_MAX_SWAPIN pages/s (default 1000 = 4 MB/s; after a thrash, old pages trickle back
#                 at a few hundred/s with PSI 0 - that is not pressure), over the last ~30 s (pswpin)
#   PSI memory    "some avg10" < POOL_MAX_PSI % (default 10)
#   1-min load    < POOL_LOAD_FACTOR x cores (default 1.5)
#
#   tools/farm/pool.sh status                 every running instance: pool (leasable) or external, holders
#   tools/farm/pool.sh adopt                  register all running instances (read-only $POOL_AVD ones
#                                             become leasable pool devices, others count as external)
#   tools/farm/pool.sh up [N]                 grow the pool to N devices (default: sized from cores/RAM),
#                                             never beyond the global cap
#   tools/farm/pool.sh lease N [-m MIN] [-t SECS] [-j NAME] -- CMD [ARGS...]
#        wait (up to SECS, default 3600) until at least MIN (default N) pool devices are free, lock up
#        to N, run CMD with POOL_DEVICES=a,b,c in the environment and every literal @DEVICES@ argument
#        replaced by that list; the locks are released when CMD exits (also on crash: flock).
#   tools/farm/pool.sh hold N [-m MIN] [-t SECS] [-T MAXSECS] [-j NAME]
#        for remote clients (another machine over ssh): lock, print "DEVICES=a,b,c", keep the locks until stdin
#        closes or the hold expires: `ssh <farm-host> 'cd projects/pboard && tools/farm/pool.sh hold 3 -j conform'`
#        kept open for the job (drive the devices with ADB_SERVER_SOCKET=tcp:<host>:5037). A hold lasts at
#        most MAXSECS (default 1800) unless renewed: every line "renew" the client writes to stdin
#        restarts the clock. On expiry the locks are released and "EXPIRED" is printed.
#   Priority: while a benchmark worker (run.sh pool mode) waits for a device, lease/hold requests do
#        not take free devices, except jobs named conform* (the conformance pass).
#   status flags held devices whose adb connection moved no data for > 10 min (IDLE).
#   tools/farm/pool.sh spawn AVD PORT [-j NAME] [-- EMULATOR-FLAGS]
#        start an instance of another AVD (a job's own), registered as external, only under the cap
#   tools/farm/pool.sh pressure               the pressure gate's current values (exit 1 if not admitting)
#   tools/farm/pool.sh down                   stop the pool's instances that nobody holds
#
# Pool devices: `-read-only` instances of $POOL_AVD (default gb18-1, snapshot gboard_bench_base) on
# ports $POOL_PORTS (default 5570-5598, even). Jobs reset a device themselves (snapshot load) before
# use; nothing is ever saved into the golden. State in $POOL_DIR (default /var/tmp/emupool):
# devices/<serial> (leasable), external/<serial> (other instances), locks/, holders/.
set -euo pipefail
# Start a long-lived process without inheriting our open file descriptors (a pool lease is an
# open flock fd; an emulator started while one is open would hold that lease for its whole life).
detached() {
  ( for f in /proc/self/fd/*; do n="${f##*/}"; [ "$n" -gt 2 ] 2>/dev/null && eval "exec $n>&-" 2>/dev/null; done
    exec nohup "$@" ) &
}
POOL_DIR="${POOL_DIR:-/var/tmp/emupool}"
POOL_AVD="${POOL_AVD:-gb18-1}"
POOL_PORTS="${POOL_PORTS:-$(seq 5570 2 5598 | paste -sd' ')}"
POOL_CAP="${POOL_CAP:-30}"
POOL_MAX_SWAPIN="${POOL_MAX_SWAPIN:-1000}"
POOL_MAX_PSI="${POOL_MAX_PSI:-10}"
POOL_LOAD_FACTOR="${POOL_LOAD_FACTOR:-1.5}"
SNAP="${POOL_SNAPSHOT:-gboard_bench_base}"
mkdir -p "$POOL_DIR/devices" "$POOL_DIR/external" "$POOL_DIR/locks" "$POOL_DIR/holders"
cmd="${1:-status}"; shift || true

default_n() {  # 2 vCPU and ~3.5 GB per instance, 8 vCPU / 12 GB kept for builds and the host
  local c m
  c=$(( ($(nproc) - 8) / 2 )); m=$(( ($(awk '/MemTotal/ {print int($2/1048576)}' /proc/meminfo) - 12) * 2 / 7 ))
  echo $(( c < m ? c : m ))
}

running_ports() {  # ports of every running emulator (any AVD, any user)
  pgrep -af 'qemu-system' 2>/dev/null | grep -o -- '-port [0-9]*' | awk '{print $2}' | sort -n | uniq
}
total() { running_ports | wc -l; }
registered() { ls "$POOL_DIR/devices" 2>/dev/null | sort -t- -k2 -n; }
is_free() { ( exec 9>"$POOL_DIR/locks/$1.lock"; flock -n 9 ) 2>/dev/null; }
# swap-in pages/s over the last ~30 s: the previous pswpin sample is kept in $POOL_DIR/vmstat.sample
# (shared by all callers); without a sample 5-120 s old, sample for 30 s now.
swapin_rate() {
  local f="$POOL_DIR/vmstat.sample" now v t0 v0
  now=$(date +%s); v=$(awk '/^pswpin/ {print $2}' /proc/vmstat)
  if [ -f "$f" ] && read -r t0 v0 < "$f" &&[ $((now - t0)) -ge 5 ] && [ $((now - t0)) -le 120 ]; then
    if [ $((now - t0)) -ge 30 ]; then echo "$now $v" > "$f.$$" && mv -f "$f.$$" "$f"; fi
    echo $(( (v - v0) / (now - t0) )); return
  fi
  sleep 30; local v2; v2=$(awk '/^pswpin/ {print $2}' /proc/vmstat)
  echo "$(date +%s) $v2" > "$f.$$" && mv -f "$f.$$" "$f"
  echo $(( (v2 - v) / 30 ))
}
psi_some10() { awk '/^some/ {sub("avg10=", "", $2); print $2}' /proc/pressure/memory; }
load1() { cut -d' ' -f1 /proc/loadavg; }
# a run.sh pool worker waiting for a device leaves $POOL_DIR/priority/<pid>.<group>
prio_waiting() {
  local f p
  for f in "$POOL_DIR"/priority/*; do
    [ -e "$f" ] || continue
    p="$(basename "$f")"; p="${p%%.*}"
    if kill -0 "$p" 2>/dev/null; then return 0; else rm -f "$f"; fi
  done
  return 1
}
# bytes moved over the device's adb connection (emulator adb port = console port + 1); status keeps
# the last value and when it changed in $POOL_DIR/activity/<serial>
adb_bytes() {
  ss -tinH state established "( sport = :$(( ${1#emulator-} + 1 )) or dport = :$(( ${1#emulator-} + 1 )) )" 2>/dev/null |
    grep -o 'bytes_\(acked\|received\):[0-9]*' | cut -d: -f2 | awk '{s += $1} END {print s + 0}'
}
idle_for() {  # serial -> seconds since its adb traffic last changed (0 on first sight)
  local f="$POOL_DIR/activity/$1" b now v t
  mkdir -p "$POOL_DIR/activity"; b=$(adb_bytes "$1"); now=$(date +%s)
  if [ -f "$f" ] && read -r v t < "$f" && [ "$v" = "$b" ]; then echo $(( now - t )); else echo "$b $now" > "$f"; echo 0; fi
}
PRESSURE=""
pressure_ok() {  # sets PRESSURE to a one-line description
  local si psi l
  si=$(swapin_rate); psi=$(psi_some10); l=$(load1)
  PRESSURE="swap-in ${si}/s (max $POOL_MAX_SWAPIN), PSI mem some avg10 ${psi}% (max $POOL_MAX_PSI), load $l (max $(awk -v f="$POOL_LOAD_FACTOR" -v c="$(nproc)" 'BEGIN {print f * c}'))"
  [ "$si" -lt "$POOL_MAX_SWAPIN" ] && awk -v p="$psi" -v m="$POOL_MAX_PSI" -v l="$l" -v f="$POOL_LOAD_FACTOR" -v c="$(nproc)" \
    'BEGIN {exit !(p < m && l < f * c)}'
}
wait_boot() { adb -s "$1" wait-for-device; until [ "$(adb -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; }
alive() { adb -s "$1" get-state >/dev/null 2>&1; }
cmdline() { pgrep -af -- "-port $1( |$)" | grep qemu-system | head -1; }

adopt() {
  for p in $(running_ports); do
    local s="emulator-$p" c
    c="$(cmdline "$p")"
    # leasable only: read-only instances of the pool AVD on the pool's own ports (other jobs'
    # instances, even read-only gb18-1 ones, stay theirs and only count towards the cap)
    if grep -q -- "-avd $POOL_AVD " <<<"$c" && grep -q -- "-read-only" <<<"$c" && grep -qw "$p" <<<"$POOL_PORTS"; then
      [ -e "$POOL_DIR/devices/$s" ] || { touch "$POOL_DIR/devices/$s"; echo "adopted $s (pool)"; }
      rm -f "$POOL_DIR/external/$s"
    elif [ ! -e "$POOL_DIR/devices/$s" ]; then
      [ -e "$POOL_DIR/external/$s" ] || echo "registered $s (external: $(grep -o -- '-avd [^ ]*' <<<"$c"))"
      grep -o -- '-avd [^ ]*' <<<"$c" > "$POOL_DIR/external/$s"
    fi
  done
  # forget instances that are gone
  for f in "$POOL_DIR"/devices/* "$POOL_DIR"/external/*; do
    [ -e "$f" ] || continue
    s="$(basename "$f")"; running_ports | grep -qx "${s#emulator-}" || { rm -f "$f"; echo "forgot $s (not running)"; }
  done
}

case "$cmd" in
  adopt) adopt ;;
  up)
    adopt >/dev/null
    N="${1:-$(default_n)}"
    have=$(registered | wc -l)
    for p in $POOL_PORTS; do
      [ "$have" -lt "$N" ] || break
      s="emulator-$p"; running_ports | grep -qx "$p" && continue
      [ "$(total)" -lt "$POOL_CAP" ] || { echo "!! hard ceiling $POOL_CAP reached ($(total) instances); pool stays at $have"; break; }
      pressure_ok || { echo "!! host under pressure ($PRESSURE); pool stays at $have"; break; }
      detached emulator -avd "$POOL_AVD" -read-only -port "$p" -no-window -no-audio -no-boot-anim \
        -gpu swiftshader_indirect -no-snapshot-save -snapshot "$SNAP" > "$POOL_DIR/emulator_$s.log" 2>&1
      wait_boot "$s"   # registered (leasable) only once booted
      touch "$POOL_DIR/devices/$s"; have=$((have + 1)); echo "started $s (before: $PRESSURE)"
    done
    for s in $(registered); do wait_boot "$s"; done
    echo "== pool: $(registered | wc -l) devices; $(total) instances on the host (hard ceiling $POOL_CAP)" ;;
  spawn)
    AVD="${1:?avd}"; PORT="${2:?port}"; shift 2; JOB="${USER}"
    [ "${1:-}" = -j ] && { JOB="$2"; shift 2; }
    [ "${1:-}" = -- ] && shift
    adopt >/dev/null
    [ "$(total)" -lt "$POOL_CAP" ] || { echo "!! hard ceiling $POOL_CAP reached ($(total) instances): lease pool devices instead (pool.sh lease/hold)"; exit 3; }
    pressure_ok || { echo "!! host under pressure ($PRESSURE): lease pool devices instead, or retry later"; exit 3; }
    detached emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect "$@" \
      > "$POOL_DIR/emulator_emulator-$PORT.log" 2>&1
    echo "-avd $AVD job=$JOB" > "$POOL_DIR/external/emulator-$PORT"
    echo "started emulator-$PORT ($AVD) as external; $(total) instances (cap $POOL_CAP)" ;;
  pressure)
    if pressure_ok; then echo "ok: $PRESSURE"; else echo "PRESSURE: $PRESSURE"; exit 1; fi ;;
  status)
    adopt >/dev/null
    free=0
    for s in $(registered); do
      if is_free "$s"; then echo "$s pool  free"; free=$((free + 1))
      else
        i=$(idle_for "$s"); flag=""; [ "$i" -gt 600 ] && flag="  IDLE $((i / 60)) min (no adb traffic)"
        echo "$s pool  held: $(cat "$POOL_DIR/holders/$s" 2>/dev/null)$flag"
      fi
    done
    for f in "$POOL_DIR"/external/*; do [ -e "$f" ] && echo "$(basename "$f") external $(cat "$f")"; done
    echo "== $free of $(registered | wc -l) pool devices free; $(total) instances on the host (hard ceiling $POOL_CAP)"
    if pressure_ok; then echo "== admitting: $PRESSURE"; else echo "== NOT admitting new instances/leases: $PRESSURE"; fi ;;
  lease|hold)
    N="${1:?count}"; shift; MIN="$N"; T=3600; TMAX="${POOL_HOLD_MAX:-1800}"; JOB="${USER}:$$"
    while [ $# -gt 0 ] && [ "$1" != -- ]; do
      case "$1" in -m) MIN="$2"; shift 2;; -t) T="$2"; shift 2;; -T) TMAX="$2"; shift 2;; -j) JOB="$2"; shift 2;; *) echo "bad option $1"; exit 2;; esac
    done
    [ "${1:-}" = -- ] && shift
    end=$(( $(date +%s) + T )); got=()
    while :; do
      if [ "${POOL_NO_GATE:-0}" != 1 ] && ! pressure_ok; then
        [ "$(date +%s)" -lt "$end" ] || { echo "!! pool: host still under pressure after ${T}s ($PRESSURE)" >&2; exit 3; }
        [ -n "${warned:-}" ] || { echo "pool: waiting for pressure to drop ($PRESSURE)" >&2; warned=1; }
        sleep 10; continue
      fi
      if [ ${#got[@]} = 0 ] && [[ "$JOB" != conform* ]] && prio_waiting; then
        [ "$(date +%s)" -lt "$end" ] || { echo "!! pool: benchmark workers kept priority for ${T}s" >&2; exit 3; }
        sleep 5; continue
      fi
      for s in $(registered); do
        [ ${#got[@]} -lt "$N" ] || break
        exec {fd}>"$POOL_DIR/locks/$s.lock"
        if flock -n "$fd"; then
          if alive "$s"; then got+=("$s"); echo "$$ $JOB $(date -u +%H:%M:%S)" > "$POOL_DIR/holders/$s"
          else exec {fd}>&-; fi
        else exec {fd}>&-; fi
      done
      [ ${#got[@]} -ge "$MIN" ] && break
      [ "$(date +%s)" -lt "$end" ] || { echo "!! pool: only ${#got[@]} of $MIN devices free after ${T}s" >&2; exit 3; }
      sleep 5
    done
    DEVS="$(IFS=,; echo "${got[*]}")"
    if [ "$cmd" = hold ]; then
      echo "DEVICES=$DEVS"
      exp=$(( $(date +%s) + TMAX ))
      while :; do   # until the client closes stdin, or the hold expires (each "renew" line extends it)
        left=$(( exp - $(date +%s) )); [ "$left" -gt 0 ] || { echo "EXPIRED"; break; }
        if read -r -t "$left" line; then [ "$line" = renew ] && exp=$(( $(date +%s) + TMAX )) && echo "RENEWED"
        else [ $? -gt 128 ] && continue; break; fi
      done
    else
      args=(); for a in "$@"; do args+=("${a//@DEVICES@/$DEVS}"); done
      POOL_DEVICES="$DEVS" "${args[@]}"
    fi ;;
  down)
    for s in $(registered); do
      if is_free "$s"; then adb -s "$s" emu kill >/dev/null 2>&1 || true; rm -f "$POOL_DIR/devices/$s"; echo "stopped $s"; fi
    done ;;
  *) sed -n '2,28p' "$0"; exit 2 ;;
esac
