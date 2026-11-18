"""On-device speed runs for docs/voice-eval.md (dev phone, headless, as shell binaries in /data/local/tmp/voice).

Run ONLY under the phone guard, inside the devshell (needs adb):
  nix develop . --command tools/phone/guard.sh --headless -- python3 -I tools/voice/phone.py ENGINE... [--threads 2]
      [--modes rtf,paced] [--cleanup]
  ... python3 -I tools/voice/phone.py --cleanup        # remove /data/local/tmp/voice entirely

Modes:
  rtf    unpaced decode of the phone set (30 utterances, ~2.6 min): RTF, CPU time, peak RSS, final latency.
  paced  8 utterances released on a real-time clock in 100 ms pieces (microphone simulation): first-partial time and
         final latency after the end of the audio, for streaming engines. Offline engines skip it.
Results: tools/voice/work/phone/<engine>.<mode>.t<threads>.jsonl (one JSON line per utterance + a summary line).
No input injection, no screen access.
"""
import argparse, json, os, random, subprocess, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engines import M, W, resolve  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "dev"))
import local_env  # noqa: E402

PHONE = os.environ.get("PHONE") or local_env.get("PBOARD_PHONE_SERIAL")  # PHONE is set by tools/phone/guard.sh
R = "/data/local/tmp/voice"
BIN = W / "android" / "bin"
OUT = W / "phone"


def adb(*a, check=True, capture=True):
    return subprocess.run(["adb", "-s", PHONE, *a], check=check, capture_output=capture, text=True).stdout


def sh(cmd, check=True):
    return adb("shell", cmd, check=check)


