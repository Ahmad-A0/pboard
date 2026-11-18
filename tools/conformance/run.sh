#!/usr/bin/env bash
# Keystroke-level conformance run: identical input through Gboard and pboard, everything observable compared
# after every step. See tools/conformance/README.md.
#
#   tools/conformance/run.sh [--quick|--full|--control] [--serial S | --phone] [--no-shrink] [--out DIR] [--report]
#   tools/conformance/run.sh --repro docs/conformance/repros/<n>.json [--serial S | --phone]
#   tools/conformance/run.sh --repro-scenario <scenario-id> --steps N [--mode quick|full] [--serial S | --phone]
#
#   --quick    (default) ~45 scenarios / ~180 steps per keyboard, a few minutes each: regression gate
#   --full     every qol item, 20 human sentences step by step + 30 in real time, 40 fuzz scenarios; then
#              shrinks the top clusters to minimal repros and writes docs/conformance/report.md
#   --control  positive control: human taps and fuzzing only (no hand-written scenario for TF-01)
#   --serial   emulator to use (default emulator-5562); --phone uses the dev phone through tools/phone/guard.sh
#   --devices a,b,c,d  sharded run: the Gboard and pboard legs run concurrently, each split over half of the devices
#              (shard.py: calibration first on every shard, the rest assigned deterministically; shard k of both
#              keyboards gets the same scenarios in the same order); each leg is analysed as soon as it ends
#   --pool N   like --devices, with N devices leased from the farm pool (tools/farm/pool.sh hold, over ssh; the
#              lease is released when the run ends). The farm host is CONFORM_POOL_HOST or PBOARD_FARM_HOST (local.env);
#              drive its emulators with ADB_SERVER_SOCKET=tcp:<host>:5037.
#   --target NAME  name in the report and the gate baseline clusters-<NAME>-<mode>.json (default emulator/phone)
#   --scenarios FILE  play these scenarios (JSON lines, e.g. a subset of a generated set; calibration scenarios are
#              added when missing) instead of generating --mode's set
#   --report   also write docs/conformance/report.md (default for --full)
# Gate: exits 1 when a divergence cluster of kind text / cursor / keyboard / focus / editor-events appears that is
# neither in the baseline docs/conformance/clusters-<target>-<mode>.json nor allowlisted (allowlist.yaml).
# Env: SKIP_BUILD=1, SNAPSHOT=<name> (default conform_base), SEED=<n> (default 1), KEEP_IMAGES=1 (no pruning),
#      CONFORM_WORKERS=<n> (analysis processes, default 3), CONFORM_MAX_LOAD (shrink waits for a quieter host),
#      CONFORM_PHONE_APP / CONFORM_PHONE_COMMIT (the main release build to test on the phone),
#      CONFORM_NO_DOCS=1 (full mode: report into <out>/report.md only; the caller writes the combined docs report),
#      CONFORM_ANALYZE_HOST=<ssh host> (sharded runs: analyse each leg there, analyze_remote.sh), CONFORM_SHRINK_ROUNDS=<n>.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! command -v adb >/dev/null 2>&1; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
HERE="$ROOT/tools/conformance"
MODE=quick; SERIAL=emulator-5562; PHONE=0; SHRINK=auto; OUT=""; REPORT=0; REPRO=""; REPRO_SC=""; STEPS=""
DEVICES=""; POOL=0; TARGET=""; SCEN_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) MODE=quick ;; --full) MODE=full ;; --control) MODE=control ;;
    --mode) MODE="$2"; shift ;;
    --serial) SERIAL="$2"; shift ;;
    --devices) DEVICES="$2"; shift ;;
    --pool) POOL="$2"; shift ;;
    --target) TARGET="$2"; shift ;;
    --scenarios) SCEN_FILE="$2"; shift ;;
    --phone) PHONE=1; source "$ROOT/tools/dev/local_env.sh"; pboard_require PBOARD_PHONE_SERIAL
             SERIAL="$PBOARD_PHONE_SERIAL" ;;
    --no-shrink) SHRINK=0 ;;
    --out) OUT="$2"; shift ;;
    --report) REPORT=1 ;;
    --repro) REPRO="$2"; shift ;;
    --repro-scenario) REPRO_SC="$2"; shift ;;
    --steps) STEPS="$2"; shift ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
  shift
