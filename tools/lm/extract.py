"""Extract normalised, tokenised sentences from every raw source of a locale.

Writes tools/data/work/<locale>/text/<source>.<split>.txt (one sentence per line, tokens separated by single
spaces, original case) and <source>.counts.tsv (train split only: token, mid-sentence count, sentence-initial
count).  Splits are deterministic hashes of conversation/document ids, so held-out conversations never leak
into training.

Usage: tools/lm/run.sh extract.py <locale> [source ...]
"""

import bz2
import collections
import csv
import glob
import gzip
import hashlib
import html
import io
import json
import multiprocessing as mp
import os
import random
import re
import sys
import tarfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import langs  # noqa: E402
from paths import RAW, work  # noqa: E402
from textnorm import TextNorm  # noqa: E402

MAX_SENT_TOKENS = 80
TN = None  # TextNorm of the locale being extracted (also set in pool workers)
LANG = None


def split_of(key: str, test_pct=3.0, dev_pct=2.0) -> str:
    h = int.from_bytes(hashlib.blake2b(key.encode(), digest_size=8).digest(), "little") % 100000 / 1000.0
    if h < test_pct:
        return "test"
    if h < test_pct + dev_pct:
        return "dev"
    return "train"


class Writer:
    def __init__(self, text_dir, name):
        self.name, self.dir = name, text_dir
        self.files = {s: open(os.path.join(text_dir, f"{name}.{s}.txt.part"), "w") for s in ("train", "dev", "test")}
        self.mid = collections.Counter()
        self.init = collections.Counter()
        self.ntok = collections.Counter()

    def add_text(self, split, text):
        for sent in TN.sentences(text):
            self.add_sentence(split, sent)

    def add_sentence(self, split, sent):
        if not sent or len(sent) > MAX_SENT_TOKENS:
            return
        self.files[split].write(" ".join(sent) + "\n")
        self.ntok[split] += len(sent)
        if split == "train":
            self.init[sent[0]] += 1
            self.mid.update(sent[1:])

    def add_lines(self, split, lines):
        f = self.files[split]
        for line in lines:
            f.write(line + "\n")
            self.ntok[split] += line.count(" ") + 1

    def close(self):
        for s, f in self.files.items():
            f.close()
            os.replace(f.name, f.name[: -len(".part")])
        with open(os.path.join(self.dir, f"{self.name}.counts.tsv"), "w") as f:
            for tok in set(self.mid) | set(self.init):
                f.write(f"{tok}\t{self.mid.get(tok, 0)}\t{self.init.get(tok, 0)}\n")
        print(f"[extract] {LANG.locale} {self.name}: " + ", ".join(f"{k}={v:,}" for k, v in sorted(self.ntok.items())),
              flush=True)


# ---------------------------------------------------------------- multilingual conversational sources

# Tatoeba overuses a handful of names (Tom, Mary, Sami, Layla, ...): replace them with common first names so
# the model does not learn that "Tom" is the likeliest sentence opener.
MALE = ("James John Robert Michael David William Richard Joseph Thomas Chris Daniel Matthew Anthony Mark "
        "Steven Paul Andrew Josh Kevin Brian George Tim Ryan Jason Eric Jacob Nick Jonathan Justin Ben "
        "Sam Alex Jack Tyler Aaron Adam Nathan Kyle Zach Ethan Noah Logan Luke Dylan Jake Carlos Luis "
        "Juan Peter Henry Owen Liam Mason Lucas Leo Max Charlie Tony Mike Dave Steve Jim Bob Joe").split()
FEMALE = ("Mary Patricia Jennifer Linda Elizabeth Susan Jessica Sarah Karen Lisa Nancy Betty Sandra Ashley "
          "Emily Michelle Amanda Melissa Stephanie Rebecca Laura Amy Anna Emma Olivia Sophia Ava Mia "
          "Isabella Abigail Madison Chloe Grace Hannah Lily Natalie Rachel Megan Lauren Kate Katie Julia "
          "Maria Sofia Camila Nicole Samantha Alice Ella Zoe Ruby Claire Jenny Kelly Erin Holly").split()
