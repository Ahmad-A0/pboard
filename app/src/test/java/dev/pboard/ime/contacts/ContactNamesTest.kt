package dev.pboard.ime.contacts

import dev.pboard.ime.contacts.ContactNames.Toggle
import dev.pboard.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNamesTest {
    @Test fun displayNamesAreSplitIntoNameTokens() {
        assertEquals(listOf("Tolu", "Adebayo"), ContactNames.tokensOf("Tolu Adebayo"))
        assertEquals(listOf("Smith", "John"), ContactNames.tokensOf("Smith, John (work)"))
        assertEquals(listOf("Anne", "Marie", "O'Brien"), ContactNames.tokensOf("Anne-Marie O’Brien"))
        // Initials, digits and punctuation carry no name.
        assertEquals(listOf("Dr", "Okafor"), ContactNames.tokensOf("Dr. J. Okafor 2"))
        assertEquals(listOf("Zoë", "Müller"), ContactNames.tokensOf("Zoë Müller"))
    }

    @Test fun tokensGetNameCapitals() {
        assertEquals(listOf("Tolu", "Adebayo"), ContactNames.tokensOf("tolu adebayo"))
        assertEquals(listOf("John", "Smith"), ContactNames.tokensOf("JOHN SMITH"))
        // Short capitals and inner capitals are kept as written; particles of a capitalised name are left out.
        assertEquals(listOf("DJ", "McKay"), ContactNames.tokensOf("DJ McKay"))
        assertEquals(listOf("Ludwig", "Beethoven"), ContactNames.tokensOf("Ludwig van Beethoven"))
        assertEquals(listOf("山田", "太郎"), ContactNames.tokensOf("山田 太郎"))
    }

    @Test fun contactsWithoutANameHaveNoTokens() {
        assertEquals(emptyList<String>(), ContactNames.tokensOf("someone@example.com"))
        assertEquals(emptyList<String>(), ContactNames.tokensOf("+44 20 7946 0000"))
        assertEquals(emptyList<String>(), ContactNames.tokensOf(""))
        assertEquals(emptyList<String>(), ContactNames.tokensOf("' '' -"))
    }

    @Test fun tokensAreDistinctAndCapped() {
        assertEquals(listOf("Tolu", "Adebayo", "Bola"), ContactNames.tokens(listOf("Tolu Adebayo", "Bola Adebayo", "tolu")))
        assertEquals(listOf("Tolu", "Adebayo"), ContactNames.tokens(listOf("Tolu Adebayo", "Bola Adebayo"), maxTokens = 2))
        val many = (0 until 30_000).map { "N" + it.toString().map { d -> 'a' + (d - '0') }.joinToString("") }
        assertEquals(ContactNames.MAX_TOKENS, ContactNames.tokens(many).size)
    }

    @Test fun theSettingIsOffByDefaultAndNeedsThePermission() {
        assertFalse(Prefs.suggestContacts.default)
        assertTrue(ContactNames.active(settingOn = true, permissionGranted = true))
        // A revoked permission disables the feature whatever the setting says; so does the setting.
        assertFalse(ContactNames.active(settingOn = true, permissionGranted = false))
        assertFalse(ContactNames.active(settingOn = false, permissionGranted = true))
    }

    @Test fun turningTheSwitchOnAsksForThePermissionAndADenialTurnsItBackOff() {
        assertEquals(Toggle.ASK_PERMISSION, ContactNames.onToggle(wantOn = true, permissionGranted = false))
        assertEquals(Toggle.ON, ContactNames.onToggle(wantOn = true, permissionGranted = true))
        assertEquals(Toggle.OFF, ContactNames.onToggle(wantOn = false, permissionGranted = true))
        assertEquals(Toggle.OFF, ContactNames.onToggle(wantOn = false, permissionGranted = false))
        assertTrue(ContactNames.afterPermissionResult(granted = true))
        assertFalse(ContactNames.afterPermissionResult(granted = false))
        // Revoked later in the system's settings: the switch shows off again.
        assertFalse(ContactNames.reconciled(settingOn = true, permissionGranted = false))
        assertTrue(ContactNames.reconciled(settingOn = true, permissionGranted = true))
        assertFalse(ContactNames.reconciled(settingOn = false, permissionGranted = true))
    }
}