done
PY=(python3 -I)
TARGET="${TARGET:-$([ $PHONE = 1 ] && echo phone || echo emulator)}"
if [ -z "$OUT" ]; then
  WHAT="${REPRO:+repro_$(basename "$REPRO" .json)}${REPRO_SC:+repro_${REPRO_SC//\//_}}"
  OUT="$HERE/out/$(date +%Y%m%d-%H%M%S)_${TARGET}_${WHAT:-$([ -n "$SCEN_FILE" ] && basename "$SCEN_FILE" .jsonl || echo "$MODE")}"
fi
mkdir -p "$OUT"
if [ "$POOL" -gt 0 ]; then
  # lease N devices from the farm pool for the whole run (released when this script exits: stdin of the hold closes)
  source "$ROOT/tools/dev/local_env.sh"
  POOL_HOST="${CONFORM_POOL_HOST:-${PBOARD_FARM_HOST:-}}"
  [ -n "$POOL_HOST" ] || { echo "--pool needs the farm's ssh host: CONFORM_POOL_HOST or PBOARD_FARM_HOST (local.env)" >&2; exit 2; }
  scp -q "$ROOT/tools/farm/pool.sh" "$POOL_HOST:/tmp/conform-pool.sh"
  coproc POOLHOLD { ssh "$POOL_HOST" "bash /tmp/conform-pool.sh hold $POOL -m 2 -t 3600 -j conform"; }
  line=""
  while [[ "$line" != DEVICES=* ]]; do read -r line <&"${POOLHOLD[0]}" || { echo "!! no devices from the pool"; exit 1; }; done
  # a hold expires after 30 min unless the client writes "renew": renew every 10 min while this script runs
  ( while sleep 600; do echo renew >&"${POOLHOLD[1]}" 2>/dev/null || exit 0; done ) &
  RENEWER=$!; trap 'kill $RENEWER 2>/dev/null' EXIT
  DEVICES="${line#DEVICES=}"
  echo "== pool: leased $DEVICES"
fi
if [ -n "$DEVICES" ]; then SERIAL="${DEVICES%%,*}"; fi
echo "== conformance: $TARGET (${DEVICES:-$SERIAL}), ${REPRO:-${REPRO_SC:-$MODE}} -> $OUT"

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  # Gradle device tasks are not used here, but scope them anyway (repo rule).
  (cd "$ROOT" && ANDROID_SERIAL="$([ $PHONE = 1 ] && echo none || echo "$SERIAL")" ./gradlew -q :app:assembleRelease :bench:assembleDebug)
fi

# Scenarios
if [ -n "$REPRO" ]; then
  "${PY[@]}" -c 'import json,sys; sys.path.insert(0,sys.argv[3]); import scenarios as S
r=json.load(open(sys.argv[1])); rows=[s.to_json() for s in S.calib()]+[r]
open(sys.argv[2],"w").write("".join(json.dumps(x)+"\n" for x in rows))' "$REPRO" "$OUT/scenarios.jsonl" "$HERE"
elif [ -n "$SCEN_FILE" ]; then
  "${PY[@]}" -c 'import json,sys; sys.path.insert(0,sys.argv[3]); import scenarios as S
