# Third-party components of the voice pack (dev.pboard.pack.voice.en)

The voice pack (module `:voicepack`, a separate APK) carries voice typing's speech recogniser; pboard's own APK
contains none of it. Everything is permissively licensed. Licence texts are in this directory and are packaged into
the pack's assets. The engine was chosen in `docs/voice-eval.md`.

| component | version | licence | how it is in the pack |
|---|---|---|---|
| sherpa-onnx (k2-fsa), Android AAR | 2.0.1 | Apache-2.0 (`LICENSE-Apache-2.0.txt`) | `libsherpa-onnx-jni.so` and its Kotlin API classes (`com.k2fsa.sherpa.onnx`), unmodified, from github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1 (sha256 pinned in `tools/voice/fetch_voicepack.sh`) |
| ONNX Runtime (Microsoft), as built by sherpa-onnx | 1.28.2 | MIT (`LICENSE-onnxruntime-MIT.txt`) | `libonnxruntime.so` |
| kaldi-native-fbank, kaldi-decoder, simple-sentencepiece (k2-fsa) | as linked by sherpa-onnx 2.0.1 | Apache-2.0 | statically linked into `libsherpa-onnx-jni.so` |
| X-ASR streaming Zipformer transducer zh-en, 480 ms chunks, punctuation and casing, int8 (github.com/Gilgamesh-J/X-ASR; sherpa-onnx export `sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05`) | 2026-06-05 | Apache-2.0 | `assets/voice/{encoder.int8.onnx,decoder.onnx,joiner.int8.onnx,tokens.txt}`, unmodified (sha256 pinned) |
