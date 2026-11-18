#!/usr/bin/env bash
# Analyse batch directories on the emulator farm host instead of this machine.
#   tools/conformance/analyze_remote.sh [--host H] [--workers N] <batch-dir>...   (default host: PBOARD_FARM_HOST)
# Mirrors the analysis code, the flake lock (same tesseract/imagemagick as here), the geometries, the reference
# library and the batch directories to the SAME absolute paths on the host, runs analyze.py there (nix shell with
# python3, imagemagick and tesseract from this checkout's nixpkgs), and copies steps.jsonl / analysis.json and the
# library back. The host keeps its own feature cache under the mirrored tools/conformance/out/cache.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/tools/dev/local_env.sh"
HOST="${PBOARD_FARM_HOST:-}"; N=8
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --workers) N="$2"; shift 2 ;;
    *) break ;;
  esac
done
[ $# -ge 1 ] || { echo "usage: $0 [--host H] [--workers N] <batch-dir>..."; exit 2; }
[ -n "$HOST" ] || { echo "no host: pass --host or set PBOARD_FARM_HOST (local.env)" >&2; exit 2; }
C="$ROOT/tools/conformance"
FL=/home/$(whoami)/.cache/conform-flake  # flake.nix + flake.lock only (a path: flake is copied to the store whole)
ssh "$HOST" "mkdir -p '$C/out/refs' '$ROOT/tools/bench' '$FL'"
rsync -a "$ROOT/flake.nix" "$ROOT/flake.lock" "$HOST:$FL/"
rsync -a --exclude out --exclude __pycache__ "$C/" "$HOST:$C/"
rsync -a "$ROOT/tools/bench/geometry" "$HOST:$ROOT/tools/bench/"
[ -d "$C/out/refs" ] && rsync -a "$C/out/refs/" "$HOST:$C/out/refs/"
for d in "$@"; do
  d="$(cd "$d" && pwd)"
  ssh "$HOST" "mkdir -p '$d'"
  rsync -a "$d/" "$HOST:$d/"
done
ARGS=""; for d in "$@"; do ARGS="$ARGS $(cd "$d" && pwd)"; done  # (paths without spaces)
ssh "$HOST" "cd '$ROOT' && nix shell --inputs-from 'path:$FL' nixpkgs#python3 nixpkgs#imagemagick nixpkgs#tesseract -c \
  bash -c 'for d in $ARGS; do CONFORM_WORKERS=$N python3 -I tools/conformance/analyze.py \"\$d\" || exit 1; done'"
for d in "$@"; do
  d="$(cd "$d" && pwd)"
  rsync -a "$HOST:$d/steps.jsonl" "$HOST:$d/analysis.json" "$d/"
done
rsync -a "$HOST:$C/out/refs/" "$C/out/refs/"
