#!/usr/bin/env bash
# Serve the update repository (dist/repo) read-only over HTTP on ONE address, e.g. a private interface, so only
# devices on that private network (the phone) can reach it. Runs in the foreground; stop with Ctrl-C.
#   tools/release/serve_repo.sh [BIND_ADDR=$PBOARD_REPO_BIND] [PORT=${PBOARD_REPO_PORT:-8737}] [REPO_DIR=dist/repo]
# PBOARD_REPO_BIND / PBOARD_REPO_PORT come from the environment or local.env (tools/dev/local_env.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/tools/dev/local_env.sh"
[ -n "${1:-}" ] || pboard_require PBOARD_REPO_BIND
ADDR="${1:-$PBOARD_REPO_BIND}"; PORT="${2:-${PBOARD_REPO_PORT:-8737}}"; REPO="${3:-$ROOT/dist/repo}"
[ -f "$REPO/index.html" ] || { echo "no $REPO/index.html: run tools/release.sh first" >&2; exit 2; }
echo "serving $REPO at http://$ADDR:$PORT/ (Obtainium: add app, source 'HTML', this URL)"
exec python3 -I -m http.server --bind "$ADDR" --directory "$REPO" "$PORT"
