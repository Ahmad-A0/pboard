#!/usr/bin/env python3
"""Reference translations with bergamot-translator (the engine Firefox uses; PyPI package `bergamot`, Python 3.10)
for Firefox Translations model directories, used to check pboard's Kotlin engine against the original.

usage: ref_bergamot.py <models_root> <flores_devtest_dir> <out_dir> <pair> [pair ...]
  <models_root>/<pair>/ holds model.*.bin, vocab.*.spm (or srcvocab/trgvocab), lex.*.bin (uncompressed).
Writes <out_dir>/<pair>.hyp. Each line is translated on its own with Firefox's settings (greedy, int8shiftAlphaAll,
shortlist), so the outputs are comparable with `./gradlew :translate:evalMt -PevalArgs="flores ..."`.
"""
import glob
import os
import sys
import time

import bergamot

CODES = {'en': 'eng_Latn', 'es': 'spa_Latn', 'fr': 'fra_Latn', 'de': 'deu_Latn', 'it': 'ita_Latn',
         'pt': 'por_Latn', 'nl': 'nld_Latn', 'pl': 'pol_Latn'}

CONFIG = """models: [{model}]
vocabs: [{src}, {trg}]
shortlist: [{lex}, false]
beam-size: 1
normalize: 1.0
word-penalty: 0
max-length-break: 128
mini-batch-words: 1024
workspace: 128
max-length-factor: 2.0
skip-cost: true
cpu-threads: 0
quiet: true
quiet-translation: true
gemm-precision: int8shiftAlphaAll
alignment: soft
"""


def model_config(mdir):
    mdir = os.path.abspath(mdir)
    one = lambda pat: sorted(p for p in glob.glob(os.path.join(mdir, pat)) if not p.endswith('.gz'))[0]
    vocab = sorted(glob.glob(os.path.join(mdir, 'vocab.*.spm')))
    src = vocab[0] if vocab else one('srcvocab.*.spm')
    trg = vocab[0] if vocab else one('trgvocab.*.spm')
    cfg = os.path.join(mdir, 'bergamot.yml')
    with open(cfg, 'w') as f:
        f.write(CONFIG.format(model=one('model.*.bin'), src=src, trg=trg, lex=one('lex.*.bin')))
    return cfg


def main():
    root, flores, out_dir = sys.argv[1:4]
    os.makedirs(out_dir, exist_ok=True)
    service = bergamot.Service(bergamot.ServiceConfig(numWorkers=1, logLevel='off'))
    opts = bergamot.ResponseOptions(alignment=False, qualityScores=False, HTML=False)
    for pair in sys.argv[4:]:
        model = service.modelFromConfigPath(model_config(os.path.join(root, pair)))
        lines = open(os.path.join(flores, CODES[pair.split('-')[0]] + '.txt'), encoding='utf-8').read().splitlines()
        t0 = time.time()
        out = [service.translate(model, bergamot.VectorString([l]), opts)[0].target.text.replace('\n', ' ')
               for l in lines]
        dt = time.time() - t0
        with open(os.path.join(out_dir, pair + '.hyp'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(out) + '\n')
        print(f'{pair}: {len(lines)} lines, {1000 * dt / len(lines):.1f} ms/line', file=sys.stderr)


if __name__ == '__main__':
    main()
