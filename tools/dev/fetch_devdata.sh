#!/usr/bin/env bash
# Fetches the DEV-ONLY data used by the engine's stand-in language model and evaluation harness
# into tools/data/raw/ (gitignored). Nothing here ships in the app.
#  - hermitdave/FrequencyWords en_50k (OpenSubtitles 2018), CC-BY-SA 4.0
#  - Tatoeba English sentences, CC-BY 2.0 FR (https://tatoeba.org)
set -euo pipefail
dir="$(cd "$(dirname "$0")/.." && pwd)/data/raw"
mkdir -p "$dir"
cd "$dir"
[ -f en_50k.txt ] || curl -sSfL -o en_50k.txt \
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt
if [ ! -f eng_sentences.tsv ]; then
  curl -sSfL -o eng_sentences.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
  bunzip2 -f eng_sentences.tsv.bz2
fi
ls -l "$dir"
