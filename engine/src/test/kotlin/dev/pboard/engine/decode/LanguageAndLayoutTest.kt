package dev.pboard.engine.decode

import dev.pboard.engine.Context
import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Touches
import dev.pboard.engine.testutil.Touches.committed
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanguageAndLayoutTest {
    private val mid = Context(listOf("so"), false)

    private fun layout(vararg rows: String): KeyboardGeometry {
        val keys = ArrayList<KeyGeom>()
        for ((r, row) in rows.withIndex()) for ((i, c) in row.withIndex()) {
            keys.add(KeyGeom(c.toString(), 54f + 108f * i + 27f * r, 70f + 140f * r, 98f, 140f))
        }
        return KeyboardGeometry(keys, 1080f, 560f)
    }

    @Test fun `AZERTY layout decodes French with accents folded`() {
        val g = layout("azertyuiop", "qsdfghjklm", "wxcvbn'")
        val (lm, lex) = Fixtures.frenchLexicon()
        val e = PboardEngine(lm, lex).also { it.setGeometry(g) }
        fun commit(typed: String) = committed(e.suggestForTaps(mid, Touches.taps(typed, g), typed), typed)
        assertEquals("bonjour", commit("bonjour"))
        assertEquals("déjà", commit("deja"))
        assertEquals("français", commit("francais"))
        assertEquals("garçon", commit("garcon"))
        // Elided article typed without the apostrophe; the layout has an apostrophe key.
        assertEquals("l'homme", commit("lhomme"))
        assertEquals("l'homme", commit("l'homme"))
    }

    @Test fun `Cyrillic layout`() {
        val g = layout("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        val lm = Fixtures.lm(listOf("привет", "как", "дела", "что", "это", "спасибо"), emptyList())
        val lex = Lexicon.build(lm.vocabulary(), TextRules.forLanguageTag("ru"))
        val e = PboardEngine(lm, lex).also { it.setGeometry(g) }
        // One neighbour-key slip: 'и' -> 'т'.
        val s = e.suggestForTaps(mid, Touches.taps("пртвет", g), "пртвет")
        assertEquals("привет", committed(s, "пртвет"))
        assertEquals("Привет", committed(e.suggestForTaps(Context(emptyList(), true), Touches.taps("привет", g), "Привет"), "Привет"))
    }

    @Test fun `two languages at once without switching`() {
        val en = Fixtures.lm(Fixtures.words())
        val (frLm, frLex) = Fixtures.frenchLexicon()
        val e = PboardEngine(listOf(
            LanguageSlot("en", en, Lexicon.build(en.vocabulary(), Fixtures.en), 1f),
            LanguageSlot("fr", frLm, frLex, 0.5f),
        )).also { it.setGeometry(dev.pboard.engine.testutil.Layouts.pixel8Qwerty()) }
        fun commit(typed: String) = committed(e.suggestForTaps(mid, Touches.taps(typed), typed), typed)
        assertEquals("hello", commit("hrllo"))
        assertEquals("déjà", commit("deja"))
        assertEquals("merci", commit("merci"))
        assertEquals("don't", commit("dont"))
    }

    @Test fun `Turkish capitalisation`() {
        val tr = TextRules.forLanguageTag("tr")
        assertEquals("İstanbul", tr.capitalize("istanbul"))
        assertEquals("ırmak", tr.lowercase("IRMAK"))
        assertEquals("İstanbul", Casing.apply("istanbul", TypedShape.LOWER, true, tr))
    }

    @Test fun `text rules`() {
        val en = TextRules.forLanguageTag("en")
        assertEquals("dont", en.skeleton("don't"))
        assertEquals("iphone", en.skeleton("iPhone"))
        assertEquals("deja", en.skeleton("déjà"))
        val fr = TextRules.forLanguageTag("fr")
        assertTrue(fr.spaceBefore('!'))
        assertTrue("l'" in fr.elisionPrefixes)
        assertTrue(TextRules.forLanguageTag("de").compounding)
        assertEquals(Locale.forLanguageTag("de"), TextRules.forLanguageTag("de").locale)
    }

    @Test fun `English dialect variant keys`() {
        val en = TextRules.forLanguageTag("en-GB")
        for ((gb, us) in Fixtures.DIALECT_PAIRS + listOf("travellers" to "travelers", "metres" to "meters", "paediatric" to "pediatric", "jewellery" to "jewelry")) {
            assertEquals(en.variantKey(us), en.variantKey(gb), "$gb/$us")
        }
        for ((a, b) in listOf("four" to "for", "filled" to "filed", "hour" to "hor", "prise" to "prize", "filling" to "filing", "your" to "yor")) {
            assertNotEquals(en.variantKey(a), en.variantKey(b), "$a/$b")
        }
    }

    @Test fun `lexicon trie basics`() {
        val lex = Lexicon.build(Fixtures.entries("the", "they", "them", "then", "The", "don't", "dont"), Fixtures.en)
        val node = lex.find("the")
        assertTrue(node > 0 && lex.isTerminal(node))
        assertEquals(setOf("the", "The"), lex.wordsAt(node).map { lex.word(it) }.toSet())
        assertEquals(listOf("they", "them", "then"), lex.topCompletions(node, 5).map { lex.word(it) })
        assertEquals("don't", lex.pathOf(lex.find("don't")))
        assertEquals(-1, lex.find("xyz"))
    }
}
