#!/usr/bin/env bash
# The WER/stability batch behind docs/voice-eval.md (host side). Skips outputs that already exist.
#   [PAUSE_LOAD=48] JOBS=24 tools/voice/run_all.sh [stab|tierA|tierA2|tierB|list=FILE]...   (FILE: lines 'engine set [--partials]')
# tierA / tierA2: every set for the main / secondary candidates; tierB: ls-other + cv for variants; stab: partial histories on the 'stab'
# subset for the streaming engines (plus parakeet-0.6b-v2 timestamps as the word-timing reference).
set -uo pipefail
cd "$(dirname "$0")"
JOBS="${JOBS:-24}"
# PAUSE_LOAD=N (emulator farm rule): SIGSTOP our decoders while the 1-min load is above N, resume below 0.8 N
if [[ -n "${PAUSE_LOAD:-}" ]]; then
  (
    paused=0
    while kill -0 $$ 2>/dev/null; do
      l=$(cut -d' ' -f1 /proc/loadavg)
      pids=$(pgrep -f "venv/bin/python -I run_host.py" -u "$(id -u)" | tr '\n' ' ')
      if awk -v l="$l" -v t="$PAUSE_LOAD" 'BEGIN{exit !(l>t)}'; then
        if [[ $paused == 0 && -n "${pids// }" ]]; then kill -STOP $pids; paused=1; echo "$(date +%T) paused (load $l)"; fi
      elif [[ $paused == 1 ]] && awk -v l="$l" -v t="$PAUSE_LOAD" 'BEGIN{exit !(l<0.8*t)}'; then
        kill -CONT $pids 2>/dev/null; paused=0; echo "$(date +%T) resumed (load $l)"
      fi
      [[ $paused == 1 && -n "${pids// }" ]] && kill -STOP $pids 2>/dev/null  # new workers started meanwhile
      sleep 15
    done
  ) &
fi
TIER_A=(zipformer-0626 xasr-160-punct xasr-480-punct moonshine-s-tiny moonshine-s-small moonshine-s-medium
        parakeet-110m-tdt vosk-small whisper-base.en-q8 futo-acft-base.en-dynctx nemotron35-560ms parakeet-0.6b-v2)
TIER_A2=(zipformer-20M kroko-en fastconf-480ms nemotron-560ms canary-180m moonshine-base whisper-tiny.en-q8
         futo-acft-tiny.en-dynctx)
TIER_B=(zipformer-0621 fastconf-80ms fastconf-1040ms whisper-base.en-q5 whisper-tiny.en-q5 whisper-base.en-q8-dynctx
        parakeet-110m-ctc moonshine-tiny)
TIER_B_CV=(nemotron-160ms nemotron35-160ms vosk-lgraph)
STAB=(zipformer-0626 zipformer-20M kroko-en xasr-160-punct xasr-480-punct fastconf-80ms fastconf-480ms
      fastconf-1040ms moonshine-s-tiny moonshine-s-small moonshine-s-medium vosk-small nemotron35-560ms nemotron-560ms
      nemotron-160ms nemotron35-160ms)

run() {  # engine set [--partials]
  local out="work/hyp/$2/$1${3:+.partials}.jsonl"
  [[ -s "$out" ]] && return
  echo "$(date +%T) start $1 $2 ${3:-}"
  # WER runs decode Moonshine whole-utterance (checked equal to its streaming final); --partials runs stream it
  local ns=1; [[ -n "${3:-}" ]] && ns=
  MOONSHINE_NONSTREAM=$ns nice -n 15 ./py run_host.py "$1" "$2" --jobs "$JOBS" ${3:-} || echo "FAILED $1 $2"
}
for tier in "${@:-tierA}"; do
  case "$tier" in
    list=*) while read -r e st p; do [[ -z "$e" || "$e" == \#* ]] || run "$e" "$st" $p; done < "${tier#list=}" ;;
    tierA) for e in "${TIER_A[@]}"; do for s in ls-clean ls-other cv ami e22; do run "$e" "$s"; done; done ;;
    tierA2) for e in "${TIER_A2[@]}"; do for s in ls-clean ls-other cv ami e22; do run "$e" "$s"; done; done ;;
    tierB) for e in "${TIER_B[@]}"; do for s in ls-other cv; do run "$e" "$s"; done; done
           for e in "${TIER_B_CV[@]}"; do run "$e" cv; done ;;
    stab) run parakeet-0.6b-v2 stab --partials
          for e in "${STAB[@]}"; do run "$e" stab --partials; done ;;
  esac
done
echo "$(date +%T) all done"
