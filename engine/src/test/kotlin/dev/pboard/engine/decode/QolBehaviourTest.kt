package dev.pboard.engine.decode

import dev.pboard.engine.CandidateKind
import dev.pboard.engine.Context
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches
import dev.pboard.engine.testutil.Touches.committed
import dev.pboard.engine.testutil.Touches.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Engine-owned quality-of-life behaviours (item IDs in test names). */
class QolBehaviourTest {
    private val mid = Context(listOf("so"), false)

    @Test fun `TP-08 lowercase i and contractions`() {
        val e = Fixtures.englishEngine()
        val expected = mapOf(
            "i" to "I", "im" to "I'm", "ive" to "I've", "dont" to "don't", "cant" to "can't", "didnt" to "didn't",
            "isnt" to "isn't", "wouldnt" to "wouldn't", "youre" to "you're", "theyre" to "they're",
        )
        for ((typed, fixed) in expected) assertEquals(fixed, committed(type(e, typed, mid), typed), typed)
    }

    @Test fun `TP-08 ambiguous contractions are left alone without strong context`() {
        val e = Fixtures.englishEngine()
        for (w in listOf("its", "well", "were", "ill", "hell", "shell")) assertEquals(w, committed(type(e, w, Context(listOf("about"), false)), w), w)
    }

    @Test fun `a rare short word typed for a neighbour-key word the context wants is corrected`() {
        // VTAP (fast, sloppy typing): Gboard 18.6 turns "want yo" into "want to"; a two-letter literal that is a word was
        // never replaced before.
        val words = Fixtures.words() + listOf("want", "yo")
        val lm = Fixtures.lm(words, Fixtures.DEFAULT_BIGRAMS + listOf("want" to "to", "want" to "to", "want" to "to"))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        assertEquals("to", committed(type(e, "yo", Context(listOf("want"), false)), "yo"))
        // Without context for the other word the literal stays.
        assertEquals("yo", committed(type(e, "yo", Context(listOf("hello"), false)), "yo"))
        assertEquals("of", committed(type(e, "of", Context(listOf("so"), false)), "of"))
    }

    @Test fun `TP-08 a valid plural is not turned into a possessive`() {
        val words = Fixtures.words() + listOf("neighbor", "neighbors", "neighbor's", "envies")
        val lm = Fixtures.lm(words, Fixtures.DEFAULT_BIGRAMS + listOf("his" to "neighbor's", "his" to "neighbor's"), boost = mapOf("neighbor's" to 3.0))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        val ctx = Context(listOf("envies", "his"), false)
        val s = type(e, "neighbors", ctx)
        assertEquals("neighbors", committed(s, "neighbors"), s.candidates.toString())
        assertTrue(s.candidates.take(3).any { it.text == "neighbor's" }, s.candidates.toString())
        // "its"/"lets" are contractions, not possessives.
        assertFalse(Fixtures.en.isPossessiveOf("it's", "its"))
        assertFalse(Fixtures.en.isPossessiveOf("let's", "lets"))
        assertTrue(Fixtures.en.isPossessiveOf("neighbour’s", "neighbours"))
    }

    @Test fun `TP-08 with autocorrect off the fix is still the centre suggestion`() {
        val e = Fixtures.englishEngine(EngineConfig(autocorrect = AutocorrectParams(enabled = false)))
        val s = type(e, "dont", mid)
        assertEquals(-1, s.autoCorrectIndex)
        assertEquals("don't", s.candidates[0].text)
        assertTrue(s.candidates.any { it.text == "dont" && it.kind == CandidateKind.TYPED })
    }

    @Test fun `TF-01 a tap on the e-r border after t-h gives the`() {
        val e = Fixtures.englishEngine()
        val g = Layouts.pixel8Qwerty()
        val t = Layouts.key(g, "t"); val h = Layouts.key(g, "h"); val ek = Layouts.key(g, "e"); val r = Layouts.key(g, "r")
        val border = TouchPoint((ek.cx + r.cx) / 2, ek.cy + 4, 400)
        val taps = listOf(TouchPoint(t.cx, t.cy, 0), TouchPoint(h.cx, h.cy, 200), border)
        for (typed in listOf("thr", "the")) assertEquals("the", committed(e.suggestForTaps(mid, taps, typed), typed), typed)
    }

