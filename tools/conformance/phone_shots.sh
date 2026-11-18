#!/usr/bin/env bash
# Screenshots for measuring per-IME geometry on the dev phone (run under tools/phone/guard.sh, which holds
# the phone lock and checks that pboard's PlaygroundActivity is in front; every screenshot is taken with
# guard.sh --shot, which checks the foreground before and after the capture):
#   tools/phone/guard.sh --open -- tools/conformance/phone_shots.sh <out-dir> [gboard|pboard ...]
# For each IME: lowercase letters (nocap field, empty) and the strip with suggestions after typing "hel"
# (taps on h, e, l at key centres found from the letters shot by geometry.py). Leaves pboard selected
# (the phone's default IME).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$1"; shift
IMES=("$@")
[ $# -gt 0 ] || IMES=(gboard pboard)
mkdir -p "$OUT"
A=(adb -s "$PHONE")
declare -A ID=([gboard]=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME [pboard]=dev.pboard/.ime.PboardIme)
DENSITY="$("${A[@]}" shell wm density | grep -o '[0-9]*' | tail -1)"
pg() { "${A[@]}" shell am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd "$@" >/dev/null; }
for ime in "${IMES[@]}"; do
  "${A[@]}" shell ime set "${ID[$ime]}" >/dev/null
  sleep 1.5
  # switching away from pboard can take the playground (same process) down: bring it back
  "${A[@]}" shell am start -W -n dev.pboard/.PlaygroundActivity >/dev/null
  sleep 2
  "$ROOT/tools/phone/guard.sh" --check
  pg reset --es variant nocap
  sleep 2.5
  "$ROOT/tools/phone/guard.sh" --shot "$OUT/${ime}_letters.png"
  # key centres of h, e, l from a provisional measurement (strip from the same shot)
  read -r H E L < <(python3 -I "$ROOT/tools/conformance/geometry.py" --letters "$OUT/${ime}_letters.png" \
      --strip "$OUT/${ime}_letters.png" --name tmp --ime tmp --density "$DENSITY" --out "$OUT/.tmp.json" >/dev/null && \
      python3 -c 'import json,sys; k={x["name"]:x for x in json.load(open(sys.argv[1]))["keys"]}; print(*["%d,%d" % (k[c]["cx"], k[c]["cy"]) for c in "hel"])' "$OUT/.tmp.json")
  for p in "$H" "$E" "$L"; do
    "$ROOT/tools/phone/guard.sh" --check
    "${A[@]}" shell input tap "${p%,*}" "${p#*,}"
    sleep 0.4
  done
  sleep 1.5
  "$ROOT/tools/phone/guard.sh" --shot "$OUT/${ime}_strip.png"
  pg reset --es variant nocap
  echo "$ime: $OUT/${ime}_letters.png $OUT/${ime}_strip.png"
done
"${A[@]}" shell ime set "${ID[pboard]}" >/dev/null
