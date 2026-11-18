#!/usr/bin/env bash
# Looks at apps' first screens (to write their apps.py entries): for each package, a cold launcher start, then a
# screenshot and the accessibility tree (uiautomator) in <out>/<package>-<n>.{png,xml}; optional taps on texts.
#   tools/appcompat/explore.sh <serial> <out> <package>[:text1:text2...] ...
# Run inside a lease: tools/farm/pool.sh lease 1 -j explore -- tools/appcompat/explore.sh @DEVICES@ out pkg ...
set -uo pipefail
S="$1"; OUT="$2"; shift 2
mkdir -p "$OUT"
A=(adb -s "$S")
shot() { "${A[@]}" exec-out screencap -p > "$OUT/$1.png"; "${A[@]}" shell uiautomator dump /sdcard/x.xml >/dev/null 2>&1
         "${A[@]}" shell cat /sdcard/x.xml > "$OUT/$1.xml"; }
for spec in "$@"; do
  IFS=: read -r -a parts <<<"$spec"; pkg="${parts[0]}"
  "${A[@]}" shell am force-stop "$pkg"
  "${A[@]}" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 7; shot "$pkg-0"
  n=1
  for t in "${parts[@]:1}"; do
    b="$(grep -oE "text=\"$t\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$OUT/$pkg-$((n-1)).xml" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)"
    if [ -n "$b" ]; then
      read -r x1 y1 x2 y2 <<<"$(tr '[],' '   ' <<<"$b")"
      "${A[@]}" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )); sleep 3
    fi
    shot "$pkg-$n"; n=$((n + 1))
  done
done
echo "explored -> $OUT"
