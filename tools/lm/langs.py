"""Per-locale configuration of the LM pipeline.

Everything language-specific lives here: which corpora feed the chat/general components, tokenisation rules
(apostrophes, elision clitics, abbreviations), case-mapping overrides (Turkish dotted/dotless i), the
prefix-folding table, compounding, and size budgets.  build.sh <locale> selects one of these.
"""

from dataclasses import dataclass, field


@dataclass
class Dialect:
    """A regional variety sharing the base model: ships as a small overlay (<locale>.lmo)."""
    locale: str                      # e.g. "en_GB"
    varcon: str                      # VarCon spelling column: A=American, B=British (-ise), Z=Oxford, C=Canadian, D=Aus
    scowl: tuple                     # SCOWL region lists accepted as this dialect's spellings
    text_sources: list               # web text of this dialect: lexical preferences (log-odds vs other dialects)
    spelling_sources: list = field(default_factory=list)  # text NOT selected by spelling: variant shares
    unmarked: bool = False           # base training text not attributed to any dialect counts as this one
    eval_sources: list = field(default_factory=list)


@dataclass
class Lang:
    locale: str                      # base pack name: asset <locale>.lm (e.g. "en"; "de_DE" if no dialects)
    lang: str                        # ISO 639-1 (wordfreq, OASST, Wikipedia, LDNOOBW)
    iso3: str                        # ISO 639-3 (Tatoeba)
    wildchat: str | None             # WildChat "language" value
    fineweb2: str | None             # FineWeb-2 subset (None = use English FineWeb)
    chat_sources: list               # extractor names feeding the chat component
    general_sources: list            # extractor names feeding the general component
    eval_sources: list = field(default_factory=list)   # eval-only extractors
    # LLM-generated conversational text: a third mixture component whose weight is fitted on held-out
    # human-written chat (leave-one-source-out), never part of an evaluation set
    synthetic_sources: list = field(default_factory=list)
    # --- tokenisation ---
    apostrophe_words: bool = True    # letters'letters is one word ("don't")
    clitics: tuple = ()              # elided clitics split off and flagged no-space-after ("l'", "d'")
    leading_apostrophe: tuple = ()   # whole words that start with an apostrophe ("'em")
    abbreviations: tuple = ()        # words whose trailing '.' does not end a sentence
    # --- case & folding (mirrored on device from the pack header) ---
    case_overrides: dict = field(default_factory=dict)   # char -> lowercase char (before Unicode default)
    fold_overrides: dict = field(default_factory=lambda: {"'": ""})  # char -> folded string
    fold_keep: str = ""              # letters whose diacritics are NOT stripped when folding (e.g. "åäö")
    # --- morphology ---
    compounding: bool = False        # closed compounds ("Hausaufgaben"): decompose OOV words on device
    linking_morphemes: tuple = ()    # allowed Fugen elements between compound parts
    # --- lexicon ---
    spell_lists: str | None = None   # "scowl" for English; None = frequency-only validation
    tatoeba_names: bool = False      # replace Tatoeba's overused "Tom"/"Mary" with varied names
    lexicon_target: int = 200_000
    single_letter_words: tuple = ()  # single letters that are words beyond the very frequent ones ("u" in texting)
    # contractions whose apostrophe-less spelling ("dont", "im") is repaired in training text and is not a word
    contraction_suffixes: tuple = ()
    contraction_stems: tuple = ()    # stems taking clitic contractions 'm 're 've 'll 'd 's (not possessives)
    # --- size budgets ---
    general_words: int = 80_000_000  # cap on general-domain training words
    wiki_share: float = 0.2          # share of general_words taken from Wikipedia
    order: int = 4
    target_bytes: int = 24_000_000   # shipping asset budget
    # --- regional varieties (overlays on the shared base) ---
    dialects: dict = field(default_factory=dict)
    lexicon_sources: list = field(default_factory=list)   # extra inputs needed by lexicon.py (e.g. varcon)
    general_caps: dict = field(default_factory=dict)      # per general source word cap (overrides shares)


EN_ABBR = ("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "etc", "inc", "ltd", "co", "mt", "ft", "prof", "gen",
           "sen", "rep", "gov", "lt", "col", "capt", "sgt", "rev", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
           "sep", "sept", "oct", "nov", "dec", "approx", "dept", "est", "fig", "vol", "ave", "blvd", "corp")

EN_CHAT = ["tatoeba", "oasst", "topicalchat", "taskmaster", "multiwoz", "sgd", "bst", "empathetic", "wow",
           "hhrlhf", "wildchat"]

