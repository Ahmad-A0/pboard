package dev.pboard.ime.theme

import dev.pboard.ime.config.GboardDefaults
import dev.pboard.ime.theme.KeyFonts.Source
import dev.pboard.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Preferences › Font › System default had no effect: the keyboard's font ignored the setting. */
class KeyFontsTest {
    @Test
    fun systemDefaultWinsOverPboardsFont() {
        assertEquals(Source.SYSTEM_DEFAULT, KeyFonts.source(useSystemFont = true, systemHasGoogleSans = true))
        assertEquals(Source.SYSTEM_DEFAULT, KeyFonts.source(useSystemFont = true, systemHasGoogleSans = false))
    }

    @Test
    fun pboardDefaultPrefersTheSystemsGoogleSansThenTheBundledFont() {
        assertEquals(Source.SYSTEM_GOOGLE_SANS, KeyFonts.source(useSystemFont = false, systemHasGoogleSans = true))
        assertEquals(Source.BUNDLED, KeyFonts.source(useSystemFont = false, systemHasGoogleSans = false))
    }

    @Test
    fun pboardFontIsTheDefault() {
        assertFalse(GboardDefaults().useSystemFont)
        assertFalse(Prefs.useSystemFont.default)
    }
}
