#!/usr/bin/env python3
"""Changelog from the merges into main (one "Merge: <description>" commit per change).

  tools/release/changelog.py [--rev REV=HEAD] [--since TAG | --all] [--title TITLE]

Walks REV's first-parent history (main's own line), takes the merge commits, and groups them under the release tags
(vX.Y.Z) that contain them, newest first. --since TAG prints only what is new after TAG (the notes of one release);
by default that is the previous v* tag before REV, or everything if there is none. --all prints every release.
Merges of main back into a branch ("Merge branch 'main' into ...") and bare checkpoint merges are skipped.
"""
import argparse
import re
import subprocess
import sys


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def tags_by_commit():
    out = {}
    for line in git("tag", "--list", "v[0-9]*", "--format=%(objectname)^{commit} %(refname:short)").splitlines():
        obj, name = line.split(" ", 1)
        try:
            sha = git("rev-parse", obj).strip()
        except subprocess.CalledProcessError:
            continue
        out.setdefault(sha, []).append(name)
    return out


# merges of main back into a branch, and early description-less checkpoint merges
SKIP = re.compile(r"^Merge (remote-tracking )?branch '[^']+'( of \S+)? into |^Merge checkpoint [0-9a-f]+$")


def entry(subject):
    return re.sub(r"^Merge:?\s*", "", subject).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rev", default="HEAD")
    ap.add_argument("--since")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--title", default="pboard changelog")
    a = ap.parse_args()

    rev = git("rev-parse", a.rev).strip()
    since = a.since
    if since is None and not a.all:
        try:
            since = git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", rev + "^").strip()
        except subprocess.CalledProcessError:
            since = None  # no earlier release: everything
    rng = [rev] if (a.all or not since) else [f"{since}..{rev}"]

    tags = tags_by_commit()
    sections = []  # (title, date, [lines])
    head_tag = tags.get(rev)
    current = [head_tag[0] if head_tag else "Unreleased", None, []]
    sections.append(current)
    log = git("log", "--first-parent", "--format=%H%x09%ad%x09%P%x09%s", "--date=short", *rng)
    for line in log.splitlines():
        sha, date, parents, subject = line.split("\t", 3)
        if sha in tags and sha != rev:
            current = [tags[sha][0], date, []]
            sections.append(current)
        if current[1] is None:
            current[1] = date
        if len(parents.split()) < 2 or SKIP.match(subject):
            continue
        current[2].append(f"- {entry(subject)} ({sha[:9]}, {date})")

    out = [f"# {a.title}", ""]
    if since and not a.all:
        out += [f"Changes since {since}.", ""]
    for title, date, lines in sections:
        if not lines and title == "Unreleased":
            continue
        out.append(f"## {title}" + (f" ({date})" if date else ""))
        out.append("")
        out += lines or ["- (no merged changes)"]
        out.append("")
    sys.stdout.write("\n".join(out))


if __name__ == "__main__":
    main()
