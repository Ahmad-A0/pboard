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
import kotlin.test.assertTrue

class TapDecodingTest {
    private val engine = Fixtures.englishEngine()
    private val mid = Context(listOf("so"), sentenceStart = false)
    private val start = Context(emptyList(), sentenceStart = true)

    private fun commit(typed: String, ctx: Context = mid, keys: String = typed) = committed(type(engine, typed, ctx, keys), typed)

    @Test fun `exact valid word is kept`() {
        val s = type(engine, "hello")
        assertEquals(-1, s.autoCorrectIndex)
        assertEquals("hello", s.candidates[0].text)
        assertEquals(CandidateKind.TYPED, s.candidates[0].kind)
    }

    @Test fun `neighbour-key typo is corrected`() {
        assertEquals("hello", commit("hrllo"))
        assertEquals("letter", commit("lettet"))
    }

    @Test fun `literal typed word is always offered`() {
        val s = type(engine, "hrllo")
        assertEquals(0, s.autoCorrectIndex)
        assertTrue(s.candidates.take(2).any { it.text == "hrllo" && it.kind == CandidateKind.TYPED })
    }

    @Test fun `contractions are restored`() {
        assertEquals("don't", commit("dont"))
        assertEquals("I'm", commit("im"))
        assertEquals("can't", commit("cant"))
        assertEquals("I'll", commit("Ill", start))
    }

    @Test fun `typed apostrophe is respected`() {
        // The apostrophe comes from another keyboard page: its touch is not on the letter layout.
        val taps = Touches.taps("dont").toMutableList()
        taps.add(3, dev.pboard.engine.TouchPoint(500f, 600f, 500))
        val s = engine.suggestForTaps(mid, taps, "don't")
        assertEquals("don't", committed(s, "don't"))
    }

    @Test fun `capitalisation of I, proper nouns and brands`() {
        assertEquals("I", commit("i"))
        assertEquals("iPhone", commit("iphone"))
        // Gboard 18.6 (conformance farm full passes): a lower-case word whose lower-case form is no word gets its capital
        // in a field that asks for capitals ("brian " -> "Brian ", "friday " -> "Friday "), not in a field without
        // them ("london " stays); a lower-case word that is a word itself keeps its case ("dome", "beau").
        assertEquals("Tom", commit("tom"))
        assertEquals("English", commit("english"))
        val noCaps = mid.copy(capitalsAllowed = false)
        assertEquals("tom", commit("tom", noCaps))
        assertEquals("english", commit("english", noCaps))
        assertEquals("met", commit("met"))
        val on = Fixtures.englishEngine(EngineConfig(autocorrect = AutocorrectParams(capitaliseLowercase = true)))
        assertEquals("Tom", committed(type(on, "tom", noCaps), "tom"))
    }

    @Test fun `a short input whose keys only spell an acronym is fixed to a likely word`() {
        val words = Fixtures.words() + listOf("meet", "printer", "AR", "JS", "WR", "Mo")
        // Acronyms like these are rare next to the words they collide with.
        val lm = Fixtures.lm(words, Fixtures.DEFAULT_BIGRAMS + listOf("meet" to "at", "printer" to "is"), boost = mapOf("AR" to -4.0, "JS" to -4.0, "WR" to -4.0))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        assertEquals("at", committed(type(e, "ar", Context(listOf("meet"), false)), "ar"))
        val js = type(e, "js", Context(listOf("printer"), false))
        assertEquals("is", committed(js, "js"), js.candidates.toString())
        // At a sentence start the capital comes from auto-caps: "Wr" is still the short lower-case input "wr".
        assertTrue(committed(type(e, "Wr", Context(emptyList(), true)), "Wr") != "WR")
        // A three-letter name the keys spell is real evidence: offered capitalised; the capital is added on space only
        // in a field that asks for capitals.
        assertTrue(type(e, "tom", mid).candidates.take(3).any { it.text == "Tom" })
        assertEquals("John", committed(type(e, "john", Context(listOf("with"), false)), "john"))
        assertEquals("john", committed(type(e, "john", Context(listOf("with"), false, capitalsAllowed = false)), "john"))
    }

    @Test fun `a lower-case word is corrected to its lower-case form when that is about as likely`() {
        // "Centre" is slightly more frequent than "centre" (as in encyclopaedic text).
        val words = Fixtures.words() + listOf("Centre", "centre")
        val lm = Fixtures.lm(words)
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        val ctx = Context(listOf("in", "the"), false)
        assertEquals("centre", committed(type(e, "centte", ctx), "centte"))
        assertEquals("Mary", committed(type(e, "maey", ctx), "maey"))
    }

