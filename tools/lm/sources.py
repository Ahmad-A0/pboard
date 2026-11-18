"""Manifest of every external data source used by the LM pipeline.

Each source records where it comes from, its licence, the languages it covers and what role it plays.
docs/data-licenses.md is the human-readable audit of this file; keep the two in sync.

Roles:
  chat     - conversational / typed-by-people text, trained into the "chat" component LM
  general  - general written text, trained into the "general" component LM
  lexicon  - word lists / frequencies used only to build and validate the lexicon
  filter   - offensive-word list
  eval     - held-out evaluation only (never trained on, nothing derived from it is shipped)

`files` is a function lang_cfg -> [(url, relative path under tools/data/raw/<source>/, kind)], kind in
{"file", "partial-parquet:<max_bytes>", "hf-first-shard"}.
"""

from dataclasses import dataclass

GH_RAW = "https://raw.githubusercontent" + ".com/"
GH_TAR = "https://codeload.git" + "hub.com/"
HF = "https://huggingface.co/datasets/"
PARLAI = "http://parl.ai/downloads/"
ANY = None  # all languages


@dataclass
class Source:
    name: str
    role: str
    langs: set | None
    licence: str
    licence_url: str
    files: object  # callable(lang_cfg) -> list of (url, rel, kind)
    notes: str = ""


def _f(*items):
    return lambda L: [(u, r, "file") for u, r in items]


