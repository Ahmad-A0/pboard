package dev.pboard.engine.decode

import dev.pboard.engine.Context
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Layouts
import kotlin.test.Test
import kotlin.test.assertEquals

/** TF-01 tap-time key resolution (device-verified Gboard behaviour). */
class TapResolveTest {
    private val engine = run {
        val lm = Fixtures.lm(Fixtures.words() + listOf("car", "care", "card", "cars", "cat"))
        PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }
    private val mid = Context(listOf("so"), false)
    private val g = Layouts.pixel8Qwerty()
    private val e = Layouts.key(g, "e")
    private val r = Layouts.key(g, "r")
    private val border = (e.cx + r.cx) / 2

    /** A tap [depth] of a key width inside `r`, measured from the e/r border. */
    private fun intoR(depth: Float) = TouchPoint(border + depth * g.keyWidth, r.cy)

    @Test fun `after wh a tap inside r near the e border types e`() {
        for (d in listOf(0.03f, 0.17f)) assertEquals("e", engine.resolveTap(mid, "wh", intoR(d), "r"), "depth $d")
        // 0.19 key widths from the centre of r: the key under the finger, as Gboard 18.6 ("Be ho" + a tap 0.18 from the
        // centre of e types e; "I do donn" + a tap 0.21 from the centre of o types o).
        assertEquals("r", engine.resolveTap(mid, "wh", intoR(0.31f), "r"))
    }

    @Test fun `after ca every depth inside r types r`() {
        for (d in listOf(0.03f, 0.17f, 0.31f, 0.49f)) assertEquals("r", engine.resolveTap(mid, "ca", intoR(d), "r"), "depth $d")
    }

    @Test fun `a tap at the key centre stays literal`() {
        assertEquals("r", engine.resolveTap(mid, "wh", intoR(0.49f), "r"))
        assertEquals("r", engine.resolveTap(mid, "wh", TouchPoint(r.cx, r.cy), "r"))
    }

    @Test fun `shift state is kept and exact fields are left alone`() {
        assertEquals("E", engine.resolveTap(mid, "Wh", intoR(0.1f), "R"))
        assertEquals("r", engine.resolveTap(mid.copy(exactKeys = true), "wh", intoR(0.1f), "r"))
        assertEquals("r", engine.resolveTap(mid.copy(autoCorrectAllowed = false), "wh", intoR(0.1f), "r"))
        // Keys that are not letters of the layout are never resolved.
        assertEquals(".", engine.resolveTap(mid, "wh", intoR(0.1f), "."))
    }

