package dev.pboard.ime.layout

import dev.pboard.ime.layout.KeyboardLanguage.Companion.keyHint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Corner hints and the start of the long-press popup: Touch & hold keys for numbers and Long press for symbols. */
class KeyHintTest {
    @Test fun topRowShowsTheDigitWhileNumberHintsAreOn() {
        assertEquals("1" to listOf("1"), keyHint("1", "%", numbersShown = true, symbolsOn = false))
        assertEquals("1" to listOf("1"), keyHint("1", "%", numbersShown = true, symbolsOn = true))
    }

    @Test fun symbolsReplaceHiddenDigitsAndComeFirst() {
        // Number row on (digits not hinted) + Long press for symbols: q shows %, long press offers % then 1.
        assertEquals("%" to listOf("%", "1"), keyHint("1", "%", numbersShown = false, symbolsOn = true))
    }

    @Test fun lowerRowsHaveOnlySymbols() {
        assertEquals("@" to listOf("@"), keyHint(null, "@", numbersShown = true, symbolsOn = true))
        // Long press for symbols off (the default): no hint, no symbol in the popup.
        assertEquals(null to emptyList<String>(), keyHint(null, "@", numbersShown = true, symbolsOn = false))
    }

    @Test fun digitStaysOfferedWithoutAnyHint() {
        assertEquals(null to listOf("1"), keyHint("1", "%", numbersShown = false, symbolsOn = false))
    }
}
