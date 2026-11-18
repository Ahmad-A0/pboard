package dev.pboard.engine.lm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class UserNgramModelTest {
    private var now = 1_000_000_000L
    private val day = 24L * 3600 * 1000
    private fun user() = UserNgramModel(halfLifeMillis = 30 * day, maxEntries = 1000, clock = { now })
    private val pack = LmPack.wrap(ByteBuffer.wrap(javaClass.getResourceAsStream("/lm/tiny.lm")!!.readBytes()))

    @Test fun learnsNewWordsAndPhrases() {
        val u = user()
        val lm = NgramLanguageModel(pack, user = u)
        assertFalse(lm.isKnown("Kalamazoo"))
        val before = lm.logProb(listOf("going", "to"), "Kalamazoo")
        repeat(3) { lm.observe(listOf("I", "am", "going", "to"), "Kalamazoo") }
        assertTrue(lm.isKnown("Kalamazoo"))
        assertTrue(lm.logProb(listOf("going", "to"), "Kalamazoo") > before + 5)
        assertTrue(lm.predict(listOf("going", "to"), "", 3).any { it.first == "Kalamazoo" })
        assertTrue(lm.predict(listOf("going", "to"), "kal", 3).first().first == "Kalamazoo")
        // known words are learned in canonical form
        lm.observe(listOf("going", "to"), "london")
        assertTrue(u.count("London") > 0.9)
    }

    @Test fun countsDecay() {
        val u = user()
        u.observe(listOf("<s>"), "zorp")
        u.observe(listOf("<s>"), "zorp")
        assertTrue(u.isKnown("zorp"))
        now += 60 * day // two half-lives
        assertEquals(0.5, u.count("zorp"), 1e-6)
        assertFalse(u.isKnown("zorp"))
    }

    @Test fun persistsAndRestores() {
        val u = user()
        repeat(4) { u.observe(listOf("see", "you"), "tmrw") }
        u.observe(listOf("thank"), "you")
        val buf = ByteArrayOutputStream().also { u.save(it) }.toByteArray()
        val v = user().also { it.load(ByteArrayInputStream(buf)) }
        for (ctx in listOf(listOf("see", "you"), listOf("you"), emptyList())) {
            assertTrue(abs(u.prob(ctx, "tmrw") - v.prob(ctx, "tmrw")) < 1e-12)
            assertEquals(u.weight(ctx), v.weight(ctx))
        }
    }

    @Test fun forgetRemovesAndBlocks() {
        val u = user()
        repeat(3) { u.observe(listOf("hi"), "blergh") }
        u.forget("blergh")
        assertEquals(0.0, u.count("blergh"))
        u.observe(listOf("hi"), "blergh")
        assertEquals(0.0, u.prob(listOf("hi"), "blergh"))
    }

    @Test fun sizeIsBounded() {
        val u = user()
        for (i in 0 until 5000) u.observe(listOf("w${i - 1}"), "w$i")
        assertTrue(u.size <= 1000)
    }

    @Test fun probabilitiesAreNormalised() {
        val u = user()
        val words = listOf("a", "b", "c", "d")
        for (i in 0 until 40) u.observe(listOf(words[i % 3]), words[(i * 7) % 4])
        for (ctx in listOf(listOf("a"), listOf("b"), listOf("zzz"), emptyList())) {
            assertEquals(1.0, words.sumOf { u.prob(ctx, it) }, 1e-9)
        }
    }
}
