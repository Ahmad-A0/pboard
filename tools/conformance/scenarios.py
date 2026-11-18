#!/usr/bin/env python3
"""Conformance scenarios: identical, fully specified input sequences for both keyboards.

Sources:
  qol    executable versions of the P0/P1 quality-of-life behaviours
  human  real tap sequences (tap_tsi / tap_vtap TUNE splits), one sentence per scenario, either one
         observed step per keystroke (real holds) or one real-time burst per word (real timing)
  fuzz   seeded random edit sequences (boundary taps, space, punctuation, backspace taps and holds,
         spacebar cursor drags, taps into the text, shift, symbols page, glides, field variants)
  calib  keyboard-state references (letters / shift / caps lock / symbols / emoji / field layouts)

  python3 -I tools/conformance/scenarios.py --mode quick|full --out scenarios.jsonl [--seed N]

Every step carries "m" (metadata for the analysis: what was tapped, the literal key under a tap and
how deep inside it, ...); the device runner ignores it. Coordinates are screen pixels of
tools/bench/geometry/gboard_pixel8.json, which pboard's layout matches.
"""
import argparse
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import Geometry, HUMAN, read_jsonl, write_jsonl  # noqa: E402

G = Geometry()
KW = G.key_width
ROW_Y = [1715.5, 1870.0, 2025.0, 2180.5]

# Symbols page 1 (Gboard 15.1 en-US; pboard mirrors it). Row 1 digits, row 2 symbols, row 3 starts with =\<.
SYMBOLS = {}
for i, c in enumerate("1234567890"):
    SYMBOLS[c] = (58.5 + 107 * i, ROW_Y[0])
for i, c in enumerate("@#$_&-+()/"):
    SYMBOLS[c] = (58.5 + 107 * i, ROW_Y[1])
for i, c in enumerate("*\"':;!?"):
    SYMBOLS[c] = (218.5 + 107 * i, ROW_Y[2])
SYMBOLS["more"] = (85.0, ROW_Y[2])        # =\< (symbols page 2)
SYMBOLS["abc"] = (85.0, ROW_Y[3])
SYMBOLS[","] = (218.5, ROW_Y[3])
SYMBOLS["."] = (860.5, ROW_Y[3])
SYMBOLS["space"] = (593.0, ROW_Y[3])
EMOJI_FIRST = (72.0, 1432.0)                # first emoji of the emoji panel's first section
EMOJI_STEP = 115.5
EMOJI_ABC = (72.0, 2197.0)


