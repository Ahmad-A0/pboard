package dev.pboard.engine.decode

import dev.pboard.engine.CandidateKind
import dev.pboard.engine.Context
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches
import dev.pboard.engine.testutil.Touches.committed
import dev.pboard.engine.testutil.Touches.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contact names ([dev.pboard.engine.KeyboardEngine.setContactNames]): an in-memory names vocabulary. */
class ContactNamesTest {
    private val met = Context(listOf("I", "met"), false)

    private fun engine(): PboardEngine {
        val words = Fixtures.words() + listOf("met", "told", "John", "Told", "rose", "will")
        val lm = Fixtures.lm(words, Fixtures.DEFAULT_BIGRAMS + listOf("I" to "told", "I" to "told", "I" to "met"),
            boost = mapOf("told" to 2.0, "Told" to -6.0, "rose" to 2.0, "will" to 3.0))
        return PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }

    @Test fun `a contact name typed capitalised is kept and a lower-case one gets its capital`() {
        val e = engine()
        // Without the contact, lower-case "tolu" becomes an ordinary word.
        assertTrue(committed(type(e, "tolu", met), "tolu") != "Tolu")
        e.setContactNames(listOf("Tolu", "Adebayo"))
        assertEquals("Tolu", committed(type(e, "Tolu", met), "Tolu"))
        assertEquals("Tolu", committed(type(e, "tolu", met), "tolu"))
        // In a field without capitals the lower-case word stays as typed, the name is offered.
        val s = type(e, "tolu", met.copy(capitalsAllowed = false))
        assertTrue(committed(s, "tolu") != "Tolu", s.toString())
        assertTrue(s.candidates.take(3).any { it.text == "Tolu" }, s.toString())
    }

    @Test fun `a capitalised typo is corrected into a contact name and the name is offered as a completion`() {
        val e = engine()
        assertTrue(committed(type(e, "Adebsyo", met), "Adebsyo") != "Adebayo")
        e.setContactNames(listOf("Tolu", "Adebayo"))
        assertEquals("Adebayo", committed(type(e, "Adebsyo", met), "Adebsyo"))
        val s = type(e, "Adeb", met)
        assertTrue(s.candidates.take(3).any { it.text == "Adebayo" && it.kind == CandidateKind.COMPLETION }, s.toString())
        val low = type(e, "adeb", met)
        assertTrue(low.candidates.take(3).any { it.text == "Adebayo" }, low.toString())
    }

    @Test fun `a contact name that is a common word leaves the word alone`() {
        val e = engine()
        val before = type(e, "rose", met)
        e.setContactNames(listOf("Rose", "Will"))
        val after = type(e, "rose", met)
        assertEquals("rose", committed(after, "rose"))
        assertEquals(before.candidates.map { it.text }, after.candidates.map { it.text })
        assertEquals("will", committed(type(e, "will", met), "will"))
    }

    @Test fun `contact names can be glided`() {
        val e = engine()
        e.setContactNames(listOf("Adebayo"))
        val s = e.suggestForSwipe(met, Touches.swipe("adebayo"))
        assertEquals("Adebayo", s.candidates.firstOrNull()?.text, s.toString())
    }

    @Test fun `contact names are replaced as a set and never exported`() {
        val e = engine()
        val empty = e.exportUserData()
        e.setContactNames(listOf("Tolu", "Adebayo", "x", "a1b2", "two words"))
        assertEquals("Tolu", committed(type(e, "tolu", met), "tolu"))
        assertTrue(e.exportUserData().contentEquals(empty))
        assertFalse(String(e.exportUserData(), Charsets.ISO_8859_1).contains("Adebayo"))
        // Learned-data reset is not a contacts reset; an empty set turns the feature off.
        e.clearUserData()
        assertEquals("Tolu", committed(type(e, "tolu", met), "tolu"))
        e.setContactNames(emptyList())
        assertTrue(committed(type(e, "tolu", met), "tolu") != "Tolu")
        assertFalse(type(e, "Adeb", met).candidates.any { it.text == "Adebayo" })
    }

    @Test fun `nothing is learned from a contact name in a no-learning field`() {
        val e = engine()
        e.setContactNames(listOf("Tolu"))
        val empty = e.exportUserData()
        val ctx = met.copy(learningAllowed = false)
        assertEquals("Tolu", committed(type(e, "Tolu", ctx), "Tolu"))
        e.onWordCommitted(ctx, "Tolu", true)
        assertTrue(e.exportUserData().contentEquals(empty))
    }

    @Test fun `a removed suggestion is not offered from contacts either`() {
        val e = engine()
        e.setContactNames(listOf("Adebayo"))
        e.removeSuggestion("Adebayo")
        assertFalse(type(e, "Adeb", met).candidates.any { it.text == "Adebayo" })
    }
}