rows=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
calib=[s.to_json() for s in S.calib()] if not any(r["id"].startswith("calib/") for r in rows) else []
open(sys.argv[2],"w").write("".join(json.dumps(x)+"\n" for x in calib+rows))' "$SCEN_FILE" "$OUT/scenarios.jsonl" "$HERE"
elif [ -n "$REPRO_SC" ]; then
  "${PY[@]}" "$HERE/scenarios.py" --mode "${MODE}" --seed "${SEED:-1}" --out "$OUT/all.jsonl" >/dev/null
  "${PY[@]}" -c 'import json,sys; sys.path.insert(0,sys.argv[5]); import scenarios as S
rows=[json.loads(l) for l in open(sys.argv[1])]; sc=[r for r in rows if r["id"]==sys.argv[2]]
assert sc, "no scenario "+sys.argv[2]+" in mode"; sc=sc[0]; sc["steps"]=sc["steps"][:int(sys.argv[3])]
out=[s.to_json() for s in S.calib()]+[sc]; open(sys.argv[4],"w").write("".join(json.dumps(x)+"\n" for x in out))' \
    "$OUT/all.jsonl" "$REPRO_SC" "${STEPS:-999}" "$OUT/scenarios.jsonl" "$HERE"
else
  "${PY[@]}" "$HERE/scenarios.py" --mode "$MODE" --seed "${SEED:-1}" --out "$OUT/scenarios.jsonl"
fi

# Emulator: the clean Gboard snapshot must exist
# (only the harness's own snapshot is ever made, and only on a local emulator: a golden snapshot such as the farm's
# gboard_bench_base is never rebuilt or written, and remote consoles cannot be listed from here)
if [ $PHONE = 0 ] && [ "${SNAPSHOT:-conform_base}" = conform_base ] && [[ "${ADB_SERVER_SOCKET:-}" != tcp:* ]]; then
  SNAP="${SNAPSHOT:-conform_base}"
  if ! adb -s "$SERIAL" emu avd snapshot list 2>/dev/null | grep -q "$SNAP"; then
    echo "== no snapshot $SNAP on $SERIAL: making it"
    bash "$HERE/make_snapshot.sh" "$SERIAL" "$SNAP"
  fi
fi