    @Test fun `taps at the edges of the period and shift keys resolve inside a word`() {
        val lm = Fixtures.lm(Fixtures.words() + listOf("home", "hold", "deal", "dell", "zone"))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en), EngineConfig(tapResolve = TapResolveParams(otherKeys = true)))
            .also { it.setGeometry(g) }
        val dot = Layouts.key(g, "."); val shift = Layouts.key(g, "shift")
        val m = Layouts.key(g, "m"); val a = Layouts.key(g, "a")
        // "ho" + a tap on the top edge of "." under `m`: "hom" (home), as Gboard 18.6 on the human VTAP set.
        assertEquals("m", e.resolveTap(mid, "ho", TouchPoint(m.cx, dot.cy - dot.height / 2 + 6f), "."))
        // Deep inside the key, or at the start of a word, the key is the key.
        assertEquals(".", e.resolveTap(mid, "ho", TouchPoint(dot.cx, dot.cy + 30f), "."))
        assertEquals(".", e.resolveTap(mid, "", TouchPoint(m.cx, dot.cy - dot.height / 2 + 6f), "."))
        // After a whole word the period's edge is the period (its prior is the word ending, as for the space bar).
        assertEquals(".", e.resolveTap(mid, "hello", TouchPoint(m.cx, dot.cy - dot.height / 2 + 12f), "."))
        // "de" + a tap on the top edge of shift under `a`: "dea" (deal).
        assertEquals("a", e.resolveTap(mid, "de", TouchPoint(a.cx, shift.cy - shift.height / 2 + 6f), "shift"))
        assertEquals("shift", e.resolveTap(mid, "de", TouchPoint(shift.cx - 30f, shift.cy + 30f), "shift"))
        // Off: the rectangle's key.
        val off = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en), EngineConfig(tapResolve = TapResolveParams(otherKeys = false)))
            .also { it.setGeometry(g) }
        assertEquals(".", off.resolveTap(mid, "ho", TouchPoint(m.cx, dot.cy - dot.height / 2 + 6f), "."))
    }

    @Test fun `at a word's start only a tap right at the edge of space, period or shift becomes a letter`() {
        val lm = Fixtures.lm(Fixtures.words() + listOf("maybe", "along", "next"))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(g) }
        val dot = Layouts.key(g, "."); val shift = Layouts.key(g, "shift"); val space = Layouts.key(g, "space")
        val m = Layouts.key(g, "m"); val a = Layouts.key(g, "a"); val n = Layouts.key(g, "n")
        val after = Context(listOf("I", "will", "be", "back"), false)
        // Within 0.15 key widths of the edge facing the letters, and only when the letter is far more likely than the key
        // (Gboard 18.6 on the human VTAP set: shift + "long" -> "along", space + "ext" -> "next").
        assertEquals("a", e.resolveTap(after, "", TouchPoint(a.cx, shift.cy - shift.height / 2 + 2f), "shift"))
        // The space bar and "." keep a large share of the prior: even at their edge they stay keys unless the letter is
        // overwhelmingly likely, which nothing here makes it.
        assertEquals(".", e.resolveTap(after, "", TouchPoint(m.cx, dot.cy - dot.height / 2 + 2f), "."))
        assertEquals("space", e.resolveTap(after, "", TouchPoint(n.cx, space.cy - space.height / 2 + 2f), "space"))
        // A deliberate period, shift or space after a word, a little deeper in the key, stays the key.
        assertEquals(".", e.resolveTap(after, "", TouchPoint(m.cx, dot.cy - dot.height / 2 + 20f), "."))
        assertEquals("shift", e.resolveTap(after, "", TouchPoint(a.cx, shift.cy - shift.height / 2 + 20f), "shift"))
        assertEquals("space", e.resolveTap(after, "", TouchPoint(n.cx, space.cy - space.height / 2 + 20f), "space"))
        assertEquals("space", e.resolveTap(after, "", TouchPoint(space.cx, space.cy), "space"))
        // Off: always the key.
        val off = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en), EngineConfig(tapResolve = TapResolveParams(wordStart = false)))
            .also { it.setGeometry(g) }
        assertEquals("shift", off.resolveTap(after, "", TouchPoint(a.cx, shift.cy - shift.height / 2 + 2f), "shift"))
    }

    @Test fun `taps at the space bar and delete key edges resolve inside a word`() {
        val lm = Fixtures.lm(Fixtures.words() + listOf("back", "bad", "hello", "hell", "help"))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(g) }
        val space = Layouts.key(g, "space"); val del = Layouts.key(g, "delete")
        val c = Layouts.key(g, "c"); val b = Layouts.key(g, "b"); val l = Layouts.key(g, "l")
        val spaceTop = space.cy - space.height / 2
        // "I will be ba" + a tap on the bar's top edge under `c`: "bac" (back), as Gboard.
        assertEquals("c", e.resolveTap(mid, "ba", TouchPoint(c.cx, spaceTop + 6f), "space"))
        // Deep inside the bar, or with no word to continue, the bar is the bar.
        assertEquals("space", e.resolveTap(mid, "ba", TouchPoint(c.cx, space.cy + 20f), "space"))
        assertEquals("space", e.resolveTap(mid, "", TouchPoint(c.cx, spaceTop + 6f), "space"))
        // "hello" + a tap on the bottom edge of `b`, just above the bar: the word ends.
        assertEquals("space", e.resolveTap(mid, "hello", TouchPoint(b.cx, spaceTop - 6f), "b"))
        // ... but not in the middle of a word that goes on.
        assertEquals("b", e.resolveTap(mid, "a", TouchPoint(b.cx, spaceTop - 6f), "b"))
        // "hel" + a tap on the delete key's top edge under `l` types "l".
        assertEquals("l", e.resolveTap(mid, "hel", TouchPoint(l.cx, del.cy - del.height / 2 + 6f), "delete"))
        assertEquals("delete", e.resolveTap(mid, "hel", TouchPoint(del.cx + 30f, del.cy + 20f), "delete"))
        assertEquals("delete", e.resolveTap(mid, "", TouchPoint(l.cx, del.cy - del.height / 2 + 6f), "delete"))
        // Off: the rectangle's key.
        val off = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en), EngineConfig(tapResolve = TapResolveParams(spaceAndDelete = false)))
            .also { it.setGeometry(g) }
        assertEquals("space", off.resolveTap(mid, "ba", TouchPoint(c.cx, spaceTop + 6f), "space"))
        assertEquals("b", off.resolveTap(mid, "hello", TouchPoint(b.cx, spaceTop - 6f), "b"))
    }

    @Test fun `the context prior steers the first letter`() {
        // "I" + "t…": after predictNext for "I" the LM favours "think"/"thank" over e…/r… words at a t/r border.
        val ctx = Context(listOf("I"), false)
        engine.predictNext(ctx)
        val t = Layouts.key(g, "t")
        val tap = TouchPoint((r.cx + t.cx) / 2 - 0.05f * g.keyWidth, r.cy) // just inside r
        assertEquals("t", engine.resolveTap(ctx, "", tap, "r"))
    }
}
