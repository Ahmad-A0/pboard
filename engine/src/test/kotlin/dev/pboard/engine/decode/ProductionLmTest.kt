package dev.pboard.engine.decode

import dev.pboard.engine.Context
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.devlm.DevData
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.UserNgramModel
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches
import dev.pboard.engine.testutil.Touches.committed
import dev.pboard.engine.testutil.Touches.type
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The engine over the shipped English packs (app/src/main/assets/lm): one user model, both dialects. */
class ProductionLmTest {
    private val dir = File(DevData.projectRoot(), "app/src/main/assets/lm")
    private val mid = Context(listOf("I", "saw", "a"), false)

    private fun engine(tag: String, user: UserNgramModel): PboardEngine {
        assumeTrue(File(dir, "en.lm").exists())
        return PboardEngine.create(listOf(EnglishPack.load(tag, dir, user).spec())).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }

    @Test fun `the LM's user model is the engine's only learning store`() {
        val user = UserNgramModel()
        val e = engine("en-GB", user)
        assertTrue(e.learned is NgramUserWords)
        repeat(4) { e.onWordCommitted(mid, "zorbix", true) }
        assertEquals(4.0, user.count("zorbix"), 1e-6)
        assertEquals(0.0, e.userVocabulary.count("zorbix"))
        assertTrue(type(e, "zorb", mid).candidates.take(3).any { it.text == "zorbix" })
        assertEquals("zorbix", committed(type(e, "zorbix", mid), "zorbix"))

        val bytes = e.exportUserData()
        val user2 = UserNgramModel()
        val e2 = engine("en-GB", user2)
        e2.importUserData(bytes)
        assertEquals(4.0, user2.count("zorbix"), 1e-3)
        e2.clearUserData()
        assertEquals(0.0, user2.count("zorbix"))
    }

    /** docs/app-compat.md #3: "Hello" at the start of a few messages made a mid-sentence "hello" come out as "Hello". */
    @Test fun `a sentence-start capital is not learned as the word's form`() {
        val user = UserNgramModel()
        val e = engine("en-US", user)
        val start = Context(emptyList(), sentenceStart = true)
        repeat(8) {
            e.onWordCommitted(start, "Hello", true)
            e.onWordCommitted(Context(listOf("Hello"), false), "world", true)
        }
        assertEquals(0.0, user.count("Hello"))
        // Counts decay with the wall clock (UserNgramModel half-life): a millisecond between commits leaves 7.9999999.
        assertTrue(user.count("hello") >= 8.0 - 1e-6)
        val because = Context(listOf("Because"), false)
        assertEquals("hello", committed(type(e, "hello", because), "hello"))
        // A word known only with its capital keeps it ("Monday"); an unknown one is learned in lower case.
        e.onWordCommitted(start, "Monday", true)
        assertTrue(user.count("Monday") >= 1.0 - 1e-6)
        e.onWordCommitted(start, "Zorbix", true)
        assertEquals(1.0, user.count("zorbix"), 1e-6)
        // Typed capitalised mid-sentence, a word keeps its capital at a sentence start too.
        e.onWordCommitted(Context(listOf("met"), false), "Quorvex", true)
        e.onWordCommitted(start, "Quorvex", true)
        assertEquals(2.0, user.count("Quorvex"), 1e-6)
        assertEquals(0.0, user.count("quorvex"))
        // Also a name whose lower-case form is a word: "Bill" typed mid-sentence stays "Bill" at a sentence start.
        e.onWordCommitted(Context(listOf("met"), false), "Bill", true)
        e.onWordCommitted(start, "Bill", true)
        assertEquals(2.0, user.count("Bill"), 1e-6)
    }

    @Test fun `no learning when the field forbids it`() {
        val user = UserNgramModel()
        val e = engine("en-US", user)
        repeat(3) { e.onWordCommitted(Context(listOf("so"), false, learningAllowed = false), "zorbix", true) }
        assertEquals(0.0, user.count("zorbix"))
    }

