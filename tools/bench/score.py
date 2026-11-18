#!/usr/bin/env python3
"""Score replay results (Replay mode=replay / predict output).

Usage: score.py results.jsonl [--set set.jsonl --geometry geometry.json] [--predict predict.jsonl]
                [--json out.json] [--label NAME]

Per item, the produced words are the field's words after the item minus the words of its context
(the harness guarantees the field held the context before the item, or reset it to the context).
Comparison is case-insensitive, curly apostrophes are normalised, and surrounding punctuation the
IME may add (e.g. auto-period) is ignored.

Metrics
  tap WER            sum of word edit distances / number of target words, over tap items
  tap word accuracy  share of tap items producing exactly the target word
  false corrections  among tap items typed *cleanly* (every letter tap inside the intended key's hit
                     box on the replay geometry, word terminated on the space bar, target has no
                     apostrophe), share whose output differs from the target
  noisy recovery     among the other tap items, share where the IME still produced the target
  swipe word acc.    share of swipe items producing exactly the target word (+ swipe WER)
  prediction hit@1 / hit@3   next word appeared in the best slot / any of the slots
With --set/--geometry the "literal" key sequence is recomputed on the replay geometry (human sets
carry the literal decoded on the participant's own keyboard). Items with a `participant` field are
also reported per participant; items with `terminatorKeyOnTarget` are split by whether the person's
word terminator landed on the space bar.
"""
import argparse
import json
import re
from collections import OrderedDict

PUNCT = re.compile(r"^[\"'.,!?;:()\-]+|[\"'.,!?;:()\-]+$")


def norm_tokens(text):
    text = text.replace("’", "'").replace("‘", "'")
    out = []
    for tok in text.split():
        t = PUNCT.sub("", tok.lower())
        if t:
            out.append(t)
    return out


def edit_distance(a, b):
    d = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(b) + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] != b[j - 1]))
            prev = cur
    return d[len(b)]


def produced(rec):
    ctx = norm_tokens(rec.get("before", rec.get("context", "")))
    after = norm_tokens(rec["after"])
    # The IME may have rewritten the last context word (e.g. a late correction); align on length.
    return after[len(ctx):], after[:len(ctx)] != ctx


def literals_on_geometry(set_path, geo_path):
    """id -> (letters hit on the replay geometry, terminator on space?) for tap items."""
    with open(geo_path) as f:
        geo = json.load(f)
    keys = [(k.get("hit") or k["visual"], k.get("label") or "<%s>" % k["name"]) for k in geo["keys"]]

    def decode(x, y):
        for h, lab in keys:
            if h[0] <= x < h[2] and h[1] <= y < h[3]:
                return lab
        return "<off>"
    out = {}
    with open(set_path, encoding="utf-8") as f:
        for line in f:
            it = json.loads(line)
            if it.get("kind") != "tap" or not it.get("points"):
                continue
            labs = [decode(p[0], p[1]) for p in it["points"]]
            out[it.get("id")] = ("".join(labs[:-1]), labs[-1] == " ")
    return out


def is_clean(r, lit):
    """Cleanly typed: letters hit spell the target exactly and the word ended on the space bar."""
    if r["target"] != r["target"].replace("'", ""):
        return None  # apostrophe words cannot be typed literally on the letter layer
    if lit is not None:
        letters, term_ok = lit
        return letters == r["target"].lower() and (term_ok or r.get("terminatorKeyOnTarget") == ".")
    if "clean" in r:
        return bool(r["clean"])
    if "literal" in r:
        return r["literal"].strip().lower() == r["target"].lower() and r.get("terminatorKeyOnTarget", "space") in ("space", ".")
    return None


def stats(rs, kind, lits):
    edits = words = exact = ctx_changed = degraded = 0
    clean_n = clean_bad = noisy_n = noisy_ok = 0
    errors = []
    for r in rs:
        hyp, changed = produced(r)
        ref = norm_tokens(r["target"])
        edits += edit_distance(ref, hyp)
        words += len(ref)
        ok = hyp == ref
        exact += ok
        ctx_changed += changed
        degraded += bool(r.get("timingDegraded"))
        lit = lits.get(r.get("id")) if lits else None
        if not ok:
            errors.append({"id": r.get("id", r["i"]), "target": r["target"],
                           "literal": lit[0] if lit else r.get("literal"), "got": " ".join(hyp)})
        if kind == "tap":
            c = is_clean(r, lit)
            if c is True:
                clean_n += 1
                clean_bad += not ok
            elif c is False:
                noisy_n += 1
                noisy_ok += ok
    k = {"items": len(rs), "words": words, "wer": edits / max(1, words), "wordAccuracy": exact / max(1, len(rs)),
         "contextRewritten": ctx_changed, "timingDegraded": degraded,
         "retriedItems": sum(1 for r in rs if r.get("attempts", 1) > 1)}
    if kind == "tap" and clean_n + noisy_n:
        k.update({"cleanItems": clean_n, "falseCorrections": clean_bad,
                  "falseCorrectionRate": clean_bad / max(1, clean_n),
                  "noisyItems": noisy_n, "noisyRecovered": noisy_ok,
                  "noisyRecoveryRate": noisy_ok / max(1, noisy_n)})
    k["errors"] = errors
    return k


