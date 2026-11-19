#!/usr/bin/env bash
# Record the README demo (docs/media/demo.gif): two glide-typed sentences and an emoji from the panel, typed without
# a break while the keyboard changes theme about every second.
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
# For the same reason the recording is made with the screen at a lower resolution (RENDER_WIDTH, same layout, set
# with `wm size` and `wm density` and reset afterwards); the GIF is 540 px wide anyway.
#
# The touches are recorded human typing: demo_trace.py takes the glide strokes from the human
# evaluation sets (tools/data/human/splits/v1, sources and licences in its ATTRIBUTION.txt), places them on the keys
# as located on a screenshot (tools/bench/measure_geometry.py) and joins them into one performance; the pauses
# between words, a few taps on single keys and the emoji taps are made up (see demo_trace.py). The bench injector
# plays the whole thing in one go, in real time, with the trace's timestamps.
#
# The themes: right before the first touch the injector arms the debug build's in-place restyling (broadcast
# dev.pboard.DEBUG, extra `themes`), which then runs on the keyboard's own timer, independent of the typing. (The
# cross-fade is an animator, so it is slowed with the system's animations and needs no scaling here.)
#
# Slow motion: a software-rendered emulator draws 15-30 uneven frames a second, which shows as a stuttering glide
# trail. So everything runs SLOW times slower while it is recorded - the injected touches, the theme timer, the
# system's animations (animator_duration_scale), the trail's fade and the long-press delay - and the recording is
# played SLOW times faster: true speed again, with SLOW times the frames to choose from (not more than 3: the decoder
# reads a much slower stroke differently). The text cursor is hidden (its
# blinking cannot be slowed). Nothing else is retimed: demo_retime.py only cuts the performance out of the recording,
# and prints how far each word's appearance in the sped-up recording is from its time in the trace. The picture is the text-field band stacked
# directly on the keyboard: the empty part of the test screen between them is left out (so is the top of the emoji
# panel, which is taller than the keyboard).
#
# The demo changes these settings and leaves them changed: theme, the enabled languages (one language only), and
# the key long-press delay, grammar check and the system's spell checker switch (back to their defaults: 300 ms, on, on).
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator to record}"
OUT="${1:-docs/media/demo.gif}"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
A=(adb -s "$ANDROID_SERIAL")
IME=dev.pboard/.ime.PboardIme

# Screen coordinates on the emoji panel: an emoji in the grid, and the ABC key.
EMOJI_XY="${EMOJI_XY:-81,1764}"
ABC_XY="${ABC_XY:-79,2212}"
# The performance (demo_trace.py steps): every word is glide-typed with a recorded stroke, twelve different people,
# from How We Swipe (hws/..., MIT) and swipe.futo.org (futo/..., MIT); the ids are in tools/data/human/splits/v1 or
# tools/media/demo_strokes.jsonl. "doesnt" is glided as its letters and comes out with the apostrophe. keys=__ : two
# spaces after a word make the full stop.
if [ -z "${STEPS:-}" ]; then
  STEPS="
    glide=futo/tune/988592
    glide=hws/42q3qldluslc37vad5i8oa27uo/keyboard_private_cayman_spouse/w0/1580131963532
    glide=futo/test/185730
    glide=futo/tune/1055394
    glide=futo/tune/870889
    glide=futo/tune/568119
    keys=__
    glide=hws/ftf2k2qq6jgl7m0as04o673nhf/she_has_absolutely_everything/w3/1583037664656
    glide=hws/tgao1rq52v714595mtr89veicf/ill_get_you_one/w2/1581892610200
    glide=futo/tune/1124152
    glide=hws/0069j4rbe1si9pjqgfhks8sprm/stays_performed_blood_equities/w0/1580017926727
    glide=futo/test/936651
    glide=futo/tune/153060
    keys=_ emoji=@EMOJI@"
fi
typed="This keyboard doesn't have internet access. Everything you type stays on device "   # then the emoji
EXPECT="${EXPECT:-$typed}"
# The theme typed in, and the themes it changes to at equal intervals: the first after THEME_FIRST_MS, the last
# (the first theme again: the GIF loops) THEME_LAST_MS before the end, while the emoji panel is still open, so that
# the keyboard comes back in it. Each change is a hard cut (THEME_FADE_MS > 0: a cross-fade). The order is a walk through colour
# space in small steps (keyboard backgrounds in OKLab): light, tinted lights, cyan, green, teal, darks, purple, pinks,
# sand, and back to light; every one shows the glide trail clearly.
THEME="${THEME:-default_light}"
THEMES="${THEMES:-gradient_light_6 color_cyan color_green color_teal color_material_dark default_dark gradient_dark_1 gradient_light_5 gradient_light_1 color_sand gradient_light_7 dynamic default_light}"
THEME_FIRST_MS="${THEME_FIRST_MS:-600}"
THEME_LAST_MS="${THEME_LAST_MS:-700}"
THEME_FADE_MS="${THEME_FADE_MS:-0}"
TRAIL_FADE_MS="${TRAIL_FADE_MS:-650}"   # the keyboard's own is 1000: consecutive strokes read cleaner
LANGUAGE="${LANGUAGE:-en-US:qwerty}"
BAND_TEXT="${BAND_TEXT:-132:284}"   # rows of the screen with the field's first two lines of text
WIDTH="${WIDTH:-540}"
RENDER_WIDTH="${RENDER_WIDTH:-720}"
SLOW="${SLOW:-3}"
FPS="${FPS:-30}"
QUALITY="${QUALITY:-90}"

