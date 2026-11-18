#!/usr/bin/env bash
# Make (and verify) the clean, fully provisioned Gboard emulator snapshot the conformance harness restores
# before every Gboard batch.
#   tools/conformance/make_snapshot.sh <serial> [snapshot-name=conform_base]
# Steps (as tools/bench/make_base_snapshot.sh, plus a GMS fallback and a behavioural check):
#  1. reboot; wipe Gboard once right after boot (it then re-syncs its remote configuration and downloads its
#     current main LM); select it; keyboard up in the playground; wait for the downloaded main LM.
#  2. synced look (tools/bench/state_check.py: ?123 and Enter in the secondary-container colour, no tonal
#     toolbar button). If it fails: force-stop GMS (on an account-less AVD this re-syncs the configuration), reopen
#     the keyboard, wait for "fetchAndUpdate() : Success", check again.
#  3. save the snapshot (nothing typed yet), then verify it: restore, type "becuase " -> "because ",
#     glide "hello" -> "hello", restore again. Exit 1 if any check fails.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! command -v adb >/dev/null 2>&1; then exec nix develop "$ROOT" --command bash "$0" "$@"; fi
SERIAL="$1"; SNAP="${2:-conform_base}"
ADB=(adb -s "$SERIAL")
GEO="$ROOT/tools/bench/geometry/gboard_pixel8.json"
IME=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
TMP="$(mktemp -d)"
APP="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
BENCH="$ROOT/bench/build/outputs/apk/debug/bench-debug.apk"
[ -f "$APP" ] && [ -f "$BENCH" ] || (cd "$ROOT" && ./gradlew -q :app:assembleDebug :bench:assembleDebug)

boot_wait() {
  "${ADB[@]}" wait-for-device
  until [ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
}
keyboard_up() {
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP
  "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "${ADB[@]}" shell am start -n dev.pboard/.PlaygroundActivity >/dev/null
  sleep 3
  "${ADB[@]}" shell am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd reset >/dev/null
  sleep 3
}
state_ok() {
  "${ADB[@]}" exec-out screencap -p > "$TMP/state.png"
  python3 -I "$ROOT/tools/bench/state_check.py" "$GEO" "$TMP/state.png"
}

echo "== rebooting $SERIAL"
"${ADB[@]}" reboot; sleep 5; boot_wait; sleep 5
"${ADB[@]}" install -r -g "$APP" >/dev/null
"${ADB[@]}" install -r "$BENCH" >/dev/null
"${ADB[@]}" shell mkdir -p /data/local/tmp/conform
echo "$(sha256sum "$APP" | cut -c1-64) $(sha256sum "$BENCH" | cut -c1-64)" | "${ADB[@]}" shell "cat > /data/local/tmp/conform/apk.sha"
"${ADB[@]}" logcat -c || true
"${ADB[@]}" shell pm clear com.google.android.inputmethod.latin >/dev/null
"${ADB[@]}" shell ime enable "$IME" >/dev/null
"${ADB[@]}" shell ime set "$IME" >/dev/null
"${ADB[@]}" shell settings put secure show_ime_with_hard_keyboard 1
"${ADB[@]}" shell settings put secure stylus_handwriting_enabled 0
# An IME crash in dev.pboard must not leave an "app keeps stopping" dialog over the playground.
"${ADB[@]}" shell settings put global hide_error_dialogs 1
keyboard_up
LMV=""
for i in $(seq 1 60); do
  [ $(( i % 5 )) = 1 ] && "${ADB[@]}" shell am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd reset >/dev/null
  LMV="$("${ADB[@]}" logcat -d 2>/dev/null | grep -o 'loadLanguageModel() : 1, version \[[0-9]*\]' | tail -1 | grep -o '[0-9]\{6,\}' || true)"
  if [ -n "$LMV" ] && [ "$LMV" != 2024102000 ]; then break; fi
  sleep 3
done
echo "== main LM: ${LMV:-none}"
[ -n "$LMV" ] && [ "$LMV" != 2024102000 ] || { echo "!! downloaded LM not loaded"; exit 4; }
sleep 30   # configuration re-sync
keyboard_up
if ! state_ok; then
  echo "== not synced: force-stopping GMS to re-sync the configuration"
  "${ADB[@]}" shell am force-stop com.google.android.gms
  for i in $(seq 1 30); do
    keyboard_up
    "${ADB[@]}" logcat -d 2>/dev/null | grep -q 'fetchAndUpdate() : Success' && state_ok && break
    sleep 5
  done
  state_ok || { echo "!! Gboard's remote configuration did not re-sync"; exit 6; }
fi
echo "== saving snapshot $SNAP"
"${ADB[@]}" emu avd snapshot save "$SNAP" | tr -d '\r'
echo "== verifying $SNAP"
python3 -I "$ROOT/tools/conformance/device.py" verify --serial "$SERIAL" --snapshot "$SNAP"