    @Test fun `dialects with the production packs`() {
        val gb = engine("en-GB", UserNgramModel())
        val us = engine("en-US", UserNgramModel())
        val ctx = Context(listOf("the"), false)
        assertEquals("colour", committed(type(gb, "colour", ctx), "colour"))
        assertEquals("color", committed(type(gb, "color", ctx), "color"))
        assertEquals("color", committed(type(us, "color", ctx), "color"))
        assertEquals("organisation", committed(type(gb, "organisaton", ctx), "organisaton"))
        assertEquals("organization", committed(type(us, "organizaton", ctx), "organizaton"))
        assertEquals("the", committed(type(gb, "teh", Context(listOf("I", "saw"), false)), "teh"))
        assertEquals("don't", committed(type(us, "dont", Context(listOf("I"), false)), "dont"))
    }

    @Test fun `the neural LM re-ranks final candidates only and the user model stays the learning store`() {
        assumeTrue(File(dir, "en.nnlm").exists())
        val e = PboardEngine.create(listOf(EnglishPack.load("en-GB", dir, UserNgramModel(), useNeural = true).spec()))
            .also { it.setGeometry(Layouts.pixel8Qwerty()) }
        val plain = engine("en-GB", UserNgramModel())
        assertTrue(e.learned is NgramUserWords)
        // Live gesture previews are the n-gram decode; the final decode is re-ranked.
        val path = Touches.swipe("hello")
        assertEquals(plain.suggestForSwipe(mid, path).candidates.map { it.text }, e.suggestForSwipePreview(mid, path).candidates.map { it.text })
        assertEquals("hello", e.suggestForSwipe(mid, path).candidates.first().text)
        // Next-word predictions come from the rescorer (its state prefetched by the commit).
        e.onWordCommitted(Context(listOf("how"), false), "are", true)
        assertTrue("you" in e.predictNext(Context(listOf("how", "are"), false)).candidates.map { it.text })
        // Re-ranking off: the n-gram engine, exactly.
        val off = PboardEngine.create(listOf(EnglishPack.load("en-GB", dir, UserNgramModel(), useNeural = true).spec()),
            EngineConfig(neuralRerankTopN = 0)).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        for (w in listOf("teh", "helo", "beautifull")) assertEquals(plain.suggestForTaps(mid, Touches.taps(w), w).candidates.map { it.text },
            off.suggestForTaps(mid, Touches.taps(w), w).candidates.map { it.text }, w)
    }

    @Test fun `prepareContext runs the neural LM's steps before the first input in a new context`() {
        assumeTrue(File(dir, "en.nnlm").exists())
        val pack = EnglishPack.load("en-GB", dir, UserNgramModel(), useNeural = true)
        val nn = pack.lm as dev.pboard.engine.lm.NeuralRescoringLm
        val e = PboardEngine.create(listOf(pack.spec())).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        val ctx = Context(listOf("I", "will", "be", "back", "in", "a"), false)
        val cold = nn.steps
        e.prepareContext(ctx)
        assertTrue(nn.steps - cold >= ctx.words.size, "the sentence's steps ran in prepareContext")
        val prepared = nn.steps
        e.suggestForSwipe(ctx, Touches.swipe("minute"))
        e.suggestForTaps(ctx, Touches.taps("min"), "min")
        assertEquals(prepared, nn.steps, "no step on the first glide or tap")
    }

    @Test fun `space bar and delete key boundaries match Gboard's probes on the phone layout`() {
        val geo = File(DevData.projectRoot(), "tools/conformance/geometry/pboard_phone440.json")
        assumeTrue(geo.exists())
        val g = dev.pboard.engine.eval.HumanSets.geometry(geo)
        val e = engine("en-US", UserNgramModel()).also { it.setGeometry(g) }
        fun key(n: String) = g.keys.first { it.label == n }
        val space = key("space"); val del = key("delete"); val c = key("c"); val b = key("b")
        val pitch = space.cy - c.cy
        val ctx = Context(listOf("I", "will", "be"), false)
        // "I will be ba" + a tap on the bar 42 % / 30 % of a row gap above its centre, under c: "bac" (back), as Gboard.
        assertEquals("c", e.resolveTap(ctx, "ba", dev.pboard.engine.TouchPoint(c.cx, space.cy - 0.42f * pitch), "space"))
        assertEquals("c", e.resolveTap(ctx, "ba", dev.pboard.engine.TouchPoint(c.cx, space.cy - 0.30f * pitch), "space"))
        assertEquals("space", e.resolveTap(ctx, "ba", dev.pboard.engine.TouchPoint(c.cx, space.cy), "space"))
        // "hel" + a tap on the delete key's visual top-left corner: "hell", as Gboard; its middle deletes.
        assertEquals("l", e.resolveTap(ctx, "hel", dev.pboard.engine.TouchPoint(del.cx - del.width / 2 + 6f, del.cy - del.height / 2 + 14f), "delete"))
        assertEquals("delete", e.resolveTap(ctx, "hel", dev.pboard.engine.TouchPoint(del.cx, del.cy), "delete"))
        // Not matched on purpose: Gboard keeps "hello" + a tap on b's bottom edge as "b"; human missed spaces say space.
        assertEquals("space", e.resolveTap(ctx, "hello", dev.pboard.engine.TouchPoint(b.cx, b.cy + b.height / 2 - 2f), "b"))
    }