if [ -n "$DEVICES" ]; then
  IFS=, read -ra DEVS <<< "$DEVICES"
  N=$(( ${#DEVS[@]} / 2 ))
  [ $N -ge 1 ] || { echo "!! --devices/--pool needs at least 2 devices"; exit 2; }
  "${PY[@]}" "$HERE/shard.py" split --scenarios "$OUT/scenarios.jsonl" --n $N --out "$OUT/shards"
  mkdir -p "$OUT/logs"
  PIDS=()
  for k in $(seq 0 $((N - 1))); do
    for side in 0 1; do
      ime=$([ $side = 0 ] && echo gboard || echo pboard); dev="${DEVS[$((side * N + k))]}"
      echo "   $ime shard $k on $dev"
      ( "${PY[@]}" "$HERE/device.py" run --serial "$dev" --ime $ime --scenarios "$OUT/shards/shard-$k.jsonl" \
          --out "$OUT/$ime.$k" --snapshot "${SNAPSHOT:-conform_base}" &&
        if [ -n "${CONFORM_ANALYZE_HOST:-}" ]; then
          bash "$HERE/analyze_remote.sh" --host "$CONFORM_ANALYZE_HOST" --workers "${CONFORM_LEG_WORKERS:-6}" "$OUT/$ime.$k"
        else
          CONFORM_WORKERS="${CONFORM_LEG_WORKERS:-1}" nice -n 10 "${PY[@]}" "$HERE/analyze.py" "$OUT/$ime.$k"
        fi ) > "$OUT/logs/$ime.$k.log" 2>&1 &
      PIDS+=($!)
    done
  done
  FAIL=0; for p in "${PIDS[@]}"; do wait "$p" || FAIL=1; done
  if [ $FAIL = 1 ]; then echo "!! a leg failed:"; tail -n 4 "$OUT"/logs/*.log; exit 1; fi
  for ime in gboard pboard; do
    "${PY[@]}" "$HERE/shard.py" merge --scenarios "$OUT/scenarios.jsonl" --out "$OUT/$ime" $(for k in $(seq 0 $((N - 1))); do echo "$OUT/$ime.$k"; done)
  done
else
  for ime in gboard pboard; do
    "${PY[@]}" "$HERE/device.py" run --serial "$SERIAL" --ime $ime --scenarios "$OUT/scenarios.jsonl" --out "$OUT/$ime" \
      --snapshot "${SNAPSHOT:-conform_base}"
    "${PY[@]}" "$HERE/analyze.py" "$OUT/$ime"
  done
fi
cp "$OUT/gboard/scenarios.jsonl" "$OUT/scenarios.projected.jsonl"
"${PY[@]}" "$HERE/diff.py" "$OUT/gboard" "$OUT/pboard" --out "$OUT/divergences.json"

if [ -n "$REPRO$REPRO_SC" ]; then
  "${PY[@]}" "$HERE/show.py" "$OUT/gboard" "$OUT/pboard" --sc '^(?!calib/)'
  exit 0
fi

SHRINK_ARGS=()
if [ "$SHRINK" = auto ] && [ "$MODE" = full ] && [ $PHONE = 0 ]; then
  cp "$OUT/scenarios.jsonl" "$OUT/scenarios.orig.jsonl"
  "${PY[@]}" "$HERE/shrink.py" --serial "${DEVICES:-$SERIAL}" --runs "$OUT" --top 20 --rounds "${CONFORM_SHRINK_ROUNDS:-3}" --out "$OUT/shrink" || true
  SHRINK_ARGS=(--shrink "$TARGET=$OUT/shrink")
fi
if { [ $REPORT = 1 ] || [ "$MODE" = full ]; } && [ "${CONFORM_NO_DOCS:-0}" != 1 ]; then
  # docs/conformance/report.md covers this run plus the latest full (else quick) run of the other target
  OTHER=$([ $TARGET = phone ] && echo emulator || echo phone)
  OTHER_DIR="$(ls -d "$HERE"/out/*_${OTHER}_full "$HERE"/out/*_${OTHER}_quick 2>/dev/null | sort | tail -1 || true)"
  TARGETS=(--target "$TARGET=$OUT")
  [ -n "$OTHER_DIR" ] && [ -f "$OTHER_DIR/divergences.json" ] && TARGETS+=(--target "$OTHER=$OTHER_DIR")
  [ -d "$OTHER_DIR/shrink" ] && SHRINK_ARGS+=(--shrink "$OTHER=$OTHER_DIR/shrink")
  "${PY[@]}" "$HERE/report.py" "${TARGETS[@]}" "${SHRINK_ARGS[@]}" --out "$ROOT/docs/conformance/report.md" \
    --clusters "$OUT/clusters.json" --repro-dir "$ROOT/docs/conformance/repros" \
    --accuracy "$ROOT/docs/conformance/accuracy.json" --notes "$ROOT/docs/conformance/notes.md" \
    --control-md "$ROOT/docs/conformance/positive-control.md"
else
  "${PY[@]}" "$HERE/report.py" --target "$TARGET=$OUT" --out "$OUT/report.md" --clusters "$OUT/clusters.json"
  sed -n '/^### Top divergences/,/^### Allowlisted/p' "$OUT/report.md" | grep '^\*\*' | head -20
fi
# Images are only kept as evidence for the report (analysis features stay in the cache): the disk is shared
[ "${KEEP_IMAGES:-0}" = 1 ] || "${PY[@]}" "$HERE/prune.py" "$OUT"
rm -rf "$OUT"/gboard.[0-9]*/img "$OUT"/pboard.[0-9]*/img  # sharded runs: hard links of the merged img/
"${PY[@]}" "$HERE/gate.py" "$OUT/divergences.json" "$ROOT/docs/conformance/clusters-$TARGET-$MODE.json"
