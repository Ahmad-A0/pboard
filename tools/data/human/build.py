#!/usr/bin/env python3
"""Build the versioned human evaluation splits (docs/eval-data.md) from the downloads in tools/data/raw/.

Usage (repo root; downloads are untrusted, so isolated mode):
  python3 -I tools/data/human/fetch.py
  python3 -I tools/data/human/build.py [--geometry tools/bench/geometry/gboard_pixel8.json]

Writes
  tools/data/human/splits/v1/*.jsonl.gz   committed: permissively licensed sets (MIT / CC BY / CC BY-SA)
  tools/data/human/out/v1/*               gitignored: full-size swipe sets and licence-unclear EnronMobile sets
  tools/data/human/splits/v1/manifest.json  counts, participants, sha256 of every file (committed and local)
Everything is deterministic: rebuilding from the pinned downloads reproduces the manifest hashes.
Held-out ('test') files must never be used for tuning; they are split by participant, never by sample.
"""
import argparse
import collections
import gzip
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
import src_futo
import src_hws
import src_text
import src_tsi
import src_vertanen
import fetch
from common import OUT, RAW, REPO, SPLITS, VERSION, load_target, sha256_file, stable_hash

CORE_SWIPES = 3000          # committed swipe subset per split per source
CORE_PER_PARTICIPANT = 6    # cap per session/user in the committed subset (participant diversity)

LICENCES = {
    "futo": "MIT (swipe.futo.org, FUTO)",
    "hws": "MIT (How We Swipe, Leiva et al. 2021, OSF sj67f)",
    "tsi": "CC BY 4.0 (Google TSI, Lertvittayakumjorn et al. UIST 2024)",
    "vtap": "CC BY 4.0 (Vertanen & Kristensson IUI 2023, OSF 5xwng)",
    "wikipedia": "CC BY-SA 4.0 (Wikipedia:Lists of common misspellings/For machines)",
    "enron": "no explicit licence (EnronMobile, Vertanen & Kristensson 2011) - local use only",
    "enronmobile": "no explicit licence (EnronMobile, Vertanen & Kristensson 2011) - local use only",
}


