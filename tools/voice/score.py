"""Score hypotheses from run_host.py against the set references.

  tools/voice/py tools/voice/score.py [--sets ls-clean,...] [--engines a,b] [--json out.json]

Three WERs per set (corpus level: total edits / total reference words):
  norm  Whisper's EnglishTextNormalizer on both sides (lower case, no punctuation, numbers to digits, US spelling):
        the Open ASR Leaderboard convention; comparable with published numbers.
  lc    lower case, punctuation removed (apostrophes kept), nothing else: penalises "10" vs "ten".
  raw   case- and punctuation-sensitive ("formatted" WER) against punctuated, cased references (LibriSpeech-PC for
        LibriSpeech, the original transcripts elsewhere); only utterances that have such a reference. Double quotes are
        dropped and typographic quotes/dashes mapped to ASCII; a word with wrong case or punctuation is an error.
Utterances whose normalised reference is empty are skipped.
"""
import argparse, json, re, sys, unicodedata
from pathlib import Path

import jiwer
from whisper_normalizer.english import EnglishTextNormalizer

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engines import W  # noqa: E402

_norm = EnglishTextNormalizer()
SETS = ["ls-clean", "ls-other", "cv", "ami", "e22"]


def norm(s):
    return _norm(s or "").split()


def lc(s):
    s = unicodedata.normalize("NFKC", s or "").lower().replace("’", "'").replace("‘", "'")
    s = re.sub(r"[^\w\s']", " ", s)
    s = re.sub(r"(?<!\w)'|'(?!\w)", " ", s)
    return s.split()


def raw(s):
    s = unicodedata.normalize("NFKC", s or "")
    for a, b in {"’": "'", "‘": "'", "“": "", "”": "", '"': "", "—": " - ", "–": "-",
                 "…": "..."}.items():
        s = s.replace(a, b)
    return s.split()


def wer(pairs):
    refs = [r for r, h in pairs if r]
    hyps = [h for r, h in pairs if r]
    if not refs:
        return None, 0
    o = jiwer.process_words([" ".join(r) for r in refs], [" ".join(h) for h in hyps])
    n = sum(len(r) for r in refs)
    return 100.0 * (o.substitutions + o.deletions + o.insertions) / n, n


def score(engine, s):
    hp = W / "hyp" / s / f"{engine}.jsonl"
    if not hp.exists():
        return None
    man = {r["id"]: r for r in map(json.loads, open(W / "data" / s / "manifest.jsonl"))}
    hyp = {r["id"]: r for r in map(json.loads, open(hp))}
    if set(hyp) != set(man):
        return None
    out = {}
    for k, f in (("norm", norm), ("lc", lc)):
        out[k], _ = wer([(f(man[i]["ref"]), f(hyp[i]["text"])) for i in man])
    out["raw"], out["raw_words"] = wer([(raw(man[i]["ref_pc"]), raw(hyp[i]["text"])) for i in man if man[i].get("ref_pc")])
    # number formatting: share of the reference's digit-bearing tokens ("$12.5", "2019", "10%") that the hypothesis
    # reproduces exactly (punctuation at token edges ignored). 0 for engines that spell numbers out.
    from collections import Counter
    numtok = lambda s: Counter(t.strip(".,;:!?\"'()") for t in raw(s) if re.search(r"\d", t))
    want = got = 0
    for i in man:
        rn = numtok(man[i].get("ref_pc") or man[i]["ref"])
        want += sum(rn.values())
        got += sum((rn & numtok(hyp[i]["text"])).values())
    out["num"] = 100.0 * got / want if want else None
    out["num_n"] = want
    dur = sum(r["dur"] for r in man.values())
    out["rtf_host_1t"] = sum(hyp[i].get("decode_ms", 0) for i in man) / 1e3 / dur
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sets", default=",".join(SETS))
    ap.add_argument("--engines", default="")
    ap.add_argument("--json", default="")
    a = ap.parse_args()
    sets = a.sets.split(",")
    engines = a.engines.split(",") if a.engines else sorted({p.stem for s in sets for p in (W / "hyp" / s).glob("*.jsonl")
                                                             if not p.stem.endswith(".partials")})
    res = {}
    for e in engines:
        res[e] = {s: score(e, s) for s in sets}
    hdr = "engine".ljust(26) + "".join(f"{s:>22}" for s in sets)
    print(hdr + "\n" + " " * 26 + "".join(f"{'norm/lc/raw':>22}" for _ in sets))
    for e in engines:
        row = e.ljust(26)
        for s in sets:
            r = res[e][s]
            row += f"{'-':>22}" if not r else f"{r['norm']:6.2f}/{r['lc']:6.2f}/{r['raw'] if r['raw'] is not None else float('nan'):6.2f}".rjust(22)
        print(row)
    if a.json:
        Path(a.json).write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
