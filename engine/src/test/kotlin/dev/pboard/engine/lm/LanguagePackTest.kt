package dev.pboard.engine.lm

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LanguagePackTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/lm/$name")!!.readBytes()
    private val tiny = LmPack.wrap(ByteBuffer.wrap(bytes("tiny.lm")))

    @Test fun tinyLexiconSource() {
        val gb = EnglishPack.load("en-GB", tiny, bytes("tiny_GB.lmo"))
        val us = EnglishPack.load("en_US", tiny, bytes("tiny_US.lmo"))
        assertEquals("en-GB", gb.tag)
        assertEquals("en-US", us.tag)
        val g = gb.entries().associateBy { it.word }
        val u = us.entries().associateBy { it.word }
        assertEquals(tiny.nLex, g.size)
        assertEquals("color", g.getValue("colour").variantKey)
        assertEquals("color", g.getValue("color").variantKey)
        assertEquals("favorite", g.getValue("favourite").variantKey)
        assertNull(g.getValue("park").variantKey)
        assertTrue(g.getValue("shit").offensive && !g.getValue("park").offensive)
        assertTrue(g.getValue("colour").logPrior > g.getValue("color").logPrior)
        assertTrue(u.getValue("color").logPrior > u.getValue("colour").logPrior)
        assertTrue(g.getValue("the").logPrior > g.getValue("dog").logPrior)
        assertTrue("London" in g && "I'm" in g && "iPhone" in g)
        val (lm, lexicon) = gb
        assertTrue(lm.isKnown("colour") && lexicon.entries().any { it.word == "colour" })
        assertEquals("en-GB", gb.spec(0.7f).tag)
    }

    @Test fun dialectMapping() {
        assertEquals("en_GB", EnglishPack.dialectOf("en-GB"))
        assertEquals("en_GB", EnglishPack.dialectOf("en-AU"))
        assertEquals("en_GB", EnglishPack.dialectOf("en_IE"))
        assertEquals("en_US", EnglishPack.dialectOf("en-US"))
        assertEquals("en_US", EnglishPack.dialectOf("en-CA"))
        assertEquals("en_US", EnglishPack.dialectOf("en"))
        assertEquals("lm/en_GB.lmo", EnglishPack.overlayAsset("en-NZ"))
    }

    @Test fun shippedEnglishLexicon() {
        val dir = File("../app/src/main/assets/lm")
        assumeTrue(File(dir, "en.lm").exists())
        val gb = EnglishPack.load("en-GB", dir).entries().associateBy { it.word }
        val us = EnglishPack.load("en-US", dir).entries().associateBy { it.word }
        assertTrue(gb.size in 150_000..250_000, "lexicon size ${gb.size}")
        for ((a, b) in listOf("colour" to "color", "realise" to "realize", "travelled" to "traveled",
                              "centre" to "center", "favourite" to "favorite", "analyse" to "analyze",
                              "cancelled" to "canceled", "modellers" to "modelers", "monetised" to "monetized",
                              "spectres" to "specters", "haemorrhaging" to "hemorrhaging")) {
            assertEquals(gb.getValue(b).variantKey, gb.getValue(a).variantKey, "$a/$b")
            assertTrue(gb.getValue(a).variantKey != null)
            assertTrue(gb.getValue(a).logPrior > gb.getValue(b).logPrior, "en-GB prefers $a")
            assertTrue(us.getValue(b).logPrior > us.getValue(a).logPrior, "en-US prefers $b")
        }
        assertTrue(gb.values.count { it.offensive } > 200)
        assertTrue(gb.getValue("fuck").offensive && !gb.getValue("Dick").offensive && !gb.getValue("sexy").offensive)
        assertTrue(gb.getValue("the").logPrior > gb.getValue("whale").logPrior)
        assertTrue(gb.getValue("Francisco").logPrior > -16f, "frequency prior, not KN continuation")
        assertTrue(gb.getValue("whilst").logPrior > us.getValue("whilst").logPrior)
        assertTrue("I'm" in gb && "iPhone" in gb && "London" in gb && "don't" in gb && "dont" !in gb)
        // coverage regressions: common nouns, texting words, inflections
        for (w in listOf("donut", "donuts", "pls", "thx", "u", "calderas", "harassments", "selfie", "lol"))
            assertTrue(w in gb, "$w missing")
    }
}
