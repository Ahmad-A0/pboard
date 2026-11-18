#!/usr/bin/env bash
# Record the README demo (docs/media/demo.gif): a tapped sentence with an autocorrection, glide-typed words and an
# emoji from the panel, while the theme changes between words.
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
# The typing is recorded human typing, not synthesised: demo_trace.py takes one tapped sentence and the glide strokes
# from the human evaluation sets (tools/data/human/splits/v1, sources and licences in its ATTRIBUTION.txt) and the
# bench injector replays them with their original timing on the keys as located on a screenshot
# (tools/bench/measure_geometry.py). Only the second space (which makes the full stop) and the taps on the emoji
# panel are made up.
#
# The injector lets the field settle after every word; the host changes the theme (debug-build PrefsReceiver) in some
# of those waits, so a theme change never lands inside a word. demo_retime.py then shortens the waits, and gifski
# encodes the frames. The picture is the text-field band stacked directly on the keyboard: the empty part of the
# test screen between them is left out.
#
# The demo changes these settings and leaves them changed: theme, and the enabled languages (one language only, so
# that the space bar does not flash the language name whenever the keyboard is rebuilt).
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator to record}"
OUT="${1:-docs/media/demo.gif}"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
rm -f "$WORK/log"
A=(adb -s "$ANDROID_SERIAL")
IME=dev.pboard/.ime.PboardIme

# What is typed (ids in tools/data/human/splits/v1).
#  - tapped, 83 wpm, "beautoful" as typed: TSI dataset (Google, CC BY 4.0), participant user01
SENTENCE="${SENTENCE:-tsi/user01/task4/6}"
KEYS="${KEYS- }"   # a second space right after the sentence: full stop
#  - glides "you would love it": How We Swipe (MIT) and swipe.futo.org (MIT), four different people
GLIDES="${GLIDES:-hws/21dadeslkumk72cll4tqlce9t0/are_you_being_a_baby/w1/1583722204007,futo/tune/545992,hws/8k86nv1j6q7rj77qhjbms4n7ju/he_would_love_anything_about_rocks/w2/1583213567898,futo/test/444185}"
# Screen coordinates on the emoji panel: an emoji in the grid, and the ABC key.
EMOJI_XY="${EMOJI_XY:-608,1764}"
ABC_XY="${ABC_XY:-79,2212}"
# The theme typed in, then "<trace item>:<theme>": the theme set once that item (0-based: the four tapped words, the
# four glides, the emoji) has been typed. The last one should be the first again (the GIF loops).
THEME="${THEME:-default_light}"
THEME_AFTER="${THEME_AFTER:-1:default_dark 3:color_blue 5:gradient_dark_1 7:gradient_light_1 8:default_light}"
LANGUAGE="${LANGUAGE:-en-US:qwerty}"
# Rows of the screen: the text band that is kept, and where the (taller) emoji panel starts.
BAND_TEXT="${BAND_TEXT:-132:262}"
PANEL_TOP="${PANEL_TOP:-1190}"
WIDTH="${WIDTH:-540}"
FPS="${FPS:-30}"
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

echo "== prepare (work dir $WORK)"
pref --es enabled_languages "$LANGUAGE" --es theme "$THEME"
"${A[@]}" shell am start -n dev.pboard/.PlaygroundActivity >/dev/null 2>&1   # no -S: that would force-stop the keyboard
sleep 2
playground reset >/dev/null
sleep 2
"${A[@]}" exec-out screencap -p > "$WORK/keys.png"
python3 -I tools/bench/measure_geometry.py "$WORK/keys.png" "$WORK/geometry.json" --name demo --ime "$IME" >/dev/null
FINAL="$(python3 -I tools/media/demo_trace.py "$WORK/geometry.json" "$WORK/trace.jsonl" --sentence "$SENTENCE" \
  --keys "$KEYS" --glide "$GLIDES" --emoji "$EMOJI_XY" --abc "$ABC_XY")"
"${A[@]}" push "$WORK/trace.jsonl" /data/local/tmp/demo_trace.jsonl >/dev/null
last_item=$(( $(wc -l < "$WORK/trace.jsonl") - 1 ))

echo "== record"
"${A[@]}" logcat -c
"${A[@]}" shell "screenrecord --bit-rate 20000000 --time-limit 150 /sdcard/pboard_demo.mp4" &
REC=$!
sleep 1.5
# settleMs: the wait after every item, long enough for a theme change to finish before the next word starts.
"${A[@]}" shell am instrument -w -e mode replay -e trace /data/local/tmp/demo_trace.jsonl -e retries 0 -e settleMs 2500 \
  -e holdMs 60 -e out demo.jsonl dev.pboard.bench/.Replay > "$WORK/instrument.txt" &
