#!/usr/bin/env bash
# Reproducible language-pack build:  tools/lm/build.sh [locale] [--eval] [--force]
#
#   locale   a key of tools/lm/langs.py (default "en": shared English base + en_GB / en_US overlays)
#   --eval   also run the Kotlin top-k / keystroke-savings / latency benchmark (:engine:lmEval)
#   --force  re-estimate the models even if cached intermediate results exist
#
# Stages (each is idempotent; intermediate data lives in tools/data/{raw,work}, both gitignored):
#   fetch -> extract -> lexicon -> encode -> build_lm (count, Kneser-Ney, mix, prune, quantise, export,
#   dialect overlays) -> evaluate (held-out perplexity)        Output: app/src/main/assets/lm/<locale>.lm(+.lmo)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
REPO="$PWD"

if [ -z "${IN_NIX_SHELL:-}" ]; then
  exec nix develop "$REPO" --command "$REPO/tools/lm/build.sh" "$@"
fi

LOCALE=en
EVAL=0
FORCE=()
for a in "$@"; do
  case "$a" in
    --eval) EVAL=1 ;;
    --force) FORCE=(--force) ;;
    -*) echo "unknown option $a" >&2; exit 2 ;;
    *) LOCALE="$a" ;;
  esac
done

run() { "$REPO/tools/lm/run.sh" "$@"; }
step() { echo; echo "=== $* ==="; }

step "fetch ($LOCALE)";    run fetch.py "$LOCALE"
step "extract";            run extract.py "$LOCALE"
step "lexicon";            run lexicon.py "$LOCALE"
step "encode";             run encode.py "$LOCALE"
step "estimate + export";  run build_lm.py "$LOCALE" "${FORCE[@]}"
step "test pack";          run make_test_pack.py
# gate: the Kotlin runtime's own checks on the freshly built packs (every word near its unigram at sentence start
# and mid-sentence, dialect spellings, coverage words); build_lm.py already ran tools/lm/lmcheck.py on the binary
step "pack checks (Kotlin)"
"$REPO/gradlew" -q -p "$REPO" :engine:test --tests 'dev.pboard.engine.lm.ShippedPackTest' \
  --tests 'dev.pboard.engine.lm.LanguagePackTest' --tests 'dev.pboard.engine.lm.NeuralLmTest'
step "perplexity";         run evaluate.py "$LOCALE"

if [ "$EVAL" = 1 ]; then
  step "Kotlin benchmark"
  ASSETS="$REPO/app/src/main/assets/lm"
  OVERLAYS=$(ls "$ASSETS"/*.lmo 2>/dev/null | tr '\n' ' ' || true)
  ./gradlew -q :engine:lmEval "-PlmArgs=$ASSETS/$LOCALE.lm $REPO/tools/data/work/$LOCALE/eval $OVERLAYS"
fi
ls -l "$REPO/app/src/main/assets/lm/"
