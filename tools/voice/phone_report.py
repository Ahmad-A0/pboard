"""Summarise the on-device runs in tools/voice/work/phone (written by phone.py) into one row per engine/config.

  tools/voice/py tools/voice/phone_report.py [--json out.json]

rtf          compute / audio over the 30-utterance phone set, decoding as fast as possible (unpaced). Streaming
             engines decode every 100 ms chunk and fetch the partial, as the keyboard would.
cpu/s        CPU-seconds per second of audio in that run (energy proxy; includes thread-pool spinning)
rss          peak resident memory of the process (MB)
load         model load time (ms)
paced runs (8 LibriSpeech utterances, audio released in real time, then 1 s of real-time silence):
  first      first non-empty partial, wall time after the onset of speech (onset = first word start in the
             parakeet-0.6b-v2 offline decode)
  visible    time after the end of the last spoken word (parakeet timestamps) at which the partial already equals the final text (case and
             punctuation ignored) and stays so; if only the final flush produces it, the flush time
  p-rtf      compute / audio while paced (the CPU idles between chunks, so DVFS and the scheduler slow each chunk)
offline engines: final = decode time of the whole utterance after it ends (median), no partials.
"""
import argparse, json, statistics, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engines import ENGINES, W, resolve  # noqa: E402
from score import lc  # noqa: E402

P = W / "phone"


def load(p):
    rows = [json.loads(l) for l in open(p) if l.startswith("{")]
    return [r for r in rows if "id" in r], next((r for r in rows if "summary" in r), None)


_onsets = None


def onsets():
    """Speech onset (ms) per phone-set utterance from parakeet-0.6b-v2 token timestamps (computed once, cached)."""
    global _onsets
    if _onsets is not None:
        return _onsets
    cache = P / "speech_bounds.json"
    if cache.exists():
        _onsets = json.loads(cache.read_text())
        return _onsets
    import sherpa_onnx as so, wave, numpy as np
    f = resolve("parakeet-0.6b-v2")["files"]
    r = so.OfflineRecognizer.from_transducer(tokens=f["tokens"], encoder=f["encoder"], decoder=f["decoder"],
                                             joiner=f["joiner"], model_type="nemo_transducer", num_threads=4)
    paths = {}
    for s in ("ls-clean", "cv"):
        for m in map(json.loads, open(W / "data" / s / "manifest.jsonl")):
            paths[m["id"]] = W / "data" / s / m["wav"]
    ids = [Path(l.strip()).stem for l in open(P / "all.txt") if l.strip()]
    _onsets = {}
    for i in ids:
        with wave.open(str(paths[i])) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
        st = r.create_stream()
        st.accept_waveform(16000, x)
        r.decode_stream(st)
        ts = st.result.timestamps
        # [onset of the first word, approximate end of the last word (last token start + 150 ms)] in ms
        _onsets[i] = [ts[0] * 1000, ts[-1] * 1000 + 150] if ts else [0.0, len(x) / 16]
    cache.write_text(json.dumps(_onsets))
    return _onsets


def paced_metrics(utts):
    on = onsets()
    first, visible = [], []
    for u in utts:
        if u.get("first_partial_ms", -1) >= 0:
            first.append(u["first_partial_ms"] - on[u["id"]][0])
        end = on[u["id"]][1]
        final = lc(u["text"])
        t_vis = None
        for a in reversed(u.get("partials", [])):
            if lc(a[2]) == final:
                t_vis = a[1]
            else:
                break
        # (final_ms counts from the end of the audio file; shift it to the end of the last word)
        visible.append((t_vis - end) if t_vis is not None else u["final_ms"] + u["dur"] * 1000 - end)
    med = lambda v: statistics.median(v) if v else float("nan")
    return dict(first_ms=med(first), visible_ms=med(visible), visible_p90=sorted(visible)[int(.9 * len(visible))] if visible else float("nan"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default="")
    a = ap.parse_args()
    res = {}
    for p in sorted(P.glob("*.rtf.*.jsonl")):
        name, _, cfg = p.name[:-len(".jsonl")].partition(".rtf.")
        if name not in ENGINES:
            continue
        utts, s = load(p)
        if not utts or not s:
            continue
        key = f"{name}@{cfg}"
        r = res[key] = dict(engine=name, cfg=cfg, rtf=sum(u["decode_ms"] for u in utts) / 1e3 / sum(u["dur"] for u in utts),
                            cpu_per_s=s["cpu_decode_s"] / s["audio_s"] if "cpu_decode_s" in s else None,
                            rss_mb=s["maxrss_kb"] / 1024, load_ms=s["load_ms"],
                            final_ms=statistics.median(u["final_ms"] for u in utts))
        pp = P / f"{name}.paced.{cfg}.jsonl"
        if pp.exists():
            pu, ps = load(pp)
            if pu and ps:
                r.update(paced_metrics(pu))
                r["paced_rtf"] = sum(u["decode_ms"] for u in pu) / 1e3 / sum(u["dur"] for u in pu)
                r["paced_cpu_per_s"] = ps["cpu_decode_s"] / ps["audio_s"] if "cpu_decode_s" in ps else None
    for pp in sorted(P.glob("*.paced.*.big.jsonl")):  # big-core-pinned paced runs (no unpaced counterpart)
        name, _, cfg = pp.name[:-len(".jsonl")].partition(".paced.")
        pu, ps = load(pp)
        if name in ENGINES and pu and ps:
            r = res[f"{name}@{cfg}"] = dict(engine=name, cfg=cfg, rtf=float("nan"), cpu_per_s=None,
                                            rss_mb=ps["maxrss_kb"] / 1024, load_ms=ps["load_ms"], final_ms=float("nan"))
            r.update(paced_metrics(pu))
            r["paced_rtf"] = sum(u["decode_ms"] for u in pu) / 1e3 / sum(u["dur"] for u in pu)
            r["paced_cpu_per_s"] = ps["cpu_decode_s"] / ps["audio_s"]
    print(f"{'engine@cfg':34s} {'rtf':>6s} {'cpu/s':>6s} {'rss':>5s} {'load':>6s} {'final':>6s} {'first':>6s} {'visible':>7s} {'p-rtf':>6s}")
    for k, r in res.items():
        f = lambda x, fmt: (fmt % x) if isinstance(x, (int, float)) and x == x else "-"
        print(f"{k:34s} {f(r['rtf'], '%6.3f')} {f(r.get('cpu_per_s'), '%6.3f')} {f(r['rss_mb'], '%5.0f')} "
              f"{f(r['load_ms'], '%6.0f')} {f(r['final_ms'], '%6.0f')} {f(r.get('first_ms'), '%6.0f')} "
              f"{f(r.get('visible_ms'), '%7.0f')} {f(r.get('paced_rtf'), '%6.3f')}")
    if a.json:
        Path(a.json).write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