class S:
    """Scenario builder."""

    def __init__(self, sid, variant="default", text="", src="qol", item=None, **field):
        self.id = sid
        self.field = {"variant": variant, "text": text}
        self.field.update(field)
        self.steps = []
        self.src = src
        self.item = item

    # Step gaps: the long default (runner gapMs, 1.2 s) only where a time window of the keyboard could join two
    # steps (space after space: double-space period ~1 s on Gboard; shift after shift: double-tap ~0.3 s; the same
    # key twice; delete after delete), after the app restarts input (taps right after it can be dropped), and for
    # the first step; elsewhere a short gap that still lets the keyboard go idle.
    SHORT_GAP = 330

    @staticmethod
    def _ends(st):
        m = st.get("m") or {}
        k = m.get("k")
        if st["op"] != "gesture":
            return (st["op"], st["op"])
        if k == "burst":
            ts = m.get("taps") or [{}]
            return (ts[0].get("key"), ts[-1].get("key"))
        if k == "glide":
            w = m.get("word") or "?"
            return (w[0], w[-1])
        key = ("sym:" + m["sym"]) if m.get("page") == "symbols" and m.get("sym") else m.get("key")
        return (key, key)

    def gaps(self):
        prev = None
        for i, st in enumerate(self.steps):
            first, last = self._ends(st)
            if i > 0 and "gap" not in st and prev is not None:
                window = prev[1] == first or prev[1] in ("app", "wait") or {prev[1], first} <= {"space"} \
                    or {prev[1], first} <= {"shift"} or {prev[1], first} <= {"delete"}
                if not window:
                    st["gap"] = self.SHORT_GAP
            prev = (first, last)

    def to_json(self):
        self.gaps()
        d = {"id": self.id, "src": self.src, "field": self.field, "steps": self.steps}
        if self.item:
            d["item"] = self.item
        return d

    # ---- low level
    def gesture(self, strokes, m, **kw):
        st = {"op": "gesture", "strokes": [[[round(x, 1), round(y, 1), round(t, 1)] for x, y, t in s] for s in strokes], "m": m}
        st.update(kw)
        self.steps.append(st)
        return self

    def tapxy(self, x, y, hold=60, m=None, **kw):
        key, depth, nb = G.depth(x, y)
        mm = {"k": "tap", "key": key, "depth": depth, "nb": nb, "x": round(x, 1), "y": round(y, 1)}
        if m:
            mm.update(m)
        return self.gesture([[(x, y, 0), (x, y, hold)]], mm, **kw)

    # ---- keys
    def key(self, name, dx=0.0, dy=0.0, hold=60, **kw):
        """Tap a key of the letters page; dx/dy in key widths from its centre."""
        x, y = G.centre(name)
        return self.tapxy(x + dx * KW, y + dy * KW, hold, **kw)

    def sym(self, name, hold=60, m=None, **kw):
        x, y = SYMBOLS[name]
        mm = {"page": "symbols", "sym": name}
        mm.update(m or {})
        return self.tapxy(x, y, hold, m=mm, **kw)

    def word(self, w, hold=60, **kw):
        for c in w:
            if c == " ":
                self.key("space", hold=hold, **kw)
            elif c in ".,":
                self.key(c, hold=hold, **kw)
            else:
                self.key(c.lower(), hold=hold, **kw)
        return self

    def space(self, **kw):
        return self.key("space", **kw)

    def delete(self, **kw):
        return self.key("delete", **kw)

    def shift(self, **kw):
        return self.key("shift", **kw)

    def symbols(self, **kw):
        return self.key("symbols", **kw)

    def hold(self, name, ms, page=None, **kw):
        x, y = SYMBOLS[name] if page == "symbols" else G.centre(name)
        return self.gesture([[(x, y, 0), (x, y, ms)]], {"k": "hold", "key": name, "ms": ms}, **kw)

    def slot(self, which="centre", hold=60, **kw):
        s = {"centre": G.slots[0], "left": G.slots[1], "right": G.slots[2]}[which]
        return self.gesture([[(s["cx"], s["cy"], 0), (s["cx"], s["cy"], hold)]], {"k": "slot", "slot": which}, **kw)

    def burst(self, taps, m=None, **kw):
        """Real-time taps: [(x, y, tDown, hold), ...] in one step."""
        metas = []
        for x, y, t, h in taps:
            key, depth, nb = G.depth(x, y)
            metas.append({"key": key, "depth": depth, "nb": nb, "t": t})
        mm = {"k": "burst", "taps": metas}
        if m:
            mm.update(m)
        return self.gesture([[(x, y, t), (x, y, t + h)] for x, y, t, h in taps], mm, **kw)

    def burst_keys(self, names, iki=150, hold=60, **kw):
        taps = []
        for i, n in enumerate(names):
            x, y = SYMBOLS[n[4:]] if n.startswith("sym:") else G.centre(n)
            taps.append((x, y, i * iki, hold))
        return self.burst(taps, **kw)

    def drag(self, x0, y0, x1, y1, ms, pre_ms=0, m=None, **kw):
        """Press at (x0,y0), stay pre_ms, move linearly to (x1,y1) over ms, release."""
        pts = [(x0, y0, 0)]
        if pre_ms:
            pts.append((x0, y0, pre_ms))
        n = max(2, int(ms / 16))
        for i in range(1, n + 1):
            f = i / n
            pts.append((x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, pre_ms + ms * f))
        pts.append((x1, y1, pre_ms + ms + 16))
        key, _, _ = G.depth(x0, y0)
        mm = {"k": "drag", "key": key, "dx": round(x1 - x0), "dy": round(y1 - y0), "ms": ms}
        if m:
            mm.update(m)
        return self.gesture([pts], mm, **kw)

    def glide(self, w, speed=1.1, hz=60, **kw):
        """A clean glide through the letters' key centres (speed px/ms, sampled at hz)."""
        cs = [G.centre(c.lower()) for c in w]
        pts = [(cs[0][0], cs[0][1], 0.0)]
        t = 30.0  # short dwell on the first key
        pts.append((cs[0][0], cs[0][1], t))
        dt = 1000.0 / hz
        for (xa, ya), (xb, yb) in zip(cs, cs[1:]):
            d = math.hypot(xb - xa, yb - ya)
            if d < 1:  # double letter: small loop
                for k in range(1, 5):
                    a = k / 4 * 2 * math.pi
                    t += dt
                    pts.append((xa + 18 * math.sin(a), ya - 18 * (1 - math.cos(a)), t))
                continue
            n = max(2, int(d / speed / dt))
            for k in range(1, n + 1):
                f = k / n
                t += d / speed / n
                pts.append((xa + (xb - xa) * f, ya + (yb - ya) * f, t))
        t += 30
        pts.append((cs[-1][0], cs[-1][1], t))
        return self.gesture([pts], {"k": "glide", "word": w}, **kw)

    # ---- non-key
    def fieldtap(self, offset, **kw):
        st = {"op": "fieldtap", "offset": offset, "m": {"k": "fieldtap", "offset": offset}}
        st.update(kw)
        self.steps.append(st)
        return self

    def app(self, cmd, **extras):
        st = {"op": "app", "cmd": cmd, "m": {"k": "app", "cmd": cmd}}
        st.update(extras)
        self.steps.append(st)
        return self

    def wait(self, ms):
        self.steps.append({"op": "wait", "ms": ms, "m": {"k": "wait"}})
        return self


# ------------------------------------------------------------------ calibration

