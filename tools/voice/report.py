"""Render the markdown tables of docs/voice-eval.md from the committed result files in tools/voice/results/.

  tools/voice/py tools/voice/score.py --json tools/voice/results/wer.json
  tools/voice/py tools/voice/phone_report.py --json tools/voice/results/phone.json
  tools/voice/py tools/voice/stability.py --json tools/voice/results/stab.json
  tools/voice/py tools/voice/report.py > /tmp/tables.md
"""
import json, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engines import ENGINES, size_bytes  # noqa: E402

R = Path(__file__).resolve().parent / "results"
SETS = ["ls-clean", "ls-other", "cv", "ami", "e22"]


def j(name):
    p = R / name
    return json.loads(p.read_text()) if p.exists() else {}


def f(x, fmt="%.1f", dash="–"):
    return dash if x is None or x != x else fmt % x


def main():
    wer, ph, st = j("wer.json"), j("phone.json"), j("stab.json")
    # phone config shown per engine: 1 thread (the energy-efficient setting) when measured, else the fastest
    best = {}
    for k, r in ph.items():
        e = r["engine"]
        if r["cfg"].endswith(".big"):
            continue
        if r["cfg"] == "t1" or e not in best or (best[e]["cfg"] != "t1" and r["rtf"] < best[e]["rtf"]):
            best[e] = r
    print("### Main comparison\n")
    print("| engine | params | size MB | licence | stream | P+C | WER clean | other | CV | AMI | E22 | raw WER CV / E22 | "
          "phone RTF | cpu/s | RSS MB | first partial ms | final / visible ms |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for e, meta in ENGINES.items():
        w = wer.get(e, {})
        if not any(w.get(s) for s in SETS) and e not in best:
            continue
        cells = [f(w.get(s, {}).get("norm") if w.get(s) else None, "%.2f") for s in SETS]
        rawc = f(w.get("cv", {}).get("raw") if w.get("cv") else None)
        rawe = f(w.get("e22", {}).get("raw") if w.get("e22") else None)
        b = best.get(e, {})
        try:
            size = size_bytes(e) / 1e6
        except Exception:
            size = None
        lat = b.get("visible_ms") if meta["streaming"] else b.get("final_ms")
        print(f"| {e} | {meta['params']} | {f(size, '%.0f')} | {meta['lic']} | {'yes' if meta['streaming'] else 'no'} | "
              f"{'yes' if meta['pc'] else 'no'} | " + " | ".join(cells) + f" | {rawc} / {rawe} | "
              f"{f(b.get('rtf'), '%.2f')}{' (' + b['cfg'] + ')' if b else ''} | {f(b.get('cpu_per_s'), '%.2f')} | "
              f"{f(b.get('rss_mb'), '%.0f')} | {f(b.get('first_ms'), '%.0f')} | {f(lat, '%.0f')} |")
    print("\n### WER detail (norm / lc / raw, %) and number formatting\n")
    print("| engine | " + " | ".join(SETS) + " | num CV / E22 |")
    print("|---|" + "---|" * (len(SETS) + 1))
    for e in sorted(wer):
        w = wer[e]
        cells = []
        for s in SETS:
            r = w.get(s)
            cells.append("–" if not r else f"{f(r['norm'], '%.2f')} / {f(r['lc'], '%.2f')} / {f(r['raw'], '%.2f')}")
        num = lambda s: f(w.get(s, {}).get("num") if w.get(s) else None, "%.0f")
        print(f"| {e} | " + " | ".join(cells) + f" | {num('cv')} / {num('e22')} |")
    print("\n### Phone detail\n")
    print("| engine@threads | rtf | cpu/s | RSS MB | load ms | final ms (unpaced) | first ms | visible ms | p90 | paced rtf |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for k in sorted(ph):
        r = ph[k]
        print(f"| {k} | {f(r['rtf'], '%.3f')} | {f(r.get('cpu_per_s'), '%.2f')} | {f(r['rss_mb'], '%.0f')} | "
              f"{f(r['load_ms'], '%.0f')} | {f(r['final_ms'], '%.0f')} | {f(r.get('first_ms'), '%.0f')} | "
              f"{f(r.get('visible_ms'), '%.0f')} | {f(r.get('visible_p90'), '%.0f')} | {f(r.get('paced_rtf'), '%.2f')} |")
    print("\n### Partial results (host, audio time)\n")
    print("| engine | first partial ms | word lag p50 / p90 ms | words only at final % | revisions / word | incl. case+punct |")
    print("|---|---|---|---|---|---|")
    for e, r in st.items():
        print(f"| {e} | {f(r['first_ms'], '%.0f')} | {f(r['lag50'], '%.0f')} / {f(r['lag90'], '%.0f')} | "
              f"{f(r['final_only'], '%.1f')} | {f(r['rev_per_word'], '%.3f')} | {f(r['rev_raw_per_word'], '%.3f')} |")


if __name__ == "__main__":
    main()
