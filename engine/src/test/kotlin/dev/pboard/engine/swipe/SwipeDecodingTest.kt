package dev.pboard.engine.swipe

import dev.pboard.engine.Context
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.eval.Synth
import dev.pboard.engine.eval.SwiperProfile
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The SHARK² template matcher ([GestureDecoder]): the fallback for non-QWERTY layouts and the second opinion. */
class SwipeDecodingTest {
    private val templates = dev.pboard.engine.decode.EngineConfig(swipe = SwipeParams(neural = false))
    private val engine = Fixtures.englishEngine(templates)
    private val mid = Context(listOf("so"), false)

    private fun top(path: List<TouchPoint>, ctx: Context = mid) = engine.suggestForSwipe(ctx, path).candidates.map { it.text }

    @Test fun `clean gestures decode`() {
        for (w in listOf("hello", "people", "thank", "something", "nothing", "believe")) {
            assertEquals(w, top(Touches.swipe(w)).first(), w)
        }
    }

    @Test fun `repeated letters are visited once`() {
        assertEquals("hello", top(Touches.swipe("helo")).first())
        assertTrue("letter" in top(Touches.swipe("leter")).take(3))
        assertTrue("little" in top(Touches.swipe("litle")).take(3))
    }

    @Test fun `short words`() {
        for (w in listOf("in", "on", "it", "is", "to", "we", "up", "my", "the", "you")) {
            assertEquals(w, top(Touches.swipe(w)).first(), w)
        }
    }

    @Test fun `contractions and capitals from gestures`() {
        assertEquals("don't", top(Touches.swipe("dont")).first())
        assertEquals("I'm", top(Touches.swipe("im")).first())
        assertEquals("Hello", top(Touches.swipe("helo"), Context(emptyList(), true)).first())
    }

    @Test fun `a superseded gesture decode gives up`() {
        val e = Fixtures.englishEngine(templates)
        e.cancelled = { true }
        assertTrue(e.suggestForSwipe(mid, Touches.swipe("something")).candidates.isEmpty())
        e.cancelled = null
        assertEquals("something", e.suggestForSwipe(mid, Touches.swipe("something")).candidates.first().text)
    }

    @Test fun `gesture is committed by default`() {
        val s = engine.suggestForSwipe(mid, Touches.swipe("people"))
        assertEquals(0, s.autoCorrectIndex)
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
}