TATOEBA_NAMES = {"Tom": MALE, "Sami": MALE, "Ziri": MALE, "Yanni": MALE, "John": MALE, "Mennad": MALE,
                 "Mary": FEMALE, "Layla": FEMALE, "Rima": FEMALE, "Skura": FEMALE, "Alice": FEMALE,
                 "Baya": FEMALE, "Salima": FEMALE, "Sonia": FEMALE}


def _diversify(sent, rng):
    chosen, out = {}, []
    for t in sent:
        base, suffix = (t[:-2], "'s") if t.endswith("'s") else (t, "")
        if base in TATOEBA_NAMES:
            if base not in chosen:
                chosen[base] = rng.choice(TATOEBA_NAMES[base])
            t = chosen[base] + suffix
        out.append(t)
    return out


def ex_tatoeba(w):
    with bz2.open(os.path.join(RAW, "tatoeba", f"{LANG.iso3}_sentences.tsv.bz2"), "rt", encoding="utf-8") as f:
        seen = set()
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3 or parts[2] in seen:
                continue
            sid, _, text = parts
            seen.add(text)
            split = split_of("tatoeba:" + sid)
            rng = random.Random(int(sid))
            for sent in TN.sentences(text):
                w.add_sentence(split, _diversify(sent, rng) if LANG.tatoeba_names else sent)


_CODEISH = re.compile(r"```|[{};]\s*$|^\s*(def|class|import|function|var|let|const|#include)\b|</?\w+>", re.M)


def ex_oasst(w):
    seen = set()
    for fname in ("oasst1.trees.jsonl.gz", "oasst2.trees.jsonl.gz"):
        with gzip.open(os.path.join(RAW, "oasst", fname), "rt", encoding="utf-8") as f:
            for line in f:
                tree = json.loads(line)
                split = split_of("oasst:" + tree["message_tree_id"])
                stack = [tree["prompt"]]
                while stack:
                    m = stack.pop()
                    stack.extend(m.get("replies", []))
                    if m["message_id"] in seen:
                        continue
                    seen.add(m["message_id"])
                    if m.get("role") != "prompter" or m.get("lang") != LANG.lang or m.get("deleted"):
                        continue
                    text = m["text"]
                    if len(text) > 1500 or _CODEISH.search(text):
                        continue
                    w.add_text(split, text)


def ex_wildchat(w):
    import pyarrow.parquet as pq
    seen = set()
    for path in sorted(glob.glob(os.path.join(RAW, "wildchat", "*.parquet"))):
        pf = pq.ParquetFile(path)
        for batch in pf.iter_batches(batch_size=2000, columns=["conversation_hash", "conversation", "language", "toxic"]):
            for row in batch.to_pylist():
                if row["language"] != LANG.wildchat or row["toxic"]:
                    continue
                split = split_of("wildchat:" + row["conversation_hash"])
                for turn in row["conversation"]:
                    text = turn["content"].strip()
                    if turn["role"] != "user" or turn.get("language") != LANG.wildchat:
                        continue
                    # keep short conversational turns; long ones are pasted documents, code or prompt templates
                    if len(text) > 300 or text.count("\n") > 2 or _CODEISH.search(text) or text in seen:
                        continue
                    seen.add(text)
                    w.add_text(split, text)


# ---------------------------------------------------------------- English conversational sources

def ex_topicalchat(w):
    for fname, split in [("train", "train"), ("valid_freq", "dev"), ("valid_rare", "dev"),
                         ("test_freq", "test"), ("test_rare", "test")]:
        for conv in json.load(open(os.path.join(RAW, "topicalchat", f"{fname}.json"))).values():
            for turn in conv["content"]:
                w.add_text(split, turn["message"])


def ex_taskmaster(w):
    for path in sorted(glob.glob(os.path.join(RAW, "taskmaster", "*.json"))):
        for conv in json.load(open(path)):
            split = split_of("taskmaster:" + conv["conversation_id"])
            for u in conv.get("utterances", []):
                if u.get("text"):
                    w.add_text(split, u["text"])


def ex_multiwoz(w):
    z = zipfile.ZipFile(os.path.join(RAW, "multiwoz", "MultiWOZ_2.1.zip"))
    test = set(z.read("MultiWOZ_2.1/testListFile.txt").decode().split())
    dev = set(z.read("MultiWOZ_2.1/valListFile.txt").decode().split())
    for did, d in json.loads(z.read("MultiWOZ_2.1/data.json")).items():
        split = "test" if did in test else "dev" if did in dev else "train"
        for turn in d["log"]:
            w.add_text(split, turn["text"])


