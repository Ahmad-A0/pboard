#!/usr/bin/env bash
# Regenerate the synthetic benchmark sets for one geometry (deterministic: fixed seeds).
#   tools/bench/make_sets.sh tools/bench/geometry/gboard_pixel8.json
# Writes tools/bench/sets/<geometry name>/{tap_v1,swipe_v1,predict_v1}.jsonl (+ .meta.json).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GEO="$(realpath --relative-to="$ROOT" "${1:?geometry.json}")"
cd "$ROOT"
PY=(python3 -I)
NAME="$("${PY[@]}" -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$GEO")"
S="tools/bench/sentences/heldout_v1.txt"
D="tools/bench/sets/$NAME"
G="tools/bench/gen_traces.py"
"${PY[@]}" "$G" --geometry "$GEO" --sentences "$S" --section tap --kind tap --seed 1 --out "$D/tap_v1.jsonl"
"${PY[@]}" "$G" --geometry "$GEO" --sentences "$S" --section swipe --kind swipe --seed 2 --out "$D/swipe_v1.jsonl"
"${PY[@]}" "$G" --geometry "$GEO" --sentences "$S" --kind predict --per-sentence 2 --seed 3 --out "$D/predict_v1.jsonl"
