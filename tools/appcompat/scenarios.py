"""Scenarios of the app-compatibility matrix, written once and played identically through Gboard and pboard.

Each scenario is a list of steps (each observed afterwards) on a freshly opened field. Key positions come from the
conformance harness's scenario builder (tools/conformance/scenarios.py: Gboard 15.1/18.6 >= 400 dp key geometry,
which pboard matches on these emulators), so typing, glides and the emoji panel land on the same keys in both.
A word is typed as one real-time burst (one observed step), editing keys one tap per step.
"""
import importlib.util
import os
import sys

_CONF = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "conformance")
sys.path.insert(0, _CONF)
# the conformance scenario builder (geometry, glides, emoji panel positions), loaded under its own name
_spec = importlib.util.spec_from_file_location("conformance_scenarios", os.path.join(_CONF, "scenarios.py"))
C = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(C)

IKI = 170  # ms between taps inside a word burst (a brisk but human typist)

# Number pad (TYPE_CLASS_NUMBER / PHONE pages): 4 columns of a quarter of the width on the letter rows' heights
# ("1 2 3 -", "4 5 6 space", "7 8 9 delete", ", 0 . enter"), the same on both keyboards (checked by screenshot).
_PAD_X = [135.0, 405.0, 675.0, 945.0]
NUMPAD = {}
for _r, _row in enumerate([["1", "2", "3", "-"], ["4", "5", "6", "space"], ["7", "8", "9", "delete"], [",", "0", ".", "enter"]]):
    for _c, _k in enumerate(_row):
        NUMPAD[_k] = (_PAD_X[_c], C.ROW_Y[_r])


class B:
    """Step builder on top of the conformance builder's geometry."""

    def __init__(self):
        self.steps = []
        self._s = C.S("x")

    def _take(self, label):
        st = self._s.steps.pop()
        st.pop("m", None)
        st["label"] = label
        self.steps.append(st)
        return self

    def word(self, text, label=None):
        names = []
        for c in text:
            names.append({" ": "space", "\n": "enter"}.get(c, c.lower()))
        self._s.burst_keys(names, iki=IKI)
        return self._take(label or "type %r" % text)

    def key(self, name, label=None):
        self._s.key(name)
        return self._take(label or name)

    def digits(self, text, label=None):
        keys = [text] if text in NUMPAD else list(text)
        taps = [(NUMPAD[c][0], NUMPAD[c][1], i * IKI, 60) for i, c in enumerate(keys)]
        self._s.burst(taps)
        return self._take(label or "number pad %r" % text)

    def glide(self, w):
        self._s.glide(w)
        return self._take("glide %s" % w)

    def xy(self, x, y, label):
        self._s.tapxy(x, y)
        return self._take(label)

    def op(self, op, label, **kw):
        d = {"op": op, "label": label}
        d.update(kw)
        self.steps.append(d)
        return self


def emoji_steps(b):
    b.key("emoji", "open emoji panel")
    b.xy(*C.EMOJI_FIRST, label="tap first emoji")
    b.xy(*C.EMOJI_ABC, label="back to letters")
    return b


# name -> (kinds it applies to, builder(field) -> (prefill text or None, steps), expected final text or None)
def _autocorrect(f):
    return None, B().word("this ").word("is ").word("becuase ").word("it works ").steps


def _swipe(f):
    return None, B().glide("hello").glide("world").key(".", "period").steps


def _backspace_correction(f):
    b = B().word("becuase ")
    b.key("delete", "delete (into the correction)").key("delete", "delete again").word("e ")
    return None, b.steps


def _midword(f):
    b = B().word("hello world ")
    b.op("setsel", "cursor into 'hello'", start=2, end=2).key("x", "type x mid-word")
    b.op("setsel", "cursor to end", start=13, end=13).word("ok ")
    return None, b.steps


def _select_replace(f):
    b = B().word("hello world ")
    b.op("setsel", "select 'world'", start=6, end=11).word("there", "type over the selection")
    return None, b.steps


def _paste(f):
    b = B().word("hi ")
    b.op("paste", "paste", text="pasted text").word(" ok")
    return None, b.steps


def _emoji(f):
    b = B().word("fun ")
    emoji_steps(b)
    b.key("delete", "delete the emoji").word("x")
    return None, b.steps


def _prefilled(f):
    b = B().word(" more", "type at the end")
    b.op("setsel", "cursor after 'Existing'", start=8, end=8).key("s", "type s")
    b.op("setsel", "cursor to end of 'text'", start=14, end=14).key("delete", "delete").key("t", "type t")
    return "Existing text", b.steps


def _appswitch(f):
    b = B().word("hello wor")
    b.op("key", "home (mid-word)", code="KEYCODE_HOME", settle=800)
    b.op("resume", "back to the app", pkg=f["app"], settle=800)
    b.op("refocus", "tap the field again")
    b.word("ld ")
    return None, b.steps


