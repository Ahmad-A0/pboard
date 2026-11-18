#!/usr/bin/env bash
# One-shot host setup for the WER runs (any Linux host): venv, test sets, models, host whisper.cpp harness.
#   tools/voice/setup_host.sh        (needs python3, cmake, g++, curl, unzip, ffmpeg or libsndfile mp3 support)
set -euo pipefail
cd "$(dirname "$0")"
[[ -x work/venv/bin/python ]] || { python3 -m venv work/venv; work/venv/bin/pip install -q -r requirements.txt; }
./fetch_data.sh
[[ -s work/data/e22/manifest.jsonl ]] || ./py prepare.py
[[ -s work/data/stab/manifest.jsonl ]] || ./py prepare.py stab
./fetch_models.sh
if [[ ! -x work/host/whisper_bench ]]; then
  mkdir -p work/src
  [[ -d work/src/whisper.cpp ]] || git clone -q --depth 1 --branch v1.9.5 https://github.com/ggml-org/whisper.cpp.git work/src/whisper.cpp
  android/build_whisper.sh host
fi
echo setup done
