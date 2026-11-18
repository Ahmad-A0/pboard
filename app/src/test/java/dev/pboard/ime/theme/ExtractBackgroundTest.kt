package dev.pboard.ime.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Landscape: the extract editor above the keyboard kept the old theme's background until a rotation, because only
 * the framework's view creation themed it. A theme change applies it again (PboardIme.rebuildAll); switching between
 * a theme with its own extract colour and one without must set or restore the background each way.
 */
class ExtractBackgroundTest {
    private val dynamic = { dark: Boolean -> Palette.baseline(dark) }
    private fun colors(id: String) = KeyboardTheme.resolve(id, false, dynamic)

    @Test
    fun themeChangesSetOrRestoreTheBackground() {
        val shown = mutableListOf<String>()
        val bg = ExtractBackground({ shown += "#%06X".format(it and 0xFFFFFF) }, { shown += "platform" })
        bg.apply(colors("dynamic")) // view created under Dynamic Color
        bg.apply(colors("color_black")) // theme changed while in landscape
        bg.apply(colors("dynamic")) // and back
        val surfaceBright = "#%06X".format(Palette.baseline(false).surfaceBright and 0xFFFFFF)
        assertEquals(listOf(surfaceBright, "platform", surfaceBright), shown)
    }
}
