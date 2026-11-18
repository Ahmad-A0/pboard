"""Registry of the speech engines/models compared in docs/voice-eval.md.

Each entry says how to run the model (kind + files, relative to tools/voice/work/models), whether it streams, and
its licence. Used by run_host.py (WER on the host) and phone.py (on-device speed); the same model files are used on
both sides.

kinds:
  sherpa-online   streaming transducer via sherpa-onnx OnlineRecognizer (encoder/decoder/joiner/tokens)
  sherpa-offline  sherpa-onnx OfflineRecognizer; sub-kind in "family": transducer | nemo_ctc | moonshine_v2 | canary
  moonshine       Moonshine Voice streaming (ORT models, own C++ runtime); "arch" = MOONSHINE_MODEL_ARCH_*
  whisper         whisper.cpp ggml model; "actx" = 0 (full 30 s window) or "dyn" (window sized to the utterance)
  vosk            Vosk/Kaldi online decoder
"""
from pathlib import Path

W = Path(__file__).resolve().parent / "work"
M = W / "models"
MS = "moonshine/download.moonshine.ai/model"


def _zip(d, tag, int8_dec=False):
    """icefall streaming zipformer export: int8 encoder/joiner, fp32 decoder (sherpa-onnx's recommended mix)."""
    return dict(encoder=f"{d}/encoder-{tag}.int8.onnx", decoder=f"{d}/decoder-{tag}{'.int8' if int8_dec else ''}.onnx",
                joiner=f"{d}/joiner-{tag}.int8.onnx", tokens=f"{d}/tokens.txt")


def _nemo(d, model_type=None):
    f = dict(encoder=f"{d}/encoder.int8.onnx", decoder=f"{d}/decoder.int8.onnx", joiner=f"{d}/joiner.int8.onnx",
             tokens=f"{d}/tokens.txt")
    if model_type:
        f["model_type"] = model_type
    return f


