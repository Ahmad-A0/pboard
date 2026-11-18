#!/usr/bin/env python3
"""Aligns a Gboard run and a pboard run of the same scenarios step by step and finds divergences.

  diff.py <gboard-run-dir> <pboard-run-dir> [--allowlist allowlist.yaml] [--out divergences.json]

Both runs must be analysed (analyze.py -> steps.jsonl). For every scenario, steps are compared in order
until the field text differs (later steps start from different states and are not compared). At each
step every observable is compared:

  text           field text (subclassified: inserted text / spacing / capitalisation / punctuation)
  cursor         selection with equal text
  composing      composing span (apps can see it; pboard composes on purpose, see allowlist)
  strip-layout   toolbar vs suggestions, number of slots, which slot is bold (the autocorrection)
  strip-content  the words in the slots
  keyboard       page (letters / symbols / emoji / ...), shift state, popups
  timing         text-change latency or visual settle time far apart
  focus          the app lost focus or died (an IME crash in dev.pboard kills the playground too)

Real-time bursts (several taps in one step) are also compared keystroke by keystroke, from the field's
change history. Each divergence gets a signature describing what each keyboard did, in terms general
enough that the same root cause in different scenarios clusters together.
"""
import argparse
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import HERE, read_jsonl, Geometry  # noqa: E402

G = Geometry()
# User impact per kind (x scenarios affected = rank). Text the user ends up with matters most; strip wording
# least (the LMs differ by design, so many strip clusters are expected).
SHIFT_KEY_EVENTS = ("key:0:59", "key:0:60")  # KEYCODE_SHIFT_LEFT / _RIGHT
IMPACT = {"focus": 10, "text": 10, "cursor": 6, "editor-events": 6, "keyboard": 5, "strip-layout": 2, "strip-content": 1,
          "timing": 1, "composing": 0.5, "transient": 0.5}
SUBIMPACT = {"inserted text": 10, "spacing": 8, "capitalisation": 8, "punctuation": 8}


# ------------------------------------------------------------------ allowlist

def load_allowlist(path):
    """Minimal YAML subset: a list of flat mappings with scalar values (`- key: value` / `  key: value`);
    `key: >` starts a folded block whose more-indented lines are joined with spaces."""
    items, cur, key, block_indent = [], None, None, None
    if not path or not os.path.exists(path):
        return items
    for raw in open(path):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if block_indent is not None and indent > block_indent and cur is not None:
            cur[key] = (cur[key] + " " + line.strip()).strip()
            continue
        block_indent = None
        m = re.match(r"^(\s*)(- )?([A-Za-z_]+):\s*(.*)$", line)
        if not m:
            continue
        if m.group(2):
            cur = {}
            items.append(cur)
        key, val = m.group(3), m.group(4).strip()
        if val in (">", "|"):
            val = ""
            block_indent = len(m.group(1)) + (2 if m.group(2) else 0)
        elif len(val) >= 2 and val[0] == val[-1] and val[0] in "'\"":
            val = val[1:-1]
        cur[key] = val
    return items


def allowed(div, allow):
    for a in allow:
        if a.get("kind") and a["kind"] != div["kind"]:
            continue
        if a.get("subkind") and not re.search(a["subkind"], div.get("subkind", "")):
            continue
        if a.get("signature") and not re.search(a["signature"], div["signature"]):
            continue
        if a.get("scenario") and not re.search(a["scenario"], div["sc"]):
            continue
        return a["id"]
    return None


# ------------------------------------------------------------------ helpers

def window_name(w):
    """'mCurrentFocus=Window{6e46273 u0 com.x/com.x.SettingsActivity}' -> 'com.x/com.x.SettingsActivity'."""
    w = re.sub(r"^.*Window\{[0-9a-f]+ u\d+ ", "", w or "?").rstrip("}")
    return re.sub(r":[0-9a-f]+$", "", w)


def edit(prev, new):
    """(position, deleted, inserted) turning prev into new (common prefix/suffix removed)."""
    if prev is None or new is None:
        return 0, prev or "", new or ""
    a = 0
    while a < len(prev) and a < len(new) and prev[a] == new[a]:
        a += 1
    b = 0
    while b < len(prev) - a and b < len(new) - a and prev[-1 - b] == new[-1 - b]:
        b += 1
    return a, prev[a:len(prev) - b], new[a:len(new) - b]


