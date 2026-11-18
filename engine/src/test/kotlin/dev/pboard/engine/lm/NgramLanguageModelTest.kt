package dev.pboard.engine.lm

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Tests against tools/lm/make_test_pack.py's tiny pack (built by the real pipeline code). */
class NgramLanguageModelTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/lm/$name")!!.readBytes()
    private val pack = LmPack.wrap(ByteBuffer.wrap(bytes("tiny.lm")))
    private val gb = DialectOverlay.read(bytes("tiny_GB.lmo"))
    private val us = DialectOverlay.read(bytes("tiny_US.lmo"))
    private val lm = NgramLanguageModel(pack)

    @Test fun header() {
        assertEquals("en", pack.locale)
        assertEquals(3, pack.order)
        assertTrue(pack.nLex > 50)
    }

    @Test fun lookupIsCaseInsensitiveAndCanonical() {
        assertEquals("London", lm.word(lm.lexIdOf("london")))
        assertEquals("iPhone", lm.word(lm.lexIdOf("IPHONE")))
        assertEquals("I'm", lm.word(lm.lexIdOf("i'm")))
        assertTrue(lm.isKnown("LONDON"))
        assertTrue(lm.isKnown("colour") && lm.isKnown("color"))
        assertFalse(lm.isKnown("xyzzy"))
        assertTrue(lm.isKnown("42"))
    }

    @Test fun matchesPythonReference() {
        for (line in bytes("tiny_expect.tsv").decodeToString().trimEnd().split('\n')) {
            val (ctx, w, lp) = line.split('\t')
            val context = if (ctx.isEmpty()) emptyList() else ctx.split(' ')
            val got = lm.staticLogProb(context, w) - pack.inClassLogProb(lm.lexIdOf(w))
            assertTrue(abs(got - lp.toFloat()) < 2e-3, "P($w | $ctx): kotlin $got vs python $lp")
        }
    }

    @Test fun conditionalDistributionsSumToOne() {
        for (ctx in listOf(emptyList(), listOf("I"), listOf("going", "to"), listOf("see", "you"), listOf("xyzzy", "the"))) {
            val ids = IntArray(4)
            val words = ctx.map { lm.lexIdOf(it).let { id -> if (id < 0) LmPack.UNK else pack.ngramIdOf(id) } }
            var m = 0
            if (ctx.size < pack.order - 1) ids[m++] = LmPack.BOS
            for (w in words) ids[m++] = w
            var sum = 0.0
            for (g in 0 until pack.nIds) if (g != LmPack.BOS) sum += exp(lm.ngramLogProb(ids, m, g).toDouble())
            assertTrue(abs(sum - 1.0) < 0.02, "sum over vocabulary after $ctx = $sum")
        }
    }

    private fun bruteForce(model: NgramLanguageModel, ctx: List<String>, prefix: String): List<Pair<String, Float>> {
        val f = pack.rules.fold(prefix)
        return (0 until pack.nLex).map { pack.word(it) }
            .filter { pack.rules.fold(it).startsWith(f) && !(model.isOffensive(it) && pack.rules.fold(it) != f) }
            .map { it to model.logProb(ctx, it) }.sortedByDescending { it.second }
    }

    @Test fun predictEqualsBruteForceRanking() {
        for (model in listOf(lm, NgramLanguageModel(pack, gb), NgramLanguageModel(pack, us))) {
            for (ctx in listOf(emptyList(), listOf("I"), listOf("I", "am"), listOf("the"), listOf("Thank"), listOf("xyzzy"))) {
                for (prefix in listOf("", "g", "t", "co", "lo")) {
                    val k = 5
                    val got = model.predict(ctx, prefix, k)
                    val all = bruteForce(model, ctx, prefix)
                    val what = "ctx=$ctx prefix='$prefix' overlay=${model.overlay?.locale}"
                    assertEquals(minOf(k, all.size), got.size, what)
                    // same scores as the exhaustive ranking (ties may be broken differently)
                    for (i in got.indices) assertEquals(all[i].second, got[i].second, 1e-4f, "$what rank $i")
                    for ((w, lp) in got) assertTrue(abs(lp - model.logProb(ctx, w)) < 1e-4, "$what $w")
                }
            }
        }
    }

    @Test fun strongOverlayBoostsAreNotPrunedFromTopK() {
        // an overlay that boosts a rare word by 8 nats: it must appear even though its base score is low
        val b = bytes("tiny_GB.lmo")
        val n = pack.nLex
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var adjOff = 0
        for (i in 0 until buf.getInt(124)) if (buf.getInt(128 + 24 * i) == 60) adjOff = buf.getLong(128 + 24 * i + 8).toInt()
        val dog = lm.lexIdOf("dog")
        buf.putShort(adjOff + 2 * dog, 8000)
        val boosted = NgramLanguageModel(pack, DialectOverlay.read(b))
        assertTrue(n > dog)
        assertEquals("dog", boosted.predict(listOf("I", "love"), "", 1).single().first)
        assertEquals("dog", boosted.predict(emptyList(), "", 1).single().first)
    }

    @Test fun prefixCompletionIsCaseAndApostropheInsensitive() {
        assertEquals("going", lm.predict(listOf("I", "am"), "goi", 3).first().first)
        assertTrue(lm.predict(emptyList(), "lon", 5).any { it.first == "London" })
        assertTrue(lm.predict(listOf("I"), "dont", 5).any { it.first == "don't" })
        assertTrue(lm.predict(emptyList(), "IPH", 5).any { it.first == "iPhone" })
    }

    @Test fun offensiveWordsAreOnlySuggestedWhenTypedLiterally() {
        assertFalse(lm.predict(listOf("oh"), "", 50).any { it.first == "shit" })
        assertFalse(lm.predict(listOf("oh"), "sh", 50).any { it.first == "shit" })
        assertTrue(lm.predict(listOf("oh"), "shit", 5).any { it.first == "shit" })
        assertTrue(lm.isOffensive("shit"))
        assertTrue(lm.logProb(listOf("oh"), "shit") > -10f, "logProb stays honest so literal typing is kept")
        val unblocked = NgramLanguageModel(pack).also { it.blockOffensive = false }
        assertTrue(unblocked.predict(listOf("oh"), "", 50).any { it.first == "shit" })
    }

    @Test fun punctuationInContext() {
        val fresh = lm.logProb(emptyList(), "I")
        assertEquals(fresh, lm.logProb(listOf("See", "you", "tomorrow."), "I"), 1e-6f)
        assertEquals(fresh, lm.logProb(listOf("See", "you", "tomorrow", "!"), "I"), 1e-6f)
        assertTrue(lm.logProb(listOf("I", "am"), "going") > lm.logProb(listOf("I", "am."), "going"))
        assertEquals(lm.logProb(listOf("Going", "to"), "the"), lm.logProb(listOf("going", "to"), "the"), 1e-6f)
    }

    @Test fun dialectOverlaysChooseSpelling() {
        val lmGb = NgramLanguageModel(pack, gb)
        val lmUs = NgramLanguageModel(pack, us)
        assertEquals("colour", lmGb.predict(listOf("the"), "col", 1).single().first)
        assertEquals("color", lmUs.predict(listOf("the"), "col", 1).single().first)
        assertTrue(lmGb.logProb(listOf("the"), "colour") > lmGb.logProb(listOf("the"), "color"))
        assertTrue(lmUs.logProb(listOf("the"), "color") > lmUs.logProb(listOf("the"), "colour"))
        assertTrue(lmGb.isKnown("color") && lmUs.isKnown("colour"), "other dialect's spelling is still known")
        assertTrue(lmGb.logProb(listOf("my"), "mum") > lmGb.logProb(listOf("my"), "mom"))
        assertTrue(lmUs.logProb(listOf("my"), "mom") > lmUs.logProb(listOf("my"), "mum"))
        lmGb.setDialect(us)
        assertEquals("color", lmGb.predict(listOf("the"), "col", 1).single().first)
    }

    @Test fun overlayFromAnotherBuildIsRejected() {
        val b = bytes("tiny_GB.lmo")
        b[96] = (b[96] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { NgramLanguageModel(pack, DialectOverlay.read(b)) }
    }

    @Test fun unknownWordsGetFinitePenalty() {
        val lp = lm.logProb(listOf("I"), "xyzzy")
        assertTrue(lp.isFinite() && lp < lm.logProb(listOf("I"), "dog"))
    }

    @Test fun splitPunct() {
        assertEquals(listOf("Hello", ","), NgramLanguageModel.splitPunct("Hello,"))
        assertEquals(listOf("you", "?!"), NgramLanguageModel.splitPunct("you?!"))
        assertEquals(listOf("quote"), NgramLanguageModel.splitPunct("\"quote\""))
        assertEquals(listOf("."), NgramLanguageModel.splitPunct("."))
    }
}
