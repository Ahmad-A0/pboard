#!/usr/bin/env bash
# Regenerate the verdict subsets (deterministic): ~300 TSI words, ~200 VTAP words, 200 FUTO and
# 200 HWS swipes, stratified over all test participants -> tools/bench/sets/verdict/*.jsonl
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
mkdir -p tools/bench/sets/verdict
for spec in tap_tsi:300 tap_vtap:200 swipe_futo:200 swipe_hws:200; do
  n="${spec%%:*}"; c="${spec##*:}"
  python3 -I tools/bench/make_subset.py "tools/data/human/splits/v1/$n.test.jsonl.gz" "tools/bench/sets/verdict/$n.jsonl" "$c"
done