SOURCES = [
    # ---------------------------------------------------------------- multilingual
    Source("tatoeba", "chat", ANY, "CC BY 2.0 FR (some sentences CC0 1.0)", "https://tatoeba.org/en/terms_of_use",
           lambda L: [(f"https://downloads.tatoeba.org/exports/per_language/{L.iso3}/{L.iso3}_sentences.tsv.bz2",
                       f"{L.iso3}_sentences.tsv.bz2", "file")],
           "Short everyday sentences written by volunteers; ~400 languages."),
    Source("oasst", "chat", ANY, "Apache-2.0", HF + "OpenAssistant/oasst2",
           _f((HF + "OpenAssistant/oasst1/resolve/main/2023-04-12_oasst_ready.trees.jsonl.gz", "oasst1.trees.jsonl.gz"),
              (HF + "OpenAssistant/oasst2/resolve/main/2023-11-05_oasst2_ready.trees.jsonl.gz", "oasst2.trees.jsonl.gz")),
           "Human-typed prompter turns only (assistant turns excluded); 35 languages, mostly en/es/ru/de/zh/fr."),
    Source("wildchat", "chat", ANY, "ODC-By 1.0", HF + "allenai/WildChat-1M",
           lambda L: [(HF + f"allenai/WildChat-1M/resolve/main/data/train-{i:05d}-of-00014.parquet",
                       f"train-{i:05d}.parquet", "file") for i in range(2)],
           "User turns only (no model output), short conversational turns, code/pasted text filtered; ~68 languages."),
    Source("wikipedia", "general", ANY, "CC BY-SA 4.0 (and GFDL)", "https://dumps.wikimedia.org/legal.html",
           lambda L: [(f"wikimedia/wikipedia:20231101.{L.lang}", f"20231101.{L.lang}.parquet", "hf-first-shard")],
           "Wikipedia 2023-11-01 dump via the wikimedia/wikipedia parquet mirror; first shard of the language (leading row groups)."),
    Source("fineweb2", "general", ANY, "ODC-By 1.0 (and Common Crawl terms of use)", HF + "HuggingFaceFW/fineweb-2",
           lambda L: [(HF + f"HuggingFaceFW/fineweb-2/resolve/main/data/{L.fineweb2}/train/000_00000.parquet",
                       f"{L.fineweb2}-000_00000.head.parquet", "partial-parquet:600000000")] if L.fineweb2 else [],
           "Filtered web text, 1000+ languages; only the leading row groups of one shard are fetched (HTTP ranges)."),
    Source("wordfreq", "lexicon", ANY, "CC BY-SA 4.0 (data), Apache-2.0 (code)",
           GH_RAW + "rspeer/wordfreq/master/README.md", lambda L: [],
           "Installed from PyPI into tools/.venv; 'large' word list (44 languages) used for lexicon frequencies."),
    Source("ldnoobw", "filter", ANY, "CC BY 4.0",
           GH_RAW + "LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/LICENSE",
           lambda L: [(GH_RAW + f"LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/{L.lang}",
                       f"{L.lang}.txt", "file")],
           "Seed list for offensive-word filtering (curated per language in tools/lm/data/offensive_<lang>.txt)."),
    # ---------------------------------------------------------------- English only
    Source("fineweb", "general", {"en"}, "ODC-By 1.0 (and Common Crawl terms of use)", HF + "HuggingFaceFW/fineweb",
           _f((HF + "HuggingFaceFW/fineweb/resolve/main/sample/10BT/000_00000.parquet", "sample-10BT-000_00000.parquet")),
           "Filtered English web text; a prefix of one shard is used."),
    Source("topicalchat", "chat", {"en"}, "CDLA-Sharing-1.0", GH_RAW + "alexa/Topical-Chat/master/DATALICENSE",
           _f(*[(GH_RAW + f"alexa/Topical-Chat/master/conversations/{f}.json", f"{f}.json")
                for f in ["train", "valid_freq", "valid_rare", "test_freq", "test_rare"]]),
           "Crowd-worker typed open-domain chat. Derived statistics are 'Results' (CDLA-Sharing s3.5): no obligations."),
    Source("taskmaster", "chat", {"en"}, "CC BY 4.0", GH_RAW + "google-research-datasets/Taskmaster/master/LICENSE",
           _f(*([(GH_RAW + "google-research-datasets/Taskmaster/master/TM-1-2019/self-dialogs.json", "tm1-self-dialogs.json"),
                 (GH_RAW + "google-research-datasets/Taskmaster/master/TM-1-2019/woz-dialogs.json", "tm1-woz-dialogs.json")]
                + [(GH_RAW + f"google-research-datasets/Taskmaster/master/TM-2-2020/data/{f}.json", f"tm2-{f}.json")
                   for f in ["flights", "food-ordering", "hotels", "movies", "music", "restaurant-search", "sports"]]
                + [(GH_RAW + f"google-research-datasets/Taskmaster/master/TM-3-2020/data/data_{i:02d}.json", f"tm3-{i:02d}.json")
                   for i in range(4)])),
           "Task-oriented dialogues (typed self-dialogs + Wizard-of-Oz). Only 4/20 TM-3 files (repetitive domain)."),
    Source("multiwoz", "chat", {"en"}, "MIT", GH_RAW + "budzianowski/multiwoz/master/LICENSE",
           _f((GH_RAW + "budzianowski/multiwoz/master/data/MultiWOZ_2.1.zip", "MultiWOZ_2.1.zip")),
           "Typed Wizard-of-Oz task dialogues."),
    Source("sgd", "chat", {"en"}, "CC BY-SA 4.0",
           GH_RAW + "google-research-datasets/dstc8-schema-guided-dialogue/master/LICENSE.txt",
           _f((GH_TAR + "google-research-datasets/dstc8-schema-guided-dialogue/tar.gz/refs/heads/master", "sgd.tar.gz")),
           "Schema-guided task dialogues (crowd-paraphrased)."),
    Source("wow", "chat", {"en"}, "CC BY 4.0",
           GH_RAW + "facebookresearch/ParlAI/main/parlai/tasks/wizard_of_wikipedia/LICENSE_DOCUMENTATION",
           _f((PARLAI + "wizard_of_wikipedia/wizard_of_wikipedia.tgz", "wizard_of_wikipedia.tgz")),
           "Wizard of Wikipedia dialogue turns only (knowledge passages ignored); ParlAI relicensed CC BY 4.0 (PR #4269)."),
    Source("soda", "chat", {"en"}, "CC BY 4.0", HF + "allenai/soda",
           _f(*[(HF + f"allenai/soda/resolve/main/{sp}.parquet", f"{sp}.parquet") for sp in ("train", "valid", "test")]),
           "SODA (AllenAI): 1.5M synthetic everyday social dialogues, GENERATED BY AN OPENAI LLM (GPT-3.5 series) "
           "from commonsense knowledge-graph triples; not human-written. Used for training only; every quality gate "
           "is measured on human-written text."),
    Source("bst", "chat", {"en"}, "CC BY 4.0",
           GH_RAW + "facebookresearch/ParlAI/main/parlai/tasks/blended_skill_talk/LICENSE_DOCUMENTATION",
           _f((PARLAI + "blended_skill_talk/blended_skill_talk.tar.gz", "blended_skill_talk.tar.gz")),
           "Blended Skill Talk crowd-worker chat."),
    Source("empathetic", "chat", {"en"}, "CC BY 4.0",
           GH_RAW + "facebookresearch/ParlAI/main/parlai/tasks/empathetic_dialogues/LICENSE_DOCUMENTATION",
           _f((PARLAI + "empatheticdialogues/empatheticdialogues.tar.gz", "empatheticdialogues.tar.gz")),
           "EmpatheticDialogues - ParlAI copy (CC BY 4.0); NOT the original repo copy (CC BY-NC 4.0)."),
    Source("hhrlhf", "chat", {"en"}, "MIT", HF + "Anthropic/hh-rlhf",
           _f(*[(HF + f"Anthropic/hh-rlhf/resolve/main/{sub}/{sp}.jsonl.gz", f"{sub}-{sp}.jsonl.gz")
                for sub in ("helpful-base", "helpful-online", "helpful-rejection-sampled") for sp in ("train", "test")]),
           "Human (crowd-worker) turns of the helpfulness subsets only; red-team subsets and model turns excluded."),
    Source("scowl", "lexicon", {"en"}, "SCOWL licence (permissive, MIT-like)", "http://wordlist.aspell.net/scowl-readme/",
           _f(("https://downloads.sourceforge.net/project/wordlist/SCOWL/2020.12.07/scowl-2020.12.07.tar.gz",
               "scowl-2020.12.07.tar.gz")),
           "Spell-checker word lists (American, sizes <= 80) used to validate spellings."),
    Source("varcon", "lexicon", {"en"}, "VarCon licence (permissive, MIT-like; part of SCOWL)",
           "http://wordlist.aspell.net/varcon-readme/",
           _f(("https://downloads.sourceforge.net/project/wordlist/VarCon/2020.12.07/varcon-2020.12.07.tar.gz",
               "varcon-2020.12.07.tar.gz")),
           "British/American/Canadian/Australian spelling-variant mapping (colour/color, -ise/-ize, ...)."),
    Source("leipzig_uk", "general", {"en"}, "CC BY 4.0 (Leipzig Corpora Collection downloadable corpora)",
           "https://wortschatz.uni-leipzig.de/en/usage",
           _f(("https://downloads.wortschatz-leipzig.de/corpora/eng-uk_web-public_2018_1M.tar.gz",
               "eng-uk_web-public_2018_1M.tar.gz"),
              ("https://downloads.wortschatz-leipzig.de/corpora/eng-uk_web_2002_1M.tar.gz", "eng-uk_web_2002_1M.tar.gz")),
           "1M-sentence samples of UK web text (sentence-shuffled); British frequencies for the en_GB overlay."),
    Source("nussms", "eval", {"en"}, "Released 'copyright-free for unlimited use' (Chen & Kan 2013); no licence file",
           GH_RAW + "kite1988/nus-sms-corpus/master/README.md",
           _f((GH_RAW + "kite1988/nus-sms-corpus/master/smsCorpus_en_xml_2015.03.09_all.zip", "smsCorpus_en_xml.zip")),
           "Real SMS messages; US/Canada senders only - evaluation only."),
    Source("dailydialog", "eval", {"en"}, "CC BY-NC-SA 4.0", "https://aclanthology.org/I17-1099/",
           _f(("https://huggingface.co/api/datasets/roskoN/dailydialog/parquet/full/test/0.parquet", "test.parquet")),
           "Written daily-life dialogues - evaluation only (non-commercial licence: never trained on or shipped)."),
]

BY_NAME = {s.name: s for s in SOURCES}


def sources_for(L):
    """Sources needed to build locale L (chat/general/eval extractors + lexicon/filter inputs)."""
    wanted = (set(L.chat_sources) | set(L.general_sources) | set(L.eval_sources) | set(L.lexicon_sources)
              | {"wordfreq", "ldnoobw"})
    if L.spell_lists:
        wanted.add(L.spell_lists)
    return [s for s in SOURCES if s.name in wanted and (s.langs is None or L.lang in s.langs)]
