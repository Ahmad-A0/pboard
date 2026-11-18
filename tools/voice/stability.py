"""Partial-result latency and stability of the streaming engines on the 'stab' subset (200 LS-clean + 200 CV).

  tools/voice/py tools/voice/stability.py [engines...] [--json out.json]

Input: work/hyp/stab/<engine>.partials.jsonl from run_host.py --partials (partials as [audio_ms, text] whenever the
text changed, fed in 100 ms chunks; Moonshine refreshes every 500 ms). Word timings come from the offline
parakeet-0.6b-v2 decode of the same audio (token timestamps; a word ends where the next word starts, the last one
at its last token + 80 ms).

Metrics (all in AUDIO time, i.e. algorithmic latency with infinitely fast compute; on-device compute lag is measured
separately by phone.py --modes paced):
  first_ms   first non-empty partial minus the onset of the first reference word (median)
  lag50/90   for each word of the final text that matches a reference word: the time from which every later partial
             shows the final text up to and including that word, minus the reference word's end (median / p90).
             Words that only settle in the final flush count with the time of the end of the audio.
  final_only share of words that only became stable at finalisation (after the audio ended)
  rev/word   words of an already-shown partial that a later partial changed or removed, per final word (lower = less
             flicker), compared case- and punctuation-insensitively; rev_raw the same on the raw text (case and
             punctuation changes count too)
"""
import argparse, difflib, json, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engines import W  # noqa: E402
from score import lc  # noqa: E402

REF = "parakeet-0.6b-v2"


def ref_words(h):
    """[(word, start_s, end_s)] from sherpa token timestamps (a token starting with a space starts a word)."""
    toks, ts = h.get("tokens", []), h.get("timestamps", [])
    words = []
    for t, s in zip(toks, ts):
        if t.startswith(" ") or t.startswith("▁") or not words:
            words.append([t.strip(" ▁"), s, s])
        else:
            words[-1][0] += t
        words[-1][2] = s
    out = []
    for i, (w, s, last) in enumerate(words):
        end = words[i + 1][1] if i + 1 < len(words) else last + 0.08
        for x in lc(w):
            out.append((x, s, end))
    return out


def common_prefix(a, b):
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q * len(v)))] if v else float("nan")


def analyse(engine, refs, man):
    p = W / "hyp" / "stab" / f"{engine}.partials.jsonl"
    if not p.exists():
        return None
    firsts, lags, final_only, nwords, rev, rev_raw, updates, upd_rev = [], [], 0, 0, 0, 0, 0, 0
    for h in map(json.loads, open(p)):
        rid = h["id"]
        rw = refs.get(rid) or []
        dur_ms = man[rid]["dur"] * 1000
        parts = [(t, lc(x), x.split()) for t, x in h.get("partials", [])]
        final = lc(h["text"])
        if rw and parts:
            first = next((t for t, w, _ in parts if w), None)
            if first is not None:
                firsts.append(first - rw[0][1] * 1000)
        # flicker
        for (t0, w0, r0), (t1, w1, r1) in zip(parts, parts[1:]):
            updates += 1
            r = len(w0) - common_prefix(w0, w1)
            rev += r
            upd_rev += r > 0
            rev_raw += len(r0) - common_prefix(r0, r1)
        if parts:  # the final text is the last "update"
            rev += len(parts[-1][1]) - common_prefix(parts[-1][1], final)
            rev_raw += len(parts[-1][2]) - common_prefix(parts[-1][2], h["text"].split())
        nwords += len(final)
        # stable emission time of each final word
        stable = [dur_ms] * len(final)
        ok_from = None
        for k in range(len(final)):
            t_ok = None
            for t, w, _ in reversed(parts):
                if common_prefix(w, final) >= k + 1:
                    t_ok = t
                else:
                    break
            stable[k] = t_ok if t_ok is not None else dur_ms
        sm = difflib.SequenceMatcher(a=final, b=[w for w, _, _ in rw], autojunk=False)
        for blk in sm.get_matching_blocks():
            for j in range(blk.size):
                i, r = blk.a + j, blk.b + j
                lags.append(stable[i] - rw[r][2] * 1000)
                final_only += stable[i] >= dur_ms
        ok_from = ok_from  # noqa
    n = max(1, len(lags))
    return dict(first_ms=pct(firsts, .5), lag50=pct(lags, .5), lag90=pct(lags, .9), final_only=100.0 * final_only / n,
                rev_per_word=rev / max(1, nwords), rev_raw_per_word=rev_raw / max(1, nwords),
                upd_with_rev=100.0 * upd_rev / max(1, updates), words=len(lags))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("engines", nargs="*")
    ap.add_argument("--json", default="")
    a = ap.parse_args()
    man = {r["id"]: r for r in map(json.loads, open(W / "data" / "stab" / "manifest.jsonl"))}
    refs = {h["id"]: ref_words(h) for h in map(json.loads, open(W / "hyp" / "stab" / f"{REF}.partials.jsonl"))}
    engines = a.engines or sorted(p.name[:-len(".partials.jsonl")] for p in (W / "hyp" / "stab").glob("*.partials.jsonl")
                                  if not p.name.startswith(REF))
    res = {}
    print(f"{'engine':26s} {'first':>7s} {'lag50':>7s} {'lag90':>7s} {'final%':>7s} {'rev/w':>6s} {'revraw/w':>8s} {'upd%':>6s}")
    for e in engines:
        r = analyse(e, refs, man)
        if not r:
            continue
        res[e] = r
        print(f"{e:26s} {r['first_ms']:7.0f} {r['lag50']:7.0f} {r['lag90']:7.0f} {r['final_only']:7.1f} "
              f"{r['rev_per_word']:6.3f} {r['rev_raw_per_word']:8.3f} {r['upd_with_rev']:6.1f}")
    if a.json:
        Path(a.json).write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