ENGINES = {
    # ---- streaming transducers (sherpa-onnx) ----
    "zipformer-20M": dict(kind="sherpa-online", streaming=True, lic="Apache-2.0", pc=False, params="20M",
                          files=_zip("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17", "epoch-99-avg-1")),
    "zipformer-0621": dict(kind="sherpa-online", streaming=True, lic="Apache-2.0", pc=False, params="70M",
                           files=_zip("sherpa-onnx-streaming-zipformer-en-2023-06-21", "epoch-99-avg-1")),
    "zipformer-0626": dict(kind="sherpa-online", streaming=True, lic="Apache-2.0", pc=False, params="66M",
                           files=_zip("sherpa-onnx-streaming-zipformer-en-2023-06-26",
                                      "epoch-99-avg-1-chunk-16-left-128")),
    "kroko-en": dict(kind="sherpa-online", streaming=True, lic="CC-BY-SA (version unstated; unclear)", pc=False,
                     params="~70M", files=dict(encoder="sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/encoder.onnx",
                                               decoder="sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/decoder.onnx",
                                               joiner="sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/joiner.onnx",
                                               tokens="sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/tokens.txt")),
    "xasr-160-punct": dict(kind="sherpa-online", streaming=True, lic="Apache-2.0", pc=True, params="148M",
                           files="glob:sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"),
    "xasr-480-punct": dict(kind="sherpa-online", streaming=True, lic="Apache-2.0", pc=True, params="148M",
                           files="glob:sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"),
    # the shipped voice pack's models in ORT format (tools/voice/to_ort.sh): same model, no graph optimisation at load
    "xasr-480-punct-ort": dict(kind="sherpa-online", streaming=True, lic="Apache-2.0", pc=True, params="148M",
                               files=dict(encoder="../ort/xasr/encoder.int8.ort", decoder="../ort/xasr/decoder.ort",
                                          joiner="../ort/xasr/joiner.int8.ort",
                                          tokens="../voicepack/assets/voice/tokens.txt")),
    "fastconf-80ms": dict(kind="sherpa-online", streaming=True, lic="NGC Terms of Use (not open)", pc=False,
                          params="114M", files=_nemo("sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8")),
    "fastconf-480ms": dict(kind="sherpa-online", streaming=True, lic="NGC Terms of Use (not open)", pc=False,
                           params="114M", files=_nemo("sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8")),
    "fastconf-1040ms": dict(kind="sherpa-online", streaming=True, lic="NGC Terms of Use (not open)", pc=False,
                            params="114M",
                            files=_nemo("sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8")),
    "nemotron-160ms": dict(kind="sherpa-online", streaming=True, lic="NVIDIA Open Model License", pc=True,
                           params="600M", files=_nemo("sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25")),
    "nemotron-560ms": dict(kind="sherpa-online", streaming=True, lic="NVIDIA Open Model License", pc=True,
                           params="600M", files=_nemo("sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25")),
    "nemotron35-160ms": dict(kind="sherpa-online", streaming=True, lic="OpenMDW-1.1", pc=True, params="600M", lang="en-US",
                             files=_nemo("sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11")),
    "nemotron35-560ms": dict(kind="sherpa-online", streaming=True, lic="OpenMDW-1.1", pc=True, params="600M", lang="en-US",
                             files=_nemo("sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11")),
    "parakeet-unified-560ms": dict(kind="sherpa-online", streaming=True, lic="NVIDIA Open Model License", pc=True,
                                   params="600M",
                                   files=_nemo("sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-560ms")),
    # ---- Moonshine streaming (own runtime) ----
    "moonshine-s-tiny": dict(kind="moonshine", streaming=True, lic="MIT", pc=True, params="34M",
                             files=dict(dir=f"{MS}/tiny-streaming-en/quantized_26_11_02"), arch=2),
    "moonshine-s-small": dict(kind="moonshine", streaming=True, lic="MIT", pc=True, params="123M",
                              files=dict(dir=f"{MS}/small-streaming-en/quantized_26_11_02"), arch=4),
    "moonshine-s-medium": dict(kind="moonshine", streaming=True, lic="MIT", pc=True, params="245M",
                               files=dict(dir=f"{MS}/medium-streaming-en/quantized_26_11_02"), arch=5),
    # ---- Vosk ----
    "vosk-small": dict(kind="vosk", streaming=True, lic="Apache-2.0", pc=False, params="(Kaldi)",
                       files=dict(dir="vosk-model-small-en-us-0.15")),
    "vosk-lgraph": dict(kind="vosk", streaming=True, lic="Apache-2.0", pc=False, params="(Kaldi)",
                        files=dict(dir="vosk-model-en-us-0.22-lgraph")),
    # ---- offline ----
    "moonshine-tiny": dict(kind="sherpa-offline", family="moonshine_v2", streaming=False, lic="MIT", pc=True,
                           params="27M", files=dict(encoder="sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/encoder_model.ort",
                                                    merged_decoder="sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/decoder_model_merged.ort",
                                                    tokens="sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/tokens.txt")),
    "moonshine-base": dict(kind="sherpa-offline", family="moonshine_v2", streaming=False, lic="MIT", pc=True,
                           params="61M", files=dict(encoder="sherpa-onnx-moonshine-base-en-quantized-2026-02-27/encoder_model.ort",
                                                    merged_decoder="sherpa-onnx-moonshine-base-en-quantized-2026-02-27/decoder_model_merged.ort",
                                                    tokens="sherpa-onnx-moonshine-base-en-quantized-2026-02-27/tokens.txt")),
    "parakeet-110m-tdt": dict(kind="sherpa-offline", family="transducer", streaming=False, lic="CC-BY-4.0", pc=True,
                              params="114M",
                              files=_nemo("sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8", "nemo_transducer")),
    "parakeet-110m-tdt-ort": dict(kind="sherpa-offline", family="transducer", streaming=False, lic="CC-BY-4.0",
                                  pc=True, params="114M",
                                  files=dict(encoder="../ort/parakeet/encoder.int8.ort",
                                             decoder="../ort/parakeet/decoder.int8.ort",
                                             joiner="../ort/parakeet/joiner.int8.ort",
                                             tokens="sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8/tokens.txt",
                                             model_type="nemo_transducer")),
    "parakeet-110m-ctc": dict(kind="sherpa-offline", family="nemo_ctc", streaming=False, lic="CC-BY-4.0", pc=True,
                              params="114M",
                              files=dict(model="sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8/model.int8.onnx",
                                         tokens="sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8/tokens.txt")),
    "parakeet-0.6b-v2": dict(kind="sherpa-offline", family="transducer", streaming=False, lic="CC-BY-4.0", pc=True,
                             params="600M", files=_nemo("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", "nemo_transducer")),
    "canary-180m": dict(kind="sherpa-offline", family="canary", streaming=False, lic="CC-BY-4.0", pc=True,
                        params="182M", files="glob:sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"),
    "whisper-tiny.en-q5": dict(kind="whisper", streaming=False, lic="MIT", pc=True, params="39M",
                               files=dict(model="whisper.cpp/ggml-tiny.en-q5_1.bin"), actx="0"),
    "whisper-tiny.en-q8": dict(kind="whisper", streaming=False, lic="MIT", pc=True, params="39M",
                               files=dict(model="whisper.cpp/ggml-tiny.en-q8_0.bin"), actx="0"),
    "whisper-base.en-q5": dict(kind="whisper", streaming=False, lic="MIT", pc=True, params="74M",
                               files=dict(model="whisper.cpp/ggml-base.en-q5_1.bin"), actx="0"),
    "whisper-base.en-q8": dict(kind="whisper", streaming=False, lic="MIT", pc=True, params="74M",
                               files=dict(model="whisper.cpp/ggml-base.en-q8_0.bin"), actx="0"),
    "whisper-base.en-q8-dynctx": dict(kind="whisper", streaming=False, lic="MIT", pc=True, params="74M",
                                      files=dict(model="whisper.cpp/ggml-base.en-q8_0.bin"), actx="dyn"),
    "futo-acft-tiny.en-dynctx": dict(kind="whisper", streaming=False, lic="Apache-2.0 (FUTO ACFT)", pc=True,
                                     params="39M", files=dict(model="whisper.cpp/tiny_en_acft_q8_0.bin"), actx="dyn"),
    "futo-acft-base.en-dynctx": dict(kind="whisper", streaming=False, lic="Apache-2.0 (FUTO ACFT)", pc=True,
                                     params="74M", files=dict(model="whisper.cpp/base_en_acft_q8_0.bin"), actx="dyn"),
}


def resolve(name):
    """Return the engine dict with absolute file paths (globbing sherpa archives whose file names vary)."""
    e = dict(ENGINES[name])
    f = e["files"]
    if isinstance(f, str) and f.startswith("glob:"):
        d = M / f[5:]
        pick = lambda pat: str(sorted(d.glob(pat))[0])
        if e["kind"] == "sherpa-online":
            f = dict(encoder=pick("encoder*.onnx"), decoder=pick("decoder*.onnx"), joiner=pick("joiner*.onnx"),
                     tokens=pick("tokens.txt"))
        elif e.get("family") == "canary":
            f = dict(encoder=pick("encoder*.onnx"), decoder=pick("decoder*.onnx"), tokens=pick("tokens.txt"))
    else:
        f = {k: (v if k == "model_type" else str(M / v)) for k, v in f.items()}
    e["files"] = f
    return e


def size_bytes(name):
    """On-disk size of the files the engine actually loads (directory engines: whole directory)."""
    e = resolve(name)
    tot = 0
    for k, v in e["files"].items():
        if k == "model_type":
            continue
        p = Path(v)
        tot += sum(x.stat().st_size for x in p.rglob("*") if x.is_file()) if p.is_dir() else p.stat().st_size
    return tot
