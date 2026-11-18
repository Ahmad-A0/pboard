#!/usr/bin/env bash
# Phone session 4 (run under tools/phone/guard.sh --headless inside the devshell): the 0.6B "high-accuracy pack"
# candidates and canary (2 and 4 threads), then big-core-pinned paced runs of the primary candidates.
set -u
cd "$(dirname "$0")/../.."
python3 -I tools/voice/phone.py nemotron35-560ms --threads 2,4 --drop-models
python3 -I tools/voice/phone.py parakeet-0.6b-v2 canary-180m --threads 2,4 --modes rtf --drop-models
python3 -I tools/voice/phone.py xasr-480-punct xasr-160-punct kroko-en zipformer-0626 moonshine-s-tiny vosk-small \
  --threads 1 --modes paced --pin big
