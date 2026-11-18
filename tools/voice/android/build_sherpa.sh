#!/usr/bin/env bash
# Build sherpa-onnx (Apache-2.0) command-line tools + C API for arm64-v8a with the devshell NDK.
# Source: tools/voice/work/src/sherpa-onnx (git clone --depth 1 --branch v2.0.1). Output: .../build-android-arm64-v8a/install
# Run inside the devshell:  nix develop . --command tools/voice/android/build_sherpa.sh
set -euo pipefail
cd "$(dirname "$0")/../work/src/sherpa-onnx"
: "${ANDROID_NDK_HOME:?run inside the devshell}"
export ANDROID_NDK="$ANDROID_NDK_HOME"
export SHERPA_ONNX_ENABLE_BINARY=ON SHERPA_ONNX_ENABLE_C_API=ON SHERPA_ONNX_ENABLE_JNI=OFF
export SHERPA_ONNX_ENABLE_TTS=OFF SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ANDROID_PLATFORM=android-29
sed -i 's/^make -j4$/make -j"$(nproc)"/' build-android-arm64-v8a.sh
./build-android-arm64-v8a.sh
ls -la build-android-arm64-v8a/install/bin build-android-arm64-v8a/install/lib
