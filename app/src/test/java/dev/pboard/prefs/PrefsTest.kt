package dev.pboard.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {
    @Test
    fun keysAreUnique() {
        val keys = Prefs.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun ranges() {
        assertEquals(300, Prefs.longPressDelayMs.default)
        assertEquals(100, Prefs.longPressDelayMs.min)
        assertEquals(2000, Prefs.longPressDelayMs.max)
        assertEquals(2000, Prefs.longPressDelayMs.sanitize(5000))
        assertEquals(Prefs.SYSTEM_DEFAULT, Prefs.keypressVolume.default)
    }

    @Test
    fun gboard186Defaults() {
        assertTrue(Prefs.numberRowInPassword.default)
        assertTrue(Prefs.showCommaKey.default && Prefs.showPeriodKey.default)
        assertTrue(Prefs.autoSwitchAfterApostrophe.default)
        assertFalse(Prefs.flickSymbols.default)
        assertTrue(Prefs.showAppIcon.default)
        assertEquals(100, Prefs.keyFontScale.default)
        assertEquals(listOf(85, 100, 115, 130, 150, 180, 200), Prefs.SCALE_STEPS)
        assertEquals(1, Prefs.clipboardRetentionHours.default)
        assertEquals(3, Prefs.toolbarPinnedCount.default)
        assertFalse(Prefs.roundedKeys.default)
    }

    @Test
    fun geometryRoundTrip() {
        val g = KeyboardGeometry(1.2f, 0.8f, 1f, 12f)
        assertEquals(g, KeyboardGeometry.decode(g.encode()))
        assertNull(KeyboardGeometry.decode("garbage"))
        assertEquals(KeyboardGeometry.MAX_HEIGHT_RATIO, KeyboardGeometry(9f).sanitized().heightRatio)
        assertEquals(KeyboardGeometry.defaultFor(KeyboardMode.ONE_HANDED),
            Prefs.geometry(KeyboardMode.ONE_HANDED, ScreenOrientation.PORTRAIT).default)
    }

    @Test
    fun languageEntries() {
        val e = LanguageCatalog.Entry.decode("en-US:qwerty")!!
        assertEquals("en-US", e.languageTag)
        assertEquals(LanguageCatalog.Layout.QWERTY, e.layout)
        assertNull(LanguageCatalog.Entry.decode("en-US:klingon"))
        assertEquals(LanguageCatalog.Layout.QWERTZ, LanguageCatalog.layoutsFor("de-DE").first())
        assertEquals(LanguageCatalog.Layout.AZERTY, LanguageCatalog.layoutsFor("fr-FR").first())
    }

    @Test
    fun themes() {
        assertTrue(ThemeCatalog.byId(Prefs.theme.default) != null)
        assertEquals(ThemeCatalog.all.size, ThemeCatalog.all.map { it.id }.toSet().size)
        assertEquals(18, ThemeCatalog.colors.size)
        assertFalse(ThemeCatalog.isCustom(ThemeCatalog.DYNAMIC))
        val c = ThemeCatalog.CustomTheme.decode("custom_ab12|70")!!
        assertEquals(70, c.brightness)
        assertEquals(c, ThemeCatalog.CustomTheme.decode(c.encode()))
    }
}