def ex_sgd(w):
    tf = tarfile.open(os.path.join(RAW, "sgd", "sgd.tar.gz"))
    for m in tf.getmembers():
        mm = re.search(r"/(train|dev|test)/dialogues_\d+\.json$", m.name)
        if mm:
            for d in json.load(tf.extractfile(m)):
                for t in d["turns"]:
                    w.add_text(mm.group(1), t["utterance"])


def ex_soda(w):
    """SODA dialogues (synthetic, LLM-generated): every turn, with SODA's own train/valid/test split."""
    import pyarrow.parquet as pq
    for fname, split in [("train.parquet", "train"), ("valid.parquet", "dev"), ("test.parquet", "test")]:
        pf = pq.ParquetFile(os.path.join(RAW, "soda", fname))
        for batch in pf.iter_batches(batch_size=2000, columns=["dialogue"]):
            for row in batch.to_pylist():
                for text in row["dialogue"] or []:
                    if text:
                        w.add_text(split, text)


def ex_bst(w):
    tf = tarfile.open(os.path.join(RAW, "bst", "blended_skill_talk.tar.gz"))
    for fname, split in [("train.json", "train"), ("valid.json", "dev"), ("test.json", "test")]:
        for d in json.load(tf.extractfile(fname)):
            for text in [d.get("free_turker_utterance"), d.get("guided_turker_utterance")] + [t[1] for t in d["dialog"]]:
                if text:
                    w.add_text(split, text)


def ex_empathetic(w):
    tf = tarfile.open(os.path.join(RAW, "empathetic", "empatheticdialogues.tar.gz"))
    for fname, split in [("train", "train"), ("valid", "dev"), ("test", "test")]:
        reader = csv.reader(io.TextIOWrapper(tf.extractfile(f"empatheticdialogues/{fname}.csv"), encoding="utf-8"))
        next(reader)
        prompts = set()
        for row in reader:
            if len(row) < 6:
                continue
            conv, prompt, utt = row[0], row[3], row[5]
            if conv not in prompts:  # the situation description, also typed by the worker
                prompts.add(conv)
                w.add_text(split, prompt.replace("_comma_", ","))
            w.add_text(split, utt.replace("_comma_", ","))


def ex_wow(w):
    tf = tarfile.open(os.path.join(RAW, "wow", "wizard_of_wikipedia.tgz"))
    for fname, split in [("valid_random_split.json", "dev"), ("test_random_split.json", "test"), ("train.json", "train")]:
        data = json.load(tf.extractfile(fname))
        for d in data:
            for turn in d["dialog"]:
                w.add_text(split, turn["text"])
        del data


def ex_hhrlhf(w):
    seen = set()
    for path in sorted(glob.glob(os.path.join(RAW, "hhrlhf", "*.jsonl.gz"))):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            for line in f:
                for turn in re.split(r"\n\nHuman: ", json.loads(line)["chosen"])[1:]:
                    text = turn.split("\n\nAssistant:")[0].strip()
                    if text in seen or len(text) > 1000:
                        continue
                    seen.add(text)
                    w.add_text(split_of("hh:" + text), text)


def ex_nussms(w):
    z = zipfile.ZipFile(os.path.join(RAW, "nussms", "smsCorpus_en_xml.zip"))
    x = z.read(z.namelist()[0]).decode("utf-8", "replace")
    for m in re.finditer(r"<message id=\"\d+\"><text>(.*?)</text>.*?<country>([^<]*)</country>", x, re.S):
        if m.group(2) in ("United States", "USA", "US", "Canada"):
            w.add_text("test", html.unescape(m.group(1)))


def _detok(s):
    s = s.replace(" ’ ", "'").replace(" ' ", "'")
    s = re.sub(r" ([,.!?;:])", r"\1", s)
    return re.sub(r" n't\b", "n't", s)


def ex_dailydialog(w):
    import pyarrow.parquet as pq
    for row in pq.read_table(os.path.join(RAW, "dailydialog", "test.parquet")).to_pylist():
        for u in row["utterances"]:
            w.add_text("test", _detok(u))


# ---------------------------------------------------------------- general sources (parallel)

