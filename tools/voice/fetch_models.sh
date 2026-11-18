#!/usr/bin/env bash
# Download the candidate speech models evaluated in docs/voice-eval.md into tools/voice/work/models (gitignored).
#   tools/voice/fetch_models.sh [name-regex]
# Public downloads only (GitHub releases / Hugging Face). Licences are recorded in docs/voice-eval.md.
set -euo pipefail
cd "$(dirname "$0")"
M=work/models
mkdir -p "$M" work/dl
want="${1:-.}"
SH=https://github.com/k2-fsa/sherpa-onnx/releases/download
WC=https://huggingface.co/ggerganov/whisper.cpp/resolve/main
VK=https://alphacephei.com/vosk/models

sherpa=(
  # streaming transducers / CTC
  asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
  asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26
  asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-21
  asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06
  asr-models/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8
  asr-models/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8
  asr-models/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8
  asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25
  asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25
  asr-models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-560ms
  asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11
  asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11
  asr-models/sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05
  asr-models/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05
  # offline
  asr-models/sherpa-onnx-moonshine-tiny-en-int8
  asr-models/sherpa-onnx-moonshine-base-en-int8
  asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27
  asr-models/sherpa-onnx-moonshine-base-en-quantized-2026-02-27
  asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8
  asr-models/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8
  asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8
  asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8
  asr-models/sherpa-onnx-zipformer-en-libriheavy-20230830-small-punct-case
  # punctuation + casing post-processor for lower-case streaming output
  punctuation-models/sherpa-onnx-online-punct-en-2024-08-06
)
for s in "${sherpa[@]}"; do
  n="${s#*/}"
  [[ "$n" =~ $want ]] || continue
  [[ -d "$M/$n" ]] && { echo "have $n"; continue; }
  echo "get $n"
  curl -fsSL --retry 5 -o "work/dl/$n.tar.bz2" "$SH/$s.tar.bz2"
  tar -xjf "work/dl/$n.tar.bz2" -C "$M"
  rm -f "work/dl/$n.tar.bz2"
done

# whisper.cpp ggml models (MIT)
mkdir -p "$M/whisper.cpp"
for f in ggml-tiny.en.bin ggml-tiny.en-q5_1.bin ggml-tiny.en-q8_0.bin ggml-base.en.bin ggml-base.en-q5_1.bin ggml-base.en-q8_0.bin; do
  [[ "whisper.cpp/$f" =~ $want ]] || continue
  [[ -s "$M/whisper.cpp/$f" ]] && continue
  echo "get $f"; curl -fsSL --retry 5 -o "$M/whisper.cpp/$f" "$WC/$f"
done

# FUTO ACFT Whisper fine-tunes (weights Apache-2.0 per huggingface.co/futo-org/acft-whisper-*.en), ggml q8_0 as
# distributed by FUTO Voice Input. Evaluation only.
for f in tiny_en_acft_q8_0.bin base_en_acft_q8_0.bin; do
  [[ "whisper.cpp/$f" =~ $want ]] || continue
  [[ -s "$M/whisper.cpp/$f" ]] && continue
  echo "get $f"; curl -fsSL --retry 5 -o "$M/whisper.cpp/$f" "https://voiceinput.futo.org/VoiceInput/$f"
done

# Moonshine streaming (MIT), ORT-format, via the moonshine-voice package's downloader (download.moonshine.ai)
for a in 2 4 5; do  # tiny / small / medium streaming
  [[ "moonshine-$a" =~ $want ]] || continue
  LD_LIBRARY_PATH=/run/current-system/sw/share/nix-ld/lib work/venv/bin/moonshine-voice download --stt --language en \
    --model-arch "$a" --root "$M/moonshine" | tail -1
done

# Vosk (Apache-2.0)
for n in vosk-model-small-en-us-0.15 vosk-model-en-us-0.22-lgraph; do
  [[ "$n" =~ $want ]] || continue
  [[ -d "$M/$n" ]] && { echo "have $n"; continue; }
  echo "get $n"
  curl -fsSL --retry 5 -o "work/dl/$n.zip" "$VK/$n.zip"
  (cd "$M" && unzip -q "../dl/$n.zip")
  rm -f "work/dl/$n.zip"
done
du -sh "$M"/* | sort -k2