    @Test fun `CO-04 a committed word re-opened without touches gets alternatives`() {
        val e = Fixtures.englishEngine()
        val s = e.suggestForTaps(Context(listOf("I", "went"), false), emptyList(), "their")
        val texts = s.candidates.map { it.text }
        assertEquals("their", committed(s, "their"), "a valid word is not auto-replaced on re-opening")
        assertTrue("there" in texts && "they're" in texts, texts.toString())
        // A misspelt word re-opened from text alone is offered its correction first, but space keeps it (SU-04,
        // as Gboard: "i like teh" + backspace shows `teh | the | they`, nothing bold).
        val re = e.suggestForTaps(mid, emptyList(), "hrllo")
        assertEquals(-1, re.autoCorrectIndex)
        assertEquals("hello", re.candidates.first { it.text != "hrllo" }.text)
        // Typing on (the IME then sends key-centre touches for the re-opened letters) corrects as usual.
        assertEquals("hello", committed(e.suggestForTaps(mid, Touches.taps("hrllo"), "hrllo"), "hrllo"))
        // Text-level decoding with re-opened autocorrection switched on (the TEXT eval sets).
        val on = Fixtures.englishEngine(EngineConfig(autocorrect = AutocorrectParams(reopenedWords = true)))
        assertEquals("hello", committed(on.suggestForTaps(mid, emptyList(), "hrllo"), "hrllo"))
    }

    @Test fun `a very short glide gives the keys it covers or the word they spell`() {
        val e = Fixtures.englishEngine()
        val g = Layouts.pixel8Qwerty()
        val t = Layouts.key(g, "t"); val y = Layouts.key(g, "y"); val i = Layouts.key(g, "i"); val n = Layouts.key(g, "n")
        fun drag(x0: Float, y0: Float, x1: Float, y1: Float) = (0..10).map { k -> TouchPoint(x0 + (x1 - x0) * k / 10f, y0 + (y1 - y0) * k / 10f, k * 16L) }
        // A drag of 0.56 key widths from the middle of t to just inside y (conformance farm-420-21, 60 px at 420 dpi): too
        // short for the gesture decoders; "ty", as Gboard 18.6, not a tap ("y").
        val ty = e.suggestForSwipe(mid, drag(t.cx, t.cy, t.cx + 0.56f * g.keyWidth, y.cy))
        assertEquals("ty", ty.candidates[0].text, ty.candidates.toString())
        // A short glide from i down to n spells a word through the keys between: "in".
        val inn = e.suggestForSwipe(mid, drag(i.cx, i.cy, n.cx, n.cy))
        assertEquals("in", inn.candidates[0].text, inn.candidates.toString())
    }

    @Test fun `a word split by an accidental space bar tap is joined again`() {
        val e = Fixtures.englishEngine()
        val g = Layouts.pixel8Qwerty()
        val space = Layouts.key(g, "space")
        val ctx = Context(listOf("I", "like"), false)
        fun split(first: String, second: String, spaceY: Float): dev.pboard.engine.Suggestions {
            val t1 = Touches.taps(first)
            e.suggestForTaps(ctx, t1, first)
            // The bar's resolution says space (here: rectangle "space"), the word is committed as typed.
            val r = e.resolveTap(ctx, first, TouchPoint(space.cx, spaceY), "space")
            assertEquals("space", r)
            e.onWordCommitted(ctx, first, true)
            return e.suggestForTaps(Context(ctx.words + first, false), Touches.taps(second), second)
        }
        val top = space.cy - space.height / 2
        // "th" + a tap on the bar's top edge + "is": "this" replaces "th " and is what space commits.
        val s = split("th", "is", top + 10f)
        assertEquals("this", s.candidates[0].text, s.candidates.toString())
        assertEquals("th ", s.candidates[0].replacesBefore)
        assertEquals(0, s.autoCorrectIndex)
        // Two real words stay two words.
        val two = split("hello", "there", top + 10f)
        assertTrue(two.candidates.none { it.replacesBefore.isNotEmpty() && it == two.candidates.getOrNull(two.autoCorrectIndex) }, two.candidates.toString())
        // Off: no joined candidate at all.
        val off = Fixtures.englishEngine(EngineConfig(tap = TapParams(joinSplitWords = false)))
        off.suggestForTaps(ctx, Touches.taps("th"), "th")
        off.resolveTap(ctx, "th", TouchPoint(space.cx, top + 10f), "space")
        off.onWordCommitted(ctx, "th", true)
        assertTrue(off.suggestForTaps(Context(ctx.words + "th", false), Touches.taps("is"), "is").candidates.none { it.replacesBefore.isNotEmpty() })
    }