def _init_worker(locale):
    global TN, LANG
    LANG = langs.get(locale)
    TN = TextNorm(LANG)


def _tok_docs(args):
    """Tokenise a batch of (doc_id, text, route) documents; returns per-route lines and counts."""
    docs, min_tokens, pct = args
    out = collections.defaultdict(lambda: {"train": [], "dev": [], "test": []})
    mid = collections.defaultdict(collections.Counter)
    init = collections.defaultdict(collections.Counter)
    nw = collections.Counter()
    for did, text, route in docs:
        split = split_of("doc:" + did, test_pct=pct, dev_pct=pct)
        for line in text.split("\n"):
            line = line.strip()
            if len(line) < 25:
                continue
            for sent in TN.sentences(line):
                if len(sent) < min_tokens or len(sent) > MAX_SENT_TOKENS:
                    continue
                out[route][split].append(" ".join(sent))
                nw[route] += len(sent)
                if split == "train":
                    init[route][sent[0]] += 1
                    mid[route].update(sent[1:])
    return dict(out), dict(mid), dict(init), nw


def _batches(docs, full, batch_docs=2000):
    buf = []
    for d in docs:
        if d[2] in full:
            continue
        buf.append(d)
        if len(buf) >= batch_docs:
            yield buf
            buf = []
    if buf:
        yield buf


def _parquet_docs(path, route_fn=lambda cols, i: "main"):
    import pyarrow.parquet as pq
    pf = pq.ParquetFile(path)
    for batch in pf.iter_batches(batch_size=1000):
        cols = batch.to_pydict()
        for i in range(len(cols["text"])):
            route = route_fn(cols, i)
            if route:
                yield str(cols["id"][i]), cols["text"][i], route


def _run_general(writers, caps, docs, min_tokens, pct=0.3):
    """writers/caps: route -> Writer / word budget. Stops when every route is full or input ends."""
    totals, full = collections.Counter(), set()
    with mp.Pool(max(1, (os.cpu_count() or 2) - 2), initializer=_init_worker, initargs=(LANG.locale,)) as pool:
        for out, mid, init, nw in pool.imap(_tok_docs, ((b, min_tokens, pct) for b in _batches(docs, full)),
                                            chunksize=1):
            for route, by_split in out.items():
                if route in full:
                    continue
                w = writers[route]
                for split, lines in by_split.items():
                    w.add_lines(split, lines)
                w.mid.update(mid.get(route, {}))
                w.init.update(init.get(route, {}))
                totals[route] += nw[route]
                if totals[route] >= caps[route]:
                    full.add(route)
            if len(full) == len(writers):
                pool.terminate()
                break


def _cap(name, default):
    return int(LANG.general_caps.get(name, default))


UK_TLDS, US_TLDS = ("uk",), ("us", "gov", "edu", "mil")
GENERIC_TLDS = ("com", "org", "net", "info", "blog", "co", "me", "io", "tv", "biz")
# Unambiguous spelling markers used to assign generic-domain documents to a dialect.  Only spellings are
# used (never vocabulary such as mum/mom), so lexical preferences measured on the routed text are not circular.
_GB_MARK = re.compile(r"\b(colour|favour|favourite|behaviour|neighbour|honour|labour|humour|flavour|centre|"
                      r"theatre|defence|offence|travelled|travelling|cancelled|organis(?:e|ed|es|ing|ation)|"
                      r"recognis(?:e|ed|es|ing)|realis(?:e|ed|es|ing)|apologis(?:e|ed|ing)|analys(?:e|ed|ing)|"
                      r"catalogue|grey|aluminium|jewellery|pyjamas|manoeuvre|sceptical|enrol|fulfil)s?\b", re.I)
_US_MARK = re.compile(r"\b(color|favor|favorite|behavior|neighbor|honor|labor|humor|flavor|center|theater|"
                      r"defense|offense|traveled|traveling|canceled|organiz(?:e|ed|es|ing|ation)|"
                      r"recogniz(?:e|ed|es|ing)|realiz(?:e|ed|es|ing)|apologiz(?:e|ed|ing)|analyz(?:e|ed|ing)|"
                      r"catalog|gray|aluminum|jewelry|pajamas|maneuver|skeptical|enroll|fulfill)s?\b", re.I)


