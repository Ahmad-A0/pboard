package dev.pboard.ime.layout

import dev.pboard.ime.layout.BuiltKeyboard.Split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split layout rows against Gboard 18.6 measured on a 10" tablet (1600 x 2560 px at 320 dpi, 2 px/dp, portrait; key caps
 * 10 px inside their cells): halves 546-549 px at 60 px from the edges, top row 5 cells of 109 px, A and Z rows 5.33
 * units of 102.5 px, bottom row ?123 / comma / emoji | space 402-1298 | period / enter.
 */
class SplitLayoutTest {
    private val ones = { n: Int -> FloatArray(n) { 1f } }
    private val none = { n: Int -> FloatArray(n) }
    private val split = Split.gboard(1600, 2f)!!

    private fun cells(widths: FloatArray, gaps: FloatArray = none(widths.size), space: Int = -1) =
        BuiltKeyboard.rowCells(widths, gaps, 10f, 4, 1600, split, space)

    @Test fun splitByDefaultFrom800dp() {
        // Gboard 18.6: split by default at 800 dp smallest width; foldables and small tablets (600-799 dp) unsplit.
        assertTrue(Split.wanted(null, 800))
        assertTrue(Split.wanted(null, 1000))
        assertFalse(Split.wanted(null, 799))
        assertFalse(Split.wanted(null, 600))
        // The toolbar's Split item: the user's choice wins on large screens...
        assertTrue(Split.wanted(true, 600))
        assertTrue(Split.wanted(true, 700))
        assertFalse(Split.wanted(false, 800))
        // ...and phones never split.
        assertFalse(Split.wanted(true, 599))
        assertFalse(Split.wanted(null, 411))
    }

    @Test fun halvesOnA10InchTablet() {
        assertEquals(60, split.leftStart)
        assertEquals(546, split.half)
        assertEquals(1540, split.rightEnd)
        // Landscape (keyboard 2419 px beside the camera cutout): halves 85 px from its edges.
        val land = Split.gboard(2419, 2f)!!
        assertEquals(86, land.leftStart)
        assertEquals(2419 - 86, land.rightEnd)
        // Too narrow for two halves apart: no split.
        assertNull(Split.gboard(1200, 2f))
        assertNotNull(Split.gboard(1400, 2f))
    }

    @Test fun topRowFiveAndFive() {
        val c = cells(ones(10))
        assertEquals((0..9).toList(), c.map { it.index })
        assertEquals(60, c[0].left); assertEquals(169, c[0].right)     // Gboard: Q cap 70-158 -> cell 60-169
        assertEquals(606, c[4].right)                                  // T cap ends 596
        assertEquals(994, c[5].left)                                   // Gboard: Y cap from 999 (cell 989)
        assertEquals(1540, c[9].right)
        // Touches in the gap go to the nearer half; the outer keys reach the screen edges.
        assertEquals(0f, c[0].hitLeft); assertEquals(800f, c[4].hitRight)
        assertEquals(800f, c[5].hitLeft); assertEquals(1600f, c[9].hitRight)
    }

    @Test fun middleRowRepeatsG() {
        val gaps = none(9).also { it[0] = 0.5f }
        val c = cells(ones(9), gaps)
        assertEquals(listOf(0, 1, 2, 3, 4, 4, 5, 6, 7, 8), c.map { it.index })
        // A: cell 60-196 holding a third of a slot before the cap -> cap 94-196 (Gboard: A cap 105-186 + 10 px insets).
        assertEquals(1f / 3f, c[0].before, 1e-4f)
        val share = c[0].right - c[0].left
        val capLeft = c[0].right - (share * c[0].width / c[0].weight).toInt()
        assertEquals(95f, capLeft.toFloat(), 2f)
        assertEquals(606, c[4].right)
        assertEquals(1f / 3f, c[9].after, 1e-4f)
        assertEquals(1540, c[9].right)
        assertEquals(102.4f, (c[6].right - c[6].left).toFloat(), 1f)  // Gboard: 102.5-103 px pitch
    }

    @Test fun zRowRepeatsV() {
        val w = ones(9).also { it[0] = 1.5f; it[8] = 1.5f }
        val c = cells(w)
        assertEquals(listOf(0, 1, 2, 3, 4, 4, 5, 6, 7, 8), c.map { it.index })
        assertEquals(136f, (c[0].right - c[0].left).toFloat(), 1f)    // Gboard shift: cap 70-186 -> cell 60-196
        assertEquals(137f, (c[9].right - c[9].left).toFloat(), 1f)    // delete: cap 1412-1529 -> cell 1402-1539
    }

    @Test fun bottomRowSpaceSpansTheGap() {
        val w = floatArrayOf(1.5f, 1f, 1f, 4f, 1f, 1.5f)
        val c = cells(w, space = 3)
        assertEquals((0..5).toList(), c.map { it.index })
        assertEquals(60, c[0].left)
        assertEquals(401f, c[3].left.toFloat(), 2f)                    // Gboard: space cap 412-1288 -> cell 402-1298
        assertEquals(1299f, c[3].right.toFloat(), 3f)
        assertEquals(1540, c[5].right)
        assertEquals(c[2].right, c[3].left); assertEquals(c[3].right, c[4].left)
        assertEquals(0f, c[0].hitLeft); assertEquals(1600f, c[5].hitRight)
    }

    @Test fun unsplitRowUnchanged() {
        // Phone: 1080 px, 6 px side padding, 10 columns (KeyboardGeometryTest.columnsSplitLikeLinearLayout).
        val c = BuiltKeyboard.rowCells(ones(10), none(10), 10f, 6, 1080)
        assertEquals(listOf(106, 106, 107, 107, 107, 107, 107, 107, 107, 107), c.map { it.right - it.left })
        assertEquals(6, c[0].left); assertEquals(0f, c[0].hitLeft); assertEquals(1080f, c[9].hitRight)
        assertEquals(c[0].right.toFloat(), c[1].hitLeft)
    }
}