    /**
     * Conformance farm-440 (main dbe4302), human sentence bursts on the phone layout. Each tap's resolution decides that
     * tap alone; changes to letters typed before come only from the word decode when the word is committed.
     */
    @Test fun `tap resolution decides only the tap itself (conformance human sentences)`() {
        val geo = File(DevData.projectRoot(), "tools/data/human/geometry/gboard_pixel8.json")
        assumeTrue(geo.exists())
        val g = dev.pboard.engine.eval.HumanSets.geometry(geo)
        val e = engine("en-US", UserNgramModel()).also { it.setGeometry(g) }
        fun tp(x: Float, y: Float) = dev.pboard.engine.TouchPoint(x, y)
        // vtap/vt_exp3/p3/mobile1542.30 keystrokes 23-27: "Please coordinate with " + h i m + a tap on the g/h border.
        val ctx = Context(listOf("Please", "coordinate", "with"), false)
        val him = listOf(tp(664.5f, 1907.2f), tp(834.0f, 1704.8f), tp(838.2f, 2030.2f), tp(592.2f, 1856.9f))
        val typed = StringBuilder()
        for ((i, p) in him.withIndex()) {
            val rect = "hime"[i].let { if (i == 3) "g" else it.toString() }
            val r = e.resolveTap(ctx, typed.toString(), p, rect)
            assertEquals(1, r.length, "tap $i resolves to one letter, got $r")
            typed.append(r)
        }
        // On the phone both keyboards typed "himh" (the last tap on the g/h border became "h"); on space Gboard kept
        // "himh", pboard's word decode drops the stray last touch and commits what the person meant.
        assertTrue(typed.toString() == "himh" || typed.toString() == "himg", typed.toString())
        assertEquals("him", committed(e.suggestForTaps(ctx, him, "himh"), "himh"))
        assertEquals("him", committed(e.suggestForTaps(ctx, him, typed.toString()), typed.toString()))
        // tsi/user13/task1/4 (x0.5) keystrokes 5-7 after "I do" + a tap on the bar's top edge (both keyboards: "don"):
        // the next touches type one letter each, never a deletion or a space of an earlier one.
        val ctx2 = Context(listOf("I"), false)
        val word = StringBuilder("don")
        for ((p, rect) in listOf(tp(766.2f, 1982.2f) to "n", tp(891.5f, 1764.3f) to "o", tp(478.6f, 1710.2f) to "t")) {
            val r = e.resolveTap(ctx2, word.toString(), p, rect)
            assertEquals(1, r.length, "one letter per tap, got $r after $word")
            word.append(r)
        }
        // The "o" touch, 0.21 key widths left of the centre of o, types "o" as on Gboard (it used to become "i").
        assertEquals("donnot", word.toString())
        // A letter tap next to the delete key types a letter: only the delete key deletes.
        val l = g.keys.first { it.label == "l" }; val del = g.keys.first { it.label == "delete" }
        assertEquals("l", e.resolveTap(ctx, "hel", tp(l.cx + l.width / 2 - 2f, del.cy - del.height / 2 - 2f), "l"))
    }

