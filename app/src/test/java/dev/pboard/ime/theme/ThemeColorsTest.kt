package dev.pboard.ime.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.pboard.prefs.ThemeCatalog
import dev.pboard.prefs.ThemeCatalog.CustomTheme
import dev.pboard.settings.ui.PreviewPalette
import dev.pboard.settings.ui.imagePalette
import dev.pboard.settings.ui.paletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The theme picker's preview and the keyboard resolve every theme through one path and get the same colours. */
class ThemeColorsTest {
    /** A Material You palette unlike the baseline, so the test sees which palette the result came from. */
    private val wallpaper = Palette.baseline(false).copy(
        surfaceContainerLowest = 0xFFFFFBF7.toInt(), surfaceContainer = 0xFFF3EDE6.toInt(),
        secondaryContainer = 0xFFF0E0CF.toInt(), onSecondaryContainer = 0xFF4F4539.toInt(),
    )
    private val wallpaperDark = Palette.baseline(true).copy(
        surfaceBright = 0xFF3D3833.toInt(), surfaceContainer = 0xFF221F1C.toInt(),
        secondaryContainer = 0xFF4F4539.toInt(), onSecondaryContainer = 0xFFF0E0CF.toInt(),
    )
    private val dynamic = { dark: Boolean -> if (dark) wallpaperDark else wallpaper }

    private fun tokens(p: PreviewPalette) = listOf(
        p.background, p.backgroundEnd, p.key, p.spaceBar, p.special, p.accent, p.text, p.hint, p.specialText,
        p.accentText, p.borderlessHeader,
    ).map { it?.toArgb() }

    private fun tokens(k: KeyboardColors) = listOf(
        k.background, k.backgroundEnd ?: k.background, k.key, k.spaceBar, k.functionalKey, k.enterKey, k.keyLabel,
        k.hintLabel, k.functionalIcon, k.enterIcon, k.borderlessHeader,
    )

    @Test
    fun previewAndKeyboardResolveEveryCatalogueThemeIdentically() {
        for (spec in ThemeCatalog.all) for (dark in listOf(false, true)) {
            val keyboard = KeyboardTheme.resolve(spec.id, dark, dynamic)
            val preview = paletteFor(spec, dark, dynamic)
            assertEquals("${spec.id} dark=$dark", tokens(keyboard), tokens(preview))
        }
    }

    /**
     * The reported mismatch: a "My themes" image theme was marked selected in the picker while the keyboard drew
     * Dynamic Color. The keyboard now draws the image theme with the picker's colours, and the picker's check mark
     * goes on the theme the keyboard draws (Dynamic Color for an id that names no theme).
     */
    @Test
    fun pickerMarksTheThemeTheKeyboardDraws() {
        val mine = CustomTheme("custom_ab12cd34", 70)
        val customs = listOf(mine)
        for (id in ThemeCatalog.all.map { it.id } + mine.id) assertEquals(id, KeyboardTheme.renderedThemeId(id, customs))
        val dynamicLight = KeyboardTheme.resolve(ThemeCatalog.DYNAMIC, false, dynamic)
        for (id in listOf("custom_deleted0", "no_such_theme", "")) {
            assertEquals(ThemeCatalog.DYNAMIC, KeyboardTheme.renderedThemeId(id, customs))
            assertEquals(dynamicLight, KeyboardTheme.resolve(id, false, dynamic, customs))
        }

        for (dark in listOf(false, true)) {
            val keyboard = KeyboardTheme.resolve(mine.id, dark, dynamic, customs)
            assertNotEquals(KeyboardTheme.resolve(ThemeCatalog.DYNAMIC, dark, dynamic), keyboard)
            assertEquals("themes/custom_ab12cd34.jpg", keyboard.backgroundImage)
            assertEquals(0.24f, keyboard.imageScrim, 1e-6f)
            assertTrue(KeyboardTheme.isDark(mine.id, dark, customs))
            val preview = imagePalette(mine.id, null, mine.brightness)
            assertEquals(tokens(keyboard), tokens(preview))
            assertEquals(keyboard.imageScrim, preview.imageScrim, 0f)
        }
        // Brightness 100 shows the picture as is, 0 dims it by 80 %.
        assertEquals(0f, KeyboardTheme.resolve(mine.id, false, dynamic, listOf(mine.copy(brightness = 100))).imageScrim, 0f)
        assertEquals(0.8f, KeyboardTheme.resolve(mine.id, false, dynamic, listOf(mine.copy(brightness = 0))).imageScrim, 1e-6f)
    }

