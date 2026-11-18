"""Build the evaluation sets for docs/voice-eval.md from tools/voice/work/raw (see fetch_data.sh).

Each set becomes work/data/<set>/{wav/*.wav, manifest.jsonl}: 16 kHz mono 16-bit WAV plus one JSON line per
utterance: id, wav (relative to the set dir), dur (s), ref (the set's standard reference), ref_pc (punctuated and
cased reference when one exists, else null).

  ls-clean / ls-other  LibriSpeech test-clean / test-other, all utterances. ref = original upper-case transcript;
                       ref_pc = LibriSpeech-PC (NVIDIA, CC BY 4.0) where it exists (~92% of utterances).
  cv                   Common Voice 9 English test (ESB / Open ASR Leaderboard copy), 1500 sampled. Punctuated + cased.
  ami                  AMI IHM test, "cleaned" ESB segmentation, 1500 sampled. Conversational. Punctuated + cased.
  e22                  Earnings-22 test segments, 600 sampled. Spontaneous financial speech with many numbers.

Sampling is uniform with a fixed seed, so the sets are reproducible; sampled clips longer than 30 s are then dropped
(3 in cv, 4 in e22), since voice typing is short-form and Whisper-style models cap at 30 s.
  tools/voice/py tools/voice/prepare.py --filter-only   re-applies the 30 s filter to existing manifests
  tools/voice/py tools/voice/prepare.py
"""
import io, json, random, subprocess, sys, tarfile, wave
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

W = Path(__file__).resolve().parent / "work"
RAW, DATA = W / "raw", W / "data"
SR = 16000
MAX_S = 30.0


def write_wav(path: Path, x: np.ndarray):
    x = np.clip(x, -1.0, 1.0)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767.0).astype("<i2").tobytes())


def decode_bytes(b: bytes) -> np.ndarray:
    try:
        x, sr = sf.read(io.BytesIO(b), dtype="float32", always_2d=True)
        x = x.mean(axis=1)
        if sr == SR:
            return x
    except Exception:
        pass
    out = subprocess.run(["ffmpeg", "-v", "error", "-i", "pipe:0", "-ac", "1", "-ar", str(SR), "-f", "f32le", "pipe:1"],
                         input=b, capture_output=True, check=True).stdout
    return np.frombuffer(out, dtype="<f4")


def librispeech(name: str, subset: str):
    d = DATA / name
    (d / "wav").mkdir(parents=True, exist_ok=True)
    pc = {}
    if not (DATA / "ls" / f"{subset}.json").exists():
        (DATA / "ls").mkdir(parents=True, exist_ok=True)
        with tarfile.open(RAW / "librispeech" / "pc-manifests.tar.gz") as t:
            t.extractall(DATA / "ls", filter="data")
    for line in open(DATA / "ls" / f"{subset}.json"):
        j = json.loads(line)
        pc[Path(j["audio_filepath"]).stem] = j["text_raw"]
    rows, trans, audio = [], {}, {}
    with tarfile.open(RAW / "librispeech" / f"{subset}.tar.gz") as t:
        for m in t:
            if m.name.endswith(".trans.txt"):
                for line in t.extractfile(m).read().decode().splitlines():
                    k, v = line.split(" ", 1)
                    trans[k] = v
            elif m.name.endswith(".flac"):
                audio[Path(m.name).stem] = t.extractfile(m).read()
    for k in sorted(audio):
        x, sr = sf.read(io.BytesIO(audio[k]), dtype="float32")
        assert sr == SR
        write_wav(d / "wav" / f"{k}.wav", x)
        rows.append({"id": k, "wav": f"wav/{k}.wav", "dur": round(len(x) / SR, 3), "ref": trans[k], "ref_pc": pc.get(k)})
    return d, rows


def oal(name: str, prefix: str, n: int, seed: int = 0):
    d = DATA / name
    (d / "wav").mkdir(parents=True, exist_ok=True)
    files = sorted((RAW / "oal").glob(f"{prefix}-*.parquet"))
    meta = []
    for fi, f in enumerate(files):
        t = pq.read_table(f, columns=["id", "text", "audio_length_s"])
        for i in range(t.num_rows):
            meta.append((fi, i, t["id"][i].as_py(), t["text"][i].as_py(), t["audio_length_s"][i].as_py()))
    rng = random.Random(seed)
    pick = sorted(rng.sample(range(len(meta)), n))
    want = {}
    for p in pick:
        want.setdefault(meta[p][0], []).append(p)
    rows = []
    for fi, ps in want.items():
        t = pq.read_table(files[fi], columns=["audio"])
        for p in ps:
            _, i, uid, text, dur = meta[p]
            safe = f"{name}_{p:05d}"
            x = decode_bytes(t["audio"][i].as_py()["bytes"])
            write_wav(d / "wav" / f"{safe}.wav", x)
            rows.append({"id": safe, "src_id": uid, "wav": f"wav/{safe}.wav", "dur": round(len(x) / SR, 3), "ref": text, "ref_pc": text})
    rows.sort(key=lambda r: r["id"])
    print(f"{name}: {len(meta)} available, {n} sampled")
    return d, filter_long(rows)


def filter_long(rows, max_s=MAX_S):
    """Dictation is short-form: drop the few clips over 30 s (CV has some 50-100 s anomalies, mostly silence)."""
    return [r for r in rows if r["dur"] <= max_s]


def stab():
    """'stab': 200 LibriSpeech test-clean + 200 Common Voice utterances (random, fixed seed), manifest only (the wavs
    stay in their sets). Used for partial-result stability and word-emission latency of the streaming engines."""
    rows = []
    for s, n in (("ls-clean", 200), ("cv", 200)):
        man = [json.loads(l) for l in open(DATA / s / "manifest.jsonl")]
        for r in sorted(random.Random(7).sample(man, n), key=lambda r: r["id"]):
            rows.append(dict(r, wav=f"../{s}/{r['wav']}"))
    (DATA / "stab").mkdir(parents=True, exist_ok=True)
    (DATA / "stab" / "manifest.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
    print(f"stab: {len(rows)} utts, {sum(r['dur'] for r in rows) / 3600:.2f} h")


def main():
    if sys.argv[1:] == ["stab"]:
        return stab()
    if sys.argv[1:] == ["--filter-only"]:
        for s in ("cv", "ami", "e22"):
            p = DATA / s / "manifest.jsonl"
            rows = [json.loads(l) for l in open(p)]
            keep = filter_long(rows)
            p.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in keep))
            print(f"{s}: {len(rows)} -> {len(keep)}")
        return
    want = set(sys.argv[1:]) or {"ls-clean", "ls-other", "cv", "ami", "e22"}
    jobs = {
        "ls-clean": lambda: librispeech("ls-clean", "test-clean"),
        "ls-other": lambda: librispeech("ls-other", "test-other"),
        "cv": lambda: oal("cv", "cv", 1500),
        "ami": lambda: oal("ami", "ami", 1500),
        "e22": lambda: oal("e22", "e22", 600),
    }
    for k in jobs:
        if k not in want:
            continue
        d, rows = jobs[k]()
        with open(d / "manifest.jsonl", "w") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"{k}: {len(rows)} utts, {sum(r['dur'] for r in rows) / 3600:.2f} h")


if __name__ == "__main__":
    main()
