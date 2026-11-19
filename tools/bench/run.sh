#!/usr/bin/env bash
# One-command IME benchmark run, optionally sharded across several emulators.
#
#   tools/bench/run.sh <device[,device...]> <ime-id> <geometry.json> <set> [key value ...]
#
#   devices   comma-separated adb serials of emulators (local "emulator-5570" or remote over adb
#             TCP "10.0.0.7:5555"); each must be an emulator (device.sh check) holding the same
#             clean snapshot (make_bench_avds.sh / make_base_snapshot.sh). Never a physical phone.
#   ime-id    e.g. com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
#             or dev.pboard/.ime.PboardIme
#   geometry  screen-space key geometry of that IME, e.g. tools/bench/geometry/gboard_pixel8.json
#   set       a trace JSONL (or .jsonl.gz) in the shared format (tap / swipe / predict items).
#             Sets made for another geometry are re-projected (see prepare_set.py).
#   key value forwarded to the Replay instrumentation as "-e key value", e.g. chain false,
#             variant incognito (limit/start would apply within each reset group; use MAX_ITEMS)
#
# Reset groups are the unit of work: every group starts from the clean state (snapshot restore;
# for non-Gboard IMEs also `pm clear` of the IME package), so results do not depend on which
# device ran a group or on how many devices there are. Groups: one per participant when the set's
# items carry 2..40 distinct "participant" values, else consecutive chunks of CHUNK items (default
# 100). Groups are spread over the devices (largest first), replayed in parallel, and the results
# are merged in group order and scored as one set.
#
# Env: SKIP_BUILD=1 (reuse APKs; the build runs assembleRelease/assembleDebug only, never install
#        tasks), APP_APK=<apk> / APP_COMMIT=<sha> (the dev.pboard build under test + its commit),
#      RESET_PER=participant|chunk, CHUNK=<n>, MAX_ITEMS=<n> (truncate the set first),
#      SNAPSHOT=<name> (default gboard_bench_base; "" = no restore), KEEP_STATE=1 (no reset at all),
#      SKIP_STATE_CHECK=1, SKIP_SANITY=1, SANITY_TRIES=<n> (snapshot restores per group until the
#        "becuase" probe is corrected 3/3; default 3, else the group is invalid), AUTO_RESTART=1 (restart a crashed local emulator from the
#        snapshot via device.sh), BENCH_DEVICE_HOOK (remote restore/restart, see device.sh),
#      RESUME=1 (keep complete per-group results already in OUT/res, e.g. after an emulator crash),
#      SCHED=static|dynamic (static: fixed largest-first assignment, the same group always on the same
#        device of the list; dynamic: devices claim the next group when free - for pools of identical
#        read-only instances, where the device does not matter),
#      LOAD_FACTOR=<f> (start a group only when the 1-min load average <= f x cores; default 1.5),
#      GROUP_GATE=<command> (called as `<command> <device> <group>` before each group; the group waits
#        until it exits 0),
#      OVERLOAD_FACTOR=<f> / OVERLOAD_PSI=<pct> (a group is invalid if, while it ran, the 1-min load
#        exceeded f x cores (default 3.5) or PSI memory "some avg10" exceeded pct (default 25): another
#        job overloading the host biases the IME even when no injection stalls; sampled every 5 s),
#      PREPARE_ARGS="--snap-terminator", OUT=<dir> (default tools/bench/out/<date>_<ime>_<set>).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! command -v adb >/dev/null 2>&1; then
  exec nix develop "$ROOT" --command bash "$0" "$@"
