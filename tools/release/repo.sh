#!/usr/bin/env bash
# Self-hosted update repository: a static directory any web server can serve, readable by Obtainium (FOSS updater,
# "HTML" source) or a browser. pboard itself never touches the network; the phone's updater app fetches from here.
#
#   tools/release/repo.sh <release dir from tools/release.sh> [REPO_DIR=dist/repo]   add a release, rebuild the index
#   tools/release/repo.sh --index [REPO_DIR=dist/repo]                                 only rebuild the index
#
# Layout: pboard-<versionCode, 6 digits>-<versionName>.apk (+ .sha256, .md release notes), pboard-latest.apk (copy of
# the newest), index.html (newest first: links, sizes, SHA-256, signer; plus a "Packs" section when packs/ exists,
# see tools/release/packs.sh), index.json (machine-readable), CHANGELOG.md.
# Only release-signed APKs are accepted (a debug-signed APK could not update the installed app anyway).
# Serve it e.g. with tools/release/serve_repo.sh, or copy the directory to any static host (docs/release.md).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "${1:-}" = --index ]; then REL=""; REPO="${2:-$ROOT/dist/repo}"
else REL="${1:?usage: repo.sh <release dir> [repo dir]}"; REPO="${2:-$ROOT/dist/repo}"; fi
mkdir -p "$REPO"
if [ -n "$REL" ]; then
  meta="$REL/release.json"; [ -f "$meta" ] || { echo "no $meta" >&2; exit 2; }
  python3 -I - "$REL" "$REPO" <<'EOF'
import json, os, shutil, sys
rel, repo = sys.argv[1], sys.argv[2]
m = json.load(open(os.path.join(rel, "release.json")))
if m["debugSigned"]:
    sys.exit("refusing: %s is debug-signed" % m["apk"])
name = "pboard-%06d-%s" % (m["versionCode"], m["versionName"])
for f in os.listdir(repo):  # one APK per versionCode
    if f.startswith("pboard-%06d-" % m["versionCode"]) and not f.startswith(name + "."):
        sys.exit("refusing: versionCode %d already published as %s" % (m["versionCode"], f))
shutil.copyfile(os.path.join(rel, m["apk"]), os.path.join(repo, name + ".apk"))
with open(os.path.join(repo, name + ".apk.sha256"), "w") as f:
    f.write("%s  %s.apk\n" % (m["sha256"], name))
shutil.copyfile(os.path.join(rel, "RELEASE_NOTES.md"), os.path.join(repo, name + ".md"))
shutil.copyfile(os.path.join(rel, "CHANGELOG.md"), os.path.join(repo, "CHANGELOG.md"))
json.dump(m, open(os.path.join(repo, name + ".json"), "w"), indent=2)
print("== added %s.apk to %s" % (name, repo))
EOF
fi
python3 -I - "$REPO" <<'EOF'
import html, json, os, shutil, sys
repo = sys.argv[1]
rels = sorted((json.load(open(os.path.join(repo, f))) | {"file": f[:-5] + ".apk"}
               for f in os.listdir(repo) if f.startswith("pboard-") and f.endswith(".json")),
              key=lambda m: m["versionCode"], reverse=True)
if rels:
    shutil.copyfile(os.path.join(repo, rels[0]["file"]), os.path.join(repo, "pboard-latest.apk"))
json.dump({"app": "dev.pboard", "releases": [{k: m[k] for k in ("versionName", "versionCode", "file", "sha256",
           "sizeBytes", "signerSha256", "commit")} for m in rels]}, open(os.path.join(repo, "index.json"), "w"), indent=2)
rows = "\n".join(
    '<li><a href="%s">%s</a> <span>versionCode %d, %.1f MB, %s</span><br><code>SHA-256 %s</code> '
    '<a href="%s">notes</a></li>' % (html.escape(m["file"]), html.escape(m["file"]), m["versionCode"],
                                      m["sizeBytes"] / 1e6, html.escape(m["commit"][:10]), m["sha256"],
                                      html.escape(m["file"][:-4] + ".md")) for m in rels)
signer = rels[0]["signerSha256"] if rels else "-"
# Packs (tools/release/packs.sh): optional add-on APKs listed in packs/index.json
packs_json = os.path.join(repo, "packs", "index.json")
packs = json.load(open(packs_json))["packs"] if os.path.isfile(packs_json) else []
pack_rows = "\n".join('<li><a href="packs/%s">%s</a> <span>%.1f MB</span><br>%s</li>' % (
    html.escape(p["file"]), html.escape(p["file"]), p["sizeBytes"] / 1e6, html.escape(p["about"])) for p in packs)
packs_html = ('<h2>Packs</h2>\n<p>Optional add-ons (voice typing, scan text, translation), signed by the same key: '
              '<a href="packs/">all packs</a></p>\n<ul>\n%s\n</ul>\n' % pack_rows) if packs else ""
open(os.path.join(repo, "index.html"), "w").write("""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>pboard releases</title>
<style>body{font:15px/1.5 system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem}
li{margin:.6rem 0}code{font-size:12px;word-break:break-all}span{color:#666}</style></head><body>
<h1>pboard releases</h1>
<p>Newest first. Every APK is signed by the pboard release key, certificate SHA-256<br><code>%s</code></p>
<ol>
%s
</ol>
%s<p><a href="CHANGELOG.md">Full changelog</a> &middot; <a href="index.json">index.json</a></p>
</body></html>
""" % (signer, rows, packs_html))
print("== %s: %d release(s), latest %s" % (repo, len(rels), rels[0]["versionName"] if rels else "-"))
EOF
