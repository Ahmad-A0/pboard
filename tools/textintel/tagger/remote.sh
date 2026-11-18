#!/bin/bash
# Runs on the rented GPU instance, inside /root/gec (code + data/ uploaded there).
#   remote.sh setup             install transformers, fetch the ELECTRA-small encoder (Apache-2.0) from Hugging Face
#   remote.sh bench <tokens>    300 training steps at this token budget, GPU utilisation sampled alongside
#   remote.sh train <args...>   the real run in the background (nohup), log in run/stdout.log
set -euo pipefail
cd /root/gec
case "$1" in
  setup)
    pip install -q transformers==4.57.1 2>&1 | tail -2
    # the encoder (google/electra-small-discriminator, Apache-2.0) is uploaded as electra/ in safetensors form:
    # the image's torch 2.5 may not torch.load the original .bin (transformers refuses it, CVE-2025-32434)
    nproc; nvidia-smi --query-gpu=name,memory.total --format=csv,noheader
    ;;
  bench)
    nvidia-smi --query-gpu=utilization.gpu,memory.used --format=csv,noheader -l 2 > "bench_util_$2.txt" &
    SMI=$!
    python train.py --data data --encoder electra --out "bench_$2" --bench-steps 300 --max-tokens "$2" --workers 6 \
      > "bench_$2.out" 2>&1 || tail -5 "bench_$2.out"
    grep -E '"event": "(start|train|done)"' "bench_$2.out" | tail -4
    kill $SMI
    echo "gpu util samples (last 60s):"; tail -30 "bench_util_$2.txt" | awk -F, '{u+=$1; n++} END {printf "mean util %.0f%%\n", u/n}'
    ;;
  train)
    shift
    nohup python train.py --data data --encoder electra --out run "$@" > run_stdout.log 2>&1 &
    echo "started pid $!"
    ;;
esac
