package dev.pboard.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextTest {
    @Test fun cleanDropsChineseAndFullWidthPunctuation() {
        assertEquals("Thank you.", VoiceText.clean("哦， 完了。 Thank you."))
        assertEquals("Thank you.", VoiceText.clean("Thank you. 好，"))
        assertEquals("", VoiceText.clean("就是"))
    }

    @Test fun cleanDropsFillers() {
        assertEquals("So, you know, we did it.", VoiceText.clean("So, um, you know, we did it."))
        assertEquals("you know.", VoiceText.clean("Um, you know."))
        assertEquals("You know.", VoiceText.fit("Um, you know.", ""))
        assertEquals("I think so.", VoiceText.clean("I think so, uh."))
        assertEquals("", VoiceText.clean("Uh."))
    }

    @Test fun cleanKeepsOrdinaryText() {
        assertEquals("He hoped there would be stew for dinner, turnips and carrots.",
            VoiceText.clean("He hoped there would be stew for dinner, turnips and carrots."))
        assertEquals("Umbrella sales rose 10%.", VoiceText.clean("Umbrella sales rose 10%."))
        assertEquals("a b", VoiceText.clean("  a   b  "))
    }

    @Test fun fitCapitalisesAtSentenceStart() {
        assertEquals("Hello there.", VoiceText.fit("hello there.", ""))
        assertEquals("Hello there.", VoiceText.fit("hello there.", null))
        assertEquals(" Next one.", VoiceText.fit("next one.", "Done."))
        assertEquals("Next one.", VoiceText.fit("next one.", "Done. "))
        assertEquals("New line.", VoiceText.fit("new line.", "Dear Sam,\n"))
    }

    @Test fun fitContinuesASentenceInLowerCase() {
        assertEquals(" and bought milk.", VoiceText.fit("And bought milk.", "I went to the store"))
        assertEquals("and bought milk.", VoiceText.fit("And bought milk.", "I went to the store, "))
        // "I" forms and acronyms keep their capitals
        assertEquals(" I think so.", VoiceText.fit("I think so.", "well"))
        assertEquals(" I'm sure.", VoiceText.fit("I'm sure.", "well"))
        assertEquals(" NASA called.", VoiceText.fit("NASA called.", "then"))
    }

    @Test fun fitRespectsNoAutoCaps() {
        assertEquals("hello there.", VoiceText.fit("hello there.", "", autoCaps = false))
    }

    @Test fun fitSpacing() {
        assertEquals("hi.", VoiceText.fit("Hi.", "("))
        assertEquals(" then", VoiceText.fit("Then", "first"))
        assertEquals("", VoiceText.fit("就是", "text"))
    }
}
