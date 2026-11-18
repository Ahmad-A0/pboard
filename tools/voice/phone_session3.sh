#!/usr/bin/env bash
# Phone session 3 (run under tools/phone/guard.sh --headless inside the devshell): Moonshine single-threaded,
# Whisper family (offline, unpaced), x-asr with 2 threads.
set -u
cd "$(dirname "$0")/../.."
python3 -I tools/voice/phone.py moonshine-s-tiny moonshine-s-small --threads 1
python3 -I tools/voice/phone.py whisper-tiny.en-q8 whisper-base.en-q8 whisper-base.en-q5 whisper-base.en-q8-dynctx \
  futo-acft-tiny.en-dynctx futo-acft-base.en-dynctx --threads 2 --modes rtf
python3 -I tools/voice/phone.py xasr-160-punct --threads 2 --modes rtf