def calib():
    """Keyboard-state references; step labels in m.label (the analysis learns pixel signatures from them)."""
    out = []
    # geometry check first: key centres must type exactly these letters (the runner stops otherwise)
    k = S("calib/keys", "nocap", src="calib")
    for c in "qpalzm":
        k.key(c)
    k_json = k.to_json
    k.to_json = lambda: dict(k_json(), expectText="qpalzm")
    out.append(k)
    s = S("calib/letters", "nocap", src="calib")
    s.steps.append({"op": "wait", "ms": 200, "m": {"k": "wait", "label": "letters/off"}})
    s.shift(m={"label": "letters/on"})
    s.shift(m={"label": "letters/off"})
    s.burst_keys(["shift", "shift"], iki=150, m={"label": "letters/caps"})
    s.shift(m={"label": "letters/off"})
    s.symbols(m={"label": "symbols"})
    s.sym("more", m={"label": "symbols2"})
    s.sym("more", m={"label": "symbols"})
    s.sym("abc", m={"label": "letters/off"})
    s.key("emoji", m={"label": "emoji"})
    s.tapxy(*EMOJI_ABC, m={"label": "letters/off"})
    out.append(s)
    s = S("calib/auto", "default", src="calib")
    s.steps.append({"op": "wait", "ms": 200, "m": {"k": "wait", "label": "letters/on"}})
    out.append(s)
    for name, field in [("email", {"variant": "email"}), ("uri", {"variant": "uri"}),
                        ("password", {"variant": "plain", "inputType": 129}),
                        ("number", {"variant": "plain", "inputType": 2}),
                        ("phone", {"variant": "plain", "inputType": 3})]:
        s = S("calib/" + name, src="calib", **field)
        s.steps.append({"op": "wait", "ms": 200, "m": {"k": "wait", "label": "field/" + name}})
        out.append(s)
    return out


# ------------------------------------------------------------------ qol items

