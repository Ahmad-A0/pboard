#!/usr/bin/env bash
# Build the arm64 benchmark harnesses into tools/voice/work/android/bin (run inside the devshell).
# Prerequisites: build_sherpa.sh (sherpa-onnx + onnxruntime), whisper.cpp source, and for vosk the
# com.alphacephei:vosk-android:0.3.75 AAR from Maven Central unpacked in work/src/vosk-android (jni/arm64-v8a/libvosk.so)
# with vosk_api.h from github.com/alphacep/vosk-api/src.
#   nix develop . --command tools/voice/android/build_bench.sh [sherpa|whisper|vosk]...
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
W="$here/../work"
: "${ANDROID_NDK_HOME:?run inside the devshell}"
CXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++"
OUT="$W/android/bin"
mkdir -p "$OUT"
want=("$@"); [[ ${#want[@]} -gt 0 ]] || want=(sherpa whisper vosk)
for t in "${want[@]}"; do
  case "$t" in
    sherpa)
      S="$W/src/sherpa-onnx"; L="$S/build-android-arm64-v8a/install/lib"
      "$CXX" -O2 -std=c++17 -I"$S" "$here/sherpa_bench.cc" -L"$L" -lsherpa-onnx-c-api -lonnxruntime \
        -static-libstdc++ -Wl,-rpath,'$ORIGIN' -o "$OUT/sherpa_bench"
      cp -f "$L/libsherpa-onnx-c-api.so" "$L/libonnxruntime.so" "$OUT/" ;;
    whisper)
      "$here/build_whisper.sh" android ;;
    vosk)
      V="$W/src/vosk-android"
      "$CXX" -O2 -std=c++17 -I"$V" "$here/vosk_bench.cc" -L"$V/jni/arm64-v8a" -lvosk \
        -static-libstdc++ -Wl,-rpath,'$ORIGIN' -o "$OUT/vosk_bench"
      cp -f "$V/jni/arm64-v8a/libvosk.so" "$OUT/" ;;
    moonshine)
      # libmoonshine.so + its minimal onnxruntime from the ai.moonshine:moonshine-voice:0.1.6 AAR (Maven Central),
      # unpacked in work/src/moonshine-aar; header from github.com/moonshine-ai/moonshine core/moonshine-c-api.h
      # Own directory: its libonnxruntime.so is a different (operator-reduced) build from sherpa-onnx's.
      A="$W/src/moonshine-aar/jni/arm64-v8a"
      mkdir -p "$OUT/ms"
      "$CXX" -O2 -std=c++17 -I"$W/src/moonshine/core" "$here/moonshine_bench.cc" -L"$A" -lmoonshine \
        -static-libstdc++ -Wl,-rpath,'$ORIGIN' -o "$OUT/ms/moonshine_bench"
      cp -f "$A/libmoonshine.so" "$A/libonnxruntime.so" "$OUT/ms/" ;;
  esac
done
ls -la "$OUT"
