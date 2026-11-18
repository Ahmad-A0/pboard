"""Decode an evaluation set with one engine on the host (for WER and partial-stability analysis).

  tools/voice/py tools/voice/run_host.py ENGINE SET [--jobs N] [--partials] [--limit N] [--threads 1]

Writes work/hyp/<SET>/<ENGINE>.jsonl: one line per utterance with id, text, decode_ms and, for streaming engines with
--partials, the partial-result history [[audio_ms, text], ...] (only when the text changes) plus, for transducers, the
final token timestamps. Streaming engines are fed 100 ms chunks, as a microphone callback would deliver them, and
decoded after each chunk; that is exactly what the keyboard would do, so the final text is the streaming result.
"""
import argparse, json, os, subprocess, sys, tempfile, time, wave
from multiprocessing import Pool
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engines import W, resolve  # noqa: E402

CHUNK = 1600  # 100 ms
TAIL = 2.0  # s of silence after the utterance before input_finished: enough to flush every model's last chunk and right context (x-asr-480 / kroko lose words or final punctuation below ~1-2 s)


def load_wav(p):
    with wave.open(str(p)) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0


class SherpaOnline:
    def __init__(self, e, threads):
        import sherpa_onnx as so
        f = e["files"]
        self.r = so.OnlineRecognizer.from_transducer(tokens=f["tokens"], encoder=f["encoder"], decoder=f["decoder"],
                                                     joiner=f["joiner"], num_threads=threads,
                                                     decoding_method="greedy_search",
                                                     model_type=f.get("model_type", ""))
        self.lang = e.get("lang")  # multilingual NeMo (nemotron-3.5): language prompt; "auto" mis-detects accents

    def run(self, x, partials):
        r, s, hist, last = self.r, self.r.create_stream(), [], ""
        if self.lang:
            s.set_option("language", self.lang)
        t0 = time.perf_counter()
        for off in range(0, len(x), CHUNK):
            s.accept_waveform(16000, x[off:off + CHUNK])
            while r.is_ready(s):
                r.decode_stream(s)
            if partials:
                t = r.get_result(s)
                if t != last:
                    hist.append([round(min(off + CHUNK, len(x)) / 16), t])
                    last = t
        s.accept_waveform(16000, np.zeros(int(TAIL * 16000), dtype=np.float32))
        s.input_finished()
        while r.is_ready(s):
            r.decode_stream(s)
        res = r.get_result_all(s)
        out = dict(text=res.text, decode_ms=(time.perf_counter() - t0) * 1e3)
        if partials:
            out["partials"] = hist
            out["tokens"] = list(res.tokens)
            out["timestamps"] = [round(t, 3) for t in res.timestamps]
        return out


class SherpaOffline:
    def __init__(self, e, threads):
        import sherpa_onnx as so
        f, fam = e["files"], e["family"]
        if fam == "transducer":
            self.r = so.OfflineRecognizer.from_transducer(tokens=f["tokens"], encoder=f["encoder"], decoder=f["decoder"],
                                                          joiner=f["joiner"], num_threads=threads,
                                                          model_type=f.get("model_type", ""))
        elif fam == "nemo_ctc":
            self.r = so.OfflineRecognizer.from_nemo_ctc(model=f["model"], tokens=f["tokens"], num_threads=threads)
        elif fam == "moonshine_v2":
            self.r = so.OfflineRecognizer.from_moonshine_v2(encoder=f["encoder"], decoder=f["merged_decoder"],
                                                            tokens=f["tokens"], num_threads=threads)
        elif fam == "canary":
            self.r = so.OfflineRecognizer.from_nemo_canary(encoder=f["encoder"], decoder=f["decoder"], tokens=f["tokens"],
                                                           src_lang="en", tgt_lang="en", num_threads=threads)
        else:
            raise ValueError(fam)

    def run(self, x, partials):
        t0 = time.perf_counter()
        s = self.r.create_stream()
        s.accept_waveform(16000, x)
        self.r.decode_stream(s)
        res = s.result
        out = dict(text=res.text, decode_ms=(time.perf_counter() - t0) * 1e3)
        if partials:
            out["tokens"] = list(res.tokens)
            out["timestamps"] = [round(t, 3) for t in res.timestamps]
            if hasattr(res, "durations") and res.durations:
                out["durations"] = [round(t, 3) for t in res.durations]
        return out