def qol():
    """The P0/P1 quality-of-life behaviours as scenarios (item id in "item")."""
    L = []

    def s(item, name, variant="default", text="", **field):
        x = S("qol/%s/%s" % (item, name), variant, text, src="qol", item=item, **field)
        L.append(x)
        return x

    # TP: typing and punctuation
    s("TP-01", "glides").glide("hello").glide("world").key(".")
    s("TP-01", "glide-then-letter", "nocap").glide("to").key("a")
    s("TP-01", "glide-after-space", "nocap", "hi ").glide("to")
    s("TP-02", "pick-centre", "nocap").word("hel").slot("centre")
    s("TP-02", "pick-inside-word", "nocap", "ab cd").fieldtap(1).slot("centre")
    s("TP-02", "pick-prediction", "nocap", "i am ").slot("centre")
    s("TP-03", "typed-space-period", "nocap").word("hello ").key(".")
    s("TP-03", "pick-then-period", "nocap").word("hel").slot("centre").key(".")
    s("TP-03", "pick-then-comma-question", "nocap").word("hel").slot("centre").key(",").symbols().sym("?")
    s("TP-03", "glide-then-comma", "nocap").glide("hello").key(",")
    s("TP-04", "double-space", "default").word("hi").burst_keys(["space", "space"], iki=200)
    s("TP-04", "double-space-950", "default").word("hi").burst_keys(["space", "space"], iki=950)
    s("TP-04", "double-space-1100", "default").word("hi").burst_keys(["space", "space"], iki=1100)
    s("TP-04", "after-period", "nocap").word("hi.").burst_keys(["space", "space"], iki=200)
    s("TP-04", "email", "email").word("hi").burst_keys(["space", "space"], iki=200)
    s("TP-04", "then-letter", "default").word("hi").burst_keys(["space", "space"], iki=200).key("t")
    s("TP-05", "empty").key("h")
    s("TP-05", "after-period", text="Hi. ").key("t")
    s("TP-05", "after-question", text="Hi? ").key("t")
    s("TP-05", "after-comma", text="Hi, ").key("t")
    s("TP-05", "after-newline", text="a\n").key("b")
    s("TP-05", "nocap", "nocap").key("h")
    s("TP-05", "abbreviation", text="see e.g. ").key("t")
    s("TP-05", "shift-off-by-hand").shift().key("h")
    s("TP-06", "cap-words", "plain", inputType=8193).word("ann lee ")
    s("TP-06", "cap-characters", "plain", inputType=4097).word("ab").symbols().sym("1").sym("2")
    s("TP-06", "cap-words-becuase", "plain", inputType=8193).word("becuase ")
    s("TP-07", "one-shot", "nocap").shift().word("ab")
    s("TP-07", "double-tap-lock", "nocap").burst_keys(["shift", "shift"], iki=150).word("ab").shift(m={"cleanup": True})
    s("TP-07", "double-tap-400", "nocap").burst_keys(["shift", "shift"], iki=400).word("ab")
    # Gboard 18.6 measures the double tap from the first tap's release to the second touch-down (< ~290 ms locks)
    s("TP-07", "double-tap-release-gap-250", "nocap").burst_keys(["shift", "shift"], iki=310, hold=60).word("ab").shift(m={"cleanup": True})
    s("TP-07", "double-tap-release-gap-330", "nocap").burst_keys(["shift", "shift"], iki=390, hold=60).word("ab")
    s("TP-07", "long-press-1300", "nocap").hold("shift", 1300).word("ab").shift(m={"cleanup": True})
    s("TP-07", "long-press-600", "nocap").hold("shift", 600).word("ab")
    s("TP-07", "lock-then-tap-off", "nocap").burst_keys(["shift", "shift"], iki=150).shift().word("ab")
    s("TP-07", "slide-shift-to-z", "nocap").drag(85, 2025, 218.5, 2025, 200, pre_ms=80).key("x")
    s("TP-07", "chord", "nocap").gesture(
        [[(85, 2025, 0), (85, 2025, 600)], [(*G.centre("a"), 150), (*G.centre("a"), 210)],
         [(*G.centre("b"), 350), (*G.centre("b"), 410)]], {"k": "chord"}).key("c")
    s("TP-07", "shift-after-autocap").shift().key("h")
    for typed in ["i", "im", "ive", "dont", "didnt", "youre", "wont", "lets", "thats", "its", "were", "ill",
                  "cant", "well", "id", "hell"]:
        s("TP-08", typed, "nocap").word(typed + " ")
    s("TP-09", "well-apostrophe").word("we").symbols().sym("'").sym("abc").word("ll ")
    s("TP-10", "open-paren").symbols().sym("(").space()
    s("TP-11", "emoji-panel", "nocap").word("hi").key("emoji").tapxy(*EMOJI_FIRST).tapxy(EMOJI_FIRST[0] + EMOJI_STEP, EMOJI_FIRST[1]).tapxy(*EMOJI_ABC).word("yo")
    s("TP-12", "decimal", "nocap").symbols().sym("3").sym(".").sym("5").space()
    s("TP-12", "time", "nocap").symbols().sym("1").sym("0").sym(":").sym("3").sym("0").space()
    s("TP-13", "interior-period", "default").word("hi.there ")
    s("TP-14", "composing", "nocap").word("becuase")
    s("TP-15", "fast-space", "nocap").burst_keys(list("becuase") + ["space"], iki=130)
    s("TP-15", "very-fast-space", "nocap").burst([(*G.centre(c), i * 130, 50) for i, c in enumerate("becuase")] +
                                                   [(*G.centre("space"), 6 * 130 + 30, 50)])
    s("TP-16", "hold-delete-1400", "nocap", "a" * 120).hold("delete", 1400)
    # Gboard 18.6 deletes on release (not touch-down): a 250 ms hold shows when the text changes
    s("TP-16", "delete-hold-250", "nocap", "hello").hold("delete", 250)
    s("TP-16", "hold-delete-3000", "nocap", "word " * 30).hold("delete", 3000)
    s("TP-17", "delete-empty", "nocap").delete()
    # CO: correction
    s("CO-01", "becuase", "nocap").word("becuase ")
    s("CO-01", "teh", "nocap").word("teh ")
    s("CO-01", "helo", "nocap").word("helo ")
    s("CO-01", "becuase-period", "nocap").word("becuase.")
    s("CO-01", "becuase-enter", "nocap").word("becuase").key("enter")
    s("CO-02", "revert", "nocap").word("becuase ").delete().space()
    s("CO-03", "keep-literal", "nocap").word("becuase").slot("left")
    s("CO-04", "tap-into-word", "nocap", "I went their yesterday").fieldtap(10).slot("right")
    s("CO-04", "tap-end-of-word", "nocap", "I went their yesterday").fieldtap(12)
    s("CO-05", "post-correction", "nocap").word("i lime to eat pizza ")
    s("CO-06", "glide-delete", "default").glide("hello").glide("world").delete().delete()
    s("CO-06", "glide-delete-prefilled", "nocap", "hi ").glide("to").delete().delete()
    s("CO-07", "learn", "nocap").word("zorbix ").word("zor")
    s("CO-09", "london", "nocap").word("london ")
    s("CO-09", "capitalised-name", "default", "I met ").shift().word("jhon ")
    # ED: editing
    s("ED-01", "drag-234", "nocap", "abcdefghijklmnopqrst").drag(593, 2180.5, 359, 2180.5, 600)
    s("ED-01", "drag-right", "nocap", "abcdefghijklmnopqrst").fieldtap(5).drag(400, 2180.5, 700, 2180.5, 600)
    s("ED-01", "flick-150", "nocap", "abcdefghij").drag(593, 2180.5, 359, 2180.5, 150)
    s("ED-01", "drag-after-typing", "nocap").word("hello wor").drag(593, 2180.5, 359, 2180.5, 600)
    s("ED-02", "delete-swipe-154", "nocap", "one two three four").drag(994, 2025, 840, 2025, 700)
    s("ED-02", "delete-swipe-294", "nocap", "one two three four").drag(994, 2025, 700, 2025, 900)
    s("ED-02", "delete-inside-key", "nocap", "one two three four").drag(994, 2025, 960, 2025, 700)
    s("ED-03", "shift-cycles-case", "nocap", "hello world").app("setsel", selStart=0, selEnd=11).shift().shift().shift().shift()
    s("ED-09", "delete-selection", "nocap", "hello world").app("setsel", selStart=6, selEnd=11).delete()
    s("ED-09", "type-over-selection", "nocap", "hello world").app("setsel", selStart=6, selEnd=11).key("x")
    s("ED-09", "space-over-selection", "nocap", "hello world").app("setsel", selStart=6, selEnd=11).space()
    # CX: context
    for v, f in [("default", {}), ("nocap", {}), ("plain", {}), ("search", {}), ("shortmessage", {}),
                 ("email", {}), ("uri", {}), ("nosuggest", {}), ("incognito", {}),
                 ("capwords", {"inputType": 8193}), ("capchars", {"inputType": 4097}), ("baretext", {"inputType": 1}),
                 ("password", {"inputType": 129}), ("visiblepw", {"inputType": 145})]:
        variant = v if not f else "plain"
        s("CX-02", v, variant, **f).word("becuase ")
    # Gboard 18.6 (the emulator farm): CAP_WORDS corrects, CAP_CHARACTERS does not; NO_SUGGESTIONS only fixes case and apostrophes.
    # Words no earlier scenario leaves in a field ("becuase" above is learned by Gboard after TP-14).
    s("CX-02", "capwords-mutli", "plain", inputType=8193).word("mutli ")
    s("CX-02", "capchars-mutli", "plain", inputType=4097).word("mutli ")
    for name, w in [("i", "i "), ("im", "im "), ("dont", "dont "), ("teh", "teh "), ("i-period", "i.")]:
        s("CX-02", "nosuggest-" + name, "nosuggest").word(w)
    s("CX-03", "nosuggest", "nosuggest").word("hel")
    s("CX-03", "password", "plain", inputType=129).word("hel")
    s("CX-03", "number", "plain", inputType=2).tapxy(180, 1830).tapxy(540, 1830)
    s("CX-03", "phone", "plain", inputType=3).tapxy(180, 1830).tapxy(540, 1830)
    s("CX-03", "datetime", "plain", inputType=4).tapxy(180, 1830).tapxy(540, 1830)
    s("CX-06", "send-single-line", "shortmessage").word("hi").key("enter")
    s("CX-06", "multiline-enter", "default").word("hi").key("enter").key("h")
    s("CX-06", "done", "plain").word("hi").key("enter")
    s("CX-04", "incognito-no-learning", "incognito").word("zorbix ").word("zorbix ").app("reset", text="", variant="nocap").word("zor")
    s("SU-09", "learned-phrase", "nocap").word("see you at the gym ").app("reset", text="see you at the ", variant="nocap").wait(300)
    # a misspelling left uncommitted when the input session ends (Gboard 18.6 on the emulator farm stopped correcting "becuase" after
    # qol/TP-14/composing in the 18:23 pass): typed, new session, typed again with a space
    s("SU-09", "abandoned-misspelling", "nocap").word("becuase").app("reset", text="", variant="nocap").word("becuase ")
    s("CX-07", "app-clears", "default").word("hello ").app("settext", text="").key("a")
    s("CX-07", "app-moves-cursor", "nocap").word("hello world").app("setsel", selStart=2, selEnd=2).key("x")
    s("CX-08", "thanks", "default", "Thanks. ").wait(300)
    s("CX-08", "see-you-at-the", "default", "see you at the ").wait(300)
    s("CX-08", "mid-word", "nocap", "hel").wait(300)
    # SU: suggestions
    s("SU-01", "strip-becuase", "nocap").word("becuase")
    s("SU-01", "strip-hel", "nocap").word("hel")
    s("SU-01", "strip-the", "nocap").word("the")
    s("SU-01", "completion-not-applied", "nocap").word("beau ")
    s("SU-02", "predictions", "default", "I am going to ").wait(300)
    s("SU-02", "after-word", "nocap").word("because ")
    s("SU-03", "autocap-case", "default").word("hel")
    s("SU-03", "capslock-case", "nocap").burst_keys(["shift", "shift"], iki=150).word("hel").shift(m={"cleanup": True})
    s("SU-04", "delete-into-word", "nocap", "i like teh ").delete()
    s("SU-04", "delete-whole-word", "nocap", "i like teh ").delete().delete().delete().delete()
    s("SU-05", "glide-candidates", "nocap").glide("to").slot("left")
    s("SU-06", "emoji-pizza", "nocap").word("pizza")
    s("SU-06", "emoji-then-space", "nocap").word("pizza ")
    s("SU-11", "offensive-prefix", "nocap").word("shi")
    s("SU-11", "offensive-typed", "nocap").word("shit ")
    s("SU-12", "toolbar-back", "nocap").key("h").delete()
    # TF: touch feel
    s("TF-01", "the-er-border", "nocap").key("t").key("h").tapxy(326, 1715.5).space()
    s("TF-02", "left-edge", "nocap").tapxy(2, 1715.5)
    s("TF-02", "right-edge", "nocap").tapxy(1078, 1870)
    s("TF-02", "strip-gap", "nocap").tapxy(540, 1645)
    s("TF-02", "below-bottom-row", "nocap").tapxy(593, 2290)
    s("TF-03", "rollover", "nocap").gesture([[(*G.centre("t"), 0), (*G.centre("t"), 100)], [(*G.centre("h"), 60), (*G.centre("h"), 150)]], {"k": "rollover"})
    s("TF-03", "rollover-word", "nocap").gesture([[(*G.centre(c), i * 90), (*G.centre(c), i * 90 + 140)] for i, c in enumerate("the")], {"k": "rollover"}).space()
    s("TF-05", "hold-e-250", "nocap").hold("e", 250)
    s("TF-05", "hold-e-450", "nocap").hold("e", 450)
    s("TF-05", "hold-e-panel", "nocap").gesture([[(*G.centre("e"), 0), (*G.centre("e"), 700), (G.centre("e")[0] + 20, 1300, 1400), (G.centre("e")[0] + 20, 1300, 1450)]], {"k": "longpress-cancel"})
    s("TF-06", "slide-to-variant", "nocap").gesture([[(272, 1715, 0), (272, 1715, 500), (380, 1600, 600), (380, 1600, 650)]], {"k": "longpress-slide"})
    s("TF-07", "symbols-slide-to-2", "nocap").drag(85, 2180.5, 165, 1715, 200, pre_ms=80).key("a")
    s("TF-08", "digit-space", "nocap").symbols().sym("1").space()
    s("TF-08", "paren-space", "nocap").symbols().sym("(").space()
    s("TF-08", "digits-stay", "nocap").symbols().sym("1").sym("2")
    s("TF-08", "quote-then-letter", "nocap").symbols().sym("'").key("a")
    s("TF-10", "slide-30", "nocap").drag(486.5, 1715.5, 516.5, 1715.5, 150)
    s("TF-10", "slide-55", "nocap").drag(486.5, 1715.5, 541.5, 1715.5, 150)
    s("TF-10", "slide-60", "nocap").drag(486.5, 1715.5, 546.5, 1715.5, 150)
    s("TF-10", "slide-80", "nocap").drag(486.5, 1715.5, 566.5, 1715.5, 150)
    s("TF-10", "slide-110", "nocap").drag(486.5, 1715.5, 596.5, 1715.5, 150)
    s("TF-11", "hold-space", "nocap").hold("space", 900)
    # LT: little touches
    s("LT-05", "zwj-family", "nocap", "a\U0001F468‍\U0001F469‍\U0001F467").delete()
    s("LT-05", "flag", "nocap", "a\U0001F1EC\U0001F1E7").delete()
    s("LT-05", "skin-tone", "nocap", "a\U0001F44D\U0001F3FD").delete()
    s("LT-05", "combining", "nocap", "aé").delete()
    s("LT-05", "emoji-single", "nocap", "a\U0001F600").delete()
    s("LT-05", "bmp-symbol", "nocap", "a\u2665").delete()
    s("LT-06", "hold-q", "nocap").hold("q", 600)
    s("LT-06", "hold-n", "nocap").hold("n", 600)
    s("LT-07", "hold-period-panel", "nocap").gesture([[(860.5, 2180.5, 0), (860.5, 2180.5, 800), (860.5, 1300, 1500), (860.5, 1300, 1550)]], {"k": "longpress-cancel"})
    s("LT-07", "hold-period-pick", "nocap").word("hi").gesture([[(860.5, 2180.5, 0), (860.5, 2180.5, 600), (860.5, 2050, 700), (860.5, 2050, 750)]], {"k": "longpress-slide"})
    s("LT-11", "symbols-reset", "nocap").symbols().app("reset", text="", variant="nocap")
    s("LT-11", "caps-lock-new-field", "nocap").burst_keys(["shift", "shift"], iki=150).app("reset", text="", variant="nocap").key("a").shift(m={"cleanup": True})
    return L


