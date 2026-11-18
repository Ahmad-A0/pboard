#!/usr/bin/env python3
"""BLEU / chrF (sacrebleu defaults, detokenised) of hypothesis files against FLORES devtest references.

usage: score.py <flores_devtest_dir> <hyp_dir> [pair ...]
  <flores_devtest_dir>/<code>.txt  (eng_Latn.txt, spa_Latn.txt, ...; FLORES-200 devtest, CC BY-SA 4.0)
  <hyp_dir>/<src>-<tgt>.hyp        (one line per source line)
Prints one TSV row per pair: pair, BLEU, chrF, lines. Needs `sacrebleu` (pip).
"""
import os
import sys

import sacrebleu

CODES = {'en': 'eng_Latn', 'es': 'spa_Latn', 'fr': 'fra_Latn', 'de': 'deu_Latn', 'it': 'ita_Latn',
         'pt': 'por_Latn', 'nl': 'nld_Latn', 'pl': 'pol_Latn'}


def main():
    flores, hyp_dir = sys.argv[1], sys.argv[2]
    pairs = sys.argv[3:] or sorted(f[:-4] for f in os.listdir(hyp_dir) if f.endswith('.hyp'))
    print('pair\tbleu\tchrf\tlines')
    for pair in pairs:
        src, tgt = pair.split('-')
        refs = open(os.path.join(flores, CODES[tgt] + '.txt'), encoding='utf-8').read().splitlines()
        hyps = open(os.path.join(hyp_dir, pair + '.hyp'), encoding='utf-8').read().splitlines()
        n = min(len(refs), len(hyps))
        bleu = sacrebleu.corpus_bleu(hyps[:n], [refs[:n]]).score
        chrf = sacrebleu.corpus_chrf(hyps[:n], [refs[:n]]).score
        print(f'{pair}\t{bleu:.2f}\t{chrf:.2f}\t{n}')


if __name__ == '__main__':
    main()