def text_kind(a, b):
    if a.lower() == b.lower():
        return "capitalisation"
    if a.replace(" ", "") == b.replace(" ", ""):
        return "spacing"
    strip = lambda s: "".join(c for c in s if not unicodedata.category(c).startswith("P"))
    if strip(a) == strip(b):
        return "punctuation"
    return "inserted text"


def step_class(m, op):
    k = (m or {}).get("k", op)
    key = (m or {}).get("key")
    if k == "tap":
        if (m or {}).get("page") == "symbols":
            s = m.get("sym")
            return "symbols-page tap" if s not in ("abc", "more", "space") else "symbols-page " + s
        if key and len(key) == 1 and key.isalpha():
            return "letter tap"
        if key in ("space", "delete", "shift", "symbols", "emoji", "enter", ".", ","):
            return key + " tap"
        return "tap off keys"
    return {"glide": "glide", "slot": "suggestion pick", "drag": "drag from %s" % key, "hold": "hold %s" % key,
            "burst": "real-time burst", "fieldtap": "tap in text", "app": "app edit", "wait": "focus",
            "chord": "chord", "rollover": "rollover", "longpress-slide": "long-press slide",
            "longpress-cancel": "long-press cancel"}.get(k, k)


def word_before(text, pos):
    i = pos
    while i > 0 and (text[i - 1].isalnum() or text[i - 1] in "'’"):
        i -= 1
    return text[i:pos]


def context_class(text, sel):
    if text is None:
        return "?"
    pos = sel if sel is not None and 0 <= sel <= len(text) else len(text)
    before = text[:pos]
    if before == "":
        return "empty field"
    if re.search(r"[.!?]\s+$", before) or before.endswith("\n"):
        return "sentence start"
    if before.endswith(" "):
        return "after space"
    if before[-1].isalpha():
        return "mid-word"
    return "after symbol"


def lit_char(m):
    key = (m or {}).get("key")
    return key if key and len(key) == 1 else None


def effect(d, i):
    """What a single tap did to the text: letter / space / delete / other."""
    if not d and len(i) == 1 and i.isalpha():
        return "letter"
    if not d and i == " ":
        return "space"
    if d and not i and len(d) == 1:
        return "delete"
    if i.endswith(" ") and (not d or i.strip()):
        return "space"  # a commit, possibly with a correction
    return "other"


def resolution(m, da, ia, db, ib):
    """Signature when one keyboard executes the key under the finger and the other a neighbouring key of
    another kind (letter vs space vs delete): tap-time key resolution across key types."""
    key = (m or {}).get("key")
    if not key:
        return None
    lit = "letter" if len(key) == 1 and key.isalpha() else key if key in ("space", "delete") else None
    if lit is None:
        return None
    ea, eb = effect(da, ia), effect(db, ib)
    if ea == eb or "other" in (ea, eb):
        return None
    name = {"letter": "a letter", "space": "a space", "delete": "a deletion"}
    key_name = "a letter key" if lit == "letter" else "the %s key" % key
    if eb == lit:
        return "tap on %s near another key: G resolves it to %s (tap-time key resolution), P executes the key under the finger" % (key_name, name[ea])
    if ea == lit:
        return "tap on %s near another key: P resolves it to %s, G executes the key under the finger" % (key_name, name[eb])
    return None


