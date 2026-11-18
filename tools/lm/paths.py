"""Filesystem layout of the LM pipeline."""

import os

LM_DIR = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(LM_DIR)
REPO = os.path.dirname(TOOLS)
RAW = os.path.join(TOOLS, "data", "raw")          # downloads (gitignored), shared by all locales
WORK = os.path.join(TOOLS, "data", "work")        # intermediate artefacts (gitignored), per locale
ASSET_DIR = os.path.join(REPO, "app", "src", "main", "assets", "lm")
LM_DATA = os.path.join(LM_DIR, "data")            # committed curated lists (offensive words, misspellings)


def work(locale: str, *sub: str) -> str:
    d = os.path.join(WORK, locale, *sub)
    os.makedirs(d, exist_ok=True)
    return d


os.makedirs(RAW, exist_ok=True)
