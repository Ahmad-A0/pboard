#!/usr/bin/env bash
# Runs on a rented GPU box (PyTorch image): unpacks the training data and trains several model
# configurations side by side on the one GPU (small LSTMs leave most of it idle; data loading is CPU-bound).
#
#   upload: tools/swipe/{swipefeat,train,export}.py, tools/swipe/gpu_run.sh, tools/swipe/work/data.tar.zst
#   then:   bash gpu_run.sh <workers-per-run> "<name>:<train.py args>" ["<name>:<args>" ...]
# Each run writes runs/<name>/{log.txt,best.pt,ckpt.pt,DONE}; rerunning resumes from ckpt.pt.
set -euo pipefail
cd "$(dirname "$0")"
W="$1"; shift
mkdir -p work runs
if [ ! -f work/train.npz ]; then
  (cd work && zstd -dc ../data.tar.zst | tar -xf -)
fi
for spec in "$@"; do
  name="${spec%%:*}"; args="${spec#*:}"
  if [ -f "runs/$name/DONE" ]; then echo "$name done"; continue; fi
  # shellcheck disable=SC2086
  nohup python train.py "runs/$name" --data work --workers "$W" $args > "runs/$name.out" 2>&1 &
  echo "started $name (pid $!)"
done