    @Test fun `sentence start capitalises corrections and keeps shift`() {
        assertEquals("Hello", commit("Hrllo", start))
        assertEquals("Hello", commit("Hello", start))
        assertEquals("iPhone", commit("Iphone", start))
        assertEquals("HELLO", commit("HELLO"))
    }

    @Test fun `valid words are not corrected into other valid words`() {
        assertEquals("form", commit("form", Context(listOf("from"), false)))
        assertEquals("were", commit("were", Context(listOf("we"), false)))
        assertEquals("ill", commit("ill", Context(listOf("I'm"), false)))
        assertEquals("its", commit("its", Context(listOf("wagged"), false)))
        assertEquals("four", commit("four"))
        assertEquals("filed", commit("filed"))
    }

    @Test fun `single letters`() {
        assertEquals("a", commit("a"))
        assertEquals("I", commit("i"))
        assertEquals("s", commit("s"))
        assertEquals("x", commit("x"))
    }

    @Test fun `edit errors`() {
        assertEquals("the", commit("teh"))
        // Gboard (device-verified) leaves these two alone; we fix them.
        assertEquals("hello", commit("helo"))
        assertEquals("The", commit("Teh", start))
        assertEquals("letter", commit("leter"))
        assertEquals("tomorrow", commit("tommorow"))
        assertEquals("hello", commit("helllo"))
        assertEquals("little", commit("litle"))
    }

    @Test fun `spelling mistakes`() {
        assertEquals("definitely", commit("definately"))
        assertEquals("receive", commit("recieve"))
        assertEquals("separate", commit("seperate"))
        assertEquals("necessary", commit("neccessary"))
    }

    @Test fun `unknown words far from the lexicon are left alone`() {
        assertEquals("kwyjibo", commit("kwyjibo"))
        assertEquals("zxqv", commit("zxqv"))
    }

    @Test fun `completions are offered while typing`() {
        val s = type(engine, "tomo")
        assertTrue(s.candidates.take(3).any { it.text == "tomorrow" && it.kind == CandidateKind.COMPLETION }, s.toString())
    }

    @Test fun `reverted autocorrection is not repeated`() {
        val e = Fixtures.englishEngine()
        assertEquals("hello", committed(type(e, "hrllo"), "hrllo"))
        e.onAutoCorrectReverted(mid, "hrllo", "hello")
        assertEquals("hrllo", committed(type(e, "hrllo"), "hrllo"))
    }

    @Test fun `next word predictions`() {
        val s = engine.predictNext(start)
        assertTrue(s.candidates.isNotEmpty())
        assertTrue(s.candidates.all { it.kind == CandidateKind.PREDICTION && it.text[0].isUpperCase() })
    }

    @Test fun `incremental decoding equals a fresh decode`() {
        val taps = Touches.taps("tomorrow", dx = 0.2f, dy = 0.15f)
        val inc = Fixtures.englishEngine()
        var last = inc.suggestForTaps(mid, taps.subList(0, 1), "t")
        for (k in 2..taps.size) last = inc.suggestForTaps(mid, taps.subList(0, k), "tomorrow".substring(0, k))
        val fresh = Fixtures.englishEngine().suggestForTaps(mid, taps, "tomorrow")
        assertEquals(fresh.candidates.map { it.text }, last.candidates.map { it.text })
        // Backspace then retype also matches.
        inc.suggestForTaps(mid, taps.subList(0, 3), "tom")
        assertEquals(fresh.candidates.map { it.text }, inc.suggestForTaps(mid, taps, "tomorrow").candidates.map { it.text })
    }

    @Test fun `no geometry still decodes typed characters`() {
        val lm = Fixtures.lm(Fixtures.words())
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en))
        assertEquals("don't", committed(e.suggestForTaps(mid, emptyList(), "dont"), "dont"))
    }

    @Test fun `non-word input is passed through`() {
        val s = engine.suggestForTaps(mid, Touches.taps("abc"), "a1c")
        assertEquals(listOf("a1c"), s.candidates.map { it.text })
        assertEquals(-1, s.autoCorrectIndex)
    }

    @Test fun `touch offsets adapt to a user`() {
        val e = Fixtures.englishEngine()
        // A user who consistently hits 0.45 key widths to the right of every key.
        repeat(40) {
            val s = e.suggestForTaps(mid, Touches.taps("hello", dx = 0.45f), "hello")
            e.onWordCommitted(mid, committed(s, "hello"), true)
        }
        val g = Layouts.pixel8Qwerty()
        assertTrue(g.keys.isNotEmpty())
        // "girl" typed with the same offset is now decoded cleanly even where taps cross key boundaries.
        val s = e.suggestForTaps(mid, Touches.taps("girl", dx = 0.45f), "girl")
        assertEquals("girl", committed(s, "girl"))
    }
}