    @Test fun `TP-02 a typed tail is not dropped and completions come before shorter words`() {
        val e = Fixtures.englishEngine()
        for (ctx in listOf(Context(emptyList(), true), mid)) {
            val s = type(e, "hel", ctx)
            assertEquals(-1, s.autoCorrectIndex, s.candidates.toString())
            val centre = s.candidates.first { it.text != "hel" }.text
            assertTrue(centre.lowercase().startsWith("hel"), "centre $centre in ${s.candidates.map { it.text }}")
        }
        // Stray taps at the end are still dropped: a repeated letter, a sloppy touch; a clean one on a key next to the
        // last one was meant (Gboard keeps "wer", "ab").
        assertEquals("and", committed(type(e, "andd", mid), "andd"))
        val f = Layouts.key(Layouts.pixel8Qwerty(), "f")
        val sloppy = Touches.taps("and") + TouchPoint(f.cx - 0.45f * Layouts.KEY_W, f.cy + 0.3f * Layouts.KEY_W)
        assertEquals("and", committed(e.suggestForTaps(mid, sloppy, "andf"), "andf"))
        assertEquals("andf", committed(type(e, "andf", mid), "andf"))
    }

    @Test fun `SU-02 predictions after a sentence end are capitalised sentence starters`() {
        val s = Fixtures.englishEngine().predictNext(Context(listOf("hello", "there"), sentenceStart = true))
        assertTrue(s.candidates.isNotEmpty())
        assertTrue(s.candidates.all { it.text[0].isUpperCase() }, s.toString())
    }

    private fun offensiveEngine(): PboardEngine {
        val lm = Fixtures.lm(Fixtures.words())
        val lex = Lexicon.build(lm.vocabulary().map { if (it.word == "damn") it.copy(offensive = true) else it }, Fixtures.en)
        return PboardEngine(lm, lex).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }

    @Test fun `SU-11 offensive words are never offered but are kept when typed`() {
        val e = offensiveEngine()
        assertFalse(type(e, "dam", mid).candidates.any { it.text == "damn" })
        assertFalse(type(e, "damj", mid).candidates.any { it.text == "damn" })
        assertEquals("damn", committed(type(e, "damn", mid), "damn"))
    }

    @Test fun `CO-07 words the user keeps typing are learned`() {
        val e = Fixtures.englishEngine()
        repeat(4) {
            val s = type(e, "zorbix", mid)
            e.onWordCommitted(mid, committed(s, "zorbix"), s.autoCorrectIndex < 0)
        }
        assertEquals(4, e.userVocabulary.count("zorbix").toInt())
        assertTrue(type(e, "zorb", mid).candidates.take(3).any { it.text == "zorbix" }, "learned word completes")
        // Intended now: a near miss of a known word typed as the learned word is not "corrected" away.
        assertEquals("zorbix", committed(type(e, "zorbix", mid), "zorbix"))
    }

    @Test fun `CO-02 reverting a correction keeps and learns the word`() {
        val e = Fixtures.englishEngine()
        val corrected = committed(type(e, "hellp", mid), "hellp")
        assertTrue(corrected != "hellp")
        e.onAutoCorrectReverted(mid, "hellp", corrected)
        assertEquals("hellp", committed(type(e, "hellp", mid), "hellp"))
        assertTrue(e.userVocabulary.isIntended("hellp"))
    }

    @Test fun `SU-09 a misspelling left in the field as typed is learned`() {
        val e = Fixtures.englishEngine()
        assertEquals("because", committed(type(e, "becuase", mid), "becuase"))
        // Typed, not corrected, and the session ended (or the cursor left the word): Gboard 18.6 keeps it next time.
        e.onWordAbandoned(mid, "becuase")
        assertEquals("becuase", committed(type(e, "becuase", mid), "becuase"))
        assertTrue(type(e, "bec", mid).candidates.any { it.text == "becuase" }, "offered from its first letters")
        // Nothing is learned where learning is off.
        val off = Fixtures.englishEngine()
        off.onWordAbandoned(mid.copy(learningAllowed = false), "becuase")
        assertEquals("because", committed(type(off, "becuase", mid), "becuase"))
    }

    @Test fun `SU-03 caps lock and shift shape candidates`() {
        val e = Fixtures.englishEngine()
        assertTrue(type(e, "HEL", mid).candidates.take(3).any { it.text == "HELLO" })
        assertTrue(type(e, "Hel", mid).candidates.take(3).any { it.text == "Hello" })
        // A typed capital carries over to the correction (at a sentence start; mid-sentence a capitalised
        // unknown word is taken as a name, CO-09).
        assertEquals("The", committed(type(e, "Teh", Context(emptyList(), true)), "Teh"))
    }

