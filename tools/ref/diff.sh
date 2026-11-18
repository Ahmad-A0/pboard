#!/usr/bin/env bash
# Pixel-diff two capture sets made by capture.sh (e.g. Gboard vs pboard).
#
#   tools/ref/diff.sh <ref-dir> <test-dir> [fuzz%]
#
# For every state present in both dirs, crops both shots to the keyboard region (top edge from
# the reference manifest), writes <test-dir>/diff/<state>.png (reference | test | highlighted
# difference) and prints the number of differing pixels at the given colour fuzz (default 2%).
set -euo pipefail
ref=${1:?ref dir}; tst=${2:?test dir}; fuzz=${3:-2%}
mkdir -p "$tst/diff"
while IFS=$'\t' read -r state file top; do
  [ -f "$tst/$file" ] || continue
  [ "$top" = None ] && top=0
  h=$(( $(magick identify -format %h "$ref/$file") - top ))
  magick "$ref/$file" -crop "x$h+0+$top" +repage /tmp/_ref_$$.png
  magick "$tst/$file" -crop "x$h+0+$top" +repage /tmp/_tst_$$.png
  n=$(magick compare -fuzz "$fuzz" -metric AE /tmp/_ref_$$.png /tmp/_tst_$$.png /tmp/_d_$$.png 2>&1 || true)
  magick /tmp/_ref_$$.png /tmp/_tst_$$.png /tmp/_d_$$.png +append "$tst/diff/$state.png"
  printf '%-40s %10s px differ\n' "$state" "$n"
done < "$ref/manifest.tsv"
rm -f /tmp/_ref_$$.png /tmp/_tst_$$.png /tmp/_d_$$.png
