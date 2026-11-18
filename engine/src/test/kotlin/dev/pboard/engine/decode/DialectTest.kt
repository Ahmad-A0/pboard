package dev.pboard.engine.decode

import dev.pboard.engine.Context
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Touches
import dev.pboard.engine.testutil.Touches.committed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** en-GB and en-US must be equally good, and neither may "correct" the other's valid spellings. */
class DialectTest {
    private val gb = Fixtures.dialectEngine("en-GB")
    private val us = Fixtures.dialectEngine("en-US")
    private val ctx = Context(listOf("about"), false)

    private fun commit(e: PboardEngine, typed: String) = committed(Touches.type(e, typed, ctx), typed)

    @Test fun `valid spellings of either dialect are never autocorrected`() {
        for ((b, a) in Fixtures.DIALECT_PAIRS) {
            assertEquals(b, commit(gb, b), "en-GB keeps $b")
            assertEquals(a, commit(gb, a), "en-GB keeps $a")
            assertEquals(b, commit(us, b), "en-US keeps $b")
            assertEquals(a, commit(us, a), "en-US keeps $a")
        }
    }

    @Test fun `misspellings are corrected towards the active dialect`() {
        assertEquals("organisation", commit(gb, "organisaton"))
        assertEquals("organization", commit(us, "organizaton"))
        assertEquals("organization", commit(us, "organisaton"))
        assertEquals("travelling", commit(gb, "travling"))
        assertEquals("traveling", commit(us, "travling"))
        assertEquals("colour", commit(gb, "colout"))
        assertEquals("color", commit(us, "colot"))
        assertEquals("favourite", commit(gb, "favouritw"))
        assertEquals("favorite", commit(us, "favoritw"))
    }

    @Test fun `the other dialect's spelling remains available as a suggestion`() {
        val s = Touches.type(gb, "colout", ctx)
        assertEquals("colour", committed(s, "colout"))
        assertTrue(s.candidates.any { it.text == "color" } || s.candidates.size < 3, s.toString())
    }

    @Test fun `gestures follow the active dialect`() {
        assertEquals("colour", gb.suggestForSwipe(ctx, Touches.swipe("colour")).candidates.first().text)
        assertEquals("color", us.suggestForSwipe(ctx, Touches.swipe("color")).candidates.first().text)
        assertEquals("realise", gb.suggestForSwipe(ctx, Touches.swipe("realise")).candidates.first().text)
        assertEquals("realize", us.suggestForSwipe(ctx, Touches.swipe("realize")).candidates.first().text)
    }
}