    @Test fun `missed space bar - a bottom-row tap just above the bar ends the word`() {
        val e = Fixtures.englishEngine()
        val g = Layouts.pixel8Qwerty()
        val space = Layouts.key(g, "space")
        val b = Layouts.key(g, "b")
        // "hello" then a touch meant for space that hit the lower edge of 'b'.
        val missed = TouchPoint(space.cx - 40f, b.cy + b.height / 2 - 12f, 900)
        val taps = Touches.taps("hello") + missed
        assertEquals("hello", committed(e.suggestForTaps(mid, taps, "hellob"), "hellob"))
        // ... and the user kept typing the next word: the two words are separated.
        val joined = taps + Touches.taps("people").map { TouchPoint(it.x, it.y, it.t + 1000) }
        assertEquals("hello people", committed(e.suggestForTaps(mid, joined, "hellobpeople"), "hellobpeople"))
        // A real word ending on the bottom row, typed normally, is not cut.
        assertEquals("then", committed(type(e, "then", mid), "then"))
    }

    @Test fun `CO-06 deleting a glided word and re-gliding commits the alternative`() {
        val e = Fixtures.englishEngine()
        val path = Touches.swipe("helo")
        val first = e.suggestForSwipe(mid, path)
        assertEquals("hello", first.candidates[0].text)
        e.onGestureWordDeleted(mid, "hello")
        val again = e.suggestForSwipe(mid, path)
        assertTrue(again.candidates[0].text != "hello" && again.candidates.any { it.text == "hello" }, again.toString())
        // Only the very next gesture is affected.
        assertEquals("hello", e.suggestForSwipe(mid, path).candidates[0].text)
    }

    @Test fun `CO-06 with live previews the whole re-glide is one gesture`() {
        val e = Fixtures.englishEngine()
        val path = Touches.swipe("helo")
        e.onGestureStarted()
        assertEquals("hello", e.suggestForSwipe(mid, path).candidates[0].text)
        e.onGestureWordDeleted(mid, "hello")
        e.onGestureStarted()
        for (k in listOf(path.size / 2, path.size * 3 / 4)) e.suggestForSwipe(mid, path.subList(0, k)) // live previews
        val again = e.suggestForSwipe(mid, path)
        assertTrue(again.candidates[0].text != "hello" && again.candidates.any { it.text == "hello" }, again.toString())
        e.onGestureStarted()
        assertEquals("hello", e.suggestForSwipe(mid, path).candidates[0].text)
    }