LANGS = {
    "en": Lang(
        locale="en", lang="en", iso3="eng", wildchat="English", fineweb2=None,
        chat_sources=EN_CHAT,
        # fineweb is routed by domain: *.uk -> fineweb_uk, *.us/.gov/.edu/.mil -> fineweb_us, rest -> fineweb
        general_sources=["fineweb", "fineweb_uk", "fineweb_us", "leipzig_uk", "wikipedia"],
        # SODA as a synthetic component was evaluated (docs/lm-eval.md) and is not shipped: set ["soda"] to rebuild it
        synthetic_sources=[],
        general_caps={"fineweb": 40_000_000, "fineweb_uk": 20_000_000, "fineweb_us": 12_000_000,
                      "leipzig_uk": 20_000_000, "wikipedia": 10_000_000},
        eval_sources=["nussms", "dailydialog"],
        leading_apostrophe=("'em", "'cause", "'til", "'bout", "'round", "'n", "'tis", "'twas"),
        abbreviations=EN_ABBR,
        spell_lists="scowl", tatoeba_names=True, lexicon_target=230_000, single_letter_words=("u",),
        contraction_suffixes=("n't",),
        contraction_stems=("i", "you", "we", "they", "it", "that", "what", "he", "she", "there", "here", "who",
                           "where", "how", "let", "when", "could", "would", "should", "might", "must", "these", "those",
                        "why", "everyone", "everybody", "someone", "somebody", "nobody", "something", "nothing",
                        "everything", "this", "y'all"),
        lexicon_sources=["varcon"],
        dialects={
            "en_GB": Dialect("en_GB", varcon="B", scowl=("british",), text_sources=["fineweb_uk", "leipzig_uk"],
                             spelling_sources=["leipzig_uk"], eval_sources=["fineweb_uk", "leipzig_uk"]),
            "en_US": Dialect("en_US", varcon="A", scowl=("american",), text_sources=["fineweb_us"],
                             spelling_sources=["topicalchat", "taskmaster", "sgd", "bst", "empathetic", "wow"],
                             unmarked=True, eval_sources=["fineweb_us"]),
        },
    ),
    "de_DE": Lang(
        locale="de_DE", lang="de", iso3="deu", wildchat="German", fineweb2="deu_Latn",
        chat_sources=["tatoeba", "oasst", "wildchat"],
        general_sources=["fineweb2", "wikipedia"],
        abbreviations=("bzw", "ca", "dr", "evtl", "ggf", "hr", "fr", "inkl", "nr", "str", "usw", "vgl", "z", "u",
                       "d", "h", "o", "s", "zb", "bspw", "jan", "feb", "aug", "sept", "okt", "nov", "dez", "st"),
        fold_overrides={"'": "", "ß": "ss"},
        compounding=True, linking_morphemes=("", "s", "es", "n", "en", "e", "er"),
        tatoeba_names=True, lexicon_target=200_000, general_words=30_000_000, wiki_share=0.4,
        target_bytes=12_000_000,
    ),
    "es_ES": Lang(
        locale="es_ES", lang="es", iso3="spa", wildchat="Spanish", fineweb2="spa_Latn",
        chat_sources=["tatoeba", "oasst", "wildchat"],
        general_sources=["fineweb2", "wikipedia"],
        apostrophe_words=False,
        abbreviations=("sr", "sra", "srta", "dr", "dra", "ud", "uds", "etc", "pág", "núm", "av", "ej"),
        tatoeba_names=True, general_words=30_000_000, wiki_share=0.4, target_bytes=12_000_000,
    ),
    "fr_FR": Lang(  # configured but not built yet: shows elision clitics
        locale="fr_FR", lang="fr", iso3="fra", wildchat="French", fineweb2="fra_Latn",
        chat_sources=["tatoeba", "oasst", "wildchat"], general_sources=["fineweb2", "wikipedia"],
        clitics=("l'", "d'", "j'", "qu'", "n'", "s'", "c'", "m'", "t'", "jusqu'", "lorsqu'", "puisqu'", "quoiqu'"),
        abbreviations=("m", "mme", "mlle", "dr", "etc", "av", "bd"),
        tatoeba_names=True, general_words=30_000_000, wiki_share=0.4, target_bytes=12_000_000,
    ),
    "tr_TR": Lang(  # configured but not built yet: shows locale case mapping
        locale="tr_TR", lang="tr", iso3="tur", wildchat="Turkish", fineweb2="tur_Latn",
        chat_sources=["tatoeba", "oasst", "wildchat"], general_sources=["fineweb2", "wikipedia"],
        apostrophe_words=True,  # Turkish writes suffixes on proper nouns after an apostrophe: "Ankara'da"
        case_overrides={"I": "ı", "İ": "i"},
        fold_overrides={"'": ""}, fold_keep="çğıöşü",
        general_words=30_000_000, wiki_share=0.4, target_bytes=12_000_000,
    ),
}


def get(locale: str) -> Lang:
    if locale not in LANGS:
        raise SystemExit(f"unknown locale {locale}; configured: {', '.join(LANGS)}")
    return LANGS[locale]