def score_replay(path, lits=None):
    recs = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    res = {}
    for kind in ("tap", "swipe"):
        rs = [r for r in recs if r["kind"] == kind]
        if not rs:
            continue
        k = stats(rs, kind, lits)
        parts = OrderedDict()
        for r in rs:
            if "participant" in r:
                parts.setdefault(r["participant"], []).append(r)
        if len(parts) > 1 and len(parts) <= 40:
            k["byParticipant"] = {p: {kk: v for kk, v in stats(g, kind, lits).items() if kk != "errors"}
                                  for p, g in sorted(parts.items())}
        if any("terminatorKeyOnTarget" in r for r in rs):
            on = [r for r in rs if r.get("terminatorKeyOnTarget", "space") in ("space", ".")]
            off = [r for r in rs if r.get("terminatorKeyOnTarget", "space") not in ("space", ".")]
            k["terminatorOnSpace"] = {kk: v for kk, v in stats(on, kind, lits).items() if kk != "errors"}
            k["terminatorMissedSpace"] = {kk: v for kk, v in stats(off, kind, lits).items() if kk != "errors"}
        res[kind] = k
    return res


def score_predict(path):
    recs = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    n = hit1 = hit3 = shown = 0
    misses = []
    for r in recs:
        n += 1
        if not r.get("stripShown", True):
            misses.append({"id": r.get("id", r["i"]), "context": r["context"], "target": r["target"], "slots": None})
            continue
        shown += 1
        preds = []
        for s in r["slots"]:
            before = norm_tokens(s["before"])
            after = norm_tokens(s["after"])
            preds.append(" ".join(after[len(before):]) if after[:len(before)] == before else "")
        tgt = " ".join(norm_tokens(r["target"]))
        h1 = bool(preds) and preds[0] == tgt
        h3 = tgt in preds
        hit1 += h1
        hit3 += h3
        if not h3:
            misses.append({"id": r.get("id", r["i"]), "context": r["context"], "target": r["target"], "slots": preds})
    return {"items": n, "stripShown": shown, "hit1": hit1 / max(1, n), "hit3": hit3 / max(1, n),
            "hit1GivenStrip": hit1 / max(1, shown), "hit3GivenStrip": hit3 / max(1, shown), "misses": misses}


def line(lab, kind, t):
    if kind == "tap":
        s = "%stap   items %d  WER %.2f%%  word acc %.2f%%" % (lab, t["items"], 100 * t["wer"], 100 * t["wordAccuracy"])
        if "cleanItems" in t:
            s += "  false-corr %d/%d = %.2f%%  noisy recovered %d/%d = %.2f%%" % (
                t["falseCorrections"], t["cleanItems"], 100 * t["falseCorrectionRate"],
                t["noisyRecovered"], t["noisyItems"], 100 * t["noisyRecoveryRate"])
    else:
        s = "%sswipe items %d  word acc %.2f%%  WER %.2f%%" % (lab, t["items"], 100 * t["wordAccuracy"], 100 * t["wer"])
    return s + "  (timing-degraded %d, retried %d)" % (t["timingDegraded"], t["retriedItems"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results", nargs="?")
    ap.add_argument("--set")
    ap.add_argument("--geometry")
    ap.add_argument("--predict")
    ap.add_argument("--json")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    out = {"label": a.label}
    lits = literals_on_geometry(a.set, a.geometry) if a.set and a.geometry else None
    if a.results:
        out.update(score_replay(a.results, lits))
    if a.predict:
        out["predict"] = score_predict(a.predict)
    if a.json:
        with open(a.json, "w") as f:
            json.dump(out, f, indent=1)
    lab = (a.label + ": ") if a.label else ""
    for kind in ("tap", "swipe"):
        if kind in out:
            print(line(lab, kind, out[kind]))
            for sub in ("terminatorOnSpace", "terminatorMissedSpace"):
                if sub in out[kind]:
                    print(line("  %s " % sub, kind, out[kind][sub]))
            for p, t in out[kind].get("byParticipant", {}).items():
                print(line("  %s " % p, kind, t))
    if "predict" in out:
        p = out["predict"]
        print("%spredict items %d  hit@1 %.2f%%  hit@3 %.2f%%  (strip shown %d)" % (
            lab, p["items"], 100 * p["hit1"], 100 * p["hit3"], p["stripShown"]))


if __name__ == "__main__":
    main()