QUICK_QOL = ["qol/TP-02/pick-centre", "qol/TP-03/typed-space-period", "qol/TP-04/double-space", "qol/TP-05/after-period",
             "qol/TP-07/one-shot", "qol/CO-01/becuase", "qol/CO-06/glide-delete", "qol/ED-01/drag-234",
             "qol/ED-02/delete-swipe-294", "qol/CX-02/incognito", "qol/SU-04/delete-into-word", "qol/TF-08/digit-space",
             "qol/TF-01/the-er-border", "qol/CX-07/app-clears", "qol/CX-02/capwords-mutli", "qol/CX-02/nosuggest-i"]


# ------------------------------------------------------------------ human taps

def human_sentences(split="tune", limit=20, seed=7, sources=("tap_tsi", "tap_vtap")):
    """Sentences (word items in order) from the human tap splits, sampled reproducibly."""
    out = []
    for src in sources:
        items = read_jsonl(os.path.join(HUMAN, "%s.%s.jsonl.gz" % (src, split)))
        sents = {}
        for it in items:
            sid = it["id"].rsplit("/w", 1)[0]
            sents.setdefault(sid, []).append(it)
        keys = sorted(sents)
        rnd = random.Random(seed)
        rnd.shuffle(keys)
        n = 0
        for k in keys:
            ws = sents[k]
            if ws[0]["context"] != "":
                continue
            # keep only sentences whose words chain (context of word i+1 == context i + target i + " ")
            ok = all(ws[i + 1]["context"].lower().startswith(ws[i]["context"].lower()) for i in range(len(ws) - 1))
            if not ok or not 3 <= len(ws) <= 10:
                continue
            out.append((src, k, ws))
            n += 1
            if n >= limit:
                break
    return out