def push(local, remote):
    """Push unless the phone already has an identical file (md5)."""
    import hashlib
    local = Path(local)
    h = hashlib.md5()
    with open(local, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    have = sh(f"md5sum {remote} 2>/dev/null", check=False).split()
    if have and have[0] == h.hexdigest():
        return 0.0
    sh(f"mkdir -p {os.path.dirname(remote)}")
    t = time.time()
    adb("push", str(local), remote)
    return time.time() - t


def phone_set():
    """30 utterances: 20 LibriSpeech test-clean (3-15 s) + 10 Common Voice, fixed seed."""
    rows = []
    for s, n in (("ls-clean", 20), ("cv", 10)):
        man = [json.loads(l) for l in open(W / "data" / s / "manifest.jsonl")]
        man = [r for r in man if 3.0 <= r["dur"] <= 15.0]
        for r in random.Random(1).sample(man, n):
            rows.append((s, r))
    return rows


def setup():
    for f in sorted(BIN.rglob("*")):
        if f.is_file():
            push(f, f"{R}/bin/{f.relative_to(BIN)}")
    sh(f"chmod 755 {R}/bin/*_bench {R}/bin/ms/moonshine_bench")
    rows = phone_set()
    for s, r in rows:
        push(W / "data" / s / r["wav"], f"{R}/wav/{r['id']}.wav")
    lst = "\n".join(f"{R}/wav/{r['id']}.wav" for _, r in rows) + "\n"
    paced = "\n".join([f"{R}/wav/{r['id']}.wav" for s, r in rows if s == "ls-clean"][:8]) + "\n"
    for name, text in (("all.txt", lst), ("paced.txt", paced)):
        p = OUT / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        push(p, f"{R}/{name}")


def model_args(name):
    """Push the engine's files to the phone; return (binary, args) for its harness."""
    e = resolve(name)
    f, k, args = e["files"], e["kind"], []
    rem = lambda p: f"{R}/m/{name}/{Path(p).name}"
    for key, p in f.items():
        if key == "model_type":
            continue
        p = Path(p)
        if p.is_dir():
            for x in p.rglob("*"):
                if x.is_file():
                    push(x, f"{R}/m/{name}/{x.relative_to(p)}")
        else:
            push(p, rem(p))
    if k == "sherpa-online":
        args = ["--mode", "online", "--encoder", rem(f["encoder"]), "--decoder", rem(f["decoder"]),
                "--joiner", rem(f["joiner"]), "--tokens", rem(f["tokens"])]
        return "sherpa_bench", args
    if k == "sherpa-offline":
        fam = e["family"]
        args = ["--mode", "offline", "--tokens", rem(f["tokens"])]
        if fam == "transducer":
            args += ["--encoder", rem(f["encoder"]), "--decoder", rem(f["decoder"]), "--joiner", rem(f["joiner"])]
            if f.get("model_type"):
                args += ["--model-type", f["model_type"]]
        elif fam == "nemo_ctc":
            args += ["--nemo-ctc", rem(f["model"])]
        elif fam == "moonshine_v2":
            args += ["--moonshine-encoder", rem(f["encoder"]), "--moonshine-merged", rem(f["merged_decoder"])]
        elif fam == "canary":
            args += ["--canary-encoder", rem(f["encoder"]), "--canary-decoder", rem(f["decoder"])]
        else:
            raise ValueError(fam)
        return "sherpa_bench", args
    if k == "whisper":
        return "whisper_bench", ["--model", rem(f["model"]), "--audio-ctx", e.get("actx", "0")]
    if k == "vosk":
        return "vosk_bench", ["--model", f"{R}/m/{name}"]
    if k == "moonshine":
        return "ms/moonshine_bench", ["--model", f"{R}/m/{name}", "--arch", str(e["arch"])]
    raise ValueError(k)


def run(name, mode, threads, pin):
    e = resolve(name)
    if mode == "paced" and not e["streaming"]:
        return
    exe, args = model_args(name)
    if exe in ("sherpa_bench", "whisper_bench"):
        args += ["--threads", str(threads)]
    if mode == "paced":
        args += ["--paced"]
    lst = f"{R}/{'paced' if mode == 'paced' else 'all'}.txt"
    d = os.path.dirname(f"{R}/bin/{exe}")
    # pin=big: restrict to the two Cortex-A76 cores (cpu6-7), approximating a foreground (top-app) IME thread;
    # unpinned shell processes get migrated to the A55s at low frequency between paced chunks.
    pre = "MOONSHINE_ORT_SINGLE_THREAD=1 " if e["kind"] == "moonshine" and threads == 1 else ""
    # (otherwise libmoonshine sizes ORT's thread pool to the performance cores)
    pre += "taskset c0 " if pin == "big" else ""
    cmd = f"cd {d} && LD_LIBRARY_PATH={d} {pre}./{os.path.basename(exe)} {' '.join(args)} {lst}"
    t = time.time()
    out = sh(cmd, check=False)
    out_p = OUT / f"{name}.{mode}.t{threads}{'.big' if pin == 'big' else ''}.jsonl"
    out_p.write_text(out)
    lines = [json.loads(l) for l in out.splitlines() if l.startswith("{")]
    summ = [j for j in lines if "summary" in j]
    utts = [j for j in lines if "id" in j]
    if summ and utts:
        s = summ[0]
        med = lambda k: sorted(u[k] for u in utts)[len(utts) // 2] if k in utts[0] else float("nan")
        rtf = sum(u["decode_ms"] for u in utts) / 1e3 / sum(u["dur"] for u in utts)
        print(f"{name:28s} {mode:5s} t{threads}{'/big' if pin == 'big' else ''}: rtf={rtf:.3f} cpu/audio={s['cpu_decode_s'] / s['audio_s']:.3f} "
              f"rss={s['maxrss_kb'] / 1024:.0f}MB load={s['load_ms']:.0f}ms first_partial~{med('first_partial_ms'):.0f} "
              f"final~{med('final_ms'):.0f} ({time.time() - t:.0f}s)", flush=True)
    else:
        print(f"{name} {mode}: FAILED\n{out[-2000:]}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("engines", nargs="*")
    ap.add_argument("--threads", default="2")
    ap.add_argument("--modes", default="rtf,paced")
    ap.add_argument("--cleanup", action="store_true", help="remove /data/local/tmp/voice afterwards")
    ap.add_argument("--drop-models", action="store_true", help="delete each engine's model files after its run")
    ap.add_argument("--pin", default="none", choices=["none", "big"])
    a = ap.parse_args()
    if a.engines:
        setup()
        for name in a.engines:
            threaded = resolve(name)["kind"] in ("sherpa-online", "sherpa-offline", "whisper", "moonshine")
            for th in (a.threads.split(",") if threaded else ["1"]):
                for mode in a.modes.split(","):
                    run(name, mode, int(th), a.pin)
            if a.drop_models:
                sh(f"rm -rf {R}/m/{name}")
    if a.cleanup:
        sh(f"rm -rf {R}")
        print("cleaned", R)


if __name__ == "__main__":
    main()
