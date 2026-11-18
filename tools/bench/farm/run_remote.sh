#!/usr/bin/env bash
# Run a benchmark command on the farm and pull its results back.
#
#   tools/bench/farm/run_remote.sh <ssh-host> <out-name> <script> [args...]
#
# <script> is a repo-relative tools/bench script (run.sh, compare.sh, gated_tsi.sh, ...); the literal
# word DEVICES in its arguments is replaced by the farm's device list (~/<repo>/farm_devices, written
# by bootstrap.sh). It runs on the host inside `nix develop` under nohup (survives a dropped ssh);
# the log is streamed until it finishes, then tools/bench/out/ on the host is pulled into
# tools/bench/out/farm/<out-name>/ here. Pull again later with --pull-only.
#   e.g. run_remote.sh azfarm tsi18 tools/bench/gated_tsi.sh emulator-5570 DEVICES tools/bench/farm/apks/pboard-2398922.apk:2398922
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HOST="${1:?ssh host}"; NAME="${2:?out name}"; shift 2
REPO="${FARM_REPO:-projects/pboard}"
SSH=(ssh -o BatchMode=yes -o ServerAliveInterval=30 "$HOST")
if [ "${1:-}" != --pull-only ]; then
  SCRIPT="${1:?script}"; shift
  DEVS="$("${SSH[@]}" "cat ~/$REPO/farm_devices")"
  ARGS=(); for a in "$@"; do ARGS+=("${a//DEVICES/$DEVS}"); done
  Q="$(printf '%q ' "$SCRIPT" "${ARGS[@]}")"
  "${SSH[@]}" "cd ~/$REPO && mkdir -p tools/bench/out && nohup nix develop --command $Q > tools/bench/out/$NAME.log 2>&1 & echo \$! > tools/bench/out/$NAME.pid"
  echo "== started on $HOST: $SCRIPT ${ARGS[*]}"
  "${SSH[@]}" "cd ~/$REPO && tail -n +1 --pid=\$(cat tools/bench/out/$NAME.pid) -f tools/bench/out/$NAME.log" || true
fi
mkdir -p "$ROOT/tools/bench/out/farm/$NAME"
rsync -az --exclude '*.png' "$HOST:$REPO/tools/bench/out/" "$ROOT/tools/bench/out/farm/$NAME/"
echo "== results in tools/bench/out/farm/$NAME/"
