package dev.pboard.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceItnTest {
    private fun itn(s: String) = VoiceItn.apply(s)

    @Test fun cardinals() {
        assertEquals("I have three kids.", itn("I have three kids."))
        assertEquals("We sold 25 units.", itn("We sold twenty five units."))
        assertEquals("We sold 25 units.", itn("We sold twenty-five units."))
        assertEquals("about 105 people", itn("about one hundred and five people"))
        assertEquals("a crowd of 12,500", itn("a crowd of twelve thousand five hundred"))
        assertEquals("100 people came", itn("a hundred people came"))
        assertEquals("10 years", itn("ten years"))
        assertEquals("Covid 19 cases", itn("Covid nineteen cases"))
        assertEquals("15", itn("Fifteen"))
        assertEquals("5 million users", itn("five million users"))
        assertEquals("3.5 stars", itn("three point five stars"))
    }

    @Test fun wordsThatAreNotNumbers() {
        assertEquals("the one I like", itn("the one I like"))
        assertEquals("wait a second", itn("wait a second"))
        assertEquals("first of all", itn("first of all"))
        assertEquals("a lot", itn("a lot"))
        assertEquals("and so on", itn("and so on"))
        assertEquals("one, two, three", itn("one, two, three"))
    }

    @Test fun yearsAndDigitStrings() {
        assertEquals("in 1984", itn("in nineteen eighty four"))
        assertEquals("by 2024", itn("by twenty twenty four"))
        assertEquals("since 2005", itn("since two thousand and five"))
        assertEquals("in 2019", itn("in two thousand nineteen"))
        assertEquals("call 5551234", itn("call five five five one two three four"))
    }

    @Test fun ordinalsAndDates() {
        assertEquals("the 21st century", itn("the twenty first century"))
        assertEquals("the 10th time", itn("the tenth time"))
        assertEquals("on March 3rd", itn("on March third"))
        assertEquals("on March 3rd, 2024.", itn("on March third twenty twenty four."))
        assertEquals("June 12", itn("June twelve"))
        assertEquals("you may third", itn("you may third"))
    }

    @Test fun times() {
        assertEquals("at 5:30 pm", itn("at five thirty pm"))
        assertEquals("5:30 pm", itn("five thirty PM"))
        assertEquals("5:30 pm.", itn("five thirty p.m."))
        assertEquals("7:05 am", itn("seven oh five a m"))
        assertEquals("at 10 pm", itn("at ten pm"))
        assertEquals("6 o'clock", itn("six o'clock"))
        assertEquals("see you at 5:45", itn("see you at five forty five"))
        assertEquals("11:15 am", itn("eleven fifteen am"))
    }

    @Test fun moneyAndPercent() {
        assertEquals("it costs $25", itn("it costs twenty five dollars"))
        assertEquals("$3.50 each", itn("three dollars and fifty cents each"))
        assertEquals("£10", itn("ten pounds"))
        assertEquals("€5.", itn("five euros."))
        assertEquals("a $2 million deal", itn("a two million dollar deal"))
        assertEquals("up 50%", itn("up fifty percent"))
        assertEquals("5%", itn("five per cent"))
        assertEquals("2.5%", itn("two point five percent"))
        assertEquals("75 cents", itn("seventy five cents"))
    }

    @Test fun fitAppliesItn() {
        assertEquals("Meet at 5:30 pm.", VoiceText.fit("meet at five thirty pm.", ""))
        assertEquals(" $25", VoiceText.fit("Twenty five dollars", "it was"))
    }
}
