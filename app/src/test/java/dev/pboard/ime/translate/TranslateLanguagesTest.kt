package dev.pboard.ime.translate

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateLanguagesTest {
    private val dirs = setOf("en" to "es", "es" to "en", "en" to "fr", "fr" to "en", "en" to "de")

    @Test fun initialUsesKeyboardLanguageAndSavedTarget() {
        assertEquals("en" to "fr", TranslateLanguages.initial(dirs, "en", "fr"))
        // saved target not reachable from the keyboard language: first available target
        assertEquals("en" to "de", TranslateLanguages.initial(dirs, "en", "it"))
        // keyboard language without a model: first source alphabetically
        assertEquals("en" to "es", TranslateLanguages.initial(dirs, "pt", "es"))
        assertNull(TranslateLanguages.initial(emptySet(), "en", ""))
    }

    @Test fun choosingLanguagesKeepsInstalledPairs() {
        assertEquals("es" to "en", TranslateLanguages.withSource(dirs, "en" to "es", "es")) // acts as a swap
        assertEquals("fr" to "en", TranslateLanguages.withSource(dirs, "en" to "de", "fr"))
        assertEquals("en" to "fr", TranslateLanguages.withTarget(dirs, "en" to "es", "fr"))
        assertEquals("es" to "en", TranslateLanguages.withTarget(dirs, "es" to "en", "fr")) // es-fr not installed
        assertEquals("es" to "en", TranslateLanguages.swapped(dirs, "en" to "es"))
        assertNull(TranslateLanguages.swapped(dirs, "en" to "de")) // no de-en model
    }

    @Test fun pickerRowsPutCurrentAndRecentsFirst() {
        val (top, rest) = TranslateLanguages.pickerRows(setOf("es", "fr", "de", "it"), "fr", listOf("de", "xx", "fr", "es")) { it.uppercase() }
        assertEquals(listOf("fr", "de", "es"), top.map { it.code })
        assertTrue(top[0].selected && !top[1].selected)
        assertEquals(listOf("it"), rest.map { it.code })
        assertEquals(listOf("es", "fr", "de"), TranslateLanguages.remember(listOf("fr", "de"), "es"))
        assertEquals(listOf("de", "fr"), TranslateLanguages.remember(listOf("fr", "de"), "de"))
    }

    @Test fun separatorOnlyAfterLetters() {
        assertTrue(TranslateLanguages.needsSeparator("text"))
        assertTrue(TranslateLanguages.needsSeparator("é"))
        assertFalse(TranslateLanguages.needsSeparator("text "))
        assertFalse(TranslateLanguages.needsSeparator("vous ?"))
        assertFalse(TranslateLanguages.needsSeparator(""))
        assertFalse(TranslateLanguages.needsSeparator(null))
    }

    @Test fun sessionSurvivesOnlyAnAppRestartOfTheSameField() {
        assertTrue(TranslateLanguages.keepOnRestart(true, true, true, true, password = false))
        // a new view start (keyboard shown again, another field or app) or a password field closes Translate
        assertFalse(TranslateLanguages.keepOnRestart(false, true, true, true, password = false))
        assertFalse(TranslateLanguages.keepOnRestart(true, false, true, true, password = false))
        assertFalse(TranslateLanguages.keepOnRestart(true, true, false, true, password = false))
        assertFalse(TranslateLanguages.keepOnRestart(true, true, true, false, password = false))
        assertFalse(TranslateLanguages.keepOnRestart(true, true, true, true, password = true))
    }

    @Test fun displayNames() {
        assertEquals("Spanish", TranslateLanguages.displayName("es", Locale.ENGLISH))
        assertEquals("Español", TranslateLanguages.displayName("es", Locale.forLanguageTag("es")))
    }
}