def describe_text(m, op, prev, a, b, sel_prev):
    """Signature of a text divergence: what each keyboard did with the same input."""
    sc = step_class(m, op)
    pa, da, ia = edit(prev, a)
    pb, db, ib = edit(prev, b)
    ctx = context_class(prev, sel_prev)
    kind = text_kind(a, b)

    def show(d, i):
        if not d and not i:
            return "no change"
        if not d:
            return "+%r" % i
        if not i:
            return "-%r" % d
        return "%r->%r" % (d, i)

    detail = "G %s, P %s" % (show(da, ia), show(db, ib))
    sig = resolution(m, da, ia, db, ib)
    if sig:
        return kind, sig, detail
    if sc == "letter tap":
        lit = lit_char(m)
        la, lb = ia.strip(), ib.strip()
        if not da and not db and len(la) == 1 and len(lb) == 1 and la.lower() != lb.lower():
            if lb.lower() == lit and la.lower() != lit:
                sig = "letter tap: G types a neighbouring key's letter (tap-time key resolution), P types the key under the finger"
            elif la.lower() == lit and lb.lower() != lit:
                sig = "letter tap: P types a neighbouring key's letter, G types the key under the finger"
            else:
                sig = "letter tap: different letter"
        elif kind == "capitalisation":
            sig = "letter tap at %s: %s" % (ctx, "G uppercase, P lowercase" if ia.strip()[:1].isupper() else "G lowercase, P uppercase")
        elif kind == "spacing":
            sig = "letter tap at %s: spacing differs (%s)" % (ctx, detail)
        elif len(da) > 0 or len(db) > 0:
            sig = "letter tap: %s rewrites the word while typing" % ("G" if da else "P")
        else:
            sig = "letter tap: different text"
    elif sc in ("space tap", ". tap", ", tap", "enter tap") or sc.startswith("symbols-page"):
        wa = (da, ia)
        corr_a = len(da) > 0 and ia.strip(" .,\n") != ""
        corr_b = len(db) > 0 and ib.strip(" .,\n") != ""
        if kind == "capitalisation":
            sig = "%s at %s: capitalisation of the committed word differs" % (sc, ctx)
        elif kind == "spacing":
            sig = "%s: spacing differs (%s)" % (sc, "G %r / P %r" % (ia, ib))
        elif corr_a and not corr_b:
            sig = "%s: G autocorrects the word, P keeps it" % sc
        elif corr_b and not corr_a:
            sig = "%s: P autocorrects the word, G keeps it" % sc
        elif corr_a and corr_b:
            sig = "%s: different autocorrection" % sc
        elif kind == "punctuation":
            sig = "%s: punctuation differs" % sc
        else:
            sig = "%s: different text" % sc
        if sc == "space tap" and (ia.endswith(". ") or ib.endswith(". ")) and ia != ib:
            sig = "space tap: double-space period differs"
    elif sc == "delete tap":
        sig = "delete: G %s, P %s" % ("deletes %d chars" % len(da) if da and not ia else show(da, ia),
                                       "deletes %d chars" % len(db) if db and not ib else show(db, ib))
        if len(da) > 1 or len(db) > 1:
            sig = "delete: one keyboard deletes more than one character (G %d, P %d)" % (len(da), len(db))
        if ia or ib:
            sig = "delete: one keyboard rewrites text (revert of a correction?)"
    elif sc == "glide":
        if kind == "spacing":
            sig = "glide: auto-space differs"
        elif kind == "capitalisation":
            sig = "glide: capitalisation differs"
        else:
            sig = "glide: different word decoded"
    elif sc == "suggestion pick":
        if kind == "spacing":
            sig = "suggestion pick: spacing differs"
        else:
            sig = "suggestion pick: different word inserted (strips differ)"
    else:
        sig = "%s: %s differs" % (sc, kind)
    return kind, sig, detail


def _token(text):
    """The word being typed (after the last whitespace), or the last word when the text ends with a space."""
    text = text or ""
    if text[-1:].isspace():
        parts = text.split()
        return (parts[-1] if parts else ""), True
    return re.split(r"\s", text)[-1], False


def intent(m, before, a, b):
    """Who matched what the person meant (human items: the intended word; fuzz boundary taps: the intended
    letter): "G", "P", "both", "neither", or None when there is no ground truth."""
    m = m or {}
    if "intended" in m and len(m["intended"]) > 1:
        want = m["intended"].strip().lower()

        def ok(t):
            tok, done = _token(t)
            tok = tok.lower().strip(".,!?;:")
            if done:
                return tok == want
            return len(tok) <= len(want) and tok == want[:len(tok)]
    elif "intended" in m:
        want = m["intended"].lower()

        def ok(t):
            _, _, ins = edit(before or "", t or "")
            return ins.lower().strip() == want
    else:
        return None
    ga, pb = ok(a), ok(b)
    return "both" if ga and pb else "G" if ga else "P" if pb else "neither"


def strip_intent(m, xa, xb):
    """Whether the word the person meant is offered in each strip (human items mid-word)."""
    w = ((m or {}).get("word") or "").lower()
    if not w:
        return None
    ga = any(norm_word(x).lower() == w for x in xa)
    pb = any(norm_word(x).lower() == w for x in xb)
    return "both" if ga and pb else "G" if ga else "P" if pb else "neither"


