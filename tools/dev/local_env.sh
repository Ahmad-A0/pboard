#!/usr/bin/env bash
# Machine-local settings (device serials, bind addresses) live OUTSIDE the tracked tree, in a KEY=VALUE file
# (see local.env.example). This helper loads it; variables already set in the environment win.
#
#   source tools/dev/local_env.sh            # exports the keys of the first file found (see below)
#   pboard_require PBOARD_PHONE_SERIAL       # exits with a clear message when a needed key is unset
#   tools/dev/local_env.sh --get KEY         # prints one value (exit 1 with a message when unset)
#   tools/dev/local_env.sh --file            # prints the file in use (empty when there is none)
#
# Lookup order (first existing file): $PBOARD_LOCAL_ENV, <this tree>/local.env, the main checkout's local.env (when
# this tree is a git worktree), ~/.config/pboard/local.env. The same order is used by tools/dev/local_env.py and by
# build.gradle.kts.
_pboard_env_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# The main checkout of a linked worktree: its .git is a file "gitdir: <main>/.git/worktrees/<name>".
_pboard_main_checkout() {
  local g="$_pboard_env_root/.git" d
  [ -f "$g" ] || return 0
  d="$(sed -n 's/^gitdir: //p' "$g")"
  case "$d" in */.git/worktrees/*) echo "${d%/.git/worktrees/*}" ;; esac
}

pboard_local_env_file() {
  local main f
  main="$(_pboard_main_checkout)"
  for f in "${PBOARD_LOCAL_ENV:-}" "$_pboard_env_root/local.env" "${main:+$main/local.env}" \
           "$HOME/.config/pboard/local.env"; do
    if [ -n "$f" ] && [ -f "$f" ]; then echo "$f"; return 0; fi
  done
  return 0
}

pboard_local_env_load() {
  local f line k v
  f="$(pboard_local_env_file)"
  if [ -n "$f" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in ''|'#'*) continue ;; esac
      k="${line%%=*}"; v="${line#*=}"
      k="${k//[[:space:]]/}"; v="${v#"${v%%[![:space:]]*}"}"; v="${v%"${v##*[![:space:]]}"}"
      [[ "$k" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
      if [ -z "${!k:-}" ]; then export "$k=$v"; fi
    done < "$f"
  fi
  # older name of the phone serial
  if [ -z "${PBOARD_PHONE_SERIAL:-}" ] && [ -n "${PBOARD_PHONE:-}" ]; then export PBOARD_PHONE_SERIAL="$PBOARD_PHONE"; fi
}

pboard_require() {
  local k
  for k in "$@"; do
    if [ -z "${!k:-}" ]; then
      echo "$k is not set: export it, or put $k=<value> in local.env at the repository root" \
           "(untracked; see local.env.example)" >&2
      exit 2
    fi
  done
}

pboard_local_env_load

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  case "${1:-}" in
    --get) (pboard_require "${2:?KEY}") || exit 1; printf '%s\n' "${!2}" ;;
    --file) pboard_local_env_file ;;
    *) echo "usage: $0 --get KEY | --file   (or: source $0)" >&2; exit 2 ;;
  esac
fi