    @Test fun `a part of a word is completed by space when the context makes it overwhelmingly likely`() {
        val e = engine("en-US", UserNgramModel())
        // Gboard 18.6 bolds these while typed (conformance farm-440): "Please provi" -> "provide", "Anything exciting goi" -> "going".
        val s = type(e, "provi", Context(listOf("Please"), false))
        assertEquals("provide", committed(s, "provi"), s.candidates.toString())
        assertEquals("going", committed(type(e, "goi", Context(listOf("Anything", "exciting"), false)), "goi"))
        // A common word typed whole is not completed ("goo" stays a word, "the" never becomes "there").
        assertEquals("the", committed(type(e, "the", Context(listOf("I", "saw"), false)), "the"))
        // Off: the part stays.
        val off = PboardEngine.create(listOf(EnglishPack.load("en-US", dir, UserNgramModel()).spec()),
            EngineConfig(autocorrect = AutocorrectParams(completeOnSpace = false))).also { it.setGeometry(Layouts.pixel8Qwerty()) }
        assertEquals("provi", committed(type(off, "provi", Context(listOf("Please"), false)), "provi"))
    }

    @Test fun `rescoring the n-gram predictions the engine already has equals predicting again`() {
        assumeTrue(File(dir, "en.nnlm").exists())
        val lm = EnglishPack.load("en-GB", dir, UserNgramModel(), useNeural = true).lm as dev.pboard.engine.lm.NeuralRescoringLm
        for (ctx in listOf(emptyList(), listOf("how", "are"), listOf("I", "want", "to"), listOf("the"), listOf("see", "you"))) {
            assertEquals(lm.predict(ctx, "", 9), lm.predictFrom(ctx, lm.ngram.predict(ctx, "", 40), 9), ctx.toString())
        }
    }

    /**
     * A word-ending tap on "." just right of the space bar is a space that missed the bar when a whole word goes on
     * (TapResolveParams.punctuationSpace; measured on Gboard).
     */
    @Test fun `a tap on the period's edge next to the space bar is a space after a whole word mid-sentence`() {
        val e = engine("en-GB", UserNgramModel())
        val g = Layouts.pixel8Qwerty()
        val dot = Layouts.key(g, "."); val comma = Layouts.key(g, ",")
        val kw = g.keyWidth
        fun into(depth: Float) = TouchPoint(dot.cx - dot.width / 2 + depth * kw, dot.cy)
        val where = Context(listOf("Where", "can", "my"), false)
        // Gboard: after a whole word, up to ~0.4 key widths into "." from the boundary is the space bar. Depths here are
        // from the visual key's edge (this layout's rectangles leave the 10 px gap out), so ~0.3.
        for (d in listOf(0.05f, 0.15f, 0.25f)) assertEquals("space", e.resolveTap(where, "little", into(d), "."), "depth $d")
        assertEquals("space", e.resolveTap(Context(listOf("It", "was"), false), "the", into(0.25f), "."))
        // Near the key's centre, and beyond the edge zone, the key is the key.
        assertEquals(".", e.resolveTap(where, "little", into(0.47f), "."))
        assertEquals(".", e.resolveTap(where, "little", TouchPoint(dot.cx, dot.cy), "."))
        // Gboard: not after a word's beginning ("litt"), at a word's start or after a number.
        assertEquals(".", e.resolveTap(where, "litt", into(0.1f), "."))
        assertEquals(".", e.resolveTap(where, "", into(0.1f), "."))
        assertEquals(".", e.resolveTap(where, "3", into(0.1f), "."))
        // Where the sentence is likely to end, a deliberate period a little inside the key stays one.
        assertEquals(".", e.resolveTap(Context(listOf("Thank", "you", "very"), false), "much", into(0.2f), "."))
        // The comma left of the bar (no emoji key between them), at its right edge.
        assertEquals("space", e.resolveTap(where, "little", TouchPoint(comma.cx + comma.width / 2 - 0.1f * kw, comma.cy), ","))
        // Off: the key.
        val off = PboardEngine.create(listOf(EnglishPack.load("en-GB", dir, UserNgramModel()).spec()),
            EngineConfig(tapResolve = TapResolveParams(punctuationSpace = false))).also { it.setGeometry(g) }
        assertEquals(".", off.resolveTap(where, "little", into(0.05f), "."))
    }
}
