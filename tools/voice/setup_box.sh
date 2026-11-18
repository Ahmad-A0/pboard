#!/usr/bin/env bash
# Set up a rented CPU box (image python:3.12-bookworm) for the WER batch: system packages, venv, test sets
# (prepared in parallel), models, host whisper.cpp harness. Only public data and our own code go to the box.
#   scp voice.tgz box:/root/ && ssh box 'tar xzf voice.tgz && tools/voice/setup_box.sh'
set -euo pipefail
cd "$(dirname "$0")"
export DEBIAN_FRONTEND=noninteractive
apt-get -qq update && apt-get -qq install -y cmake ffmpeg unzip rsync >/dev/null
[[ -x work/venv/bin/python ]] || { python3 -m venv work/venv; work/venv/bin/pip install -q -r requirements.txt; }
./fetch_data.sh > work/fetch_data.log 2>&1
./fetch_models.sh > work/fetch_models.log 2>&1 &
mkdir -p work/data/ls && tar -xzf work/raw/librispeech/pc-manifests.tar.gz -C work/data/ls  # before the parallel runs
for s in ls-clean ls-other cv ami e22; do ./py prepare.py "$s" > "work/prepare_$s.log" 2>&1 & done
mkdir -p work/src
[[ -d work/src/whisper.cpp ]] || git clone -q --depth 1 --branch v1.9.5 https://github.com/ggml-org/whisper.cpp.git work/src/whisper.cpp
android/build_whisper.sh host > work/build_whisper.log 2>&1
wait
./py prepare.py stab
md5sum work/data/*/manifest.jsonl
echo vast setup done
