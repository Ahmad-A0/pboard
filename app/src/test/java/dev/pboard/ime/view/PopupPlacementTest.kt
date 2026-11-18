package dev.pboard.ime.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug report: holding q or p, the long-press bubble was cut off by the screen edge. Gboard 18.6 (1080 px, 440 dpi)
 * keeps the popup on screen: q's number bubble (166 px) spans x 0..165 over a key centred at 58.5, p's spans
 * 914..1079 over a key centred at 1020. Panels whose default cell fits (a, e, o, comma) stay centred on the key.
 */
class PopupPlacementTest {
    private val w = 1080f
    private val bubble = 166f
    private val cell = 110f
    private val pad = 28f

    /** The single bubble is the one-cell panel: cell + padding on each side, centred on the key before the shift. */
    private fun bubbleCenter(keyCenterX: Float): Float {
        val half = cell / 2f + pad
        return keyCenterX + PopupPanel.edgeShift(keyCenterX - half, keyCenterX + half, 0f, w)
    }

    @Test
    fun singleBubbleOnAnEdgeKeySlidesFlushWithTheEdge() {
        assertEquals(bubble, cell + 2 * pad, 0.01f)
        assertEquals(83f, bubbleCenter(58.5f), 0.01f)          // q: bubble 0..166
        assertEquals(w - 83f, bubbleCenter(1020f), 0.01f)      // p: bubble 914..1080
    }

    @Test
    fun singleBubbleThatFitsStaysOnTheKey() {
        assertEquals(271f, bubbleCenter(271f), 0.01f)          // e
        assertEquals(83f, bubbleCenter(83f), 0.01f)            // exactly flush
    }

    @Test
    fun edgeShiftKeepsSpansInside() {
        assertEquals(24.5f, PopupPanel.edgeShift(-24.5f, 140f, 0f, w), 0.01f)
        assertEquals(-10f, PopupPanel.edgeShift(900f, 1090f, 0f, w), 0.01f)
        assertEquals(0f, PopupPanel.edgeShift(30f, 525f, 0f, w), 0.01f)
        // Wider than the bounds: centred.
        assertEquals(-10f, PopupPanel.edgeShift(-50f, 1150f, 0f, w), 0.01f)
    }

    @Test
    fun columnSplitMatchesGboardPanels() {
        // a: 4 columns from the key rightwards; e: 3 balanced; o: 5, all to the left; comma: 2 to the right.
        assertEquals(0 to 3, PopupPanel.columnSplit(4, 112.5f, 0f, w, cell, pad))
        assertEquals(1 to 1, PopupPanel.columnSplit(3, 271f, 0f, w, cell, pad))
        assertEquals(4 to 0, PopupPanel.columnSplit(5, 913f, 0f, w, cell, pad))
        assertEquals(0 to 1, PopupPanel.columnSplit(2, 218.5f, 0f, w, cell, pad))
    }
}
