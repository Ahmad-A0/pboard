"""Google 'Tap Typing with Touch Sensing Images' (TSI, CC BY 4.0) -> word-level tap traces.

16 participants copy-typed MacKenzie & Soukoreff phrases (and random strings, skipped here) on a custom Gboard build
on a Pixel 6 Pro, with auto-correction, prediction and key-press feedback disabled. Every touch-down is aligned to
the prompt character the participant intended (ref_char), including taps they later deleted.

Two readings of each word are derived:
  first pass  - the taps as first typed, before any in-word correction (what a keyboard actually has to decode;
                this is the trace emitted),
  final       - the non-deleted taps (what the participant settled on), kept as annotation.
Insertion taps (extra taps aligned to no prompt character) are not in the published data, so insertion errors are
under-represented. Only the first touch frame is published: no hold durations.
"""
import csv
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
from common import Layout, RAW, balanced_split

SRC = "tsi"
SYNTH_TERM_MS = 250  # gap before a synthetic terminating space tap (flagged terminator="synthetic")
DIR = os.path.join(RAW, "google_tsi")


def source_layout():
    with open(os.path.join(DIR, "keyboard_data.json"), encoding="utf-8") as f:
        kb = json.load(f)
    keys = kb["keys_info"]
    centres = {k: (v["key_center_x"], v["key_center_y"]) for k, v in keys.items() if len(k) == 1 and k.isalpha()}
    sp, pe = keys["SPACE"], keys["."]
    specials = {"space": (sp["key_center_x"], sp["key_center_y"], sp["key_width"], sp["key_height"]),
                ".": (pe["key_center_x"], pe["key_center_y"], pe["key_width"], pe["key_height"])}
    info = kb["keyboard_info"]
    return Layout("tsi_pixel6pro_gboard", centres, specials, bounds=(0, 0, info["keyboard_width"], info["keyboard_height"]),
                  meta={"device": kb["device_info"], "keyboard": info})


def load():
    csv.field_size_limit(sys.maxsize)
    prompts = {}
    with open(os.path.join(DIR, "prompt_data.csv"), encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            prompts[(r["participant_id"], r["task_id"], int(r["trial_id"]))] = (r["prompt_type"], r["prompt"])
    trials = {}
    with open(os.path.join(DIR, "touch_data.csv"), encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            key = (r["participant_id"], r["task_id"], int(r["trial_id"]))
            trials.setdefault(key, []).append({
                "t": int(r["timestamp_ms"]), "ref": r["ref_char"], "i": int(r["ref_char_index_in_prompt"]),
                "x": float(r["first_frame_touch_x"]), "y": float(r["first_frame_touch_y"]),
                "major": float(r["first_frame_touch_major"]), "minor": float(r["first_frame_touch_minor"]),
                "deleted": r["was_deleted"] == "True"})
    for v in trials.values():
        v.sort(key=lambda d: d["t"])
    return prompts, trials


def word_spans(prompt):
    """[(start, end, token)] of whitespace tokens; core span excludes trailing '.'"""
    out, i = [], 0
    for tok in prompt.split(" "):
        if tok:
            core = tok.rstrip(".")
            out.append((i, i + len(core), core))
        i += len(tok) + 1
    return out


def first_pass(word_taps, term_taps):
    """Taps as first typed: everything before the first re-typed index, then later taps that extend past the
    furthest index reached (the participant carrying on after an in-word fix), then the first terminator."""
    seen, prefix, corrected = set(), [], False
    rest = []
    for k, tp in enumerate(word_taps):
        if tp["i"] in seen:
            corrected = True
            rest = word_taps[k:]
            break
        seen.add(tp["i"])
        prefix.append(tp)
    if rest:
        far = max(tp["i"] for tp in prefix)
        for tp in rest:
            if tp["i"] > far:
                prefix.append(tp)
                far = tp["i"]
    t_last = prefix[-1]["t"] if prefix else -1
    term = next((tp for tp in term_taps if tp["t"] > t_last), None)
    return prefix, term, corrected


def convert(dst):
    src = source_layout()
    prompts, trials = load()
    splits = balanced_split(SRC, {k[0] for k in trials})
    stats = {"trials": 0, "words": 0, "skipped_no_taps": 0, "skipped_no_term": 0, "corrected_in_word": 0}
    for (pid, task, trial), taps in sorted(trials.items()):
        ptype, prompt = prompts[(pid, task, trial)]
        if ptype != "phrase":
            continue
        stats["trials"] += 1
        spans = word_spans(prompt)
        for wi, (s, e, core) in enumerate(spans):
            if not core or any(c not in "abcdefghijklmnopqrstuvwxyz" for c in core.lower()):
                continue
            wt = [tp for tp in taps if s <= tp["i"] < e]
            term_taps = [tp for tp in taps if tp["i"] == e]
            if not wt:
                stats["skipped_no_taps"] += 1
                continue
            fp, term, corrected = first_pass(wt, term_taps)
            synthetic = False
            if term is None:
                if e < len(prompt):  # participant omitted the space/period: words merged, not a word trace
                    stats["skipped_no_term"] += 1
                    continue
                synthetic = True  # last word of a prompt without final period: nothing was typed after it
            stats["words"] += 1
            stats["corrected_in_word"] += corrected
            seq = fp + ([term] if term else [])
            pts, offs, keys, lit = [], [], [], []
            t0 = seq[0]["t"]
            for tp in seq:
                ch = tp["ref"]
                key = "space" if ch == "SPACE" else ch
                du, dv = src.offset_of(key, tp["x"], tp["y"])
                X, Y = dst.place(key, du, dv)
                pts.append([round(X, 1), round(Y, 1), tp["t"] - t0])
                offs.append([round(du, 3), round(dv, 3)])
                keys.append(" " if key == "space" else key)
                l = src.literal(tp["x"], tp["y"])
                lit.append(" " if l == "space" else l)
            if synthetic:
                sx, sy = dst.place("space", 0.0, 0.0)
                pts.append([round(sx, 1), round(sy, 1), pts[-1][2] + SYNTH_TERM_MS])
                offs.append(None)
                keys.append(" ")
                lit.append(" ")
            final = [tp for tp in wt if not tp["deleted"]]
            final.sort(key=lambda d: d["t"])
            fin_lit = "".join(src.nearest_letter(tp["x"], tp["y"]) for tp in final)
            yield {
                "context": prompt[:s], "target": core, "kind": "tap", "points": pts,
                "id": "%s/%s/%s/%d/w%d" % (SRC, pid, task, trial, wi), "src": SRC, "participant": pid,
                "split": splits[pid],
                "terminator": "synthetic" if synthetic else ("space" if term["ref"] == "SPACE" else "period"),
                # Key the (real) terminating tap lands on in the target geometry: ~7 % of TSI space taps sit
                # above the bar on the bottom letter row. Replay harnesses may snap or skip those.
                "terminatorKeyOnTarget": dst.literal(pts[-1][0], pts[-1][1]),
                "intended": "".join(keys), "literal": "".join(lit), "finalLiteral": fin_lit,
                "correctedInWord": corrected, "offsetsKu": offs,
                "touchMajorPx": [round(tp["major"], 1) for tp in seq],
            }
    convert.stats = stats


if __name__ == "__main__":
    from common import load_target
    n = 0
    for it in convert(load_target()):
        n += 1
        if n <= 3:
            print(json.dumps(it)[:400])
    print(n, convert.stats)