def ex_fineweb(w):
    """English FineWeb, routed by dialect so British and American web text can drive the dialect overlays:
    *.uk or generic-TLD pages using only British spellings -> fineweb_uk; .us/.gov/.edu/.mil or generic-TLD
    pages using only American spellings -> fineweb_us; everything else -> fineweb."""
    import urllib.parse
    path = glob.glob(os.path.join(RAW, "fineweb", "*.parquet"))[0]
    routes = {"fineweb": w}
    for extra in ("fineweb_uk", "fineweb_us"):
        if extra in LANG.general_sources:
            routes[extra] = Writer(w.dir, extra)

    def route(cols, i):
        if cols["language_score"][i] < 0.8:
            return None
        tld = (urllib.parse.urlsplit(cols["url"][i]).hostname or "").rsplit(".", 1)[-1]
        if tld in UK_TLDS and "fineweb_uk" in routes:
            return "fineweb_uk"
        if tld in US_TLDS and "fineweb_us" in routes:
            return "fineweb_us"
        if tld in GENERIC_TLDS and len(routes) > 1:
            text = cols["text"][i]
            n_gb, n_us = len(_GB_MARK.findall(text)), len(_US_MARK.findall(text))
            if n_gb >= 2 and n_us == 0 and "fineweb_uk" in routes:
                return "fineweb_uk"
            if n_us >= 2 and n_gb == 0 and "fineweb_us" in routes:
                return "fineweb_us"
        return "fineweb"

    caps = {r: _cap(r, LANG.general_words * (1 - LANG.wiki_share)) for r in routes}
    _run_general(routes, caps, _parquet_docs(path, route), 4)
    for r, wr in routes.items():
        if wr is not w:
            wr.close()


def ex_fineweb2(w):
    path = os.path.join(RAW, "fineweb2", f"{LANG.fineweb2}-000_00000.head.parquet")
    _run_general({"main": w}, {"main": _cap("fineweb2", LANG.general_words * (1 - LANG.wiki_share))},
                 _parquet_docs(path), 4)


def ex_wikipedia(w):
    path = os.path.join(RAW, "wikipedia", f"20231101.{LANG.lang}.parquet")
    _run_general({"main": w}, {"main": _cap("wikipedia", LANG.general_words * LANG.wiki_share)},
                 _parquet_docs(path), 6)


def ex_leipzig_uk(w):
    """Leipzig UK web corpora (sentence-shuffled samples): one sentence per line, 'id<TAB>text'."""
    def docs():
        for path in sorted(glob.glob(os.path.join(RAW, "leipzig_uk", "*.tar.gz")), reverse=True):
            tf = tarfile.open(path)
            for m in tf:
                if m.name.endswith("-sentences.txt"):
                    for line in io.TextIOWrapper(tf.extractfile(m), encoding="utf-8", errors="replace"):
                        sid, _, text = line.rstrip("\n").partition("\t")
                        yield f"{os.path.basename(path)}:{sid}", text, "main"
    _run_general({"main": w}, {"main": _cap("leipzig_uk", 20_000_000)}, docs(), 4, pct=1.0)


EXTRACTORS = {
    "tatoeba": ex_tatoeba, "oasst": ex_oasst, "wildchat": ex_wildchat, "topicalchat": ex_topicalchat,
    "taskmaster": ex_taskmaster, "multiwoz": ex_multiwoz, "sgd": ex_sgd, "bst": ex_bst,
    "empathetic": ex_empathetic, "wow": ex_wow, "hhrlhf": ex_hhrlhf, "nussms": ex_nussms,
    "dailydialog": ex_dailydialog, "fineweb": ex_fineweb, "fineweb2": ex_fineweb2, "wikipedia": ex_wikipedia,
    "leipzig_uk": ex_leipzig_uk, "soda": ex_soda,
}
DERIVED = {"fineweb_uk": "fineweb", "fineweb_us": "fineweb"}  # written by another extractor


def main(locale, names):
    _init_worker(locale)
    text_dir = work(locale, "text")
    for name in LANG.chat_sources + LANG.general_sources + LANG.synthetic_sources + LANG.eval_sources:
        if names and name not in names or name in DERIVED:
            continue
        if not names and os.path.exists(os.path.join(text_dir, f"{name}.counts.tsv")):
            continue
        w = Writer(text_dir, name)
        EXTRACTORS[name](w)
        w.close()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