def human(limit=10, mode="steps", split="tune", seed=7, speed=1.0):
    """mode steps: one observed step per tap (real holds); burst: one real-time step per word; sentence: the whole
    sentence as one real-time burst at the person's own rhythm (gaps between words = that word's median
    inter-key interval), time-compressed by `speed` (0.5 = twice as fast; holds kept >= 40 ms). Field states after
    every keystroke come from the field's change history."""
    L = []
    for src, sid, ws in human_sentences(split, limit, seed):
        tag = mode if mode != "sentence" else "sentence-x%g" % speed
        s = S("human-%s/%s/%s" % (tag, src.split("_")[1], sid.split("/", 1)[1]), "default", src="human")
        s.item = " ".join(w["target"] for w in ws)
        if mode == "sentence":
            taps, metas, t0 = [], [], 0.0
            for w in ws:
                holds = w.get("holdMs") or [45] * len(w["points"])
                pts = w["points"]
                ikis = sorted(b[2] - a[2] for a, b in zip(pts, pts[1:])) or [183]
                gt = {"word": w["target"], "intended": w.get("intended") or (w["target"] + " "), "n": len(pts)}
                for j, ((x, y, t), h) in enumerate(zip(pts, holds)):
                    taps.append((x, y, (t0 + t) * speed, max(40, h * speed)))
                    metas.append(dict(gt, j=j))
                t0 += pts[-1][2] + ikis[len(ikis) // 2]
            s.burst(taps, m={"speed": speed})
            for tm, gm in zip(s.steps[-1]["m"]["taps"], metas):
                tm.update(gm)
            L.append(s)
            continue
        for w in ws:
            holds = w.get("holdMs") or [45] * len(w["points"])
            pts = w["points"]
            # ground truth: the word the person meant (with its terminator) and each tap's index in it
            gt = {"word": w["target"], "intended": w.get("intended") or (w["target"] + " "), "n": len(pts)}
            if mode == "steps":
                for j, ((x, y, t), h) in enumerate(zip(pts, holds)):
                    s.tapxy(x, y, max(30, h), m=dict(gt, j=j))
            else:
                taps = [(x, y, t, max(30, h)) for (x, y, t), h in zip(pts, holds)]
                s.burst(taps, m=dict(gt))
        L.append(s)
    return L


# ------------------------------------------------------------------ fuzzing

WORDS = ("the of and to in is you that it he was for on are as with his they at be this have from or one had by "
         "word but not what all were we when your can said there use each which she do how their if will up other "
         "about out many then them these so some her would make like him into time has look two more write go see "
         "number no way could people my than first water been call who oil its now find long down day did get come "
         "made may part where here because hello world thanks really going great maybe tomorrow whether which while "
         "becuase recieve teh helo definately wierd").split()

VARIANTS = [("default", {}), ("default", {}), ("default", {}), ("nocap", {}), ("email", {}), ("uri", {}),
            ("plain", {"inputType": 129}), ("plain", {"inputType": 8193}), ("nosuggest", {}),
            ("shortmessage", {}), ("incognito", {})]


def boundary_tap(rnd, s, c, strength):
    """Tap letter c near (or across) a horizontal boundary with a neighbour, or with Gaussian noise."""
    x, y = G.centre(c)
    if rnd.random() < strength:
        # within 0.05..0.45 key widths of the left or right edge (inside the key or just across)
        side = rnd.choice([-1, 1])
        x = x + side * KW * rnd.uniform(0.05, 0.62)
        y = y + rnd.gauss(0, 0.12) * KW
    else:
        x += rnd.gauss(0, 0.22) * KW
        y += rnd.gauss(0, 0.18) * KW
    x = min(max(x, 1), 1079)
    s.tapxy(x, y, max(30, int(rnd.gauss(70, 20))), m={"intended": c})


def fuzz(n=10, steps=24, seed=1):
    L = []
    for i in range(n):
        rnd = random.Random(seed * 1000 + i)
        variant, f = rnd.choice(VARIANTS)
        prefill = rnd.choice(["", "", "", "Hi there. ", "i think ", "hello world"])
        s = S("fuzz/%d/%d" % (seed, i), variant, prefill, src="fuzz", **f)
        text_len = len(prefill)
        page = "letters"
        caps = False
        while len(s.steps) < steps:
            r = rnd.random()
            if page == "symbols":
                c = rnd.choice(["1", "2", "5", "(", ")", "?", "!", "'", "-", "@", "space", "abc", "abc"])
                s.sym(c)
                text_len += 1 if c not in ("abc",) else 0
                if c in ("space", "abc"):
                    page = "letters"
                continue
            if r < 0.42:  # a word, with boundary taps
                w = rnd.choice(WORDS)
                cut = len(w) if rnd.random() < 0.8 else rnd.randint(1, len(w))
                for c in w[:cut]:
                    boundary_tap(rnd, s, c, 0.35)
                text_len += cut
            elif r < 0.55:
                s.space(dx=rnd.gauss(0, 0.6))
                text_len += 1
            elif r < 0.60:
                s.key(rnd.choice([".", ","]))
                text_len += 1
            elif r < 0.68:
                s.delete()
                text_len = max(0, text_len - 1)
            elif r < 0.70:
                ms = rnd.choice([500, 800, 1200])
                s.hold("delete", ms)
                text_len = max(0, text_len - 1 - max(0, (ms - 400) // 50))
            elif r < 0.74:
                d = rnd.choice([-1, 1]) * rnd.choice([60, 120, 240])
                s.drag(593, 2180.5, 593 + d, 2180.5, rnd.choice([400, 600]))
            elif r < 0.78 and text_len > 0:
                s.fieldtap(rnd.randint(0, text_len))
            elif r < 0.81:
                s.shift()
                caps = False
            elif r < 0.82:
                s.burst_keys(["shift", "shift"], iki=150)
                caps = True
            elif r < 0.85:
                s.symbols()
                page = "symbols"
            elif r < 0.91:
                w = rnd.choice([w for w in WORDS if len(w) >= 3 and len(set(w)) > 1])
                s.glide(w, speed=rnd.uniform(0.9, 1.5))
                text_len += len(w) + 1
            elif r < 0.95:
                s.slot(rnd.choice(["centre", "centre", "left", "right"]))
                text_len += 4
            elif r < 0.97:
                iki = rnd.choice([150, 300, 600, 900])
                s.burst_keys(["space", "space"], iki=iki)
                text_len += 2
            elif variant in ("default", "nocap"):
                s.key("enter")
                text_len += 1
        if caps:
            s.shift(m={"cleanup": True})  # release caps lock (no RNG use: earlier steps are unchanged)
        L.append(s)
    return L


def build(mode, seed=1):
    sc = calib()
    q = qol()
    if mode == "quick":
        sc += [x for x in q if x.id in QUICK_QOL]
        hs = human(limit=1, mode="steps", seed=seed)
        for h in hs:
            h.steps = h.steps[:14]  # the first words of one sentence, keystroke by keystroke
        sc += hs
        for sp in (1.0, 0.7, 0.5):
            sc += human(limit=1, mode="sentence", seed=seed + 1, speed=sp)
        sc += fuzz(n=2, steps=12, seed=seed)
    else:
        sc += q
        sc += human(limit=3, mode="steps", seed=seed)
        sc += human(limit=16, mode="sentence", seed=seed + 1, speed=1.0)
        for sp in (0.7, 0.5):
            sc += human(limit=8, mode="sentence", seed=seed + 1, speed=sp)
        sc += fuzz(n=16, steps=24, seed=seed)
    return [x.to_json() for x in sc]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", default="quick", choices=["quick", "full", "qol", "human", "fuzz", "calib", "control", "speed", "phone"])
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--n", type=int, default=10)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.mode in ("quick", "full"):
        rows = build(a.mode, a.seed)
    elif a.mode == "control":
        # positive control: only human taps and fuzzing (no hand-written TF-01 scenario)
        rows = [x.to_json() for x in calib() + human(6, "steps", seed=a.seed + 10) + human(10, "burst", seed=a.seed + 11)
                + fuzz(6, steps=20, seed=a.seed + 4)]
    elif a.mode == "phone":
        # shared hardware: every qol item, a little tap-by-tap observation, real-time sentences at 1.0/0.7/0.5x, some fuzz
        hs = human(2, "steps", seed=a.seed)
        for h in hs:
            h.steps = h.steps[:20]
        rows = [x.to_json() for x in calib() + qol() + hs
                + human(6, "sentence", seed=a.seed + 1, speed=1.0)
                + [h for sp in (0.7, 0.5) for h in human(3, "sentence", seed=a.seed + 1, speed=sp)]
                + fuzz(6, steps=24, seed=a.seed)]
    elif a.mode == "speed":
        # fast typing: the same sentences at real speed, 0.7x and 0.5x
        rows = [x.to_json() for x in calib() + [h for sp in (1.0, 0.7, 0.5) for h in human(a.n, "sentence", seed=a.seed + 1, speed=sp)]]
    elif a.mode == "qol":
        rows = [x.to_json() for x in calib() + qol()]
    elif a.mode == "human":
        rows = [x.to_json() for x in calib() + human(a.n, "steps", seed=a.seed) + human(a.n, "burst", seed=a.seed + 1)]
    elif a.mode == "fuzz":
        rows = [x.to_json() for x in calib() + fuzz(a.n, seed=a.seed)]
    else:
        rows = [x.to_json() for x in calib()]
    write_jsonl(a.out, rows)
    nsteps = sum(len(r["steps"]) for r in rows)
    print("%d scenarios, %d steps -> %s" % (len(rows), nsteps, a.out))


if __name__ == "__main__":
    main()
