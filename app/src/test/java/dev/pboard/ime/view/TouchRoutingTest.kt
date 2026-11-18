package dev.pboard.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The head-to-head's TSI user10 item 278 ("you" after "Did "), replayed on the farm 420 dpi layout. The bar spans y
 * 1517-1633, its buttons and pills are 89 px tall about the centre line (bottom at 1619), and the keys view starts at
 * 1633 with the top row at 1654.
 */
class TouchRoutingTest {
    private val barTop = 1517f
    private val barHeight = 116f
    private val buttonsBottom = (barHeight - 1f) / 2f + 89f / 2f // barCenterY + barButtonDiameter / 2

    private fun owner(y: Float): String =
        if (y >= barTop + barHeight) "keys" else if (TouchRouting.barTouchToKeys(y - barTop, buttonsBottom)) "keys" else "bar"

    @Test fun item278TypesOnTheKeys() {
        // y, o (in the gap: 12 px below the mic, 23 px above the top row), u, space
        val taps = listOf(574f to 1736f, 987f to 1631f, 631f to 1735f, 788f to 2092f)
        assertEquals(listOf("keys", "keys", "keys", "keys"), taps.map { owner(it.second) })
    }

    @Test fun theBarKeepsItsButtonsAndPills() {
        assertEquals("bar", owner(1575f)) // the mic's centre
        assertEquals("bar", owner(1530f)) // the top of a pill
        assertEquals("bar", owner(1619f)) // the bottom edge of the buttons
        assertEquals("keys", owner(1620f))
    }

    @Test fun watchdogResetsTrackersLeftByALostUp() {
        // DOWN on a key, the UP never arrives, then the next tap's DOWN: the old tracker is stale.
        assertTrue(TouchRouting.staleOnFirstDown(firstPointerDown = true, liveTrackers = 1))
        // Normal typing: the previous tap ended.
        assertFalse(TouchRouting.staleOnFirstDown(firstPointerDown = true, liveTrackers = 0))
        // A second finger while the first holds a key (rollover) is not a lost UP.
        assertFalse(TouchRouting.staleOnFirstDown(firstPointerDown = false, liveTrackers = 1))
    }
}
