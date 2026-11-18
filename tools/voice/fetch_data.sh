#!/usr/bin/env bash
# Download the public English test sets used by docs/voice-eval.md into tools/voice/work/raw (gitignored).
# Evaluation only: nothing derived from these is shipped. Licences are listed in docs/voice-eval.md.
set -euo pipefail
cd "$(dirname "$0")"
RAW=work/raw
mkdir -p "$RAW/librispeech" "$RAW/oal"
HF=https://huggingface.co/datasets/hf-audio/open-asr-leaderboard/resolve/main

get() { # url dest
  [[ -s "$2" ]] && { echo "have $2"; return; }
  echo "get $1"
  curl -fL --retry 5 -C - -o "$2.part" "$1" && mv "$2.part" "$2"
}

# LibriSpeech test-clean / test-other (CC BY 4.0), OpenSLR 12
for s in test-clean test-other; do
  get "https://www.openslr.org/resources/12/$s.tar.gz" "$RAW/librispeech/$s.tar.gz"
done
# LibriSpeech-PC punctuated + cased references (CC BY 4.0), OpenSLR 145
get "https://www.openslr.org/resources/145/manifests.tar.gz" "$RAW/librispeech/pc-manifests.tar.gz"

# Open ASR Leaderboard test sets (ESB): Common Voice 9 test (CC0), AMI IHM (CC BY 4.0), Earnings-22 (CC BY-SA 4.0)
for i in 0 1 2; do get "$HF/common_voice/test-0000$i-of-00003.parquet" "$RAW/oal/cv-$i.parquet"; done
for i in 0 1 2; do get "$HF/ami_cleaned/test-0000$i-of-00003.parquet" "$RAW/oal/ami-$i.parquet"; done
for i in 0 1 2 3 4; do get "$HF/earnings22/test-0000$i-of-00005.parquet" "$RAW/oal/e22-$i.parquet"; done
sha256sum "$RAW"/librispeech/* "$RAW"/oal/* > "$RAW/SHA256SUMS"
echo done
