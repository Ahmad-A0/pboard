#!/usr/bin/env python3
"""Max difference (px) between the key hit boxes of two geometry files (999 if keys are missing).

Usage: geometry_delta.py <reference.json> <measured.json>
"""
import json
import sys


def main():
    a = {k["label"]: k for k in json.load(open(sys.argv[1]))["keys"]}
    b = {k["label"]: k for k in json.load(open(sys.argv[2]))["keys"]}
    if not set(a) <= set(b):
        print(999)
        return
    print("%g" % max(abs(u - v) for l in a for u, v in zip(a[l]["hit"], b[l]["hit"])))


if __name__ == "__main__":
    main()