def slots_of(st):
    s = st.get("strip") or {}
    return [x["text"] for x in s.get("slots", [])]


def norm_word(t):
    return re.sub(r"[^\w<>'’ ]", "", t or "").strip()


def ocr_equal(a, b):
    """Slot texts equal, tolerating one OCR character error in words of 5+ letters (fonts differ)."""
    a, b = norm_word(a), norm_word(b)
    if a == b:
        return True
    if len(a) >= 5 and len(a) == len(b):
        return sum(1 for x, y in zip(a, b) if x != y) <= 1
    return False


def burst_states(st, m):
    """Field text after each keystroke of a real-time burst (from the change history)."""
    taps = (m or {}).get("taps") or []
    hist = st.get("hist") or []
    times = [t["t"] for t in taps]
    out = []
    for i in range(len(times)):
        nxt = times[i + 1] if i + 1 < len(times) else None
        txt = None
        for h in hist:
            if h[0] < 0:
                continue
            if nxt is not None and h[0] >= nxt:
                break
            txt = h[2]
        out.append(txt)
    if out:
        out[-1] = st.get("text")
    # carry forward: a keystroke that changed nothing keeps the previous state
    return out


def transients(st, before=None, min_ms=40):
    """Intermediate field texts inside one step that stayed for >= min_ms (visible for a few frames), other
    than the text before the step and the final one."""
    hist = [h for h in (st.get("hist") or []) if h[0] >= 0 and h[1] in ("text", "sel")]
    out = []
    for i, h in enumerate(hist[:-1]):
        nxt = hist[i + 1][0]
        if nxt - h[0] >= min_ms and h[2] not in (st.get("text"), before) and (not out or out[-1] != h[2]):
            out.append(h[2])
    return out


def state_seq(st, before, min_ms=30):
    """[(t, text)] distinct field texts during a step that lasted >= min_ms, starting with the text before."""
    hist = [h for h in (st.get("hist") or []) if h[0] >= 0 and h[1] in ("text", "sel")]
    seq = [(-1, before)]
    for i, h in enumerate(hist):
        dur = (hist[i + 1][0] - h[0]) if i + 1 < len(hist) else 1e9
        if dur >= min_ms and seq[-1][1] != h[2]:
            seq.append((h[0], h[2]))
    if seq[-1][1] != st.get("text"):
        seq.append((1e9, st.get("text")))
    return seq


def latency(st, m):
    """ms from the step's last touch-up to the first text change (None if none)."""
    hist = st.get("hist") or []
    tend = st.get("tEnd") or 0
    for h in hist:
        if h[0] >= 0 and h[1] == "text":
            return h[0] - tend
    return None


# ------------------------------------------------------------------ main comparison

