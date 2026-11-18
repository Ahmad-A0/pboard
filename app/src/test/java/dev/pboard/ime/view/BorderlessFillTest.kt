package dev.pboard.ime.view

import dev.pboard.ime.layout.Codes
import dev.pboard.ime.layout.KeyStyle
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.ime.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Key borders off: the keyboard drew no space bar (it dropped every normal key's cap) and kept the function keys'
 * caps. Gboard 18.6 keeps only the space bar, ?123 / ABC and enter (measured on Black, Cyan, Default, Dynamic Color).
 */
class BorderlessFillTest {
    private fun colors(id: String) = KeyboardTheme.resolve(id, false, { Palette.baseline(it) })

    @Test
    fun onlySpaceSymbolsAndEnterKeepACap() {
        for (id in listOf("dynamic", "default_light", "color_black", "color_cyan", "gradient_light_1")) {
            val c = colors(id)
            assertEquals(id, 0, BorderlessFill.of(KeyStyle.NORMAL, 'a'.code, c.key, c))
            assertEquals(id, 0, BorderlessFill.of(KeyStyle.NORMAL, Codes.EMOJI, c.key, c))
            assertEquals(id, 0, BorderlessFill.of(KeyStyle.FUNCTIONAL, Codes.SHIFT, c.functionalKey, c))
            assertEquals(id, 0, BorderlessFill.of(KeyStyle.FUNCTIONAL, Codes.DELETE, c.functionalKey, c))
            assertEquals(id, 0, BorderlessFill.of(KeyStyle.FUNCTIONAL, '.'.code, c.functionalKey, c))
            assertEquals(id, c.spaceBar, BorderlessFill.of(KeyStyle.NORMAL, Codes.SPACE, c.key, c))
            assertEquals(id, c.enterKey, BorderlessFill.of(KeyStyle.ACCENT, Codes.ENTER, c.enterKey, c))
        }
    }

    /** The space bar and ?123 colours Gboard draws with key borders off. */
    @Test
    fun borderlessColoursMatchGboard() {
        fun space(id: String) = colors(id).let { BorderlessFill.of(KeyStyle.NORMAL, Codes.SPACE, it.key, it) }
        fun symbols(id: String) = colors(id).let { BorderlessFill.of(KeyStyle.FUNCTIONAL, Codes.SYMBOLS, it.functionalKey, it) }
        assertEquals(0xFF727272.toInt(), space("color_black")) // measured #737373
        assertEquals(0xFF727272.toInt(), symbols("color_black"))
        assertEquals(0xFFB9EDF3.toInt(), space("color_cyan"))
        assertEquals(0xFFB9EDF3.toInt(), symbols("color_cyan"))
        assertEquals(0xFFFFFFFF.toInt(), space("default_light"))
        assertEquals(0xFFE1E3E1.toInt(), symbols("default_light"))
        val dynamic = Palette.baseline(false)
        assertEquals(dynamic.surfaceContainerLowest, space("dynamic"))
        assertEquals(dynamic.secondaryContainer, symbols("dynamic"))
    }
}