    @Test
    fun dynamicColorUsesTheMaterialYouPalette() {
        val light = KeyboardTheme.resolve(ThemeCatalog.DYNAMIC, false, dynamic)
        assertEquals(wallpaper.surfaceContainer, light.background)
        assertEquals(wallpaper.surfaceContainerLowest, light.key)
        assertEquals(wallpaper.secondaryContainer, light.functionalKey)
        assertEquals(wallpaper.secondaryContainer, light.enterKey)
        val dark = KeyboardTheme.resolve(ThemeCatalog.DYNAMIC, true, dynamic)
        assertEquals(wallpaperDark.surfaceContainer, dark.background)
        assertEquals(wallpaperDark.surfaceBright, dark.key) // Gboard 18.6 dark letter keys
        assertEquals(wallpaperDark.onSecondaryContainer, dark.enterIcon)
        // Unknown ids (e.g. a deleted theme) and image themes missing from the list fall back to Dynamic Color.
        assertEquals(light, KeyboardTheme.resolve("custom_0123abcd", false, dynamic))
        assertEquals(dark, KeyboardTheme.resolve("no_such_theme", true, dynamic))
    }

    @Test
    fun systemAutoFollowsNightMode() {
        fun id(dark: Boolean) = if (dark) ThemeCatalog.DEFAULT_DARK else ThemeCatalog.DEFAULT_LIGHT
        for (dark in listOf(false, true)) {
            assertEquals(KeyboardTheme.resolve(id(dark), dark, dynamic), KeyboardTheme.resolve(ThemeCatalog.SYSTEM_AUTO, dark, dynamic))
            assertEquals(dark, KeyboardTheme.isDark(ThemeCatalog.SYSTEM_AUTO, dark))
        }
        assertEquals(true, KeyboardTheme.isDark("color_black", false))
        assertEquals(false, KeyboardTheme.isDark("color_cyan", true))
    }

    /**
     * Gboard 18.6's Black keyboard (measured): #2E2E2E keys and #141414 function keys, which the keyboard and the
     * preview now both draw (before: #727272 / #393939 on the keyboard, #161616 / #020202 in the preview).
     */
    @Test
    fun blackMatchesGboard() {
        val black = ThemeCatalog.byId("color_black")!!
        val k = KeyboardTheme.resolve(black.id, false, dynamic)
        assertEquals(0xFF000000.toInt(), k.background)
        assertNull(k.backgroundEnd)
        assertEquals(0xFF2E2E2E.toInt(), k.key)
        assertEquals(0xFF141414.toInt(), k.functionalKey)
        assertEquals(0xFF80CBC4.toInt(), k.enterKey)
        assertEquals(0xFFFFFFFF.toInt(), k.enterIcon)
        assertEquals(0xFFFFFFFF.toInt(), k.keyLabel)
        assertEquals(0xFFB9B9B9.toInt(), k.functionalIcon)
        assertEquals(0xFFD5D5D5.toInt(), k.hintLabel)
        assertEquals(0xFF999999.toInt(), k.barIcon) // toolbar icons, measured on the keyboard
        assertEquals(0xFF727272.toInt(), k.spaceBar) // thumbnail / borderless space bar
    }