def _rotate(f):
    b = B().word("hello wor")
    b.op("rotate", "rotate to landscape (mid-word)", rot=1)
    b.op("rotate", "rotate back", rot=0)
    b.op("refocus", "tap the field again (if the keyboard went away)", ifHidden=True)
    b.word("ld ")
    return None, b.steps


def _hide_reopen(f):
    """Back hides the keyboard mid-word; tapping the field brings it back (EditorInfo's selection is stale then)."""
    b = B().word("becuase ").word("hello wor")
    b.op("back", "back hides the keyboard (mid-word)")
    b.op("refocus", "tap the field again")
    b.word("ld ")
    b.key("delete", "delete").key("delete", "delete").word("d ")
    return None, b.steps


def _app_clears(f):
    b = B().word("hello wor")
    b.op("settext", "app clears the field (mid-word)", text="")
    b.word("abc ")
    return None, b.steps


def _app_changes(f):
    b = B().word("hello wor")
    b.op("settext", "app replaces the text (mid-word)", text="Changed")
    b.word(" text ")
    return None, b.steps


def _enter(f):
    b = B().word("hello")
    b.key("enter", "enter / send")
    b.word("again")
    return None, b.steps


def _digits(f):
    b = B().digits("12345")
    b.digits("delete", "delete").digits("6")
    b.op("setsel", "cursor after '12'", start=2, end=2).digits("0", "type 0 mid-number")
    b.op("settext", "app replaces the number", text="42").op("setsel", "cursor to end", start=2, end=2).digits("7")
    return None, b.steps


def _url_autocomplete(f):
    """Chrome's inline autocompletion: the completion is selected text after the cursor while typing."""
    b = B().word("exa", "type 'exa' (inline completion)")
    b.word("m", "type 'm'")
    b.key("delete", "delete (drops the completion)").key("delete", "delete")
    b.word("ample", "type 'ample'")
    b.key("enter", "go")
    return None, b.steps


def _password(f):
    b = B().word("hunter two")
    b.key("delete", "delete").key("delete", "delete")
    b.op("setsel", "select all", start=0, end=8).word("abc", "type over the selection")
    return None, b.steps


def _terminal(f):
    b = B().word("echo hello")
    b.key("delete", "delete").word("p")
    b.key("enter", "enter")
    b.word("ls")
    b.key("enter", "enter")
    return None, b.steps


TEXTY = ("text", "multiline")
SCENARIOS = {
    "autocorrect": (TEXTY, _autocorrect),
    "swipe": (TEXTY, _swipe),
    "backspace_correction": (TEXTY, _backspace_correction),
    "midword": (TEXTY + ("url", "email"), _midword),
    "select_replace": (TEXTY + ("url", "email"), _select_replace),
    "paste": (TEXTY + ("url", "email", "password"), _paste),
    "emoji": (TEXTY, _emoji),
    "prefilled": (TEXTY + ("url", "email", "password"), _prefilled),
    "appswitch": (TEXTY + ("url", "email", "password", "terminal"), _appswitch),
    "rotate": (TEXTY + ("url", "email", "password", "terminal"), _rotate),
    "hide_reopen": (TEXTY + ("url", "email"), _hide_reopen),
    "app_clears": (TEXTY + ("url", "email", "password"), _app_clears),
    "app_changes": (TEXTY + ("url", "email"), _app_changes),
    "enter": (("text", "url", "email"), _enter),
    "password": (("password",), _password),
    "digits": (("number", "phone"), _digits),
    "url_autocomplete": (("urlhistory",), _url_autocomplete),
    "terminal": (("terminal",), _terminal),
}


def for_field(f, names=None):
    """The scenarios (runner JSON, minus IME specifics) of one field."""
    out = []
    for name, (kinds, fn) in SCENARIOS.items():
        if names and name not in names:
            continue
        if f["kind"] not in kinds or name in f.get("skip", ()):
            continue
        if not f.get("clear") and name in ("prefilled", "app_clears", "app_changes", "midword", "select_replace", "paste"):
            continue
        prefill, steps = fn(f)
        setup = list(f["open"]) + [dict({"op": "focus", "sel": f["sel"]}, **f.get("focusOpts", {}))]
        if f.get("clear"):
            setup.append({"op": "settext", "text": prefill or ""})
            if prefill:
                setup.append({"op": "setsel", "start": len(prefill), "end": len(prefill)})
            setup.append({"op": "sleep", "ms": 600})
        # every scenario ends by hiding the keyboard: the field must keep its text and any composition must close
        steps = steps + [{"op": "hide", "label": "hide the keyboard (end)", "final": True}]
        sc = {"id": "%s/%s" % (f["id"], name), "field": f["id"], "scenario": name, "app": f["app"],
              "setup": setup, "steps": steps, "sel": f["sel"]}
        if f.get("probe"):
            sc["probe"] = f["probe"]
        out.append(sc)
    return out