fi
[ $# -ge 4 ] || { sed -n '2,33p' "$0"; exit 2; }
DEVICES="$1"; IME="$2"; GEO="$3"; SET="$4"; shift 4
POOL_MODE=0; POOL_PAR=0
case "$DEVICES" in pool|pool:*) POOL_MODE=1; POOL_PAR="${DEVICES#pool}"; POOL_PAR="${POOL_PAR#:}"; DEVICES="";; esac
POOL_DIR="${POOL_DIR:-/var/tmp/emupool}"
IFS=, read -r -a DEVS <<< "$DEVICES"
EXTRA=()
while [ $# -ge 2 ]; do EXTRA+=(-e "$1" "$2"); shift 2; done
PY=(python3 -I)
DEV_SH="$ROOT/tools/bench/device.sh"
IME_PKG="${IME%%/*}"
IS_GBOARD=0; [ "$IME_PKG" = com.google.android.inputmethod.latin ] && IS_GBOARD=1
SET_NAME="$(basename "$SET" .gz)"; SET_NAME="${SET_NAME%.jsonl}"
GEO_NAME="$("${PY[@]}" -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$GEO")"
OUT="${OUT:-$ROOT/tools/bench/out/$(date +%Y%m%d-%H%M%S)_${IME_PKG##*.}_${SET_NAME}}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
export BENCH_DEVICE_CACHE="$OUT"
[ -n "${AVD:-}" ] && AUTO_RESTART=1   # older interface: AVD=<name> meant "may restart"
echo "== bench: ime=$IME geometry=$GEO_NAME set=$SET devices=$DEVICES -> $OUT"
for d in "${DEVS[@]}"; do
  "$DEV_SH" check "$d" || exit 2
  "$DEV_SH" info "$d" > /dev/null || true   # caches the AVD name for crash restarts
done

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  (cd "$ROOT" && ./gradlew -q :app:assembleRelease :bench:assembleDebug)
fi
APP_APK="${APP_APK:-$ROOT/app/build/outputs/apk/release/app-release.apk}"
BENCH_APK="$ROOT/bench/build/outputs/apk/debug/bench-debug.apk"
for f in "$APP_APK" "$BENCH_APK"; do [ -s "$f" ] || { echo "!! missing APK $f"; exit 2; }; done
SNAPSHOT="${SNAPSHOT-gboard_bench_base}"