RUN=$!
# When to change the theme: the test screen logs the field's text on every change, so the theme is set as soon as the
# text an item should leave is there, at the start of the injector's wait. Only the last item (the emoji panel) is
# followed by the injector's own "item <i>/<n> ..." line, logged once it has settled.
python3 - "$WORK/trace.jsonl" $THEME_AFTER > "$WORK/cues.txt" <<'PY'
import json, sys
items = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8")]
for cue in sys.argv[2:]:
    i, theme = cue.split(":")
    i = int(i)
    print(theme, "text=" + json.dumps(items[i + 1]["context"]) if i + 1 < len(items) else "item %d/" % i, sep="\t")
PY
mkfifo "$WORK/log"
"${A[@]}" logcat -v raw -s PboardBench:I > "$WORK/log" &
LOG=$!
while read -r line; do
  while IFS=$'\t' read -r theme cue; do
    case "$cue" in
      text=*) [ "$line" = "$cue" ] && pref --es theme "$theme" ;;
      *) [[ "$line" == "$cue"* ]] && pref --es theme "$theme" ;;
    esac
  done < "$WORK/cues.txt"
  [[ "$line" == "item $last_item/"* ]] && break
done < "$WORK/log"
kill "$LOG" 2>/dev/null || true
rm -f "$WORK/log"
wait "$RUN" || true
grep -E "summary|error" "$WORK/instrument.txt" || true
sleep 2
"${A[@]}" shell pkill -INT screenrecord
wait "$REC" || true
sleep 1
"${A[@]}" pull /sdcard/pboard_demo.mp4 "$WORK/raw.mp4" >/dev/null
"${A[@]}" shell run-as dev.pboard.bench cat files/demo.jsonl > "$WORK/replay.jsonl"
"${A[@]}" shell rm -f /sdcard/pboard_demo.mp4 /data/local/tmp/demo_trace.jsonl
# Every item must have continued from what the ones before produced (otherwise the injector rewrote the field).
got="$(field_text)"
echo "final text: $got"
if [[ "$got" != "$FINAL "* ]] || [ "$(grep -c '"chained":true' "$WORK/replay.jsonl")" != "$last_item" ]; then
  echo "expected '$FINAL' + an emoji; see $WORK/replay.jsonl" >&2
  exit 1
fi

echo "== frames"
t0=${BAND_TEXT%:*}; t1=${BAND_TEXT#*:}
read -r kb0 kb1 < <(python3 -c 'import json, sys
g = json.load(open(sys.argv[1])); print(g["keyboard"]["y"], g["rows"][-1]["bottom"] + 29)' "$WORK/geometry.json")
stack() {  # $1 = first row of the lower band
  echo "[0:v]fps=$FPS,split[a][b];[a]crop=iw:$((t1 - t0)):0:$t0[top];[b]crop=iw:$((kb1 - $1)):0:$1[bot];[top][bot]vstack"
}
rm -rf "$WORK/frames" "$WORK/sel"
mkdir -p "$WORK/frames" "$WORK/sel"
ffmpeg -v error -y -i "$WORK/raw.mp4" -filter_complex "$(stack "$kb0"),scale=$WIDTH:-2:flags=lanczos" "$WORK/frames/%05d.png"
# Small RGB copies for the retimer, including the rows the emoji panel covers.
SW=108; SH=$(( ((t1 - t0) + (kb1 - PANEL_TOP)) / 10 ))
ffmpeg -v error -y -i "$WORK/raw.mp4" -filter_complex "$(stack "$PANEL_TOP"),scale=$SW:$SH:flags=area" -f rawvideo -pix_fmt rgb24 "$WORK/small.rgb"
python3 -I tools/media/demo_retime.py "$WORK/small.rgb" "$SW" "$SH" --fps "$FPS" --field-rows "$(( (t1 - t0) / 10 ))" \
  --panel-rows "$(( (kb0 - PANEL_TOP) / 10 ))" > "$WORK/select.txt"
n=0
while read -r f; do
  n=$((n + 1))
  ln -sf "$WORK/frames/$(printf %05d "$f").png" "$WORK/sel/$(printf %05d "$n").png"
done < "$WORK/select.txt"

echo "== encode ($n frames, $FPS fps)"
mkdir -p "$(dirname "$OUT")"
gifski --quiet --fps "$FPS" --width "$WIDTH" --quality "$QUALITY" -o "$OUT" "$WORK"/sel/*.png
ls -l "$OUT"