    @Test fun `CO-09 at a sentence start a word-like unknown name is not turned into another name`() {
        val lm = Fixtures.lm(Fixtures.words() + "Mike", boost = mapOf("Mike" to -3.0))
        val start = Context(emptyList(), true)
        // The tiny fixture lexicon is a poor letter model: test the proper-noun rule on its own.
        val cfg = EngineConfig(autocorrect = AutocorrectParams(nameMinLetterLogProb = -20f))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en), cfg).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        assertEquals("Mikey", committed(type(e, "Mikey", start), "Mikey"))
        assertTrue(type(e, "Mikey", start).candidates.take(3).any { it.text == "Mike" })
        // Misspelt common words are still fixed, and so are garbled names.
        assertEquals("Hello", committed(type(e, "Hrllo", start), "Hrllo"))
        assertEquals("John", committed(type(e, "Jhon", start), "Jhon"))
        // Without the rule (and without the one keeping a typed tail, TP-02) the name would have been replaced.
        val plain = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en),
            EngineConfig(autocorrect = AutocorrectParams(protectNamesAtSentenceStart = false, keepTypedTail = false))).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        assertEquals("Mike", committed(type(plain, "Mikey", start), "Mikey"))
    }

    @Test fun `CO-09 an unknown word capitalised mid-sentence is corrected as Gboard 18 does`() {
        val e = Fixtures.englishEngine()
        val met = Context(listOf("I", "met"), false)
        // Gboard 18.6 (conformance farm-440): "I met Jhon" -> "John"; the old protection is a switch.
        assertEquals("John", committed(type(e, "Jhon", met), "Jhon"))
        val protect = Fixtures.englishEngine(EngineConfig(autocorrect = AutocorrectParams(protectCapitalisedUnknown = true)))
        assertEquals("Jhon", committed(type(protect, "Jhon", met), "Jhon"))
        assertTrue(type(protect, "Jhon", met).candidates.any { it.text == "John" })
        // Lower case, it is corrected; at a sentence start, capitals carry no signal.
        assertEquals("John", committed(type(e, "jhon", met), "jhon"))
        assertEquals("John", committed(type(e, "Jhon", Context(emptyList(), true)), "Jhon"))
        // Known words are still case-fixed ("Iphone" -> "iPhone").
        assertEquals("iPhone", committed(type(e, "Iphone", met), "Iphone"))
    }

    @Test fun `CO-09 an unknown name capitalised mid-sentence is not turned into an ordinary word`() {
        val words = Fixtures.words() + listOf("met", "told", "John", "Told")
        val lm = Fixtures.lm(words, Fixtures.DEFAULT_BIGRAMS + listOf("I" to "told", "I" to "told", "I" to "met"), boost = mapOf("told" to 2.0, "Told" to -6.0))
        fun engine(cfg: EngineConfig = EngineConfig()) = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en), cfg).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        val e = engine()
        val met = Context(listOf("I", "met"), false)
        // "Tolu" capitalised mid-sentence stays (the ordinary word "told" is still offered), lower case it is corrected.
        val s = type(e, "Tolu", met)
        assertEquals("Tolu", committed(s, "Tolu"), s.toString())
        assertTrue(s.candidates.take(3).any { it.text == "Told" }, s.toString())
        assertTrue(committed(type(e, "tolu", met), "tolu") != "tolu")
        // Without the rule it would have been replaced.
        val off = engine(EngineConfig(autocorrect = AutocorrectParams(capitalisedOnlyToNames = false)))
        assertTrue(committed(type(off, "Tolu", met), "Tolu") != "Tolu")
        // A garbled name still becomes the name (Gboard 18.6: "I met Jhon" -> "John"); at a sentence start capitals carry
        // no signal and a misspelt word is fixed.
        assertEquals("John", committed(type(e, "Jhon", met), "Jhon"))
        assertEquals("Hello", committed(type(e, "Hrllo", Context(emptyList(), true)), "Hrllo"))
    }

    @Test fun `CO-02 a correction undone by retyping is not applied again and the word is learned`() {
        val e = Fixtures.englishEngine()
        val ctx = Context(listOf("so"), false)
        assertEquals("hello", committed(type(e, "hrllo", ctx), "hrllo"))
        // The IME reports the revert (ImeRules.retypesCorrectedOriginal) when the original is retyped and committed.
        e.onAutoCorrectReverted(ctx, "hrllo", "hello")
        assertEquals("hrllo", committed(type(e, "hrllo", ctx), "hrllo"))
        assertEquals("hrllo", committed(type(e, "hrllo", Context(listOf("I", "said"), false)), "hrllo"))
        // Survives a restart (user data).
        val again = Fixtures.englishEngine().also { it.importUserData(e.exportUserData()) }
        assertEquals("hrllo", committed(type(again, "hrllo", ctx), "hrllo"))
    }

    @Test fun `TP-13 URL, email and handle tokens are left alone`() {
        val e = Fixtures.englishEngine()
        for (t in listOf("www.gooogle.com", "foo@bar.com", "#tag", "@user", "a/b/c")) {
            val s = e.suggestForTaps(mid, emptyList(), t)
            assertEquals(-1, s.autoCorrectIndex, t)
            assertEquals(t, s.candidates[0].text)
        }
    }

    @Test fun `TP-12 tokens with digits are never corrected`() {
        val e = Fixtures.englishEngine()
        val s = e.suggestForTaps(mid, Touches.taps("nd"), "2nd")
        assertEquals(listOf("2nd"), s.candidates.map { it.text })
    }

    @Test fun `SU-01 a case-only variant much rarer than the word does not take a strip slot`() {
        val words = Fixtures.words() + listOf("tour", "tourist", "tours", "Tour", "US")
        val lm = Fixtures.lm(words, boost = mapOf("Tour" to -4.0))
        val e = PboardEngine(lm, Lexicon.build(lm.vocabulary(), Fixtures.en)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        val tour = type(e, "tour", mid)
        assertFalse(tour.candidates.any { it.text == "Tour" }, tour.candidates.toString())
        // A case variant about as likely as the word keeps its slot.
        val us = type(e, "us", mid)
        assertTrue(us.candidates.any { it.text == "US" }, us.candidates.toString())
    }
}