# Prepare (re-project if needed, ensure synthetic tap words end with space) and split into groups.
"${PY[@]}" "$ROOT/tools/bench/prepare_set.py" "$SET" "$GEO" "$OUT/set.jsonl" ${PREPARE_ARGS:-}
if [ -n "${MAX_ITEMS:-}" ]; then head -n "$MAX_ITEMS" "$OUT/set.jsonl" > "$OUT/set.tmp" && mv "$OUT/set.tmp" "$OUT/set.jsonl"; fi
cp "$GEO" "$OUT/geometry.json"
if [ "${RESUME:-0}" = 1 ]; then rm -rf "$OUT/groups"; else rm -rf "$OUT/groups" "$OUT/res" "$OUT/logs"; fi
mkdir -p "$OUT/groups" "$OUT/res" "$OUT/logs"; rm -rf "$OUT/claims"; mkdir -p "$OUT/claims"
RESET_PER="$("${PY[@]}" - "$OUT/set.jsonl" "$OUT/groups" "${RESET_PER:-auto}" "${CHUNK:-100}" "$(( ${#DEVS[@]} > 0 ? ${#DEVS[@]} : 1 ))" <<'EOF'
import json, sys, collections
src, d, mode, chunk, ndev = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
items = [l for l in open(src) if l.strip()]
parts = collections.OrderedDict()
for l in items:
    parts.setdefault(json.loads(l).get("participant"), []).append(l)
if mode == "auto":
    mode = "participant" if None not in parts and 1 < len(parts) <= 40 else "chunk"
if mode == "participant":
    groups = list(parts.values())
else:
    groups = [items[i:i + chunk] for i in range(0, len(items), chunk)]
for i, ls in enumerate(groups):
    with open("%s/%03d.jsonl" % (d, i), "w") as f:
        f.writelines(ls)
# Longest-processing-time-first assignment of groups to devices.
load = [0] * ndev
assign = [[] for _ in range(ndev)]
for i in sorted(range(len(groups)), key=lambda i: -len(groups[i])):
    k = min(range(ndev), key=lambda k: load[k])
    load[k] += len(groups[i]); assign[k].append("%03d" % i)
for k in range(ndev):
    with open("%s/assign_%d.txt" % (d, k), "w") as f:
        f.write(" ".join(sorted(assign[k])))
with open("%s/order.txt" % d, "w") as f:
    f.write(" ".join("%03d" % i for i in sorted(range(len(groups)), key=lambda i: -len(groups[i]))))
print(mode if mode == "participant" else "chunk%d" % chunk)
EOF
)"
NGROUPS=$(ls "$OUT"/groups/*.jsonl | wc -l)
echo "== reset per $RESET_PER: $NGROUPS group(s) over ${#DEVS[@]} device(s)"
KIND="$("${PY[@]}" -c 'import json,sys; print(json.loads(open(sys.argv[1]).readline())["kind"])' "$OUT/set.jsonl")"

if [ "$KIND" = predict ]; then
  read -r SLOTS READY < <("${PY[@]}" -c '
import json,sys
s=json.load(open(sys.argv[1]))["suggestionStrip"]
slots=",".join("%d:%d" % (p["cx"], p["cy"]) for p in s["slots"])
r=s.get("ready")
ready=(",".join("%d:%d" % tuple(p) for p in r["points"]) + ";" + ",".join(map(str, r["rgb"])) + ";" + str(r["tol"])) if r else "-"
print(slots, ready)' "$GEO")
  ARGS=(-e mode predict -e slots "$SLOTS")
  [ "$READY" != "-" ] && ARGS+=(-e ready "'$READY'")
  RES=predict.jsonl
else
  ARGS=(-e mode replay)
  RES=results.jsonl
fi

reset_ime() {  # $1 = device, $2 = group; leaves the IME selected and shown in a clean playground
  local dev="$1" g="$2" STATE="kept"
  local -a A=(adb -s "$dev")
  if [ "${KEEP_STATE:-0}" != 1 ] && [ -n "$SNAPSHOT" ]; then
    # At most RESET_SLOTS (default 3) restores/restarts at once on this host, across all runs: an
    # emulator (re)booting from the snapshot takes ~6 cores for a minute or two, and bunched resets
    # push the load high enough to disturb the groups that are replaying on other devices.
    local slot sfd=""
    mkdir -p "${RESET_SLOT_DIR:-/var/tmp/emupool/reset-slots}"
    while [ -z "$sfd" ]; do
      for slot in $(seq 1 "${RESET_SLOTS:-3}"); do
        exec {sfd}>"${RESET_SLOT_DIR:-/var/tmp/emupool/reset-slots}/$slot.lock"
        flock -n "$sfd" && break
        exec {sfd}>&-; sfd=""
      done
      [ -n "$sfd" ] || sleep 2
    done
    if ! "$DEV_SH" restore "$dev" "$SNAPSHOT"; then
      [ "${AUTO_RESTART:-0}" = 1 ] && "$DEV_SH" restart "$dev" "$SNAPSHOT" || { exec {sfd}>&-; echo "!! [$dev] snapshot $SNAPSHOT not restored"; return 5; }
    fi
    exec {sfd}>&-
    STATE="snapshot:$SNAPSHOT"
  fi
  # (explicit checks: this function runs in an `||` context, where `set -e` does not apply)
  # An APK signed with another key than the copy already on the device (e.g. a golden prepared on
  # another host) cannot update it: uninstall it first (the snapshot is restored per group anyway).
  install_apk() {  # apk package [install flags]
    "${A[@]}" install -r ${3:-} "$1" >/dev/null 2>&1 && return 0
    "${A[@]}" uninstall "$2" >/dev/null 2>&1 || true
    "${A[@]}" install ${3:-} "$1" >/dev/null
  }
  install_apk "$APP_APK" dev.pboard -g || { echo "!! [$dev] install of $APP_APK failed"; return 8; }
  install_apk "$BENCH_APK" dev.pboard.bench || { echo "!! [$dev] install of $BENCH_APK failed"; return 8; }
  # The installed dev.pboard must be the APK under test (a failed or skipped install would leave
  # whatever the snapshot holds).
  local want got
  want="$(sha256sum "$APP_APK" | cut -c1-16)"
  got="$("${A[@]}" shell 'sha256sum $(pm path dev.pboard | head -1 | cut -d: -f2)' | cut -c1-16)"
  [ "$want" = "$got" ] || { echo "!! [$dev] installed dev.pboard ($got) is not $APP_APK ($want)"; return 8; }
  if [ "${KEEP_STATE:-0}" != 1 ] && [ $IS_GBOARD = 0 ]; then
    # Never pm clear Gboard (that wipes its synced remote configuration); the snapshot is its clean state.
    "${A[@]}" shell pm clear "$IME_PKG" >/dev/null
    STATE="$STATE+pm-clear:$IME_PKG"
  fi
  "${A[@]}" shell ime enable "$IME" >/dev/null
  "${A[@]}" shell ime set "$IME" >/dev/null
  "${A[@]}" shell settings put secure show_ime_with_hard_keyboard 1
  "${A[@]}" shell settings put secure stylus_handwriting_enabled 0
  "${A[@]}" shell input keyevent KEYCODE_WAKEUP
  "${A[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "${A[@]}" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null  # e.g. ANR dialogs on a busy host
  "${A[@]}" shell am start -n dev.pboard/.PlaygroundActivity >/dev/null
  # State waits instead of fixed sleeps: the playground answers a reset and the IME window is shown.
  local t=0
  until "${A[@]}" shell am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd reset 2>/dev/null | grep -q 'data="' \
        && "${A[@]}" shell dumpsys input_method | grep -q "mInputShown=true"; do
    sleep 0.3; t=$((t + 1)); [ $t -lt 100 ] || { echo "!! [$dev] IME not shown"; return 7; }
  done
  echo "group $g on $dev: state=$STATE"
  # Visual state check, retried briefly while the keyboard finishes drawing.
  local ok=0
  for _ in 1 2 3 4 5 6 7 8; do
    "${A[@]}" exec-out screencap -p > "$OUT/logs/state_$g.png"
    if "${PY[@]}" "$ROOT/tools/bench/state_check.py" "$GEO" "$OUT/logs/state_$g.png" > "$OUT/logs/state_$g.txt"; then ok=1; break; fi
    sleep 0.5
  done
  cat "$OUT/logs/state_$g.txt"
  # Geometry check (non-Gboard IMEs, whose layout changes with the build and with device state, e.g. a
  # language key): the keys measured on this screenshot must match the run's geometry within 3 px,
  # else the replayed touches would land key-shifted. A mismatch fails the group (return 10).
  if [ $IS_GBOARD = 0 ] && [ "${SKIP_GEOMETRY_CHECK:-0}" != 1 ]; then
    local gd="" k
    for k in 1 2 3 4 5 6 7 8; do
      gd="$("${PY[@]}" "$ROOT/tools/bench/measure_geometry.py" "$OUT/logs/state_$g.png" "$OUT/logs/geom_$g.json" >/dev/null 2>&1 &&
        "${PY[@]}" "$ROOT/tools/bench/geometry_delta.py" "$GEO" "$OUT/logs/geom_$g.json")" &&
        awk -v d="$gd" 'BEGIN {exit !(d <= 3)}' && break   # else retry: the keyboard may still be animating in
      sleep 1.5; "${A[@]}" exec-out screencap -p > "$OUT/logs/state_$g.png"
    done
    echo "geometry check: max key delta ${gd:-unmeasurable} px" | tee "$OUT/logs/geomcheck_$g.txt"
    awk -v d="${gd:-999}" 'BEGIN {exit !(d <= 3)}' || { echo "!! [$dev] keyboard geometry differs from $GEO (${gd:-unmeasurable} px)"; return 10; }
  fi
  if [ $ok = 0 ] && [ $IS_GBOARD = 1 ] && [ "${SKIP_STATE_CHECK:-0}" != 1 ]; then echo "!! [$dev] IME state check failed"; return 6; fi
  # Sanity probe: "becuase" on key centres + space, three times; each must come out as "because".
  # A miss returns 9: the caller then restores the snapshot again (a missed correction can teach the
  # IME the misspelling, so retrying in place would be biased) and re-probes, up to SANITY_TRIES.
  if [ "${SKIP_SANITY:-0}" != 1 ]; then
    "${A[@]}" shell mkdir -p /data/local/tmp/bench
    "${A[@]}" push "$OUT/sanity.jsonl" /data/local/tmp/bench/sanity.jsonl >/dev/null
    "${A[@]}" shell am instrument -w -e mode replay -e trace /data/local/tmp/bench/sanity.jsonl \
      -e out sanity_result.jsonl dev.pboard.bench/.Replay > "$OUT/logs/sanity_instrument_$g.txt"
    "${A[@]}" shell run-as dev.pboard.bench cat files/sanity_result.jsonl > "$OUT/logs/sanity_result_$g.jsonl"
    # Recorded only; a group whose probe is not corrected all three times is marked invalid later.
    "${PY[@]}" -c 'import json,sys; rs=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]; print("== sanity: becuase x3 ->", [r["after"] for r in rs]); sys.exit(0 if len(rs) == 3 and all(r["after"].strip().lower() == "because" for r in rs) else 1)' "$OUT/logs/sanity_result_$g.jsonl" || return 9
  fi
}

process_group() {  # $1 = device, $2 = group: reset, replay, pull (load-gated)
  local dev="$1" g="$2" t0 waited=0 l0 try=1 rc
  local -a A=(adb -s "$dev")
  if [ "${RESUME:-0}" = 1 ] && [ -s "$OUT/res/$g.jsonl" ] && \
     [ "$(wc -l < "$OUT/res/$g.jsonl")" = "$(wc -l < "$OUT/groups/$g.jsonl")" ]; then
    echo "== [$dev] group $g already complete (RESUME)"; return 0
  fi
  # Optional site gate (GROUP_GATE=<command>): the group starts only once the command exits 0 (polled
  # every 30 s), e.g. a script that yields the host to another job or brings the device back up.
  while [ -n "${GROUP_GATE:-}" ] && ! $GROUP_GATE "$dev" "$g"; do sleep 30; done
  # Load gate: an oversubscribed host delays the IME's decoder (Gboard then commits literal words),
  # which biases results. Start a group only at load1 <= LOAD_FACTOR x cores (polling every 30 s).
  while ! awk -v f="${LOAD_FACTOR:-1.5}" -v n="$(nproc)" '{ exit !($1 <= f * n) }' /proc/loadavg; do
    [ $((waited % 300)) = 0 ] && echo "== [$dev] load $(cut -d' ' -f1 /proc/loadavg) > ${LOAD_FACTOR:-1.5} x $(nproc); waiting"
    sleep 30; waited=$((waited + 30))
  done
  l0="$(cut -d' ' -f1 /proc/loadavg)"
  t0=$(date +%s)
  # host overload monitor for the whole group (reset + replay): max load1 and PSI memory some avg10
  local me=$BASHPID
  ( m=0; p=0; while kill -0 "$me" 2>/dev/null; do
      read -r l _ < /proc/loadavg; q=$(awk '/^some/ {sub("avg10=", "", $2); print $2}' /proc/pressure/memory 2>/dev/null || echo 0)
      m=$(awk -v a="$m" -v b="$l" 'BEGIN {print (b > a ? b : a)}'); p=$(awk -v a="$p" -v b="${q:-0}" 'BEGIN {print (b > a ? b : a)}')
      echo "$m $p" > "$OUT/logs/host_$g.txt"; sleep 5
    done ) &
  local hostmon=$!
  while true; do
    rc=0; reset_ime "$dev" "$g" || rc=$?
    [ $rc = 0 ] && break
    [ $rc = 9 ] || { kill "$hostmon" 2>/dev/null; return $rc; }
    if [ $try -ge "${SANITY_TRIES:-3}" ]; then echo "== [$dev] group $g: sanity still failing after $try restores; group will be invalid"; break; fi
    echo "== [$dev] group $g: sanity probe missed; restoring the snapshot again ($try)"
    try=$((try + 1))
  done
  "${A[@]}" shell mkdir -p /data/local/tmp/bench
  "${A[@]}" push "$OUT/groups/$g.jsonl" /data/local/tmp/bench/set.jsonl >/dev/null
  "${A[@]}" shell run-as dev.pboard.bench rm -f "files/$RES"
  echo "== [$dev] replaying group $g ($KIND, $(wc -l < "$OUT/groups/$g.jsonl") items)"
  "${A[@]}" shell am instrument -w "${ARGS[@]}" -e trace /data/local/tmp/bench/set.jsonl -e out "$RES" \
    "${EXTRA[@]}" dev.pboard.bench/.Replay > "$OUT/logs/instrument_$g.txt"
  "${A[@]}" shell run-as dev.pboard.bench cat "files/$RES" > "$OUT/res/$g.jsonl"
  kill "$hostmon" 2>/dev/null; wait "$hostmon" 2>/dev/null
  local hm=(0 0); read -r -a hm < "$OUT/logs/host_$g.txt" 2>/dev/null || true
  echo "group=$g device=$dev items=$(wc -l < "$OUT/groups/$g.jsonl") results=$(wc -l < "$OUT/res/$g.jsonl") seconds=$(( $(date +%s) - t0 )) sanity_tries=$try load_start=$l0 load_end=$(cut -d' ' -f1 /proc/loadavg) waited=$waited load_max=${hm[0]:-0} psi_max=${hm[1]:-0} cores=$(nproc) start=$t0 end=$(date +%s)" | tee -a "$OUT/logs/timing.txt"
}

worker() {  # $1 = device index (fixed device list)
  local k="$1" dev="${DEVS[$1]}" g order
  if [ "${SCHED:-static}" = dynamic ]; then order="$(cat "$OUT/groups/order.txt")"; else order="$(cat "$OUT/groups/assign_$k.txt")"; fi
  for g in $order; do
    # dynamic: whichever device is free first claims the next group (largest first)
    if [ "${SCHED:-static}" = dynamic ]; then mkdir "$OUT/claims/$g" 2>/dev/null || continue; fi
    process_group "$dev" "$g" || return $?
  done
}

# Pool mode (devices "pool" or "pool:<max parallel>"): every group leases one free device from the
# shared farm pool (tools/farm/pool.sh; flock on $POOL_DIR/locks/<serial>.lock) for its duration, so
# several runs - benchmark legs, conformance - share the emulators without idle reservations.
pool_worker() {
  # Claim the next unclaimed group, lease a free *booted* pool device, run the group. A group that
  # fails (device trouble: restore/restart/install) is released for another try (up to 3) and this
  # worker avoids that device for the rest of the run, so one bad device cannot eat the whole set.
  local g dev fd s rc n bad=" "
  while :; do
    g=""
    for s in $(cat "$OUT/groups/order.txt"); do
      [ -e "$OUT/claims/$s.done" ] && continue
      mkdir "$OUT/claims/$s" 2>/dev/null && { g="$s"; break; }
    done
    [ -n "$g" ] || break
    dev=""
    mkdir -p "$POOL_DIR/priority"; : > "$POOL_DIR/priority/$BASHPID.$g"   # pool.sh lease/hold yield to us
    while [ -z "$dev" ]; do
      for s in $(ls "$POOL_DIR/devices" | sort -t- -k2 -n); do
        case "$bad" in *" $s "*) continue;; esac
        exec {fd}>"$POOL_DIR/locks/$s.lock"
        if flock -n "$fd"; then
          if [ "$(adb -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
            dev="$s"; echo "$$ bench:$SET_NAME:$g $(date -u +%H:%M:%S)" > "$POOL_DIR/holders/$s"; break
          fi
        fi
        exec {fd}>&-
      done
      [ -n "$dev" ] || sleep 3
    done
    rm -f "$POOL_DIR/priority/$BASHPID.$g"
    "$DEV_SH" info "$dev" > /dev/null || true
    rc=0; process_group "$dev" "$g" || rc=$?
    exec {fd}>&-
    if [ $rc = 0 ]; then touch "$OUT/claims/$g.done"; continue; fi
    n=$(( $(cat "$OUT/claims/$g.tries" 2>/dev/null || echo 0) + 1 )); echo $n > "$OUT/claims/$g.tries"
    echo "!! [$dev] group $g failed ($rc, try $n); avoiding $dev in this worker"
    bad="$bad$dev "
    if [ $n -lt 3 ]; then rmdir "$OUT/claims/$g"; else touch "$OUT/claims/$g.done"; fi
  done
}

"${PY[@]}" "$ROOT/tools/bench/gen_traces.py" --geometry "$GEO" --pairs "becuase:because,becuase:because,becuase:because" --out "$OUT/sanity.jsonl" >/dev/null
START=$(date +%s)
PIDS=()
if [ $POOL_MODE = 1 ]; then
  [ -d "$POOL_DIR/devices" ] || { echo "!! no pool at $POOL_DIR (tools/farm/pool.sh up)"; exit 2; }
  NW="${POOL_PAR:-0}"; [ "$NW" -gt 0 ] 2>/dev/null || NW=$NGROUPS
  [ "$NW" -le "$NGROUPS" ] || NW=$NGROUPS
  for k in $(seq 1 "$NW"); do
    pool_worker > "$OUT/logs/worker_pool$k.log" 2>&1 &
    PIDS+=($!); DEVS+=("pool$k")
  done
else
  for k in "${!DEVS[@]}"; do
    worker "$k" > "$OUT/logs/worker_${DEVS[$k]}.log" 2>&1 &
    PIDS+=($!)
  done
fi
FAIL=0
for k in "${!PIDS[@]}"; do
  wait "${PIDS[$k]}" || { FAIL=1; echo "!! worker on ${DEVS[$k]} failed:"; tail -3 "$OUT/logs/worker_${DEVS[$k]}.log"; }
done
ELAPSED=$(( $(date +%s) - START ))
cat "$OUT"/logs/worker_*.log > "$OUT/instrument.txt"
# Merge in group order; a group is valid iff its sanity probe was corrected 3/3, it completed, no
# item stayed timing-degraded and the host was not overloaded while it ran. results.jsonl = all, results_valid.jsonl = valid groups only.
"${PY[@]}" "$ROOT/tools/bench/merge_groups.py" "$OUT" "$RES" | tee -a "$OUT/instrument.txt"
NITEMS=$(wc -l < "$OUT/set.jsonl"); NRES=$(wc -l < "$OUT/$RES")
echo "elapsed $ELAPSED s for $NRES/$NITEMS items on ${#DEVS[@]} device(s) ($(( ELAPSED * 100 / (NRES > 0 ? NRES : 1) )) s per 100 items)" | tee -a "$OUT/instrument.txt"
[ "$FAIL" = 0 ] && [ "$NRES" = "$NITEMS" ] || echo "!! incomplete run ($NRES/$NITEMS items); scoring partial results"

# What was measured: IME version, Gboard main LM, device build, reset policy, commits.
D0="${DEVS[0]}"
if [ $POOL_MODE = 1 ]; then D0="$(sed -n 's/.*device=\([^ ]*\).*/\1/p' "$OUT/logs/timing.txt" | head -1)"; DEVICES="pool"; fi
VER="$(adb -s "$D0" shell dumpsys package "$IME_PKG" | grep -m1 versionName | tr -d ' \r' | cut -d= -f2)"
LMV=""
if [ $IS_GBOARD = 1 ]; then
  LMV="$(adb -s "$D0" logcat -d 2>/dev/null | grep -o 'loadLanguageModel() : 1, version \[[0-9]*\]' | tail -1 | grep -o '[0-9]\{6,\}' || true)"
fi
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || cut -c1-7 "$ROOT/.harness_commit" 2>/dev/null || echo unknown)"
{ echo "ime=$IME"; echo "imeVersion=$VER"; if [ $IS_GBOARD = 1 ]; then echo "gboardMainLm=${LMV:-not logged}"; fi
  echo "resetPer=$RESET_PER"; echo "snapshot=${SNAPSHOT:-none}"; echo "devices=$DEVICES"; echo "appApk=$APP_APK"
  echo "appCommit=${APP_COMMIT:-$COMMIT}"; echo "harnessCommit=$COMMIT"; echo "elapsedSeconds=$ELAPSED"
  echo "build=$(adb -s "$D0" shell getprop ro.build.fingerprint | tr -d '\r')"; } > "$OUT/device.txt"
LABEL="$SET_NAME [${IME_PKG##*.} $VER${LMV:+ lm=$LMV}, app ${APP_COMMIT:-$COMMIT}, reset/$RESET_PER]"

VRES="${RES%.jsonl}_valid.jsonl"
if [ "$KIND" = predict ]; then
  "${PY[@]}" "$ROOT/tools/bench/score.py" --predict "$OUT/$VRES" --json "$OUT/score.json" --label "$LABEL valid groups" | tee "$OUT/score.txt"
  "${PY[@]}" "$ROOT/tools/bench/score.py" --predict "$OUT/$RES" --json "$OUT/score_all.json" --label "$LABEL ALL groups" > "$OUT/score_all.txt"
else
  "${PY[@]}" "$ROOT/tools/bench/score.py" "$OUT/$VRES" --set "$OUT/set.jsonl" --geometry "$GEO" --json "$OUT/score.json" --label "$LABEL valid groups" | tee "$OUT/score.txt"
  "${PY[@]}" "$ROOT/tools/bench/score.py" "$OUT/$RES" --set "$OUT/set.jsonl" --geometry "$GEO" --json "$OUT/score_all.json" --label "$LABEL ALL groups" > "$OUT/score_all.txt"
fi
[ "$FAIL" = 0 ]
