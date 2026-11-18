#!/usr/bin/env bash
# Phone session 5 (under tools/phone/guard.sh --headless in the devshell): first-load time of the voice packs' models
# as ONNX vs ORT format, streaming latency of the ORT X-ASR, and the parakeet final-pass decode time; then cleanup.
set -u
cd "$(dirname "$0")/../.."
python3 -I tools/voice/phone.py xasr-480-punct-ort --threads 1 --modes rtf,paced
python3 -I tools/voice/phone.py xasr-480-punct parakeet-110m-tdt-ort parakeet-110m-tdt --threads 1 --modes rtf
python3 -I tools/voice/phone.py --cleanup
