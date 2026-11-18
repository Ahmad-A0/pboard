#!/usr/bin/env bash
# Convert a pack's ONNX models to ORT format, pre-optimised for ARM (onnxruntime's own converter, ORT 1.28 to match
# the 1.28.2 runtime in sherpa-onnx 2.0.1). An .onnx file is re-optimised at every load (7-10 s for the 155 MB X-ASR
# encoder on the dev phone); the .ort file is loaded as is.
#   tools/voice/to_ort.sh DIR        (converts DIR/*.onnx to DIR/*.ort in place; the .onnx files are kept)
# Needs the venv (tools/voice/requirements.txt + onnxruntime==1.28.0 onnx).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dir="$1"
"$here/py" -m onnxruntime.tools.convert_onnx_models_to_ort --target_platform arm --optimization_style Fixed -- "$dir"
ls -la "$dir"
