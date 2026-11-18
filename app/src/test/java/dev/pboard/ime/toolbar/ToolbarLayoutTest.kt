package dev.pboard.ime.toolbar

import dev.pboard.ime.toolbar.ToolbarItem.CLIPBOARD
import dev.pboard.ime.toolbar.ToolbarItem.EMOJI
import dev.pboard.ime.toolbar.ToolbarItem.FLOATING
import dev.pboard.ime.toolbar.ToolbarItem.ONE_HANDED
import dev.pboard.ime.toolbar.ToolbarItem.SETTINGS
import dev.pboard.ime.toolbar.ToolbarItem.TEXT_EDITING
import dev.pboard.ime.toolbar.ToolbarItem.THEME
import dev.pboard.ime.toolbar.ToolbarItem.UNDO
import dev.pboard.ime.toolbar.ToolbarItem.VOICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {
    private val base = ToolbarLayout(
        listOf(CLIPBOARD, SETTINGS, THEME, ONE_HANDED, TEXT_EDITING, FLOATING, EMOJI, UNDO), 5, VOICE,
    )

    @Test
    fun dropIntoFullBarSpillsLastPinnedToFrontOfPanel() {
        // Gboard: dragging Undo between clipboard and settings pushes the last bar item (theme…) out.
        val l = base.moveToBar(UNDO, 1)
        assertEquals(listOf(CLIPBOARD, UNDO, SETTINGS, THEME, ONE_HANDED), l.pinned)
        assertEquals(listOf(TEXT_EDITING, FLOATING, EMOJI), l.panel)
    }

    @Test
    fun dragFromBarToPanelUnpinsAndShrinksBar() {
        val l = base.moveToPanel(SETTINGS, 2)
        assertEquals(listOf(CLIPBOARD, THEME, ONE_HANDED, TEXT_EDITING), l.pinned)
        assertEquals(listOf(FLOATING, EMOJI, SETTINGS, UNDO), l.panel)
        assertEquals(4, l.pinnedCount)
    }

    @Test
    fun reorderWithinBarKeepsCount() {
        val l = base.moveToBar(TEXT_EDITING, 0)
        assertEquals(listOf(TEXT_EDITING, CLIPBOARD, SETTINGS, THEME, ONE_HANDED), l.pinned)
        assertEquals(5, l.pinnedCount)
    }

    @Test
    fun barWithRoomGrows() {
        val l = base.moveToPanel(THEME, 0).moveToBar(EMOJI, 4)
        assertEquals(listOf(CLIPBOARD, SETTINGS, ONE_HANDED, TEXT_EDITING, EMOJI), l.pinned)
    }

    @Test
    fun replacingPowerKeyMovesOldOneToPanelFront() {
        val l = base.replacePowerKey(UNDO)
        assertEquals(UNDO, l.powerKey)
        assertEquals(VOICE, l.panel.first())
        assertFalse(UNDO in l.order)
    }

    @Test
    fun togglePinned() {
        assertTrue(base.togglePinned(EMOJI, capacity = 6).isPinned(EMOJI))
        assertFalse(base.togglePinned(CLIPBOARD).isPinned(CLIPBOARD))
    }

    @Test
    fun decodeDropsUnknownAndAppendsMissing() {
        val l = ToolbarLayout.decode(listOf("settings", "bogus", "clipboard"), 2, "voice")
        assertEquals(listOf(SETTINGS, CLIPBOARD), l.pinned)
        assertEquals(ToolbarItem.entries.size - 1, l.order.size) // everything except the power key
        assertEquals(VOICE, l.powerKey)
    }

    @Test
    fun defaultDecodesToDefault() {
        val d = ToolbarLayout.DEFAULT
        assertEquals(d, ToolbarLayout.decode(d.order.map { it.id }, d.pinnedCount, d.powerKey!!.id))
        // Gboard 18.6: a fresh phone install shows three items on the bar.
        assertEquals(listOf(CLIPBOARD, SETTINGS, THEME), d.pinned)
        assertEquals(ToolbarItem.DEFAULT_BAR_ITEMS, d.pinnedCount)
    }

    @Test
    fun freshBarCanGrowToCapacity() {
        val l = ToolbarLayout.DEFAULT.moveToBar(ONE_HANDED, 3).moveToBar(TEXT_EDITING, 4).moveToBar(UNDO, 5)
        assertEquals(ToolbarItem.BAR_CAPACITY, l.pinnedCount)
        assertEquals(listOf(CLIPBOARD, SETTINGS, THEME, ONE_HANDED, UNDO), l.pinned)
    }

    @Test
    fun offeredHidesPlannedItems() {
        val l = ToolbarLayout.DEFAULT.offered(largeScreen = false)
        assertFalse(l.order.any { it.availability != ToolbarItem.Availability.ALWAYS })
        assertEquals(3, l.pinnedCount)
    }

    @Test
    fun voiceTypingOffHidesTheMicEverywhere() {
        // Settings > Voice typing off: no mic in the power slot, on the bar or in the "more" panel.
        val pinnedMic = ToolbarLayout(listOf(VOICE, CLIPBOARD, SETTINGS, THEME), pinnedCount = 2, powerKey = UNDO)
        assertEquals(VOICE, ToolbarLayout.DEFAULT.offered(largeScreen = false).powerKey)
        assertEquals(null, ToolbarLayout.DEFAULT.offered(largeScreen = false, voiceTyping = false).powerKey)
        val off = pinnedMic.offered(largeScreen = false, voiceTyping = false)
        assertFalse(VOICE in off.order)
        assertEquals(listOf(CLIPBOARD), off.pinned)
        assertEquals(UNDO, off.powerKey) // another item in the power slot stays
        assertFalse(VOICE.isOffered(largeScreen = true, voiceTyping = false))
        assertTrue(CLIPBOARD.isOffered(largeScreen = false, voiceTyping = false))
    }
}
