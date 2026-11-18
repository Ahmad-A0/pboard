#!/usr/bin/env python3
"""Check an IME's visual state signature in a screenshot (exit 1 on mismatch).

Usage: state_check.py <geometry.json> <screenshot.png>
The geometry's optional "stateCheck" lists probes {"name", "point": [x, y], "rgb": [r, g, b], "tol"}
and optional "unsyncedRgb" per probe. For Gboard it samples the ?123 and Enter keys: with Gboard's
its remote configuration synced both use the secondary-container colour; on compiled-in defaults (e.g. after
`pm clear` without a successful re-sync) ?123 is grey and Enter primary-container blue.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngio  # noqa: E402


def main():
    with open(sys.argv[1]) as f:
        geo = json.load(f)
    checks = geo.get("stateCheck") or []
    if not checks:
        print("== state check: none defined for %s" % geo.get("name"))
        return
    im = pngio.read_png(sys.argv[2])
    bad = []
    for c in checks:
        x, y = c["point"]
        got = im.px(int(x), int(y))
        ok = all(abs(g - e) <= c.get("tol", 12) for g, e in zip(got, c["rgb"]))
        print("== state check %s at %s: %s (expected %s) %s" % (c["name"], c["point"], got, c["rgb"], "ok" if ok else "MISMATCH"))
        if not ok:
            bad.append(c["name"])
    if bad:
        print("!! IME is not in its expected (synced) state: %s" % ", ".join(bad))
        sys.exit(1)


if __name__ == "__main__":
    main()
