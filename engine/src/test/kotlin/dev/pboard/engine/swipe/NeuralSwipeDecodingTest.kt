package dev.pboard.engine.swipe

import dev.pboard.engine.Context
import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.eval.SwiperProfile
import dev.pboard.engine.eval.Synth
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine with the neural gesture decoder ([SwipeParams.neural]) and the bundled model. */
class NeuralSwipeDecodingTest {
    private val config = EngineConfig(swipe = SwipeParams(neural = true))
    private val engine = Fixtures.englishEngine(config)
    private val mid = Context(listOf("so"), false)

    private fun top(path: List<TouchPoint>, ctx: Context = mid) = engine.suggestForSwipe(ctx, path).candidates.map { it.text }

    /** A human-paced gesture through the key centres of [keys] (~200 ms per segment, 60 Hz). */
    private fun glide(keys: String, g: KeyboardGeometry = Layouts.pixel8Qwerty()): List<TouchPoint> {
        val pts = keys.map { c -> g.keys.first { it.label == c.toString() }.let { it.cx to it.cy } }
        val out = ArrayList<TouchPoint>()
        var t = 0L
        out.add(TouchPoint(pts[0].first, pts[0].second, t))
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            for (s in 1..12) { t += 16; out.add(TouchPoint(x0 + (x1 - x0) * s / 12, y0 + (y1 - y0) * s / 12, t)) }
        }
        return out
    }

    @Test fun `bundled model is present and used`() {
        assertNotNull(SwipeModel.bundled())
        assertNotNull(engine.swipeModel)
    }

    @Test fun `clean gestures decode`() {
        for (w in listOf("hello", "people", "thank", "something", "nothing", "believe")) {
            assertEquals(w, top(glide(w)).first(), w)
            assertTrue(w in top(Touches.swipe(w)).take(3), "fast $w")
        }
    }

    @Test fun `repeated letters are visited once`() {
        assertEquals("hello", top(glide("helo")).first())
        assertTrue("letter" in top(glide("leter")).take(3))
        assertTrue("little" in top(glide("litle")).take(3))
    }

    @Test fun `short words`() {
        for (w in listOf("in", "on", "it", "is", "to", "we", "up", "my", "the", "you")) {
            assertTrue(w in top(glide(w)).take(3), w)
        }
    }

    @Test fun `contractions and capitals from gestures`() {
        assertEquals("don't", top(glide("dont")).first())
        assertEquals("Hello", top(glide("helo"), Context(emptyList(), true)).first())
    }

    @Test fun `a superseded gesture decode gives up`() {
        val e = Fixtures.englishEngine(config)
        e.cancelled = { true }
        assertTrue(e.suggestForSwipe(mid, glide("something")).candidates.isEmpty())
        e.cancelled = null
        assertEquals("something", e.suggestForSwipe(mid, glide("something")).candidates.first().text)
    }

    @Test fun `a tap-sized gesture is decoded as a tap`() {
        val k = Layouts.key(Layouts.pixel8Qwerty(), "a")
        val s = engine.suggestForSwipe(mid, listOf(TouchPoint(k.cx, k.cy, 0), TouchPoint(k.cx + 3, k.cy + 2, 30)))
        assertEquals("a", s.candidates.first().text)
    }

    @Test fun `sloppy human-like gestures still decode`() {
        val synth = Synth(Layouts.pixel8Qwerty())
        val rng = Random(7)
        var hits = 0; var n = 0
        for (w in listOf("hello", "people", "thank", "something", "believe", "friend", "money", "school", "mother", "night")) {
            repeat(3) {
                val prof = SwiperProfile.random(rng)
                n++
                if (w in top(synth.swipe(w, prof, rng)).take(3)) hits++
            }
        }
        assertTrue(hits >= n * 0.9, "top-3 $hits/$n")
    }

    @Test fun `the same words decode on a shorter keyboard (350-399 dp size set)`() {
        val g = Layouts.pixel8Qwerty()
        val y0 = g.keys.first { it.label == "q" }.cy
        val short = KeyboardGeometry(g.keys.map { KeyGeom(it.label, it.cx, y0 + (it.cy - y0) * 0.9f, it.width, it.height * 0.9f, it.alternates) }, g.width, g.height)
        val e = Fixtures.englishEngine(config).also { it.setGeometry(short) }
        for (w in listOf("hello", "people", "something")) assertEquals(w, e.suggestForSwipe(mid, glide(w, short)).candidates.first().text)
    }

    /** An AZERTY-style letter block (a/q and z/w swapped, m on the middle row), Pixel 8 pitches. */
    private fun azerty(): KeyboardGeometry {
        val rows = listOf("azertyuiop" to 0f, "qsdfghjklm" to 0f, "wxcvbn" to 1.5f)
        val keys = ArrayList<KeyGeom>()
        for ((r, row) in rows.withIndex()) for ((i, c) in row.first.withIndex())
            keys.add(KeyGeom(c.toString(), 53.5f + 107f * (i + row.second), 1715.5f + 154.75f * r, 107f, 154f))
        return KeyboardGeometry(keys, 1080f, 2400f)
    }

    @Test fun `a layout-agnostic model decodes other layouts itself`() {
        val model = SwipeModel.bundled() ?: return
        val g = azerty()
        val ks = dev.pboard.engine.decode.KeySpace(g, dev.pboard.engine.decode.TextRules.forLanguageTag("en"))
        if (model.version < 2) { assertNull(NeuralSwipeEncoder.create(model, ks)); return }
        assertNotNull(NeuralSwipeEncoder.create(model, ks))
        val e = Fixtures.englishEngine(config).also { it.setGeometry(g) }
        for (w in listOf("hello", "people", "something")) assertTrue(w in e.suggestForSwipe(mid, glide(w, g)).candidates.map { it.text }.take(3), w)
    }

    /** The version 2 path end to end (any layout, auxiliary network on short strokes), with a tiny random model. */
    @Test fun `a version 2 model with an auxiliary network runs on QWERTY and AZERTY`() {
        val tiny = SwipeModel.read(javaClass.getResourceAsStream("/swipe/v2_tiny.pbsw")!!.readBytes())
        for (g in listOf(Layouts.pixel8Qwerty(), azerty())) {
            val e = Fixtures.englishEngine(config).also { it.swipeModel = tiny; it.setGeometry(g) }
            for (w in listOf("to", "hello", "something")) assertTrue(e.suggestForSwipe(mid, glide(w, g)).candidates.isNotEmpty(), w)
        }
    }

    /**
     * The IME describes keys by hit box (BuiltKeyboard.engineGeometry): Gboard's `a` and `l` boxes run to the screen
     * edge, so their centres sit 0.28 key from the drawn keys. That is still QWERTY for the neural decoder.
     */
    @Test fun `hit-box centres of the row-end keys do not disable the neural decoder`() {
        val g = Layouts.pixel8Qwerty()
        val hit = KeyboardGeometry(g.keys.map { k ->
            when (k.label) { "a" -> k.copy(cx = k.cx - 29.5f, width = 165f); "l" -> k.copy(cx = k.cx + 29.5f, width = 166f); else -> k }
        }, g.width, g.height)
        val rules = dev.pboard.engine.decode.TextRules.forLanguageTag("en")
        assertNotNull(SwipeFeatures.fitQwerty(dev.pboard.engine.decode.KeySpace(hit, rules)))
        val e = Fixtures.englishEngine(config).also { it.setGeometry(hit) }
        for (w in listOf("hello", "always", "about", "tell", "school")) assertTrue(w in e.suggestForSwipe(mid, glide(w, g)).candidates.map { it.text }.take(3), w)
        // A letter moved by a whole key is a different layout.
        val moved = KeyboardGeometry(g.keys.map { k -> if (k.label == "a") k.copy(cx = k.cx + 107f) else k }, g.width, g.height)
        assertNull(SwipeFeatures.fitQwerty(dev.pboard.engine.decode.KeySpace(moved, rules)))
    }

    @Test fun `layouts that are not QWERTY still decode (template fallback for v1 models, v2 decodes them itself)`() {
        val g = Layouts.pixel8Qwerty()
        // Swap two letters: a v1 model's classes would no longer match the keys.
        val swapped = KeyboardGeometry(g.keys.map { k -> when (k.label) { "y" -> k.copy(label = "z"); "z" -> k.copy(label = "y"); else -> k } }, g.width, g.height)
        val ks = dev.pboard.engine.decode.KeySpace(swapped, dev.pboard.engine.decode.TextRules.forLanguageTag("en"))
        assertNull(SwipeFeatures.fitQwerty(ks))
        val e = Fixtures.englishEngine(config).also { it.setGeometry(swapped) }
        assertEquals("hello", e.suggestForSwipe(mid, glide("helo", swapped)).candidates.first().text)
    }

    /**
     * Conformance fuzz/1/1 step 11: a glide f-o-r runs over u on its way back, so "four" fits the path as well, and at a
     * sentence start the language model makes it "For" (Gboard 18.6). pboard gave "Four" only after its user model had
     * learned "four" from an app's prefilled text (fixed in the IME: ImeRules.learnsCommittedWord).
     */
    @Test fun `for at a sentence start on the shipped packs`() {
        val dir = java.io.File(dev.pboard.engine.devlm.DevData.projectRoot(), "app/src/main/assets/lm")
        org.junit.Assume.assumeTrue(java.io.File(dir, "en.lm").exists())
        val pack = dev.pboard.engine.lm.EnglishPack.load("en-US", dir, dev.pboard.engine.lm.UserNgramModel(), useNeural = true)
        val e = dev.pboard.engine.decode.PboardEngine.create(listOf(pack.spec()), EngineConfig()).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        val start = Context(emptyList(), sentenceStart = true)
        assertEquals(listOf("For", "Four"), e.suggestForSwipe(start, glide("for")).candidates.take(2).map { it.text })
        assertEquals("For", e.suggestForSwipePreview(start, glide("for")).candidates.first().text)
    }
}