class Moonshine:
    UPDATE = 0.5  # s of audio between partial updates (moonshine-voice's default update_interval)

    def __init__(self, e, threads):
        # libmoonshine otherwise sizes ONNX Runtime's thread pool to all cores in every worker process
        os.environ["MOONSHINE_ORT_SINGLE_THREAD"] = "1"
        from moonshine_voice import Transcriber, ModelArch
        self.t = Transcriber(e["files"]["dir"], ModelArch(e["arch"]))

    def run(self, x, partials):
        from moonshine_voice import Transcriber
        t0 = time.perf_counter()
        if not partials and os.environ.get("MOONSHINE_NONSTREAM"):
            # Whole-utterance decode (how Moonshine reports its own WER); used only after checking it matches the
            # streaming final text (see docs/voice-eval.md).
            tr = self.t.transcribe_without_streaming(x.tolist(), 16000)
            return dict(text=" ".join(l.text.strip() for l in tr.lines if l.text and l.text.strip()),
                        decode_ms=(time.perf_counter() - t0) * 1e3)
        st = self.t.create_stream()
        st.start()
        hist, last, since = [], "", 0
        text = lambda tr: " ".join(l.text.strip() for l in tr.lines if l.text and l.text.strip())
        for off in range(0, len(x), CHUNK):
            c = x[off:off + CHUNK]
            self.t._lib.moonshine_transcribe_add_audio_to_stream  # noqa (keep the C path explicit below)
            st_add(st, c)
            since += len(c)
            if since >= self.UPDATE * 16000:
                since = 0
                tr = st.update_transcription(Transcriber.MOONSHINE_FLAG_FORCE_UPDATE)
                t = text(tr)
                if partials and t != last:
                    hist.append([round(min(off + CHUNK, len(x)) / 16), t])
                last = t
        tr = st.stop()
        out = dict(text=text(tr) if tr else "", decode_ms=(time.perf_counter() - t0) * 1e3)
        st.close() if hasattr(st, "close") else None
        if partials:
            out["partials"] = hist
        return out


def st_add(st, c):
    """Add audio without the convenience auto-update (we control the update cadence)."""
    import ctypes
    arr = (ctypes.c_float * len(c))(*c.tolist())
    err = st._lib.moonshine_transcribe_add_audio_to_stream(st._transcriber._handle, st._handle, arr, len(c), 16000, 0)
    if err:
        raise RuntimeError(f"moonshine add_audio error {err}")


class Vosk:
    def __init__(self, e, threads):
        import vosk
        vosk.SetLogLevel(-1)
        self.vosk = vosk
        self.m = vosk.Model(e["files"]["dir"])

    def run(self, x, partials):
        t0 = time.perf_counter()
        r = self.vosk.KaldiRecognizer(self.m, 16000)
        pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
        segs, hist, last = [], [], ""
        for off in range(0, len(pcm), CHUNK * 2):
            if r.AcceptWaveform(pcm[off:off + CHUNK * 2]):
                t = json.loads(r.Result()).get("text", "")
                if t:
                    segs.append(t)
                cur = " ".join(segs)
            else:
                p = json.loads(r.PartialResult()).get("partial", "")
                cur = " ".join(segs + ([p] if p else []))
            if partials and cur != last:
                hist.append([round(min(off // 2 + CHUNK, len(x)) / 16), cur])
                last = cur
        t = json.loads(r.FinalResult()).get("text", "")
        if t:
            segs.append(t)
        out = dict(text=" ".join(segs), decode_ms=(time.perf_counter() - t0) * 1e3)
        if partials:
            out["partials"] = hist
        return out


def worker(args):
    name, shard, threads, partials, setdir = args
    e = resolve(name)
    k = e["kind"]
    if k == "whisper":
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write("\n".join(str(setdir / r["wav"]) for r in shard) + "\n")
        exe = W / "host" / "whisper_bench"
        cmd = [str(exe), "--model", e["files"]["model"], "--threads", str(threads), "--audio-ctx", e.get("actx", "0"), f.name]
        out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
        os.unlink(f.name)
        res = {}
        for line in out.splitlines():
            j = json.loads(line)
            if "id" in j:
                res[j["id"]] = dict(text=j["text"], decode_ms=j["decode_ms"])
        return [dict(id=r["id"], **res.get(r["id"], dict(text="", decode_ms=0, missing=True))) for r in shard]
    eng = {"sherpa-online": SherpaOnline, "sherpa-offline": SherpaOffline, "moonshine": Moonshine, "vosk": Vosk}[k](e, threads)
    res = []
    for r in shard:
        x = load_wav(setdir / r["wav"])
        res.append(dict(id=r["id"], **eng.run(x, partials)))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("engine")
    ap.add_argument("set")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--partials", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    setdir = W / "data" / a.set
    rows = [json.loads(l) for l in open(setdir / "manifest.jsonl")]
    if a.limit:
        rows = rows[:a.limit]
    # longest first, round-robin, so the shards finish together
    order = sorted(rows, key=lambda r: -r["dur"])
    n = min(a.jobs, len(order))
    shards = [order[i::n] for i in range(n)]
    t0 = time.time()
    with Pool(n) as p:
        parts = p.map(worker, [(a.engine, s, a.threads, a.partials, setdir) for s in shards])
    byid = {r["id"]: r for part in parts for r in part}
    out = Path(a.out) if a.out else W / "hyp" / a.set / f"{a.engine}{'.partials' if a.partials else ''}.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as f:
        for r in rows:
            f.write(json.dumps(byid[r["id"]], ensure_ascii=False) + "\n")
    print(f"{a.engine} {a.set}: {len(rows)} utts in {time.time() - t0:.0f}s with {n} jobs -> {out}")


if __name__ == "__main__":
    main()
