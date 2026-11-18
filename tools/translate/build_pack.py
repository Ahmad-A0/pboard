#!/usr/bin/env python3
"""Builds a pboard translation pack (format 1, docs/translate.md) from Firefox Translations models.

usage: build_pack.py <lang> <out_dir> [--models <dir>] [--registry <models.json>] [--directions en-xx,xx-en]
                     [--version N] [--repo <repo_root>]

  <lang>         language paired with English, e.g. es; the pack id and the pack APK suffix
  --models       directory holding <pair>/ model dirs (model.*.bin, vocab*.spm, lex.*.bin, plain or .gz); missing
                 files are downloaded from Mozilla's public model registry (Release models only)
  --registry     cached copy of https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json
  --directions   which models to include (default: both directions that exist)

Writes:
  <out_dir>/<lang>/translate/manifest.json + <pair>/<files> + LICENSE   (assets of the pack APK, module :translatepack)
  <out_dir>/pboard-translate-<lang>-v<version>.zip                       (pack file for Settings › Translate › Import)

The model files are copied unmodified (MPL-2.0, Mozilla); the pack carries the MPL-2.0 text and their origin.
"""
import argparse
import gzip
import hashlib
import json
import os
import shutil
import sys
import urllib.request
import zipfile

REGISTRY_URL = 'https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json'
NAMES = {'es': 'Spanish', 'fr': 'French', 'de': 'German', 'it': 'Italian', 'pt': 'Portuguese', 'nl': 'Dutch',
         'pl': 'Polish', 'ru': 'Russian', 'uk': 'Ukrainian', 'cs': 'Czech', 'sv': 'Swedish', 'da': 'Danish',
         'fi': 'Finnish', 'el': 'Greek', 'tr': 'Turkish', 'ja': 'Japanese', 'zh': 'Chinese', 'ko': 'Korean'}


def release_model(registry, pair):
    """The released Firefox model for a pair (prefer the Android release, then any release)."""
    cands = [m for m in registry['models'].get(pair, []) if (m.get('releaseStatus') or '').startswith('Release')]
    cands.sort(key=lambda m: ('Android' not in (m['releaseStatus'] or ''), m['architecture'] != 'base-memory'))
    return cands[0] if cands else None


def fetch(url, dest):
    tmp = dest + '.part'
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, 'wb') as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, dest)


def materialise(registry, base_url, pair, models_dir, out_pair_dir):
    """Copies (downloading if needed) the three files of a pair into out_pair_dir; returns (manifest entry, files)."""
    m = release_model(registry, pair)
    if m is None:
        return None
    os.makedirs(out_pair_dir, exist_ok=True)
    names = {}
    for kind, info in m['files'].items():
        gz_name = os.path.basename(info['path'])
        name = gz_name[:-3] if gz_name.endswith('.gz') else gz_name
        src_dir = os.path.join(models_dir, pair)
        plain, packed = os.path.join(src_dir, name), os.path.join(src_dir, gz_name)
        dest = os.path.join(out_pair_dir, name)
        if not os.path.exists(plain) and not os.path.exists(packed):
            os.makedirs(src_dir, exist_ok=True)
            print(f'downloading {pair} {gz_name}', file=sys.stderr)
            fetch(base_url + '/' + info['path'], packed)
        if os.path.exists(plain):
            shutil.copyfile(plain, dest)
        else:
            with gzip.open(packed, 'rb') as s, open(dest, 'wb') as d:
                shutil.copyfileobj(s, d)
        if 'uncompressedHash' in info:
            h = hashlib.sha256(open(dest, 'rb').read()).hexdigest()
            if h != info['uncompressedHash']:
                raise SystemExit(f'{dest}: sha256 {h} != registry {info["uncompressedHash"]}')
        names[kind] = name
    src, tgt = pair.split('-')
    entry = {'from': src, 'to': tgt, 'type': 'marian-ssru', 'model': f'{pair}/{names["model"]}'}
    if 'vocab' in names:
        entry['vocab'] = f'{pair}/{names["vocab"]}'
    else:
        entry['srcVocab'] = f'{pair}/{names["srcVocab"]}'
        entry['trgVocab'] = f'{pair}/{names["trgVocab"]}'
    if 'lexicalShortlist' in names:
        entry['shortlist'] = f'{pair}/{names["lexicalShortlist"]}'
    entry['size'] = sum(os.path.getsize(os.path.join(out_pair_dir, n)) for n in names.values())
    entry['upstream'] = {'architecture': m['architecture'], 'path': os.path.dirname(m['files']['model']['path']),
                         'flores200-plus': m.get('metrics', {}).get('flores200-plus', {})}
    return entry


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('lang')
    ap.add_argument('out')
    ap.add_argument('--models', default='models')
    ap.add_argument('--registry')
    ap.add_argument('--directions')
    ap.add_argument('--version', type=int, default=1)
    ap.add_argument('--repo', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
    a = ap.parse_args()
    if a.registry and os.path.exists(a.registry):
        registry = json.load(open(a.registry))
    else:
        registry = json.load(urllib.request.urlopen(REGISTRY_URL, timeout=60))
    base_url = registry['baseUrl']
    lang = a.lang
    pairs = a.directions.split(',') if a.directions else [f'en-{lang}', f'{lang}-en']
    root = os.path.join(a.out, lang, 'translate')
    if os.path.exists(root):
        shutil.rmtree(root)
    os.makedirs(root)
    models = []
    for pair in pairs:
        e = materialise(registry, base_url, pair, a.models, os.path.join(root, pair))
        if e is None:
            print(f'no released model for {pair}, skipped', file=sys.stderr)
            continue
        models.append(e)
    if not models:
        raise SystemExit('no models')
    shutil.copyfile(os.path.join(a.repo, 'third_party', 'mpl-2.0', 'LICENSE'), os.path.join(root, 'LICENSE'))
    manifest = {
        'format': 1, 'id': lang, 'version': a.version, 'name': NAMES.get(lang, lang),
        'license': 'MPL-2.0',
        'attribution': 'Firefox Translations models by Mozilla (https://github.com/mozilla/translations), '
                       'unmodified; source: ' + base_url,
        'models': models,
    }
    with open(os.path.join(root, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)
    zpath = os.path.join(a.out, f'pboard-translate-{lang}-v{a.version}.zip')
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for dirpath, _, files in os.walk(root):
            for fn in sorted(files):
                p = os.path.join(dirpath, fn)
                z.write(p, os.path.relpath(p, root))
    total = sum(m['size'] for m in models)
    print(f'{lang}: {len(models)} models, {total / 1e6:.1f} MB unpacked, zip {os.path.getsize(zpath) / 1e6:.1f} MB -> {zpath}')


if __name__ == '__main__':
    main()
