package dev.pboard.engine.textintel

import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.NeuralLm
import dev.pboard.engine.lm.NeuralRescoringLm
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Inline completion over the shipped English pack (skipped when the assets are absent). */
class InlineCompleterTest {
    private val dir = File(System.getProperty("pboard.lmDir") ?: "../app/src/main/assets/lm")

    private fun completer(tag: String = "en-US", config: CompleterConfig = CompleterConfig()): InlineCompleter {
        assumeTrue(File(dir, "en.lm").exists())
        val pack = EnglishPack.load(tag, dir)
        val nnFile = File(dir, "en.nnlm")
        val lm = if (nnFile.exists()) NeuralRescoringLm(pack.model, NeuralLm.read(nnFile), cacheSize = 32) else pack.model
        return InlineCompleter(lm, pack.model, config)
    }

    private val c by lazy { completer() }

    private fun words(s: String) = if (s.isEmpty()) emptyList() else s.split(' ')

    @Test fun completesAConfidentPhrase() {
        val loose = completer(config = CompleterConfig(minProbability = 0.3f))
        val r = loose.complete(words("Talk to you"), "")
        assertNotNull(r, "Talk to you -> ${loose.path(words("Talk to you"), "", 4).map { it.word to it.joint }}")
        assertEquals("next time", r.text)
        assertTrue(r.probability >= 0.3f)
    }

    @Test fun completesALongWordMidWordKeepingTheTypedLetters() {
        // a single word only from 9 letters on (Gboard's rule): "qu" -> "questions"
        val r = c.complete(words("Let me know if you have any"), "qu")
        assertNotNull(r, c.path(words("Let me know if you have any"), "qu", 4).map { it.word to it.joint }.toString())
        assertEquals("questions", r.words.first())
        assertEquals("estions", r.text)
    }

    @Test fun staysQuietWhenUnsure() {
        assertNull(c.complete(words("I think"), ""))
        assertNull(c.complete(words("The"), "x"))
        assertNull(c.complete(words("so"), "1"))
    }

    @Test fun aLoneWordIsNotAPhrase() {
        // "Thank you so" -> "much" is the strip's job; Smart Compose needs two words or more by default
        val path = c.path(words("Thank you so"), "", 4)
        assertEquals("much", path.first().word)
        assertNull(c.choose(path.take(1), "", 0f, 2))
        assertNotNull(c.choose(path.take(1), "", 0f, 1))
    }

    @Test fun pathIsGreedyAndJointProbabilityFalls() {
        val path = c.path(words("see you"), "", 4)
        assertTrue(path.isNotEmpty())
        for (i in 1 until path.size) assertTrue(path[i].joint <= path[i - 1].joint)
        assertEquals(path.size, c.choose(path, "", 0f, 1)?.words?.size)
    }

    @Test fun respectsTheThreshold() {
        val strict = completer(config = CompleterConfig(minProbability = 0.99f))
        assertNull(strict.complete(words("Nice to"), ""))
    }

    @Test fun dialectSpellingInCompletions() {
        val gb = completer("en-GB")
        val us = completer("en-US")
        val pg = gb.path(words("What is your favourite"), "col", 1)
        val pu = us.path(words("What is your favorite"), "col", 1)
        assertEquals("colour", pg.firstOrNull()?.word?.lowercase())
        assertEquals("color", pu.firstOrNull()?.word?.lowercase())
    }
}
