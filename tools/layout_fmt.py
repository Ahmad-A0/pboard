#!/usr/bin/env python3
"""Re-formats keyboard layout JSON (app/src/main/assets/layouts/*.json) compactly: objects are indented,
but arrays of scalars and key objects stay on one line, so a keyboard row reads as one line.

usage: layout_fmt.py <file.json>...
"""
import json
import sys


def flat(v):
    return json.dumps(v, ensure_ascii=False, separators=(", ", ": "))


def fmt(v, ind=0):
    pad = "  " * ind
    if isinstance(v, dict):
        if all(not isinstance(x, (dict, list)) or (isinstance(x, list) and all(not isinstance(y, (dict, list)) for y in x))
               for x in v.values()) and len(flat(v)) < 110:
            return flat(v)
        items = [f'{pad}  {json.dumps(k, ensure_ascii=False)}: {fmt(x, ind + 1)}' for k, x in v.items()]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(v, list):
        if all(not isinstance(x, (dict, list)) for x in v) or len(flat(v)) < 110:
            return flat(v)
        items = [f"{pad}  {fmt(x, ind + 1)}" for x in v]
        return "[\n" + ",\n".join(items) + "\n" + pad + "]"
    return json.dumps(v, ensure_ascii=False)


for path in sys.argv[1:]:
    data = json.load(open(path, encoding="utf-8"))
    open(path, "w", encoding="utf-8").write(fmt(data) + "\n")
