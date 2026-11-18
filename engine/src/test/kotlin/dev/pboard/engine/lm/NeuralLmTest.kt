package dev.pboard.engine.lm

import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NeuralLmTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/lm/$name")!!.readBytes()
    private val pack = LmPack.wrap(ByteBuffer.wrap(bytes("tiny.lm")))
    private val nn = NeuralLm.read(bytes("tiny.nnlm"))

    @Test fun matchesNumpyInt8Reference() {
        val lines = bytes("tiny_nnlm_expect.tsv").decodeToString().trimEnd().split('\n')
        val toks = lines[0].split(' ').map { it.toInt() }
        var s = nn.initialState()
        for ((t, tok) in toks.withIndex()) {
            s = nn.step(s, tok)
            val out = nn.output(s)
            val want = lines[t + 1].split(' ').map { it.toFloat() }
            assertEquals(nn.nv, want.size)
            for (id in 0 until nn.nv) assertEquals(want[id], out.logProb(id), 2e-3f, "step $t id $id")
        }
    }

    @Test fun twoThreadsGiveIdenticalResults() {
        val nn2 = NeuralLm.read(bytes("tiny.nnlm"), threads = 2)
        var a = nn.initialState()
        var b = nn2.initialState()
        for (tok in listOf(LmPack.BOS, 7, 9, 30, 11)) {
            a = nn.step(a, tok); b = nn2.step(b, tok)
            val oa = nn.output(a); val ob = nn2.output(b)
            for (id in 0 until nn.nv) assertEquals(oa.logProb(id), ob.logProb(id), 0f)
        }
    }

    @Test fun distributionIsNormalised() {
        val out = nn.output(nn.step(nn.initialState(), LmPack.BOS))
        val sum = (0 until nn.nv).sumOf { exp(out.logProb(it).toDouble()) }
        assertEquals(1.0, sum, 1e-4)
    }

    private val ngram = NgramLanguageModel(pack)

    @Test fun rescoringMatchesInterpolationFormula() {
        val lm = NeuralRescoringLm(ngram, nn, alpha = 0.5f)
        val ctx = listOf("I", "am")
        val out = lm.output(ctx)
        for (w in listOf("going", "the", "park", "London")) {
            val lex = ngram.lexIdOf(w)
            val g = pack.ngramIdOf(lex)
            val nnLp = out.logProb(g) + ngram.wordTerm(lex)
            val want = ln(0.5 * exp(nnLp.toDouble()) + 0.5 * exp(ngram.logProb(ctx, w).toDouble()))
            assertEquals(want.toFloat(), lm.logProb(ctx, w), 1e-4f, w)
        }
        assertEquals(ngram.logProb(ctx, "xyzzy"), lm.logProb(ctx, "xyzzy"), 1e-6f, "unknown words keep the n-gram score")
    }

    @Test fun predictScoresAreConsistentAndFiltered() {
        val lm = NeuralRescoringLm(ngram, nn)
        for (ctx in listOf(emptyList(), listOf("I"), listOf("going", "to"), listOf("oh"))) {
            for (prefix in listOf("", "t", "g")) {
                val got = lm.predict(ctx, prefix, 5)
                assertTrue(got.isNotEmpty())
                for (i in 1 until got.size) assertTrue(got[i - 1].second >= got[i].second)
                for ((w, lp) in got) {
                    assertEquals(lm.logProb(ctx, w), lp, 1e-4f, "$ctx/$prefix/$w")
                    assertTrue(pack.rules.fold(w).startsWith(prefix))
                    assertFalse(ngram.isOffensive(w), "$w suggested")
                }
            }
        }
    }

    @Test fun statesAreCachedPerSentencePrefix() {
        val lm = NeuralRescoringLm(ngram, nn)
        lm.output(listOf("I", "am"))
        val s0 = lm.steps
        lm.logProb(listOf("I", "am"), "going")
        lm.predict(listOf("I", "am"), "", 3)
        assertEquals(s0, lm.steps, "same context: no new step")
        lm.output(listOf("I", "am", "going"))
        assertEquals(s0 + 1, lm.steps, "one more word: one step")
        lm.output(listOf("See", "you.", "I", "am"))
        assertEquals(s0 + 1, lm.steps, "the previous sentence does not matter")
    }

    @Test fun prefetchRunsTheStepInTheBackground() {
        val lm = NeuralRescoringLm(ngram, nn)
        val plain = NeuralRescoringLm(ngram, nn)
        val ctx = arrayListOf("I", "am")
        lm.prefetch(ctx).get()
        ctx.add("mutated")  // prefetch copied the list
        val s0 = lm.steps
        assertEquals(3L, s0, "<s> I am")
        assertEquals(plain.predict(listOf("I", "am"), "", 5), lm.predict(listOf("I", "am"), "", 5))
        assertEquals(plain.logProb(listOf("I", "am"), "going"), lm.logProb(listOf("I", "am"), "going"))
        assertEquals(s0, lm.steps, "the queries found the prefetched state")
        // a query racing a running prefetch waits for it instead of stepping again
        val f = lm.prefetch(listOf("I", "am", "going"))
        lm.predict(listOf("I", "am", "going"), "", 3)
        f.get()
        assertEquals(s0 + 1, lm.steps)
        // LanguagePack.prefetch is a no-op without a neural LM
        EnglishPack.load("en-GB", pack, bytes("tiny_GB.lmo")).prefetch(listOf("I"))
    }

    @Test fun concurrentQueriesForOneContextStepItOnce() {
        val lm = NeuralRescoringLm(ngram, nn)
        val ctx = listOf("I", "am", "going", "to")
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val futures = (0 until 8).map { i ->
            pool.submit<Float> {
                start.await()
                if (i % 2 == 0) lm.logProb(ctx, "the") else lm.predict(ctx, "", 3).let { lm.logProb(ctx, "the") }
            }
        }
        start.countDown()
        val values = futures.map { it.get() }
        pool.shutdown()
        assertEquals(1, values.toSet().size)
        assertEquals(NeuralRescoringLm(ngram, nn).logProb(ctx, "the"), values[0])
        assertEquals(ngram.sentenceIds(ctx).size.toLong(), lm.steps, "each id stepped once despite 8 racing queries")
    }

    @Test fun rejectsANeuralModelForAnotherPack() {
        val b = bytes("tiny.nnlm")
        b[64] = (b[64] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { NeuralRescoringLm(ngram, NeuralLm.read(b)) }
    }

    @Test fun languagePackUsesTheRescorer() {
        val p = EnglishPack.load("en-GB", pack, bytes("tiny_GB.lmo"), neural = nn)
        val (lm, lexicon) = p
        assertTrue(lm is NeuralRescoringLm)
        assertTrue(p.spec().lm === lm && lexicon.entries().any { it.word == "colour" })
        assertEquals("colour", lm.predict(listOf("the"), "col", 1).single().first)
    }

    @Test fun shippedNeuralModel() {
        val dir = File("../app/src/main/assets/lm")
        assumeTrue(File(dir, "en.nnlm").exists() && File(dir, "en.lm").exists())
        assertTrue(File(dir, "en.nnlm").length() <= 9_000_000, "asset budget (9 MB)")
        val p = EnglishPack.load("en-US", dir, useNeural = true)
        val lm = p.lm
        assertTrue(lm is NeuralRescoringLm)
        assertTrue("you" in lm.predict(listOf("how", "are"), "", 3).map { it.first })
        assertTrue("tomorrow" in lm.predict(listOf("see", "you"), "tom", 3).map { it.first })
        assertTrue(abs(lm.logProb(listOf("thank"), "you")) < 3f)
    }
}
