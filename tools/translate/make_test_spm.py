#!/usr/bin/env python3
"""Builds the tiny SentencePiece fixture translate/src/test/resources/spm/tiny.spm (unigram, nmt_nfkc, byte fallback,
like the Firefox Translations vocabularies) from our own sentences in tools/translate/bench_en.txt, and prints the
reference encodings used by SentencePieceTest. Needs `sentencepiece` (pip).

usage: make_test_spm.py <repo_root>
       make_test_spm.py --scrub <file.spm>     (only replace the machine paths recorded by the trainer)
"""
import json
import os
import sys



def _varint(b, i):
    v = shift = 0
    while True:
        c = b[i]; i += 1
        v |= (c & 0x7F) << shift; shift += 7
        if c < 0x80:
            return v, i


def _enc(v):
    out = bytearray()
    while True:
        out.append((v & 0x7F) | (0x80 if v > 0x7F else 0))
        v >>= 7
        if not v:
            return bytes(out)


def _fields(b):
    """Yields (field number, wire type, raw bytes of the whole field, payload) of a protobuf message."""
    i = 0
    while i < len(b):
        start = i
        key, i = _varint(b, i)
        wt = key & 7
        if wt == 0:
            _, j = _varint(b, i); payload = b[i:j]
        elif wt == 1:
            j = i + 8; payload = b[i:j]
        elif wt == 5:
            j = i + 4; payload = b[i:j]
        elif wt == 2:
            n, i = _varint(b, i); j = i + n; payload = b[i:j]
        else:
            raise ValueError('wire type %d' % wt)
        yield key >> 3, wt, b[start:j], payload
        i = j


def scrub_paths(path):
    """The trainer records its absolute input path and model prefix in ModelProto.trainer_spec (field 2: input = 1,
    model_prefix = 2). Replace them with repo-relative names so the fixture carries no machine paths. Idempotent; the
    pieces and normaliser are untouched."""
    names = {1: b'tools/translate/bench_en.txt', 2: b'tiny'}
    data = open(path, 'rb').read()
    out = bytearray()
    for num, wt, raw, payload in _fields(data):
        if num == 2 and wt == 2:
            spec = bytearray()
            for n2, wt2, raw2, _ in _fields(payload):
                spec += bytes([(n2 << 3) | 2]) + _enc(len(names[n2])) + names[n2] if n2 in names and wt2 == 2 else raw2
            raw = bytes([(2 << 3) | 2]) + _enc(len(spec)) + bytes(spec)
        out += raw
    open(path, 'wb').write(bytes(out))


if len(sys.argv) == 3 and sys.argv[1] == '--scrub':   # make_test_spm.py --scrub <file.spm>: only rewrite the paths
    scrub_paths(sys.argv[2])
    sys.exit(0)
root = sys.argv[1]
import sentencepiece as spm  # noqa: E402

out = os.path.join(root, 'translate/src/test/resources/spm/tiny')
os.makedirs(os.path.dirname(out), exist_ok=True)
spm.SentencePieceTrainer.train(
    input=os.path.join(root, 'tools/translate/bench_en.txt'), model_prefix=out, vocab_size=420, model_type='unigram',
    normalization_rule_name='nmt_nfkc', byte_fallback=True, character_coverage=1.0, unk_id=1, eos_id=0, bos_id=-1,
    pad_id=-1, user_defined_symbols=['__source__'], minloglevel=2)
os.rename(out + '.model', out + '.spm')
scrub_paths(out + '.spm')
os.remove(out + '.vocab')
sp = spm.SentencePieceProcessor(model_file=out + '.spm')
for c in ["Hello, how are you?", "  Ｈｅｌｌｏ  wörld…  ½ ﬁ", "Tab\there nbsp and​zero-width",
          "Emoji 😀 and 中文", "á and Å", "__source__ x", "   ", "line1\nline2"]:
    print('        %s to intArrayOf(%s),' % (json.dumps(c), ', '.join(map(str, sp.encode(c)))))
