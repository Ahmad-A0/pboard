#!/usr/bin/env bash
# Run one pipeline step with the venv interpreter: tools/lm/run.sh <script.py> [args...]
# (must be run inside the flake devshell: nix develop --command tools/lm/run.sh ...)
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
script="$1"; shift
case "$script" in /*) ;; *) script="$LM_DIR/$script" ;; esac
exec $PY "$script" "$@"
