package dev.pboard.prefs

import dev.pboard.prefs.LanguageCatalog.Entry
import dev.pboard.prefs.LanguageCatalog.Layout.QWERTY
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Languages as the source of truth for the keyboard's languages (KeyboardLanguages). */
class KeyboardLanguagesTest {
    private val available = KeyboardLanguages.availableFromLayoutFiles(listOf("common.json", "en_US.json", "en_GB.json"))
    private val gb = Entry("en-GB", QWERTY)
    private val us = Entry("en-US", QWERTY)

    @Test fun availableLanguagesAreTheLayoutFiles() {
        assertEquals(listOf("en-GB", "en-US"), available)
        assertEquals(listOf(QWERTY), KeyboardLanguages.layoutsFor("en-GB", available))
        assertEquals(emptyList<LanguageCatalog.Layout>(), KeyboardLanguages.layoutsFor("de-DE", available))
    }

    @Test fun enabledKeepsOnlyUsableEntriesInOrder() {
        // An old store with a language and a layout that do not exist: dropped / QWERTY; duplicates collapse.
        val stored = listOf("de-DE:qwertz", "en-US:dvorak", "en-GB:qwerty", "en-US:qwerty")
        assertEquals(listOf(us, gb), KeyboardLanguages.enabled(stored, available))
        assertEquals(listOf(gb), KeyboardLanguages.enabled(listOf("fr-FR:azerty"), available)) // never empty
    }

    @Test fun languageSwitchTogglesBetweenTheEnabledDialects() {
        val both = listOf(gb, us)
        assertEquals(us, KeyboardLanguages.next(both, "en-GB"))
        assertEquals(gb, KeyboardLanguages.next(both, "en-US"))
        assertEquals(gb, KeyboardLanguages.next(listOf(gb), "en-GB"))
        assertEquals(gb, KeyboardLanguages.active(both, "")) // nothing chosen yet: the first one
        assertEquals(gb, KeyboardLanguages.active(both, "de-DE"))
        assertEquals(us, KeyboardLanguages.active(both, "en-US"))
        // en-US removed while active: back to the first enabled one.
        assertEquals(gb, KeyboardLanguages.active(listOf(gb), "en-US"))
    }

    @Test fun addKeyboardOffersOnlyAvailableLanguagesNotYetEnabled() {
        assertEquals(listOf("en-US"), KeyboardLanguages.addable(listOf(gb), available))
        assertEquals(emptyList<String>(), KeyboardLanguages.addable(listOf(gb, us), available))
    }

    @Test fun firstRunCopiesTheSystemSubtypesCurrentFirst() {
        assertEquals(listOf("en-GB:qwerty"), KeyboardLanguages.seed("en-GB", listOf("en-GB"), available))
        assertEquals(listOf("en-US:qwerty", "en-GB:qwerty"), KeyboardLanguages.seed("en-US", listOf("en-GB", "en-US"), available))
        assertEquals(listOf("en-GB:qwerty"), KeyboardLanguages.seed(null, listOf("fr-FR"), available))
    }
}