pref() { "${A[@]}" shell am broadcast -n dev.pboard/.debug.PrefsReceiver "$@" >/dev/null; }
playground() { "${A[@]}" shell am broadcast -a dev.pboard.bench.PLAYGROUND -p dev.pboard --es cmd "$@"; }
field_text() { playground get | python3 -c 'import sys, re, json
m = re.search(r"data=\"(.*)\"\s*$", sys.stdin.read(), re.S)
print(json.loads(m.group(1))["text"] if m else "")'; }

"${A[@]}" shell wm size reset; "${A[@]}" shell wm density reset
[ "$("${A[@]}" shell wm size | tr -d '\r' | awk 'END {print $NF}')" = 1080x2400 ] || { echo "needs a 1080x2400 screen" >&2; exit 1; }
# Positions in this script are for 1080x2400; px scales them to the screen as recorded.
SW_PX=$RENDER_WIDTH; SH_PX=$((RENDER_WIDTH * 20 / 9))
px() { echo $(( ($1 * SW_PX + 540) / 1080 )); }
"${A[@]}" shell settings get secure default_input_method | grep -q "^$IME" || { echo "pboard is not the current keyboard" >&2; exit 1; }
"${A[@]}" shell pm path dev.pboard.bench >/dev/null || { echo "bench injector not installed" >&2; exit 1; }
command -v gifski >/dev/null || { echo "gifski not found" >&2; exit 1; }

echo "== prepare (work dir $WORK)"
pref --es enabled_languages "$LANGUAGE" --es theme "$THEME"
"${A[@]}" shell am start -n dev.pboard/.PlaygroundActivity >/dev/null 2>&1   # no -S: that would force-stop the keyboard
sleep 2
"${A[@]}" shell am broadcast -a dev.pboard.DEBUG --es themes , >/dev/null   # no restyling left over from a run
playground reset >/dev/null
sleep 2
"${A[@]}" exec-out screencap -p > "$WORK/keys.png"
python3 -I tools/bench/measure_geometry.py "$WORK/keys.png" "$WORK/geometry-1080.json" --name demo --ime "$IME" >/dev/null
# The same keyboard at the recording resolution.
python3 - "$WORK/geometry-1080.json" "$WORK/geometry.json" "$SW_PX" <<'PY'
import json, sys
f = int(sys.argv[3]) / 1080.0
def scale(v, k=None):
    if isinstance(v, dict):
        return {n: scale(x, n) for n, x in v.items()}
    if isinstance(v, list):
        return [scale(x) for x in v]
    return v * f if isinstance(v, (int, float)) and not isinstance(v, bool) and k not in ("density", "row") else v
json.dump(scale(json.load(open(sys.argv[1]))), open(sys.argv[2], "w"))
PY
restore() {
  "${A[@]}" shell wm size reset; "${A[@]}" shell wm density reset
  "${A[@]}" shell settings put global animator_duration_scale 1
  "${A[@]}" shell settings put secure spell_checker_enabled 1
  "${A[@]}" shell am broadcast -a dev.pboard.DEBUG --ei trailFadeMs 1000 >/dev/null
  "${A[@]}" shell am broadcast -a dev.pboard.DEBUG --es themes , >/dev/null
  pref --ei long_press_delay_ms 300 --ez grammar_check true
  playground reset --ez cursor true >/dev/null
}
trap restore EXIT
"${A[@]}" shell settings put global animator_duration_scale "$SLOW"
pref --ei long_press_delay_ms $((300 * SLOW)) --ez grammar_check false
"${A[@]}" shell settings put secure spell_checker_enabled 0   # no spelling or grammar marks under the demo text
"${A[@]}" shell wm size "${SW_PX}x${SH_PX}"
"${A[@]}" shell wm density $((420 * SW_PX / 1080))
sleep 5
playground reset --ez cursor false >/dev/null
sleep 2
"${A[@]}" shell am broadcast -a dev.pboard.DEBUG --ei trailFadeMs $((TRAIL_FADE_MS * SLOW)) >/dev/null
emoji="$(px "${EMOJI_XY%,*}"),$(px "${EMOJI_XY#*,}"),$(px "${ABC_XY%,*}"),$(px "${ABC_XY#*,}")"
# shellcheck disable=SC2086
python3 -I tools/media/demo_trace.py "$WORK/geometry.json" "$WORK/trace.jsonl" --slow "$SLOW" ${STEPS//@EMOJI@/$emoji}
read -r -a themes <<< "$THEMES"
read -r length every < <(python3 -c 'import json, sys
s = json.load(open(sys.argv[1])); n, first = int(sys.argv[2]), int(sys.argv[3])
length = s[-1]["up"] - s[0]["down"]
print(length, max(1, round((length - int(sys.argv[4]) - first) / max(1, n - 1))))' "$WORK/trace.jsonl.timing.json" "${#themes[@]}" "$THEME_FIRST_MS" "$THEME_LAST_MS")
echo "performance: $length ms; a theme change every $every ms"
echo "am broadcast -a dev.pboard.DEBUG --es themes $(IFS=,; echo "${themes[*]}") --ei themeEveryMs $((every * SLOW)) --ei themeDelayMs $((THEME_FIRST_MS * SLOW)) --ei themeFadeMs $THEME_FADE_MS" > "$WORK/arm.sh"
"${A[@]}" push "$WORK/trace.jsonl" /data/local/tmp/demo_trace.jsonl >/dev/null
"${A[@]}" push "$WORK/arm.sh" /data/local/tmp/demo_arm.sh >/dev/null

echo "== record"
"${A[@]}" shell "screenrecord --bit-rate 20000000 --time-limit 175 /sdcard/pboard_demo.mp4" &
REC=$!
sleep 1.5
"${A[@]}" shell am instrument -w -e mode replay -e trace /data/local/tmp/demo_trace.jsonl -e retries 0 -e settleMs $((1500 * SLOW)) -e stallSwipeMs 1000 \
  -e preShell "'sh /data/local/tmp/demo_arm.sh'" -e out demo.jsonl dev.pboard.bench/.Replay > "$WORK/instrument.txt"
grep -E "summary|error" "$WORK/instrument.txt" || true
"${A[@]}" shell pkill -INT screenrecord
wait "$REC" || true
sleep 1
"${A[@]}" pull /sdcard/pboard_demo.mp4 "$WORK/raw.mp4" >/dev/null
"${A[@]}" shell run-as dev.pboard.bench cat files/demo.jsonl > "$WORK/replay.jsonl"
"${A[@]}" shell rm -f /sdcard/pboard_demo.mp4 /data/local/tmp/demo_trace.jsonl /data/local/tmp/demo_arm.sh
got="$(field_text)"
echo "final text: $got"
python3 -c 'import json, sys
r = json.loads(open(sys.argv[1]).readline()); print("injection: largest stall %.0f ms of true time, %d attempt(s)" % (r["stallMs"] / float(sys.argv[2]), r["attempts"]))' "$WORK/replay.jsonl" "$SLOW"
[[ "$got" == "$EXPECT"?* ]] || { echo "expected '$EXPECT' + an emoji" >&2; exit 1; }

echo "== frames"
t0=$(px "${BAND_TEXT%:*}"); t1=$(px "${BAND_TEXT#*:}")
read -r kb0 kb1 < <(python3 -c 'import json, sys
g = json.load(open(sys.argv[1])); print(round(g["keyboard"]["y"]), round(g["rows"][-1]["bottom"]) + int(sys.argv[2]))' "$WORK/geometry.json" "$(px 29)")
STACK="[0:v]setpts=PTS/$SLOW,fps=$FPS,scale=$SW_PX:$SH_PX:flags=bicubic,split[a][b];[a]crop=iw:$((t1 - t0)):0:$t0[top];[b]crop=iw:$((kb1 - kb0)):0:$kb0[bot];[top][bot]vstack"
rm -rf "$WORK/frames" "$WORK/sel"
mkdir -p "$WORK/frames" "$WORK/sel"
ffmpeg -v error -y -i "$WORK/raw.mp4" -filter_complex "$STACK,scale=$WIDTH:-2:flags=lanczos" "$WORK/frames/%05d.png"
# Small RGB copies for the cut and the timing check.
SW=108; SH=$(( ((t1 - t0) + (kb1 - kb0)) * 108 / SW_PX ))
ffmpeg -v error -y -i "$WORK/raw.mp4" -filter_complex "$STACK,scale=$SW:$SH:flags=area" -f rawvideo -pix_fmt rgb24 "$WORK/small.rgb"
python3 -I tools/media/demo_retime.py "$WORK/small.rgb" "$SW" "$SH" --fps "$FPS" --field-rows "$(( (t1 - t0) * 108 / SW_PX ))" \
  --timing "$WORK/trace.jsonl.timing.json" > "$WORK/select.txt" 2> "$WORK/timing.txt"
cat "$WORK/timing.txt"
n=0
while read -r f; do
  n=$((n + 1))
  ln -sf "$WORK/frames/$(printf %05d "$f").png" "$WORK/sel/$(printf %05d "$n").png"
done < "$WORK/select.txt"

echo "== encode ($n frames, $FPS fps)"
mkdir -p "$(dirname "$OUT")"
gifski --quiet --fps "$FPS" --width "$WIDTH" --quality "$QUALITY" -o "$OUT" "$WORK"/sel/*.png
ls -l "$OUT"
