package dev.pboard.engine.decode

import dev.pboard.engine.Candidate
import dev.pboard.engine.CandidateKind
import dev.pboard.engine.Context
import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.LanguageSpec
import dev.pboard.engine.LexiconSource
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches
import dev.pboard.engine.testutil.Touches.committed
import dev.pboard.engine.testutil.Touches.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The additive Api.kt surface: language specs, alternates, context flags, learning and user-data hooks. */
class ApiAdditionsTest {
    private val mid = Context(listOf("so"), false)

    @Test fun `languages from API specs`() {
        val lm = Fixtures.lm(Fixtures.words())
        val e = PboardEngine.create(listOf(LanguageSpec("en-GB", lm, LexiconSource { lm.vocabulary().asSequence() })))
        e.setGeometry(Layouts.pixel8Qwerty())
        assertEquals("hello", committed(type(e, "hrllo", mid), "hrllo"))
        // setLanguages through the interface replaces them.
        val (frLm, _) = Fixtures.frenchLexicon()
        e.setLanguages(listOf(LanguageSpec("fr", frLm, LexiconSource { frLm.vocabulary().asSequence() })))
        assertEquals("déjà", committed(type(e, "deja", mid), "deja"))
    }

    @Test fun `long-press alternates route characters without a key or fold`() {
        val base = Layouts.pixel8Qwerty()
        val withAlt = KeyboardGeometry(base.keys.map { if (it.label == "d") it.copy(alternates = listOf("ð")) else it }, base.width, base.height)
        val lm = Fixtures.lm(listOf("við", "vit", "og"), emptyList())
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), TextRules.forLanguageTag("is"))).also { it.setGeometry(withAlt) }
        assertEquals("við", committed(e.suggestForTaps(mid, Touches.taps("vid"), "vid"), "vid"))
        // Typed explicitly by long-press, the alternate is taken as is.
        assertEquals("við", committed(e.suggestForTaps(mid, Touches.taps("vid"), "við"), "við"))
    }

    @Test fun `no learning when learning is not allowed`() {
        val e = Fixtures.englishEngine()
        val incognito = Context(listOf("so"), false, learningAllowed = false)
        repeat(5) { e.onWordCommitted(incognito, "zorbix", true) }
        e.onAutoCorrectReverted(incognito, "hellp", "help")
        e.onSuggestionPicked(incognito, Candidate("zorbix", 1f, CandidateKind.TYPED), "zorbix")
        assertEquals(0, e.userVocabulary.count("zorbix").toInt())
        assertFalse(e.userVocabulary.isIntended("hellp"))
    }

    @Test fun `no autocorrect where it is not allowed, but the fix is still offered first`() {
        val e = Fixtures.englishEngine()
        val urlField = Context(listOf("so"), false, autoCorrectAllowed = false)
        val s = type(e, "dont", urlField)
        assertEquals(-1, s.autoCorrectIndex)
        assertEquals("don't", s.candidates[0].text)
    }

    @Test fun `removed suggestions are no longer offered unless typed in full`() {
        val e = Fixtures.englishEngine()
        e.removeSuggestion("hello")
        val s = type(e, "hrllo", mid)
        assertFalse(s.candidates.any { it.text == "hello" }, s.toString())
        assertEquals("hello", committed(type(e, "hello", mid), "hello"))
    }

    @Test fun `picking the literal word learns it as intended`() {
        val e = Fixtures.englishEngine()
        e.onSuggestionPicked(mid, Candidate("zorbix", 0.1f, CandidateKind.TYPED), "zorbix")
        assertTrue(e.userVocabulary.isIntended("zorbix"))
    }

    @Test fun `user data survives export and import`() {
        val a = Fixtures.englishEngine()
        repeat(4) { a.onWordCommitted(mid, "zorbix", true) }
        repeat(30) {
            a.suggestForTaps(mid, Touches.taps("hello", dx = 0.3f), "hello")
            a.onWordCommitted(mid, "hello", true)
        }
        a.removeSuggestion("tomorrow")
        a.onAutoCorrectReverted(mid, "hellp", "help")
        val bytes = a.exportUserData()

        val b = Fixtures.englishEngine()
        b.importUserData(bytes)
        assertEquals(a.userVocabulary.snapshot(), b.userVocabulary.snapshot())
        assertTrue(b.userVocabulary.isIntended("hellp"))
        assertFalse(type(b, "tomorow", mid).candidates.any { it.text == "tomorrow" })
        assertEquals("hellp", committed(type(b, "hellp", mid), "hellp"))
        assertTrue(bytes.contentEquals(b.exportUserData()), "round trip is stable")

        // Corrupt or foreign data is ignored; clearing forgets everything.
        b.importUserData(byteArrayOf(1, 2, 3))
        assertEquals(a.userVocabulary.snapshot(), b.userVocabulary.snapshot())
        b.clearUserData()
        assertEquals(0, b.userVocabulary.count("zorbix").toInt())
        assertTrue(type(b, "tomorow", mid).candidates.any { it.text == "tomorrow" })
    }

    @Test fun `geometry and context keep their old constructors`() {
        val k = KeyGeom("a", 1f, 2f, 3f, 4f)
        assertTrue(k.alternates.isEmpty())
        val c = Context(listOf("x"), true)
        assertTrue(c.learningAllowed && c.autoCorrectAllowed)
    }
}
