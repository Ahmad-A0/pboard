#!/usr/bin/env bash
# Record the README demo (docs/media/demo.gif): tapped words with an autocorrection, a suggestion picked from the
# strip, three glide-typed words, an emoji from the panel, then the same text under a series of themes.
#
#   ANDROID_SERIAL=emulator-5556 tools/media/demo_gif.sh [out.gif]
#
# Needs (inside the devshell): a 1080x2400 @ 420 dpi emulator (the project's Pixel 8 AVD profile) with the DEBUG app
# and the bench injector installed and pboard as the current keyboard:
#   ./gradlew :app:assembleDebug :bench:assembleDebug
#   adb install -r app/build/outputs/apk/debug/app-debug.apk; adb install -r bench/build/outputs/apk/debug/bench-debug.apk
#   adb shell ime enable dev.pboard/.ime.PboardIme; adb shell ime set dev.pboard/.ime.PboardIme
# and gifski on the PATH (e.g. `nix shell nixpkgs#gifski`).
#
# How: the keys are located on a screenshot (tools/bench/measure_geometry.py), demo_trace.py turns the script below
# into a touch trace and the bench injector replays it with exact timestamps while `screenrecord` runs. The emoji taps
# and the theme changes (debug-build PrefsReceiver) are sent from the host afterwards. demo_retime.py then removes the
# waiting between the steps and gives every theme the same beat, and gifski encodes the frames.
#
# The demo changes these settings and leaves them changed: theme (back to THEMES' last entry) and the language key
# (hidden, so that the emoji key shows).
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator to record}"
OUT="${1:-docs/media/demo.gif}"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
A=(adb -s "$ANDROID_SERIAL")
IME=dev.pboard/.ime.PboardIme

# What is typed. `<`, `^` and `>` tap the left, middle and right suggestion.
TAP="${TAP:-cofee tomorow <. }"
EXPECT="${EXPECT:-Coffee tomorrow morning. }"
GLIDE="${GLIDE:-see,you,there}"
FINAL="${FINAL:-Coffee tomorrow morning. See you there}"   # the field before the emoji
# The first theme is the one typed in; the last one ends the loop (keep them equal).
THEMES="${THEMES:-default_light default_dark dynamic color_blue color_teal gradient_dark_1 color_sand gradient_light_1 default_light}"
# Screen coordinates on the emoji panel: an emoji in the grid, and the ABC key.
EMOJI_XY="${EMOJI_XY:-608 1764}"
ABC_XY="${ABC_XY:-79 2212}"
# Bands of the screen that are kept (y0:y1), stacked: the text field, and the keyboard with room for the emoji panel.
BAND_TOP="${BAND_TOP:-110:352}"
BAND_BOTTOM="${BAND_BOTTOM:-1180:2272}"
WIDTH="${WIDTH:-540}"
FPS="${FPS:-25}"
QUALITY="${QUALITY:-90}"

