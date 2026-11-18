package dev.pboard.ime.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug report: in one-handed mode the exit button "does nothing". With the keyboard on the left edge the button
 * column was laid out in the keyboard's 4 dp edge margin (x 0, 10 px wide) instead of the free column on the right,
 * so the exit / swap / resize buttons were drawn over the keys and a tap on them typed a letter.
 */
class OneHandedLayoutTest {
    // PboardIme.applyOneHanded at 1080 px, 420 dpi: keyboard 0.8 of the width, 4 dp (10 px) from its edge.
    private val width = 1080
    private val edge = 10
    private val free = width - (width * 0.8f).toInt() - edge

    @Test
    fun keyboardOnTheRightPutsTheButtonsOnTheLeft() {
        assertEquals(0 to free, OneHandedLayout.sideColumn(width, sideLeft = free, sideRight = edge))
    }

    @Test
    fun keyboardOnTheLeftPutsTheButtonsOnTheRight() {
        assertEquals((width - free) to free, OneHandedLayout.sideColumn(width, sideLeft = edge, sideRight = free))
    }

    @Test
    fun theColumnIsTheFreeSpaceBesideTheKeyboard() {
        for ((l, r) in listOf(free to edge, edge to free)) {
            val (x, w) = OneHandedLayout.sideColumn(width, l, r)
            val keyboard = l until width - r
            assertEquals(maxOf(l, r), w)
            assert(x + w <= keyboard.first || x >= keyboard.last + 1) { "column $x+$w overlaps the keyboard $keyboard" }
        }
    }
}
