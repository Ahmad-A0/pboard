#!/usr/bin/env bash
# Capture the core set of keyboard reference screenshots for side-by-side diffing.
#
#   tools/ref/capture.sh <serial> <ime-id> [out-dir] [extra capture.py args...]
#
# Examples (run inside the devshell: nix develop . --command ...):
#   tools/ref/capture.sh emulator-5554 com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME docs/ref/gboard/set
#   tools/ref/capture.sh emulator-5554 dev.pboard/.ime.PboardIme docs/ref/pboard/set
#   tools/ref/capture.sh emulator-5554 dev.pboard/.ime.PboardIme docs/ref/pboard/set --only 'light_(symbols|longpress_e)'
#   # dev phone (Gboard 18.x geometry, adb-input touches, guard re-checked before every step):
#   tools/phone/guard.sh --open -- bash -c 'tools/ref/capture.sh $PHONE com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME \
#       docs/ref/gboard18/set --profile gboard18 --guard tools/phone/guard.sh --restore-ime dev.pboard/.ime.PboardIme'
#
# Output: one PNG per state (<theme>_<state>.png, 1080x2400 on the reference AVD) and
# manifest.tsv with the keyboard's top edge (screen y, px) per shot, for cropping:
#   magick a.png -crop 1080x$((2400-top))+0+$top a_kb.png
# Diff two captures:  tools/ref/diff.sh docs/ref/gboard/set docs/ref/pboard/set
#
# Needs: an emulator (touches go through the emulator console for exact hold timings),
# the FieldZoo helper app (built and installed automatically), python3.
# Side effects on the device: sets the IME, toggles `cmd uimode night`, sets
# secure stylus_handwriting_enabled=0 (console touches come from a stylus-capable device),
# and leaves light mode on at the end.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
serial=${1:?serial}; ime=${2:?ime id}; out=${3:-docs/ref/$(echo "$ime" | cut -d/ -f1)/set}
shift $(( $# >= 3 ? 3 : $# ))
export ADB=${ADB:-$(command -v adb)}

if ! "$ADB" -s "$serial" shell pm path dev.pboard.fieldzoo >/dev/null 2>&1; then
  apk=$("$here/fieldzoo/build.sh" | tail -1)
  "$ADB" -s "$serial" install -r "$apk"
fi
mkdir -p "$out"
python3 "$here/capture.py" --serial "$serial" --ime "$ime" --out "$out" "$@"
