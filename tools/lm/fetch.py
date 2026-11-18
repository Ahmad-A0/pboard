"""Download every source needed for a locale into tools/data/raw/<source>/ (idempotent, resumable).

Usage: tools/lm/run.sh fetch.py <locale> [source ...]
"""

import hashlib
import io
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import langs  # noqa: E402
from paths import RAW  # noqa: E402
from sources import sources_for  # noqa: E402

UA = {"User-Agent": "pboard-lm-pipeline/1.0"}


def download(url: str, dest: str) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    tmp = dest + ".part"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    for attempt in range(5):
        try:
            have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
            headers = dict(UA)
            if have:
                headers["Range"] = f"bytes={have}-"
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                mode = "ab" if have and r.status == 206 else "wb"
                with open(tmp, mode) as f:
                    while chunk := r.read(1 << 20):
                        f.write(chunk)
            os.replace(tmp, dest)
            return
        except Exception as e:  # network hiccup: retry with resume
            print(f"  retry {attempt + 1} for {url}: {e}", file=sys.stderr)
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"failed to download {url}")


class HttpRangeFile(io.RawIOBase):
    """Seekable read-only file over HTTP range requests (enough for pyarrow to read a parquet footer)."""

    def __init__(self, url):
        self.url, self.pos = url, 0
        req = urllib.request.Request(url, method="HEAD", headers=UA)
        with urllib.request.urlopen(req, timeout=60) as r:
            self.size = int(r.headers["content-length"])
            self.url = r.geturl()  # follow the CDN redirect once

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos

    def read(self, n=-1):
        if n < 0:
            n = self.size - self.pos
        n = min(n, self.size - self.pos)
        if n <= 0:
            return b""
        for attempt in range(5):
            try:
                hdr = dict(UA, Range=f"bytes={self.pos}-{self.pos + n - 1}")
                with urllib.request.urlopen(urllib.request.Request(self.url, headers=hdr), timeout=120) as r:
                    data = r.read()
                self.pos += len(data)
                return data
            except Exception as e:
                print(f"  range retry {attempt + 1}: {e}", file=sys.stderr)
                time.sleep(3 * (attempt + 1))
        raise RuntimeError(f"range read failed: {self.url}")

    def readinto(self, b):
        data = self.read(len(b))
        b[: len(data)] = data
        return len(data)


def partial_parquet(url: str, dest: str, max_bytes: int, columns=("id", "text")) -> None:
    """Copy the leading row groups of a remote parquet file (up to ~max_bytes compressed) to dest."""
    if os.path.exists(dest):
        return
    import pyarrow.parquet as pq
    src = pq.ParquetFile(HttpRangeFile(url))
    cols = [c for c in columns if c in src.schema_arrow.names]
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    writer, used = None, 0
    for i in range(src.num_row_groups):
        rg = src.metadata.row_group(i)
        if used and used + rg.total_byte_size > max_bytes * 3:  # total_byte_size is uncompressed
            break
        t = src.read_row_group(i, columns=cols)
        writer = writer or pq.ParquetWriter(dest + ".part", t.schema, compression="zstd")
        writer.write_table(t)
        used += rg.total_byte_size
        print(f"  row group {i + 1}/{src.num_row_groups} ({used / 1e6:.0f} MB uncompressed)", flush=True)
    writer.close()
    os.replace(dest + ".part", dest)


def hf_first_shard(spec: str) -> str:
    repo, _, sub = spec.partition(":")
    api = f"https://huggingface.co/api/datasets/{repo}/tree/main/{sub}"
    with urllib.request.urlopen(urllib.request.Request(api, headers=UA), timeout=60) as r:
        files = sorted(x["path"] for x in json.load(r) if x["path"].endswith(".parquet"))
    return f"https://huggingface.co/datasets/{repo}/resolve/main/{files[0]}"


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main(locale, names):
    L = langs.get(locale)
    manifest_path = os.path.join(RAW, "MANIFEST.json")
    manifest = json.load(open(manifest_path)) if os.path.exists(manifest_path) else {}
    for src in sources_for(L):
        if names and src.name not in names:
            continue
        for url, rel, kind in src.files(L):
            dest = os.path.join(RAW, src.name, rel)
            if not os.path.exists(dest):
                print(f"[fetch] {src.name}: {url}", flush=True)
            if kind == "file":
                download(url, dest)
            elif kind.startswith("partial-parquet:"):
                partial_parquet(url, dest, int(kind.split(":")[1]))
            elif kind == "hf-first-shard":  # first shard of a HF parquet dir, leading 450 MB
                if not os.path.exists(dest):
                    url = hf_first_shard(url)
                    partial_parquet(url, dest, 450_000_000)
            key = f"{src.name}/{rel}"
            if key not in manifest:
                manifest[key] = {"url": url, "bytes": os.path.getsize(dest), "sha256": sha256(dest)}
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=1, sort_keys=True)
    print(f"[fetch] {locale}: done")


if __name__ == "__main__":
    main(sys.argv[1], set(sys.argv[2:]))
