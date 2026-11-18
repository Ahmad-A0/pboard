"""Add punctuation and casing to a lower/upper-case engine's hypotheses with the Edge-Punct-Casing CNN-BiLSTM
(Apache-2.0, 7.5 MB int8; sherpa-onnx-online-punct-en-2024-08-06), writing work/hyp/<set>/<engine>+punct.jsonl.

  tools/voice/py tools/voice/punct.py ENGINE [SET...]

This is the post-processor a lower-case streaming model would need for Gboard-style output; it runs on the final
text of each utterance (the model is also usable on partials).
"""
import json, sys, time
from pathlib import Path

import sherpa_onnx as so

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engines import M, W  # noqa: E402

D = M / "sherpa-onnx-online-punct-en-2024-08-06"


def main():
    engine, sets = sys.argv[1], sys.argv[2:] or ["ls-clean", "ls-other", "cv", "ami", "e22"]
    cfg = so.OnlinePunctuationConfig(model_config=so.OnlinePunctuationModelConfig(
        cnn_bilstm=str(D / "model.int8.onnx"), bpe_vocab=str(D / "bpe.vocab"), num_threads=1))
    p = so.OnlinePunctuation(cfg)
    for s in sets:
        src = W / "hyp" / s / f"{engine}.jsonl"
        if not src.exists():
            continue
        out, ms = [], 0.0
        for h in map(json.loads, open(src)):
            t0 = time.perf_counter()
            t = p.add_punctuation_with_case(h["text"].lower()) if h["text"].strip() else ""
            ms += (time.perf_counter() - t0) * 1e3
            out.append(dict(h, text=t, punct_ms=round((time.perf_counter() - t0) * 1e3, 2)))
        (W / "hyp" / s / f"{engine}+punct.jsonl").write_text("".join(json.dumps(o, ensure_ascii=False) + "\n" for o in out))
        print(f"{engine}+punct {s}: {len(out)} utts, {ms / max(1, len(out)):.1f} ms/utt")


if __name__ == "__main__":
    main()
