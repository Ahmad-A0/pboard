#!/usr/bin/env bash
# Build whisper.cpp (MIT) static libraries.
#   android (default): arm64-v8a with the devshell NDK, armv8.2-a+dotprod+fp16 (Cortex-A76/A55), into build-android
#   host:              native x86-64 build into build-host, plus tools/voice/work/host/whisper_bench
# Source: tools/voice/work/src/whisper.cpp (git clone --depth 1 --branch v1.9.5).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
W="$here/../work"
S="$W/src/whisper.cpp"
common=(-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF
        -DWHISPER_BUILD_SERVER=OFF -DGGML_OPENMP=OFF)
if [[ "${1:-android}" == host ]]; then
  cmake -S "$S" -B "$S/build-host" "${common[@]}" -DGGML_NATIVE=ON
  cmake --build "$S/build-host" -j"$(nproc)"
  mkdir -p "$W/host"
  g++ -O2 -std=c++17 -I"$S/include" -I"$S/ggml/include" -I"$here" "$here/whisper_bench.cc" \
    "$S/build-host/src/libwhisper.a" "$S"/build-host/ggml/src/libggml.a "$S"/build-host/ggml/src/libggml-cpu.a \
    "$S"/build-host/ggml/src/libggml-base.a -lpthread -o "$W/host/whisper_bench"
  ls -la "$W/host"
else
  : "${ANDROID_NDK_HOME:?run inside the devshell}"
  cmake -S "$S" -B "$S/build-android" "${common[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
  cmake --build "$S/build-android" -j"$(nproc)"
  CXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++"
  mkdir -p "$W/android/bin"
  "$CXX" -O2 -std=c++17 -I"$S/include" -I"$S/ggml/include" -I"$here" "$here/whisper_bench.cc" \
    "$S/build-android/src/libwhisper.a" "$S"/build-android/ggml/src/libggml.a "$S"/build-android/ggml/src/libggml-cpu.a \
    "$S"/build-android/ggml/src/libggml-base.a -static-libstdc++ -o "$W/android/bin/whisper_bench"
  ls -la "$W/android/bin"
fi
