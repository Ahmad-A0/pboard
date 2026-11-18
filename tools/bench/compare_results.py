#!/usr/bin/env python3
"""Item-for-item comparison of two replay result files (e.g. unsharded vs sharded).

Usage: compare_results.py a/results.jsonl b/results.jsonl [--show N]
Compares the final field text per item id; prints counts, per-participant differences and the
first N differing items. Exit status 0 iff every common item is identical.
"""
import json
import sys


def load(p):
    return {r.get("id", r.get("i")): r for r in (json.loads(l) for l in open(p) if l.strip())}


def main():
    a, b = load(sys.argv[1]), load(sys.argv[2])
    show = int(sys.argv[sys.argv.index("--show") + 1]) if "--show" in sys.argv else 10
    common = [k for k in a if k in b]
    diff = [k for k in common if a[k]["after"] != b[k]["after"]]
    print("items: %d vs %d, common %d, identical %d, different %d" % (len(a), len(b), len(common), len(common) - len(diff), len(diff)))
    by = {}
    for k in common:
        p = a[k].get("participant", a[k].get("group", "?"))
        t = by.setdefault(p, [0, 0])
        t[0] += 1
        t[1] += a[k]["after"] != b[k]["after"]
    print("per participant/group (different/total): " + ", ".join("%s %d/%d" % (p, v[1], v[0]) for p, v in by.items()))
    for k in diff[:show]:
        print("  %s target=%r: %r vs %r (groupValid %s/%s)" % (k, a[k]["target"], a[k]["after"][-30:], b[k]["after"][-30:],
                                                            a[k].get("groupValid"), b[k].get("groupValid")))
    sys.exit(0 if not diff else 1)


if __name__ == "__main__":
    main()
