#!/usr/bin/env python3
"""Download the human typing datasets adopted in docs/eval-data.md into tools/data/raw/<source>/.

Usage (from the repo root, isolated mode because downloads are untrusted):
  python3 -I tools/data/human/fetch.py [--only futo_swipe,google_tsi,...] [--force]

Every source gets its own subfolder. Files are fetched over HTTPS, their SHA-256 is printed and
compared with the pinned value (if any) so a silently changed upstream file is noticed. Nothing in
tools/data/raw/ is committed (gitignored); only this script and the derived, versioned splits are.
"""
import argparse
import hashlib
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.normpath(os.path.join(HERE, "..", "raw"))

HF_FUTO = "https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/"
GH_TSI = "https://raw." + "githubusercontent.com/google-research-datasets/tap-typing-with-touch-sensing-images/main/"

# name -> list of (relative path, url, sha256 or None). Pinned hashes are the files used for v1 splits.
SOURCES = {
    # swipe.futo.org (MIT). Publisher's session-disjoint splits: dev -> our tune, test -> our held-out.
    "futo_swipe": [
        ("LICENSE", HF_FUTO + "LICENSE", None),
        ("README.md", HF_FUTO + "README.md", None),
        ("swipe-5/layouts/qwerty.json", HF_FUTO + "swipe-5/layouts/qwerty.json",
         "1965ecd59c9e4bff89446bb56ff3a2d0070b16eeae4ce424ce08b06ed6864632"),
        ("dev.jsonl", HF_FUTO + "dev.jsonl", "8d9d34ec1c12f564d02dcb69cf132ba19d45b75632a205af90d3a81290e24972"),
        ("test.jsonl", HF_FUTO + "test.jsonl", "d4e42e34ced1cae925a5ec94672bdaf48b4532598a1e39d72884f9c7b588721e"),
    ],
    # Google Tap Typing with Touch Sensing Images (CC BY 4.0): Gboard build on Pixel 6 Pro, 16 participants.
    "google_tsi": [
        ("LICENSE", GH_TSI + "LICENSE", None),
        ("README.md", GH_TSI + "README.md", None),
        ("keyboard_data.json", GH_TSI + "keyboard_data.json", "642e95984ed8b04f5bb7c56bb6237c6654034c5ff0c15aef55a127c93530213d"),
        ("prompt_data.csv", GH_TSI + "prompt_data.csv", "c0992922ff3f85440c7f3eaf2537d82df75202a9882d20757e49859ae5d13ba6"),
        ("touch_data.csv", GH_TSI + "touch_data.csv", "f6aaa9c79596a5d61c5d2429d722db30013fd92d333d28863adc77e54ef4d8a3"),
    ],
    # Vertanen & Kristensson IUI 2023 "A Dataset of Noisy Typing on QWERTY Keyboards" (CC BY 4.0), OSF 5xwng.
    "vertanen_noisy": [
        ("vertanen_noisy_typing_iui2023.zip", "https://osf.io/download/7ev5z/",
         "76d9cc798b5694686333da8baec46ce025174068bbfef909a31fe01e67dcdf5d"),
        ("vertanen_noisy_typing_json.zip", "https://osf.io/download/sdb23/",
         "286468f58cc2d91acda2955035db15ec06eb309ae40dce1af16ebdb24e7fa821"),
        ("vertanen_paper_noisy_typing.pdf", "https://osf.io/download/2yegp/", None),
    ],
    # Leiva et al. MobileHCI 2021 "How We Swipe" (MIT), OSF sj67f.
    "howweswipe": [
        ("metadata.tsv", "https://osf.io/download/wy9q8/", None),
        ("swipelogs.zip", "https://osf.io/download/x4yjn/", "c7e6398d630906d664654d8f5a7ba2b1531882636fd110d8d6c05a723ca00874"),
        ("stats-words.tsv", "https://osf.io/download/vpc5a/", "0694c533cd181bc2c8948d8e0240490363e292909e1d6d6ea9ffb936663ea527"),
    ],
    # Vertanen & Kristensson MobileHCI 2011 EnronMobile (no explicit licence; local-only use, see docs).
    "enronmobile": [
        ("readme.txt", "https://www.keithv.com/software/enronmobile/readme.txt", None),
        ("enronmobile.zip", "https://www.keithv.com/software/enronmobile/enronmobile.zip",
         "bedd81e7002ee2d9b033ce1d31bbce79a6e3258a6a1c2557a8f7e0910922d72e"),
    ],
    # Wikipedia:Lists of common misspellings/For machines (CC BY-SA 4.0). Fetched live (the page changes), so
    # the hash of the copy used for v1 is recorded in splits/v1/manifest.json ("inputs") instead.
    "wikipedia_misspellings": [
        ("for_machines.wiki", "https://en.wikipedia.org/w/index.php?title=Wikipedia:Lists_of_common_misspellings/"
                              "For_machines&action=raw", None),
    ],
    # Not fetched: GitHub Typo Corpus (its only download URL, an S3 object, returned 404 on 2026-11-08) and the
    # Aalto Typing37k / Birkbeck corpora (no licence / access-controlled). See docs/eval-data.md.
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(name, force=False):
    for rel, url, pin in SOURCES[name]:
        dst = os.path.join(RAW, name, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if force or not os.path.exists(dst):
            tmp = dst + ".part"
            print("GET", url, file=sys.stderr)
            req = urllib.request.Request(url, headers={"User-Agent": "pboard-eval-data/1"})
            with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    f.write(b)
            os.replace(tmp, dst)
        h = sha256(dst)
        status = "ok" if pin in (None, h) else "MISMATCH (pinned %s)" % pin
        print("%s  %s/%s  %s" % (h, name, rel, status))
        fetch.mismatches += status != "ok"


fetch.mismatches = 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", default=",".join(SOURCES))
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    for name in a.only.split(","):
        fetch(name.strip(), a.force)
    if fetch.mismatches:
        sys.exit("%d file(s) differ from the v1 pins: the v1 splits cannot be reproduced from them" % fetch.mismatches)


if __name__ == "__main__":
    main()
