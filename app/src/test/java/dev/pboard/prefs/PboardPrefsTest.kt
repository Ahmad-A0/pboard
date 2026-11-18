package dev.pboard.prefs

import dev.pboard.ime.toolbar.ToolbarItem
import dev.pboard.ime.toolbar.ToolbarLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PboardPrefsTest {
    private val sp = FakeSharedPreferences()
    private val prefs = PboardPrefs.forTesting(sp)

    @Test
    fun defaultsAndWrites() {
        assertFalse(prefs[Prefs.numberRow])
        assertEquals(OneHandedMode.OFF, prefs[Prefs.oneHandedMode])
        prefs[Prefs.numberRow] = true
        prefs[Prefs.oneHandedMode] = OneHandedMode.LEFT
        prefs[Prefs.longPressDelayMs] = 99_999 // clamped
        assertTrue(prefs[Prefs.numberRow])
        assertEquals(OneHandedMode.LEFT, prefs[Prefs.oneHandedMode])
        assertEquals("left", sp.getString("one_handed_mode", null)) // stable id, not enum name
        assertEquals(2000, prefs[Prefs.longPressDelayMs])
        assertTrue(prefs.isSet(Prefs.numberRow))
        prefs.reset(Prefs.numberRow)
        assertFalse(prefs.isSet(Prefs.numberRow))
    }

    @Test
    fun listsKeepOrder() {
        prefs[Prefs.enabledLanguages] = listOf("fr-FR:azerty", "en-US:qwerty")
        assertEquals(listOf("fr-FR:azerty", "en-US:qwerty"), prefs[Prefs.enabledLanguages])
        prefs[Prefs.enabledLanguages] = emptyList()
        assertEquals(emptyList<String>(), prefs[Prefs.enabledLanguages])
    }

    @Test
    fun wrongStoredTypeFallsBackToDefault() {
        sp.edit().putString("number_row", "yes").apply()
        assertFalse(prefs[Prefs.numberRow])
    }

    @Test
    fun flowEmitsCurrentThenChanges() = runBlocking {
        val seen = mutableListOf<Boolean>()
        val job = launch { prefs.flow(Prefs.glideTyping).take(2).toList(seen) }
        yield()
        prefs[Prefs.glideTyping] = false
        job.join()
        assertEquals(listOf(true, false), seen)
    }

    /**
     * The theme picker's Apply (one edit: theme + key borders) reaches both observers: the keyboard rebuilds on every
     * key of [PboardPrefs.changes] (PboardIme.watchPrefs) and the picker's selection follows [PboardPrefs.flow].
     */
    @Test
    fun themeApplyNotifiesKeyboardAndPicker() = runBlocking {
        val changed = mutableListOf<String?>()
        val selected = mutableListOf<String>()
        val keys = launch { prefs.changes.take(2).toList(changed) }
        val picker = launch { prefs.flow(Prefs.theme).take(2).toList(selected) }
        yield()
        prefs.edit {
            this[Prefs.theme] = "color_black"
            this[Prefs.keyBorders] = false
        }
        keys.join()
        picker.join()
        assertEquals(setOf("theme", "key_borders"), changed.toSet())
        assertEquals(listOf(ThemeCatalog.DYNAMIC, "color_black"), selected)
    }

    /** One-handed enter, swap and exit (toolbar / side buttons) each reach the keyboard's rebuild trigger. */
    @Test
    fun oneHandedWritesNotifyKeyboard() = runBlocking {
        val changed = mutableListOf<String?>()
        val keys = launch { prefs.changes.take(3).toList(changed) }
        yield()
        prefs[Prefs.oneHandedMode] = OneHandedMode.RIGHT // enter
        prefs[Prefs.oneHandedMode] = OneHandedMode.LEFT // swap
        prefs[Prefs.oneHandedMode] = OneHandedMode.OFF // exit
        keys.join()
        assertEquals(List(3) { "one_handed_mode" }, changed)
        assertEquals(OneHandedMode.OFF, prefs[Prefs.oneHandedMode])
    }

    @Test
    fun observeCallback() {
        val seen = mutableListOf<Int>()
        val handle = prefs.observe(Prefs.longPressDelayMs) { seen += it }
        prefs[Prefs.longPressDelayMs] = 450
        handle.close()
        prefs[Prefs.longPressDelayMs] = 500
        assertEquals(listOf(450), seen)
    }

    @Test
    fun toolbarRoundTrip() = runBlocking {
        val l = ToolbarLayout.DEFAULT.moveToBar(ToolbarItem.UNDO, 0)
        l.save(prefs)
        assertEquals(l, ToolbarLayout.load(prefs))
        assertEquals(l, ToolbarLayout.flow(prefs).first())
        ToolbarLayout.reset(prefs)
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.load(prefs))
    }
}
