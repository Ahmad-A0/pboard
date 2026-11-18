package dev.pboard.engine.textintel

import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.NeuralLm
import dev.pboard.engine.lm.NeuralRescoringLm
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The grammar checker over the shipped English pack (skipped when the assets are absent). */
class GrammarCheckerTest {
    private val dir = File(System.getProperty("pboard.lmDir") ?: "../app/src/main/assets/lm")
    private val morphFile = File("../app/src/main/assets/textintel/en_morph.txt")

    private fun checker(tag: String = "en-GB", neural: Boolean = true): GrammarChecker {
        assumeTrue(File(dir, "en.lm").exists() && morphFile.exists())
        val pack = EnglishPack.load(tag, dir)
        val nnFile = File(dir, "en.nnlm")
        val lm = if (neural && nnFile.exists()) NeuralRescoringLm(pack.model, NeuralLm.read(nnFile), cacheSize = 64) else pack.model
        return GrammarChecker(lm, pack.model, EnglishMorphology.read(morphFile.inputStream()))
    }

    private val gb by lazy { checker("en-GB") }
    private val us by lazy { checker("en-US") }

    /** Applies every fix and returns the corrected text. */
    private fun GrammarChecker.fix(text: String): String {
        var out = text
        for (i in check(text).sortedByDescending { it.start }) out = out.substring(0, i.start) + i.replacement + out.substring(i.end)
        return out
    }

    @Test fun fixesClearErrors() {
        val cases = listOf(
            "I went their yesterday." to "I went there yesterday.",
            "He go to school every day." to "He goes to school every day.",
            "They was late again." to "They were late again.",
            "I could of done it myself." to "I could have done it myself.",
            "She ate a apple for lunch." to "She ate an apple for lunch.",
            "We saw the the new film." to "We saw the new film.",
            "She don't like coffee." to "She doesn't like coffee.",
            "I have went there before." to "I have gone there before.",
            "I think i will stay home." to "I think I will stay home.",
            "Thanks alot for the help." to "Thanks a lot for the help.",
            "He is taller then me." to "He is taller than me.",
        )
        for ((wrong, right) in cases) assertEquals(right, gb.fix(wrong), wrong)
    }

    @Test fun leavesCorrectTextAlone() {
        val clean = listOf(
            "Does he go to school every day?", "Let it go.", "Make it work before Friday.", "There is a cat on the roof.",
            "Their house is lovely.", "It's raining again.", "The dog wagged its tail.", "She put it there yesterday.",
            "He found it under the bed.", "We visited a university and an old castle.", "I'll be there in an hour.",
            "I could have done it myself.", "If I were you, I would go.", "I saw that that was a mistake.",
            "He had had enough.", "Your phone is ringing.", "You're welcome.", "I want to go too.",
            "She is more careful than me.", "I am supposed to be there.", "We used to live here.",
            "Can you send me the file?", "Will you be home tonight?", "Did you see it?", "I insist that he go.",
        )
        for (t in clean) for (c in listOf(gb, us)) assertTrue(c.check(t).isEmpty(), "$t -> ${c.check(t)}")
    }

    @Test fun neverFlagsEitherDialectsSpelling() {
        val texts = listOf(
            "I love the colour of your organisation's new logo, it is my favourite.",
            "I love the color of your organization's new logo, it is my favorite.",
            "We travelled to the centre to practise and analyse the programme.",
            "We traveled to the center to practice and analyze the program.",
            "Have you got the licence? I have gotten the license.",
        )
        for (t in texts) for (c in listOf(gb, us)) assertTrue(c.check(t).isEmpty(), "$t -> ${c.check(t)}")
    }

    @Test fun issueRangesAndMarks() {
        val t = "We saw the the film."
        val issue = gb.check(t).single()
        assertEquals("", issue.replacement)
        assertEquals("the", t.substring(issue.markStart, issue.markEnd))
        assertEquals("We saw the film.", t.substring(0, issue.start) + t.substring(issue.end))
        val their = gb.check("I went their yesterday.").single()
        assertEquals("their", "I went their yesterday.".substring(their.markStart, their.markEnd))
        assertEquals("there", their.replacement)
    }

    /** What the spell checker service reports: Gboard-style ranges ("a apple" -> "an apple", "The the" -> "The"). */
    @Test fun displayEditsMarkWhatGboardMarks() {
        fun shown(t: String): List<String> = gb.check(t).map { gb.displayEdit(t, it) }.map { "${t.substring(it.start, it.end)}>${it.replacement}" }
        assertEquals(listOf("the the>the"), shown("We saw the the new film."))
        assertEquals(listOf("a apple>an apple"), shown("She ate a apple for lunch."))
        assertEquals(listOf("of done>have done"), shown("I could of done it myself."))
        assertEquals(listOf("their>there"), shown("I went their yesterday."))
        assertEquals(listOf("more taller>taller"), shown("I am more taller than him."))
    }

    @Test fun caseOfTheFixFollowsTheText() {
        assertEquals("You're welcome.", gb.fix("Your welcome."))
        assertEquals("It's a nice day.", gb.fix("Its a nice day."))
    }

    @Test fun theWordBeingTypedIsLeftAloneUntilTheSentenceGoesOn() {
        // "I went their" may become "I went their way": not judged without what follows
        assertTrue(gb.check("I think we should go to their").isEmpty())
        assertEquals(listOf("there"), gb.check("I went their yesterday").map { it.replacement })
    }

    @Test fun skipsNonProse() {
        assertTrue(gb.check("see https://example.com/their/go a apple").isEmpty())
        assertTrue(gb.check("ok").isEmpty())
    }

    @Test fun aAnUsesSoundNotLetter() {
        assertEquals(true, gb.vowelSound("hour"))
        assertEquals(false, gb.vowelSound("university"))
        assertEquals(true, gb.vowelSound("uninformed"))
        assertEquals(false, gb.vowelSound("one"))
        assertEquals(true, gb.vowelSound("FBI"))
        assertEquals(false, gb.vowelSound("URL"))
        assertEquals(true, gb.vowelSound("8"))
        assertEquals(null, gb.vowelSound("herb"))
    }

    @Test fun tokenizerKeepsContractionsAndOffsets() {
        val text = "I don't know, it's “fine”… really?"
        val toks = TextTokens.tokenize(text)
        assertEquals(listOf("I", "don't", "know", ",", "it's", "“", "fine", "”", "…", "really", "?"), toks.map { it.text })
        for (t in toks) assertEquals(t.text, text.substring(t.start, t.end).replace('’', '\''))
        val s = TextTokens.sentences(text, toks)
        assertEquals(2, s.size)
        assertTrue(s.all { it.terminated })
    }
}