def compare(ga, pa, scen):
    """Yields divergences for every scenario."""
    by = lambda rows: {(r["sc"], r.get("step")): r for r in rows if not r.get("aborted")}
    A, B = by(ga), by(pa)
    abortA = {r["sc"]: r for r in ga if r.get("aborted")}
    abortB = {r["sc"]: r for r in pa if r.get("aborted")}
    out = []
    for sid, sc in scen.items():
        if sid.startswith("calib/"):
            continue
        steps = sc["steps"]
        prev = A.get((sid, -1)), B.get((sid, -1))
        first = {}
        for i in range(-1, len(steps)):
            a, b = A.get((sid, i)), B.get((sid, i))
            if a is None or b is None:
                if (a is None) != (b is None):
                    who = "G" if a is None else "P"
                    ab = abortA.get(sid) if a is None else abortB.get(sid)
                    out.append(mk(sid, sc, i, steps, "focus", "lost",
                                  "%s: the playground %s" % (who, "died (IME crash?)" if ab and ab.get("focusWindow") == "dead"
                                                            else "lost focus to %s" % window_name((ab or {}).get("focusWindow", "?"))),
                                  a, b, prev))
                break
            m = steps[i].get("m") if i >= 0 else {"k": "focus"}
            op = steps[i]["op"] if i >= 0 else "reset"
            divs = []
            fa, fb = a.get("windowFocus", True) and not a.get("playgroundDead"), b.get("windowFocus", True) and not b.get("playgroundDead")
            if fa != fb:
                who = "G" if not fa else "P"
                bad = a if not fa else b
                divs.append(("focus", "lost", "%s: %s after %s" % (who, "the playground died (IME crash?)" if bad.get("playgroundDead")
                                                                   else "focus went to %s" % window_name(bad.get("focusWindow")), step_class(m, op)), ""))
            # Real-time bursts: compare the sequences of distinct field states each keyboard went through (states
            # that lasted >= 30 ms), which does not depend on how fast each keyboard is; lag is reported apart.
            if (m or {}).get("k") == "burst" and a.get("text") is not None:
                ptxt = prev[0].get("text") if prev[0] else ""
                qa, qb = state_seq(a, ptxt), state_seq(b, ptxt)
                n = min(len(qa), len(qb))
                di = next((k for k in range(n) if qa[k][1] != qb[k][1]), None)
                if di is None and len(qa) != len(qb) and a.get("text") != b.get("text"):
                    di = n
                if di is not None:
                    base = qa[di - 1][1] if di > 0 else ptxt
                    x = qa[di][1] if di < len(qa) else qa[-1][1]
                    y = qb[di][1] if di < len(qb) else qb[-1][1]
                    t = min(qa[di][0] if di < len(qa) else 1e9, qb[di][0] if di < len(qb) else 1e9)
                    taps = m.get("taps") or []
                    k = max([j for j, tp in enumerate(taps) if tp["t"] <= t] or [0])
                    tap = taps[k] if taps else {}
                    mk_m = {"k": "tap", "key": tap.get("key"), "depth": tap.get("depth"), "nb": tap.get("nb")}
                    kind, sig, detail = describe_text(mk_m, "gesture", base, x, y, len(base))
                    # ground truth per keystroke (human sentence bursts label every tap with the word meant and the
                    # tap's index in it): a letter tap is judged by the letter meant when taps and letters align,
                    # a terminator (space, punctuation) by the committed word
                    it, j = tap.get("intended"), tap.get("j")
                    aligned = bool(it) and j is not None and tap.get("n") == len(it)
                    if aligned and j < len(it) - 1 and it[j].isalpha():
                        who = intent({"intended": it[j]}, base, x, y)
                    elif aligned and j == len(it) - 1:
                        who = intent({"intended": it}, base, x, y)
                    else:
                        who = intent(m, base, x, y)
                    divs.append(("text", kind, sig, "real-time burst, keystroke %d (%s): %s" % (k, tap.get("key"), detail), who))
                else:
                    lags = [qb[k][0] - qa[k][0] for k in range(1, n) if qa[k][0] < 1e8 and qb[k][0] < 1e8]
                    if lags:
                        med = sorted(lags)[len(lags) // 2]
                        if abs(med) > 100:
                            divs.append(("timing", "lag", "real-time typing: the field lags behind the keystrokes on %s" % ("P" if med > 0 else "G"),
                                         "median %+d ms (P minus G) over %d states" % (med, len(lags))))
            ta, tb = a.get("text"), b.get("text")
            if ta != tb and not any(d[0] == "text" for d in divs):
                ptxt = prev[0].get("text") if prev[0] else ""
                kind, sig, detail = describe_text(m, op, ptxt, ta or "", tb or "", prev[0].get("selStart") if prev[0] else None)
                if bool(a.get("skipped")) != bool(b.get("skipped")):
                    sig = "suggestion pick: no suggestions showing on %s (tap skipped), suggestions on the other" % ("G" if a.get("skipped") else "P")
                divs.append(("text", kind, sig, detail, intent(m, ptxt, ta or "", tb or "")))
            if ta == tb:
                if (a.get("selStart"), a.get("selEnd")) != (b.get("selStart"), b.get("selEnd")):
                    sc_ = step_class(m, op)
                    da = (a.get("selStart") or 0) - ((prev[0] or {}).get("selStart") or 0)
                    db = (b.get("selStart") or 0) - ((prev[1] or {}).get("selStart") or 0)
                    divs.append(("cursor", "selection", "%s: cursor %s" % (sc_, "moves differently" if da != db else "selection differs"),
                                 "G sel %s-%s (moved %+d), P sel %s-%s (moved %+d)" % (a.get("selStart"), a.get("selEnd"), da,
                                                                                    b.get("selStart"), b.get("selEnd"), db)))
                if (a.get("compStart"), a.get("compEnd")) != (b.get("compStart"), b.get("compEnd")):
                    divs.append(("composing", "span", "composing region differs (G %s, P %s)" % (
                        "none" if a.get("compStart", -1) < 0 else "set", "none" if b.get("compStart", -1) < 0 else "set"),
                        "G %s-%s, P %s-%s" % (a.get("compStart"), a.get("compEnd"), b.get("compStart"), b.get("compEnd"))))
            # keyboard state
            ka, kb = a.get("kb") or {}, b.get("kb") or {}
            ctx = context_class(ta, a.get("selStart"))
            if ka.get("page") != kb.get("page"):
                divs.append(("keyboard", "page", "%s: keyboard page differs (G %s, P %s)" % (step_class(m, op), ka.get("page"), kb.get("page")), ""))
            elif ka.get("page") == "letters" and ka.get("shift") != kb.get("shift"):
                if i < 0:
                    # a new input session (field focused / restarted): the context goes to the detail
                    divs.append(("keyboard", "shift", "new input session: shift state differs (%s on P while G shows %s)" % (
                        kb.get("shift"), "auto-capitalisation (on)" if ka.get("shift") == "on" else ka.get("shift")),
                        "field %s, text before `%s`" % ((sc.get("field") or {}).get("variant"), ta)))
                else:
                    divs.append(("keyboard", "shift", "%s at %s: shift state differs (G %s, P %s)" % (
                        step_class(m, op), ctx, ka.get("shift"), kb.get("shift")), ""))
            elif bool(ka.get("overlay")) != bool(kb.get("overlay")):
                divs.append(("keyboard", "popup", "%s: %s shows a popup/panel the other does not" % (
                    step_class(m, op), "G" if ka.get("overlay") else "P"), "dist G %s P %s" % (ka.get("dist"), kb.get("dist"))))
            # strip (only when both show the letters/symbols keyboard)
            if ka.get("page") in ("letters", "symbols", "symbols2") and kb.get("page") == ka.get("page"):
                sa_, sb_ = a.get("strip") or {}, b.get("strip") or {}
                ma, mb = sa_.get("mode"), sb_.get("mode")
                if ma != mb and {ma, mb} != {"toolbar", "other"}:
                    divs.append(("strip-layout", "mode", "strip at %s: %s on G, %s on P" % (ctx, ma, mb),
                                 "after %s: G %s | P %s" % (step_class(m, op), slots_of(a), slots_of(b))))
                elif ma == mb == "suggestions":
                    xa, xb = slots_of(a), slots_of(b)
                    ea0 = sum("<emoji>" in x for x in xa)
                    eb0 = sum("<emoji>" in x for x in xb)
                    if len(xa) != len(xb) and (len(xa) - ea0) != (len(xb) - eb0):
                        divs.append(("strip-layout", "slots", "strip at %s: %s word slots on G than on P" % (
                            ctx, "more" if len(xa) - ea0 > len(xb) - eb0 else "fewer"), "G %s | P %s" % (xa, xb)))
                    elif (sa_.get("bold") is None) != (sb_.get("bold") is None):
                        divs.append(("strip-layout", "bold", "strip at %s: %s marks an autocorrection (bold), the other does not" % (
                            ctx, "G" if sa_.get("bold") is not None else "P"), "G %s | P %s" % (xa, xb)))
                    if not all(ocr_equal(x, y) for x, y in zip(xa, xb)) or len(xa) != len(xb):
                        word = word_before(ta or "", a.get("selStart") or len(ta or ""))
                        ea = sum("<emoji>" in x for x in xa)
                        eb = sum("<emoji>" in x for x in xb)
                        if ea != eb:
                            sub = "emoji"
                            sig = "strip at %s: %s" % (ctx, "emoji suggestions on G only" if not eb else
                                                        "emoji suggestions on P only" if not ea else
                                                        "more emoji suggestions on %s" % ("G" if ea > eb else "P"))
                        elif ctx == "mid-word":
                            lit_a = any(norm_word(x).lower() == word.lower() for x in xa)
                            lit_b = any(norm_word(x).lower() == word.lower() for x in xb)
                            if lit_a != lit_b:
                                sub = "literal"
                                sig = "strip mid-word: the typed word is shown on %s only" % ("G" if lit_a else "P")
                            else:
                                sub = "candidates"
                                sig = "strip mid-word: candidates differ"
                        else:
                            sub = "predictions"
                            sig = "strip at %s: predictions differ" % ctx
                        si = strip_intent(m, xa, xb) if ctx == "mid-word" else None
                        divs.append(("strip-content", sub, sig, "G %s | P %s" % (xa, xb), si))
            # what the app received besides text: key events (e.g. DEL in an empty field) and editor actions
            # (shift key events are compared on their own: the farm's Gboard emulator sends them, the phone's does not)
            eva_all = [h[1] for h in (a.get("hist") or []) if h[0] >= 0 and (h[1].startswith("key:0:") or h[1].startswith("action:"))]
            evb_all = [h[1] for h in (b.get("hist") or []) if h[0] >= 0 and (h[1].startswith("key:0:") or h[1].startswith("action:"))]
            for shift_group in (False, True):
                eva = [e for e in eva_all if (e in SHIFT_KEY_EVENTS) == shift_group]
                evb = [e for e in evb_all if (e in SHIFT_KEY_EVENTS) == shift_group]
                if eva == evb:
                    continue
                name = lambda e: {"key:0:67": "DEL key events", "key:0:66": "ENTER key events", "key:0:59": "SHIFT key events",
                                  "key:0:60": "SHIFT key events"}.get(e, e.replace("action:", "editor action "))
                sa, sb = set(eva), set(evb)
                if sa != sb:
                    sig = "%s: %s" % (step_class(m, op), "; ".join(
                        ["%s on G only" % name(e) for e in sorted(sa - sb)] + ["%s on P only" % name(e) for e in sorted(sb - sa)]))
                else:
                    sig = "%s: %s key events/actions on G" % (step_class(m, op), "more" if len(eva) > len(evb) else "fewer")
                divs.append(("editor-events", "events", sig, "G %d %s | P %d %s" % (len(eva), sorted(sa), len(evb), sorted(sb))))
            # transient states (only when the step itself ended the same on both)
            if ta == tb and (m or {}).get("k") != "burst":
                xa, xb = transients(a, (prev[0] or {}).get("text")), transients(b, (prev[1] or {}).get("text"))
                if xa != xb:
                    who = "G" if xa and not xb else "P" if xb and not xa else "both"
                    divs.append(("transient", "text", "%s: %s shows an intermediate text before the final one" % (step_class(m, op), who),
                                 "G %r | P %r" % (xa, xb)))
            # timing
            la, lb = latency(a, m), latency(b, m)
            if la is not None and lb is not None and abs(la - lb) > 150 and max(la, lb) > 200:
                divs.append(("timing", "latency", "%s: the text changes later on %s" % (step_class(m, op), "P" if lb > la else "G"),
                             "text changes %d ms after the touch on G, %d ms on P" % (la, lb)))
            ia_, ib_ = a.get("idleMs"), b.get("idleMs")
            if ia_ is not None and ib_ is not None and abs(ia_ - ib_) > 500 and a.get("idle") and b.get("idle"):
                divs.append(("timing", "settle", "%s: the keyboard settles later on %s" % (step_class(m, op), "P" if ib_ > ia_ else "G"),
                             "settles after %d ms on G, %d ms on P" % (ia_, ib_)))
            for dv in divs:
                kind, sub, sig, detail = dv[:4]
                d = mk(sid, sc, i, steps, kind, sub, sig, a, b, prev, detail)
                if len(dv) > 4 and dv[4] is not None:
                    d["intent"] = dv[4]
                if kind not in first:
                    first[kind] = d
                    d["first"] = True
                out.append(d)
            prev = (a, b)
            # Later steps start from different states (text, keyboard page or shift): their divergences are
            # consequences of this one, so the scenario stops here.
            if ta != tb or any(d[0] == "focus" or (d[0] == "keyboard" and d[1] in ("page", "shift")) for d in divs):
                break
    return out


def mk(sid, sc, i, steps, kind, sub, sig, a, b, prev, detail=""):
    m = steps[i].get("m") if 0 <= i < len(steps) else {}
    return {"sc": sid, "src": sc.get("src"), "item": sc.get("item"), "step": i, "kind": kind, "subkind": sub,
            "signature": sig, "detail": detail, "m": m,
            "prevText": (prev[0] or {}).get("text") if prev and prev[0] else None,
            "prevSel": (prev[0] or {}).get("selStart") if prev and prev[0] else None,
            "G": obs_summary(a), "P": obs_summary(b), "field": sc.get("field")}


def obs_summary(o):
    if not o:
        return None
    s = o.get("strip") or {}
    return {"text": o.get("text"), "sel": [o.get("selStart"), o.get("selEnd")], "comp": [o.get("compStart"), o.get("compEnd")],
            "load1": o.get("load1"),
            "strip": {"mode": s.get("mode"), "slots": [x["text"] for x in s.get("slots", [])], "bold": s.get("bold")},
            "kb": o.get("kb"), "ime": o.get("ime"), "idleMs": o.get("idleMs")}


def cluster(divs, allow):
    cl = {}
    for d in divs:
        d["allowed"] = allowed(d, allow)
        # ground truth splits a cluster: where only one keyboard matched what the person meant
        side = d.get("intent") if d.get("intent") in ("G", "P") else None
        sig = d["signature"] + ({"G": " [Gboard matched the intended text]", "P": " [pboard matched the intended text]"}[side]
                                if side else "")
        key = (d["kind"], sig)
        c = cl.setdefault(key, {"kind": d["kind"], "subkind": d["subkind"], "signature": sig, "intentSide": side,
                                "allowed": d["allowed"], "n": 0, "scenarios": set(), "first": 0, "examples": [],
                                "srcs": set()})
        c["n"] += 1
        c["scenarios"].add(d["sc"])
        c["srcs"].add(d["src"])
        if d.get("first"):
            c["first"] += 1
        c["examples"].append(d)
        if d.get("intent"):
            c.setdefault("intent", {})
            c["intent"][d["intent"]] = c["intent"].get(d["intent"], 0) + 1
    out = []
    for c in cl.values():
        imp = IMPACT.get(c["kind"], 1)
        if c["kind"] == "text":
            imp = SUBIMPACT.get(c["subkind"], 5)
        c["impact"] = imp
        c["score"] = len(c["scenarios"]) * imp
        c["scenarios"] = sorted(c["scenarios"])
        c["srcs"] = sorted(s or "?" for s in c["srcs"])
        # shortest example first (best starting point for a repro)
        c["examples"].sort(key=lambda d: (d["step"], len(d["sc"])))
        out.append(c)
    out.sort(key=lambda c: (c["allowed"] is not None, -c["score"], -c["n"]))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("gboard")
    ap.add_argument("pboard")
    ap.add_argument("--allowlist", default=os.path.join(HERE, "allowlist.yaml"))
    ap.add_argument("--out")
    ap.add_argument("--meta", help="regenerated scenarios.jsonl whose step metadata (m) is merged in")
    a = ap.parse_args()
    ga = read_jsonl(os.path.join(a.gboard, "steps.jsonl"))
    pa = read_jsonl(os.path.join(a.pboard, "steps.jsonl"))
    scen = {s["id"]: s for s in read_jsonl(os.path.join(a.gboard, "scenarios.jsonl"))}
    if a.meta:
        # step metadata (e.g. ground truth added later) from a regenerated scenario file with the same ids/seeds
        for s_ in read_jsonl(a.meta):
            t = scen.get(s_["id"])
            if t:  # same generator and seed: one is a prefix of the other
                for st, sm in zip(t["steps"], s_["steps"]):
                    if sm.get("m"):
                        st["m"] = dict(st.get("m") or {}, **sm["m"])
    divs = compare(ga, pa, scen)
    cl = cluster(divs, load_allowlist(a.allowlist))
    res = {"divergences": divs, "clusters": cl}
    out = a.out or os.path.join(os.path.dirname(os.path.abspath(a.gboard)), "divergences.json")
    json.dump(res, open(out, "w"), indent=1, ensure_ascii=False, default=list)
    for c in cl[:40]:
        print("%-6s %3d sc %3d first  %-14s %s%s" % (c["score"], len(c["scenarios"]), c["first"], c["kind"],
                                                    c["signature"], "  [allowed: %s]" % c["allowed"] if c["allowed"] else ""))
    print("-> %s (%d divergences, %d clusters)" % (out, len(divs), len(cl)))


if __name__ == "__main__":
    main()