def gz_write(path, items):
    """Deterministic gzip JSONL (mtime 0, no filename) so hashes are reproducible."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    n = 0
    with open(path, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as gz:
            w = io.TextIOWrapper(gz, encoding="utf-8")
            for it in items:
                w.write(json.dumps(it, separators=(",", ":"), ensure_ascii=False) + "\n")
                n += 1
            w.flush()
    return n


def describe(path, items, committed, sources, note=""):
    parts = collections.Counter(it.get("participant", "") for it in items)
    return {"file": os.path.relpath(path, REPO), "items": len(items), "participants": len(parts),
            "committed": committed, "sha256": sha256_file(path),
            "sources": {s: LICENCES[s] for s in sorted(sources)}, "note": note}


def core_subset(items, n, cap):
    ranked = sorted(items, key=lambda it: stable_hash("core", VERSION, it["id"]))
    per, out = collections.Counter(), []
    for it in ranked:
        if per[it["participant"]] < cap:
            per[it["participant"]] += 1
            out.append(it)
            if len(out) >= n:
                break
    return sorted(out, key=lambda it: it["id"])


def literal_items(traces):
    """Text-level view of tap traces: the letters actually touched (decoded on the participant's own layout)."""
    for it in traces:
        typed = it["literal"][:-1]  # the last tap is the word terminator (space/period), reported separately
        if " " in typed or "." in typed or not typed:
            continue  # a mid-word tap landed on space/period: not a single-token text item
        term = it["literal"][-1]
        yield {"context": it["context"], "typed": typed, "target": it["target"], "src": it["src"],
               "error": typed != it["target"].lower(), "participant": it["participant"], "split": it["split"],
               "terminatorMissed": it.get("terminator") != "synthetic" and term not in " .",
               "id": it["id"]}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--geometry", default=None)
    ap.add_argument("--skip-local", action="store_true", help="skip licence-unclear local-only sets")
    a = ap.parse_args()
    dst = load_target(a.geometry)
    sdir = os.path.join(SPLITS, VERSION)
    odir = os.path.join(OUT, VERSION)
    files, stats = [], {}

    def emit(name, items, committed, sources, note=""):
        path = os.path.join(sdir if committed else odir, name + ".jsonl.gz")
        gz_write(path, items)
        files.append(describe(path, items, committed, sources, note))
        print("%-46s %6d items" % (os.path.relpath(path, REPO), len(items)), file=sys.stderr)

    # ---- taps
    taps = {}
    for mod in (src_tsi, src_vertanen):
        items = list(mod.convert(dst))
        stats[mod.SRC] = mod.convert.stats
        taps[mod.SRC] = items
        for sp in ("tune", "test"):
            emit("tap_%s.%s" % (mod.SRC, sp), [it for it in items if it["split"] == sp], True, [mod.SRC])

    # ---- swipes
    for mod, name in ((src_futo, "futo"), (src_hws, "hws")):
        by_split = {"tune": [], "test": []}
        if mod is src_futo:
            for sp in by_split:
                by_split[sp] = list(mod.convert(dst, sp))
                stats["futo_" + sp] = mod.convert.stats
        else:
            for it in mod.convert(dst):
                by_split[it["split"]].append(it)
            stats["hws"] = mod.convert.stats
        for sp, items in by_split.items():
            emit("swipe_%s_full.%s" % (name, sp), items, False, [name], "full split, rebuild locally")
            emit("swipe_%s.%s" % (name, sp), core_subset(items, CORE_SWIPES, CORE_PER_PARTICIPANT), True, [name],
                 "core subset: %d swipes, <= %d per participant" % (CORE_SWIPES, CORE_PER_PARTICIPANT))
        del by_split

    # ---- text-level autocorrect
    wiki = src_text.wikipedia()
    for sp in ("tune", "test"):
        lit = [x for src in ("tsi", "vtap") for x in literal_items(taps[src]) if x["split"] == sp]
        emit("text_typos.%s" % sp, lit, True, ["tsi", "vtap"],
             "letters actually touched, decoded on the participant's own keyboard (errors and clean words, with context)")
        emit("text_wikipedia.%s" % sp, [x for x in wiki if x["split"] == sp], True, ["wikipedia"],
             "word-level misspellings, no context; split by correct word; CC BY-SA file")
    if not a.skip_local:
        et = src_text.enron_typos()
        sents = src_text.enron_sentences()
        for sp in ("tune", "test"):
            emit("text_enron_typos.%s" % sp, [x for x in et if x["split"] == sp], False, ["enron"])
            ss = [x for x in sents if x["split"] == sp]
            emit("sentences_enronmobile.%s" % sp, ss, False, ["enronmobile"], "split by sender")
            txt = os.path.join(odir, "sentences_enronmobile.%s.txt" % sp)
            with open(txt, "w", encoding="utf-8") as f:
                f.write("# EnronMobile mobile_orig sentences, %s split (by sender), pboard eval-data %s\n" % (sp, VERSION))
                f.write("# --- section: %s\n" % sp)
                for x in ss:
                    f.write(x["text"] + "\n")

    inputs = {}
    for name, entries in fetch.SOURCES.items():
        for rel, url, _ in entries:
            p = os.path.join(RAW, name, rel)
            if os.path.exists(p) and not rel.endswith((".pdf", "README.md", "LICENSE", "readme.txt")):
                inputs[name + "/" + rel] = {"url": url, "sha256": sha256_file(p)}
    manifest = {
        "format": "pboard-human-eval/1", "version": VERSION, "inputs": inputs,
        "traceFormat": "JSONL {context, target, kind: tap|swipe, points: [[x, y, tMs]...], ...annotations}; "
                       "points are SCREEN px of the target geometry, t from the item's first touch",
        "geometry": dst.meta, "files": files, "converterStats": stats,
        "rule": "split by participant/session/sender; 'test' files are held out and must never be used for tuning",
    }
    with open(os.path.join(sdir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1, sort_keys=False)
        f.write("\n")


if __name__ == "__main__":
    main()
