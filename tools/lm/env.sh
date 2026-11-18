# Sourced by build.sh (inside the flake devshell). Sets $PY to an isolated venv interpreter that can
# load manylinux wheels (numpy/pyarrow) on NixOS.
set -euo pipefail
LM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(dirname "$LM_DIR")"
REPO_DIR="$(dirname "$TOOLS_DIR")"
VENV="$TOOLS_DIR/.venv"

if [ ! -x "$VENV/bin/python" ]; then
  python3 -I -m venv "$VENV"
  "$VENV/bin/python" -I -m pip install -q -r "$LM_DIR/requirements.txt"
fi

if [ -e /etc/NIXOS ] || [ -d /nix/store ]; then
  # manylinux wheels need libstdc++/zlib; resolve them from the flake's pinned nixpkgs once and cache
  LDCACHE="$VENV/.ld_library_path"
  if [ ! -s "$LDCACHE" ] || ! [ -d "$(cut -d: -f1 "$LDCACHE")" ]; then
    LIBSTDCXX="$(nix build --inputs-from "$REPO_DIR" nixpkgs#stdenv.cc.cc.lib --no-link --print-out-paths 2>/dev/null | head -1)"
    ZLIB="$(nix build --inputs-from "$REPO_DIR" nixpkgs#zlib --no-link --print-out-paths 2>/dev/null | head -1)"
    echo "$LIBSTDCXX/lib:$ZLIB/lib" > "$LDCACHE"
  fi
  export LD_LIBRARY_PATH="$(cat "$LDCACHE")${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
PY="$VENV/bin/python -I"
export PYTHONHASHSEED=0