pref() { "${A[@]}" shell am broadcast -n dev.pboard/.debug.PrefsReceiver "$@" >/dev/null; }
playground() { "${A[@]}" shell am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd "$@"; }
field_text() { playground get | python3 -c 'import sys, re, json
m = re.search(r"data=\"(.*)\"\s*$", sys.stdin.read(), re.S)
print(json.loads(m.group(1))["text"] if m else "")'; }

[ "$("${A[@]}" shell wm size | tr -d '\r' | awk 'END {print $NF}')" = 1080x2400 ] || { echo "needs a 1080x2400 screen" >&2; exit 1; }
"${A[@]}" shell settings get secure default_input_method | grep -q "^$IME" || { echo "pboard is not the current keyboard" >&2; exit 1; }
"${A[@]}" shell pm path dev.pboard.bench >/dev/null || { echo "bench injector not installed" >&2; exit 1; }
command -v gifski >/dev/null || { echo "gifski not found" >&2; exit 1; }

read -r -a themes <<< "$THEMES"
echo "== prepare (work dir $WORK)"
pref --ez show_language_switch_key false --es theme "${themes[0]}"
"${A[@]}" shell am start -n dev.pboard/.PlaygroundActivity >/dev/null 2>&1   # no -S: that would force-stop the keyboard
sleep 2
playground reset >/dev/null
sleep 2
"${A[@]}" exec-out screencap -p > "$WORK/keys.png"
python3 -I tools/bench/measure_geometry.py "$WORK/keys.png" "$WORK/geometry.json" --name demo --ime "$IME" >/dev/null
python3 -I tools/media/demo_trace.py "$WORK/geometry.json" "$WORK/trace.jsonl" --tap "$TAP" --expect "$EXPECT" --glide "$GLIDE" >/dev/null
"${A[@]}" push "$WORK/trace.jsonl" /data/local/tmp/demo_trace.jsonl >/dev/null
emoji_key="$(python3 -c 'import json, sys
k = {k["name"]: k for k in json.load(open(sys.argv[1]))["keys"]}["emoji"]; print(round(k["cx"]), round(k["cy"]))' "$WORK/geometry.json")"

echo "== record"
"${A[@]}" shell "screenrecord --bit-rate 20000000 --time-limit 120 /sdcard/pboard_demo.mp4" &
REC=$!
sleep 1.5
"${A[@]}" shell am instrument -w -e mode replay -e trace /data/local/tmp/demo_trace.jsonl -e retries 0 -e settleMs 150 \
  -e out demo.jsonl dev.pboard.bench/.Replay | grep -E "summary|error" || true
# Every glide must have continued from what the taps produced (otherwise the injector rewrote the field).
got="$(field_text)"
"${A[@]}" shell run-as dev.pboard.bench cat files/demo.jsonl > "$WORK/replay.jsonl"
if [ "$got" != "$FINAL" ] || grep -q '"kind":"swipe".*"chained":false' "$WORK/replay.jsonl"; then
  echo "typed text is '$got' (expected '$FINAL'); see $WORK/replay.jsonl" >&2
  "${A[@]}" shell pkill -INT screenrecord
  exit 1
fi
sleep 0.3
# shellcheck disable=SC2086
{
  "${A[@]}" shell input tap $emoji_key; sleep 1.2
  "${A[@]}" shell input tap $EMOJI_XY; sleep 0.8
  "${A[@]}" shell input tap $ABC_XY; sleep 1.5
}
for t in "${themes[@]:1}"; do
  pref --es theme "$t"
  sleep 1.6
done
sleep 1
"${A[@]}" shell pkill -INT screenrecord
wait "$REC" || true
sleep 1
"${A[@]}" pull /sdcard/pboard_demo.mp4 "$WORK/raw.mp4" >/dev/null
"${A[@]}" shell rm -f /sdcard/pboard_demo.mp4 /data/local/tmp/demo_trace.jsonl
echo "final text: $(field_text)"

echo "== frames"
t0=${BAND_TOP%:*}; t1=${BAND_TOP#*:}; b0=${BAND_BOTTOM%:*}; b1=${BAND_BOTTOM#*:}
STACK="[0:v]fps=$FPS,split[a][b];[a]crop=iw:$((t1 - t0)):0:$t0[top];[b]crop=iw:$((b1 - b0)):0:$b0[bot];[top][bot]vstack"
rm -rf "$WORK/frames" "$WORK/sel"
mkdir -p "$WORK/frames" "$WORK/sel"
ffmpeg -v error -y -i "$WORK/raw.mp4" -filter_complex "$STACK,scale=$WIDTH:-2:flags=lanczos" "$WORK/frames/%05d.png"
# Small RGB copies for the retimer's change detection.
SW=108; SH=$(( ((t1 - t0) + (b1 - b0)) / 10 ))
ffmpeg -v error -y -i "$WORK/raw.mp4" -filter_complex "$STACK,scale=$SW:$SH:flags=area" -f rawvideo -pix_fmt rgb24 "$WORK/small.rgb"
python3 -I tools/media/demo_retime.py "$WORK/small.rgb" "$SW" "$SH" --fps "$FPS" --field-rows "$(( (t1 - t0) / 10 ))" \
  --themes "$(( ${#themes[@]} - 1 ))" --skip 2 > "$WORK/select.txt"
n=0
while read -r f; do
  n=$((n + 1))
  ln -sf "$WORK/frames/$(printf %05d "$f").png" "$WORK/sel/$(printf %05d "$n").png"
done < "$WORK/select.txt"

echo "== encode ($n frames, $FPS fps)"
mkdir -p "$(dirname "$OUT")"
gifski --quiet --fps "$FPS" --width "$WIDTH" --quality "$QUALITY" -o "$OUT" "$WORK"/sel/*.png
ls -l "$OUT"
