package dev.pboard.ime.theme

import dev.pboard.prefs.ThemeCatalog
import dev.pboard.prefs.ThemeCatalog.CustomTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Suggestion-strip divider colour per theme family, as Gboard 18.6. The dark themes used to get the label colour at 25 %, drawn opaque.
 */
class DividerColorsTest {
    private val wallpaper = Palette.baseline(false).copy(outlineVariant = 0xFFC9C5BE.toInt())
    private val wallpaperDark = Palette.baseline(true).copy(outlineVariant = 0xFF4B4741.toInt())
    private val dynamic = { dark: Boolean -> if (dark) wallpaperDark else wallpaper }

    private fun divider(id: String, systemDark: Boolean = false, customs: List<CustomTheme> = emptyList()) =
        "%08X".format(KeyboardTheme.resolve(id, systemDark, dynamic, customs).divider)

    @Test
    fun dynamicColorUsesOutlineVariant() {
        assertEquals("FFC9C5BE", divider(ThemeCatalog.DYNAMIC, false))
        assertEquals("FF4B4741", divider(ThemeCatalog.DYNAMIC, true))
    }

    @Test
    fun defaultThemesUseTheFixedGm3OutlineVariant() {
        assertEquals("FFC4C7C5", divider(ThemeCatalog.DEFAULT_LIGHT))
        assertEquals("FF444746", divider(ThemeCatalog.DEFAULT_DARK))
        assertEquals("FFC4C7C5", divider(ThemeCatalog.SYSTEM_AUTO, false))
        assertEquals("FF444746", divider(ThemeCatalog.SYSTEM_AUTO, true))
    }

    @Test
    fun colourThemes() {
        assertEquals("3337474F", divider("color_material_light"))
        assertEquals("33303030", divider("color_google_light"))
        assertEquals("1F000000", divider("color_sand"))
        for (id in listOf("color_cyan", "color_light_pink")) assertEquals(id, "1A000000", divider(id))
        for (id in listOf(
            "color_material_dark", "color_google_dark", "color_red", "color_green", "color_teal", "color_blue",
            "color_deep_purple", "color_pink", "color_brown", "color_blue_grey", "color_black", "color_holo_blue",
            "color_holo_white",
        )) assertEquals(id, "1AFFFFFF", divider(id))
    }

    @Test
    fun gradientAndImageThemes() {
        for (s in ThemeCatalog.lightGradients) assertEquals(s.id, "1F000000", divider(s.id))
        for (s in ThemeCatalog.darkGradients) assertEquals(s.id, "1AFFFFFF", divider(s.id))
        val mine = CustomTheme("custom_ab12cd34", 50)
        assertEquals("1AFFFFFF", divider(mine.id, customs = listOf(mine)))
    }

    @Test
    fun everyColourThemeIsCovered() {
        // Every catalogue colour theme is pinned above (a new theme must get its Gboard divider, not a default).
        val pinned = setOf(
            "color_material_light", "color_google_light", "color_sand", "color_cyan", "color_light_pink",
            "color_material_dark", "color_google_dark", "color_red", "color_green", "color_teal", "color_blue",
            "color_deep_purple", "color_pink", "color_brown", "color_blue_grey", "color_black", "color_holo_blue",
            "color_holo_white",
        )
        assertEquals(pinned, ThemeCatalog.colors.map { it.id }.toSet())
    }
}
