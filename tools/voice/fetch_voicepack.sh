#!/usr/bin/env bash
# Inputs of the voice pack APKs (docs/voice-eval.md), into tools/voice/work (gitignored):
#   work/voicepack/            module :voicepack, dev.pboard.pack.voice.en
#     sherpa-onnx-2.0.1.aar      sherpa-onnx 2.0.1 Android AAR (Apache-2.0; bundles ONNX Runtime 1.28.2, MIT)
#     assets/voice/              X-ASR 480 ms streaming Zipformer transducer with punctuation and casing, int8
#                                (Apache-2.0), converted to ORT format for ARM
#   work/voicepack-accurate/   module :voicepackaccurate, dev.pboard.pack.voice.en.accurate (optional)
#     assets/voice/accurate/     NVIDIA parakeet-tdt_ctc-110m, TDT head, int8 (CC-BY-4.0), ORT format: the final
#                                re-decode of each utterance
# Downloads are checked against pinned SHA-256 sums; the ORT conversion (tools/voice/to_ort.sh) needs the venv with
# onnxruntime==1.28.0 and onnx. Then:  ./gradlew :voicepack:assembleRelease :voicepackaccurate:assembleRelease
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p work/dl
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download

fetch_model() { # name sums-text -> echoes the directory holding the verified files
  local name=$1 sums=$2 src=work/models/$1
  if ! (cd "$src" 2>/dev/null && echo "$sums" | sha256sum -c --status); then
    curl -fsSL --retry 5 -o "work/dl/$name.tar.bz2" "$REL/asr-models/$name.tar.bz2"
    tar -xjf "work/dl/$name.tar.bz2" -C work/dl
    src=work/dl/$name
    (cd "$src" && echo "$sums" | sha256sum -c --quiet)
  fi
  echo "$src"
}

to_ort() { # src-dir dest-dir onnx-files...
  local src=$1 dest=$2; shift 2
  local tmp; tmp=$(mktemp -d work/dl/ort.XXXXXX)
  for f in "$@"; do cp "$src/$f" "$tmp/"; done
  ./to_ort.sh "$tmp" >/dev/null
  mkdir -p "$dest"
  for f in "$@"; do mv "$tmp/${f%.onnx}.ort" "$dest/"; done
  rm -rf "$tmp"
}

# --- main pack
OUT=work/voicepack
mkdir -p "$OUT/assets/voice"
AAR_SHA=a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7
if ! echo "$AAR_SHA  $OUT/sherpa-onnx-2.0.1.aar" | sha256sum -c --status 2>/dev/null; then
  curl -fsSL --retry 5 -o "$OUT/sherpa-onnx-2.0.1.aar" "$REL/v2.0.1/sherpa-onnx-2.0.1.aar"
  echo "$AAR_SHA  $OUT/sherpa-onnx-2.0.1.aar" | sha256sum -c --quiet
fi
if [[ ! -s "$OUT/assets/voice/encoder.int8.ort" ]]; then
  src=$(fetch_model sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05 \
"908596dcc137a73b95be908ca55e88caa1b3dbbe8027c171615f4b0609c5eb1e  encoder.int8.onnx
a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257  decoder.onnx
aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3  joiner.int8.onnx
b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c  tokens.txt")
  rm -f "$OUT"/assets/voice/*.onnx
  to_ort "$src" "$OUT/assets/voice" encoder.int8.onnx decoder.onnx joiner.int8.onnx
  cp "$src/tokens.txt" "$OUT/assets/voice/"
fi

# --- optional accurate pack
ACC=work/voicepack-accurate
if [[ ! -s "$ACC/assets/voice/accurate/encoder.int8.ort" ]]; then
  src=$(fetch_model sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8 \
"0f35509ddeb9b39002fb077d979a9fe74f06eb0bc4dd5c34f512f82e5111d657  encoder.int8.onnx
f7c331c5504c2e593c76ed22b728e3f554af6c4a383dde862e719ced08b1da19  decoder.int8.onnx
bf7dff69e9f2cdbe9943d70da358f38b361c115ba0105bae7e908e0d6ec782f6  joiner.int8.onnx
450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10  tokens.txt")
  to_ort "$src" "$ACC/assets/voice/accurate" encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx
  cp "$src/tokens.txt" "$ACC/assets/voice/accurate/"
fi
ls -la "$OUT/assets/voice" "$ACC/assets/voice/accurate"
echo "voice pack inputs ready"