    /**
     * Every colour theme draws Gboard 18.6's measured colours (theme preview sheets on the farm pool emulator, checked
     * against the applied keyboard): background (gradient: top stop), key, function key, enter, key label,
     * function label / icon, enter icon, number hint. Gradient keys are white with alpha over the gradient.
     */
    @Test
    fun colourThemesUseGboardsColours() {
        fun row(vararg v: Long) = v.map { (if (it > 0xFFFFFF) it else 0xFF000000L or it).toInt() }
        val measured = mapOf(
        "default_light" to row(0xF0F4F9, 0xFFFFFF, 0xE1E3E1, 0x0B57D0, 0x1F1F1F, 0x1F1F1F, 0xFFFFFF, 0x444746),
        "default_dark" to row(0x1E1F20, 0x37393B, 0x444746, 0xA8C7FA, 0xE3E3E3, 0xE3E3E3, 0x062E6F, 0xC4C7C5),
        "color_material_light" to row(0xECEFF1, 0xFBFBFC, 0xDFE2E4, 0x5CB5AB, 0x061D28, 0x465760, 0xFFFFFF, 0x727D82),
        "color_material_dark" to row(0x263238, 0x404A50, 0x313C42, 0x6EACA8, 0xD9DBDC, 0xC2C5C7, 0xFFFFFF, 0xB2B6B9),
        "color_google_light" to row(0xE8EAED, 0xFFFFFF, 0xCCCED5, 0x1A73E8, 0x000000, 0x3D3D3F, 0xFFFFFF, 0x666666),
        "color_google_dark" to row(0x292E33, 0x484C4F, 0x373C41, 0x5E97F6, 0xFFFFFF, 0xC3C5C6, 0xFFFFFF, 0xB5B7B8),
        "color_red" to row(0xC62828, 0xD04E4E, 0xCA3838, 0xF44336, 0xFFFFFF, 0xEFC4C4, 0xFFFFFF, 0xF6DBDB),
        "color_green" to row(0x43A047, 0x64B168, 0x51A755, 0x2D7D32, 0xFFFFFF, 0xCBE5CC, 0xFFFFFF, 0xE0EFE1),
        "color_teal" to row(0x00695C, 0x2E8479, 0x147468, 0x4DB6AC, 0xFFFFFF, 0xB9D6D2, 0xFFFFFF, 0xD5E6E4),
        "color_blue" to row(0x1565C0, 0x3F80CB, 0x2771C4, 0x2196F3, 0xFFFFFF, 0xBFD5ED, 0xFFFFFF, 0xD8E6F5),
        "color_cyan" to row(0x80DEEA, 0xACE9F1, 0x99E4EE, 0x00ACC1, 0x333333, 0x51676A, 0xFFFFFF, 0x4B5859),
        "color_deep_purple" to row(0x4527A0, 0x664DB1, 0x5337A7, 0x9575CD, 0xFFFFFF, 0xCCC3E5, 0xFFFFFF, 0xE0DBEF),
        "color_pink" to row(0xAD1457, 0xBB3E75, 0xB32664, 0xF50057, 0xFFFFFF, 0xE8BED1, 0xFFFFFF, 0xF1D8E3),
        "color_light_pink" to row(0xF8BBD0, 0xFAD2E0, 0xF9C8D9, 0xE36087, 0x333333, 0x6E5F64, 0xFFFFFF, 0x5B5356),
        "color_sand" to row(0xDACDC5, 0xF0EBE7, 0xE7DFDA, 0xFF7276, 0x1E1E1E, 0x595756, 0xFFFFFF, 0x484746),
        "color_brown" to row(0x4E342E, 0x6D5853, 0x5B433E, 0x8D6E63, 0xFFFFFF, 0xCEC7C5, 0xFFFFFF, 0xE2DDDC),
        "color_blue_grey" to row(0x37474F, 0x5B686E, 0x46555C, 0x78909C, 0xFFFFFF, 0xC8CCCE, 0xFFFFFF, 0xDEE1E2),
        "color_black" to row(0x000000, 0x2E2E2E, 0x141414, 0x80CBC4, 0xFFFFFF, 0xB9B9B9, 0xFFFFFF, 0xD5D5D5),
        "color_holo_blue" to row(0x161616, 0x5C5C5C, 0x282828, 0x003D5C, 0xFFFFFF, 0xBFBFBF, 0xFFFFFF, 0xDEDEDE),
        "color_holo_white" to row(0x161616, 0x5C5C5C, 0x282828, 0x282828, 0xFFFFFF, 0xBFBFBF, 0xFFFFFF, 0xDEDEDE),
        "gradient_light_1" to row(0xFCB8B9, 0x3CFFFFFF, 0x3CFFFFFF, 0xEA6671, 0x1E1E1E, 0x2C2B2F, 0xFFFFFF, 0x484143),
        "gradient_dark_1" to row(0x1C1269, 0x3FFFFFFF, 0x3CFFFFFF, 0xF93FA5, 0xFFFFFF, 0xEEE2EE, 0xFFFFFF, 0xE2DDEA),
        "gradient_light_2" to row(0xE3E0D7, 0x4CFFFFFF, 0x48FFFFFF, 0xC65B2F, 0x000000, 0x2D2E2D, 0xFFFFFF, 0x2F2F2D),
        "gradient_dark_2" to row(0x5C5F64, 0x2DFFFFFF, 0x1EFFFFFF, 0x80CBC4, 0xFFFFFF, 0xDBDCDC, 0xFFFFFF, 0xE1E2E3),
        "gradient_light_3" to row(0xDDC9C8, 0x4CFFFFFF, 0x4BFFFFFF, 0xBC729D, 0x000000, 0x2B2B2C, 0xFFFFFF, 0x2F2C2C),
        "gradient_dark_3" to row(0x604742, 0x2CFFFFFF, 0x2AFFFFFF, 0x8D6E63, 0xFFFFFF, 0xE1DDDC, 0xFFFFFF, 0xE3E0DF),
        "gradient_light_4" to row(0xE5EDFA, 0x4AFFFFFF, 0x43FFFFFF, 0x249DD6, 0x000000, 0x2A2A2A, 0xFFFFFF, 0x2D2E30),
        "gradient_dark_4" to row(0x51636D, 0x2DFFFFFF, 0x2DFFFFFF, 0x78909C, 0xFFFFFF, 0xDEE0E1, 0xFFFFFF, 0xE1E3E5),
        "gradient_light_5" to row(0xF7C2D4, 0x4BFFFFFF, 0x4CFFFFFF, 0xE15383, 0x000000, 0x312529, 0xFFFFFF, 0x32292C),
        "gradient_dark_5" to row(0x017B6C, 0x2EFFFFFF, 0x2EFFFFFF, 0x4DB6AC, 0xFFFFFF, 0xD5E4E4, 0xFFFFFF, 0xD5E8E6),
        "gradient_light_6" to row(0xFBE3D7, 0x3DFFFFFF, 0x3BFFFFFF, 0x249DD6, 0x1E1E1E, 0x2C2F30, 0xFFFFFF, 0x484746),
        "gradient_dark_6" to row(0x1571D8, 0x2DFFFFFF, 0x27FFFFFF, 0x2196F3, 0xFFFFFF, 0xD8E3F3, 0xFFFFFF, 0xD8E6F7),
        "gradient_light_7" to row(0xFFE7A7, 0x3BFFFFFF, 0x38FFFFFF, 0x8C85D8, 0x1E1E1E, 0x2F2D2E, 0xFFFFFF, 0x4A4741),
        "gradient_dark_7" to row(0x35608A, 0x2DFFFFFF, 0x2DFFFFFF, 0x355F87, 0xFFFFFF, 0xDDE2E6, 0xFFFFFF, 0xDEE4EA),
        "gradient_light_8" to row(0xB2DEA1, 0x3DFFFFFF, 0x3EFFFFFF, 0x6B9E81, 0x1E1E1E, 0x2D2C2A, 0xFFFFFF, 0x41463E),
        "gradient_dark_8" to row(0x55585D, 0x2DFFFFFF, 0x28FFFFFF, 0x5E97F6, 0xFFFFFF, 0xDEDFE0, 0xFFFFFF, 0xE1E2E3),
        "gradient_light_9" to row(0xFEC59A, 0x3DFFFFFF, 0x3BFFFFFF, 0xF56C4A, 0x1E1E1E, 0x332D26, 0xFFFFFF, 0x4B433C),
        "gradient_dark_9" to row(0x615654, 0x28FFFFFF, 0x2BFFFFFF, 0xB35736, 0xFFFFFF, 0xDDDCDC, 0xFFFFFF, 0xE4E3E2),
        )
        val themed = ThemeCatalog.all.filter { it.kind == ThemeCatalog.Kind.SOLID || it.kind == ThemeCatalog.Kind.GRADIENT }
        assertEquals(themed.map { it.id }.toSet(), measured.keys)
        for (spec in themed) for (dark in listOf(false, true)) {
            val k = KeyboardTheme.resolve(spec.id, dark, dynamic)
            assertEquals(spec.id, measured.getValue(spec.id), listOf(k.background, k.key, k.functionalKey, k.enterKey,
                k.keyLabel, k.functionalIcon, k.enterIcon, k.hintLabel))
            val p = paletteFor(spec, dark, dynamic)
            assertEquals(spec.id, Color(k.key), p.key)
            assertEquals(spec.id, Color(k.enterKey), p.accent)
        }
    }

    /** Toolbar icons (measured on the keyboard): 60 % of the label over colour themes, the label elsewhere. */
    @Test
    fun toolbarIconsMatchGboard() {
        fun bar(id: String) = KeyboardTheme.resolve(id, false, dynamic).barIcon
        assertEquals(0xFF999999.toInt(), bar("color_black"))
        assertEquals(0xFF627178.toInt(), bar("color_material_light"))
        assertEquals(0xFF1F1F1F.toInt(), bar("default_light"))
        assertEquals(0xFFE3E3E3.toInt(), bar("default_dark"))
        assertEquals(0xFF1E1E1E.toInt(), bar("gradient_light_1"))
    }
}
