#!/usr/bin/env bash
# Captures the icon-relevant states of pboard on the dev phone (Gboard 18.x geometry), through the phone guard.
#
#   tools/icons/phone_capture.sh <out-dir> [apk-to-install-first]
#
# Run inside the devshell (adb on PATH). Takes the phone lock once for the whole run, so the APK installed here is the
# one captured (other jobs install their builds too), then drives FieldZoo with tools/ref/capture.py for the states
# targets.py scores, checks the installed build is still ours, and leaves pboard selected.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
out="$(realpath -m "${1:?out dir}")"
apk="${2:+$(realpath "$2")}"
cd "$repo"
if [[ -z "${PBOARD_GUARD_HELD:-}" ]]; then
  exec tools/phone/guard.sh --open -- "$0" "$out" ${apk:+"$apk"}
fi
phone="${PHONE:?PHONE is set by tools/phone/guard.sh}"
stamp() { adb -s "$phone" shell dumpsys package dev.pboard | grep -m1 lastUpdateTime; }
if [[ -n "$apk" ]]; then
  adb -s "$phone" install -r "$apk"
  adb -s "$phone" shell am start -W -n dev.pboard/.PlaygroundActivity >/dev/null
  sleep 1
fi
before="$(stamp)"
states='(qwerty_lower|text_autocap|qwerty_capslock|field_(go|search|send|next|previous|multiline|done)|emoji_panel|clipboard_panel)$'
tools/ref/capture.sh "$phone" dev.pboard/.ime.PboardIme "$out" \
  --profile gboard18 --guard tools/phone/guard.sh --restore-ime dev.pboard/.ime.PboardIme --only "$states"
[[ "$(stamp)" == "$before" ]] || { echo "phone_capture: pboard was reinstalled during the capture" >&2; exit 5; }
echo "phone_capture: captured build installed at ${before#*=}"
