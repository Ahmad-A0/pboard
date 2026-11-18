package dev.pboard.ime.layout

import dev.pboard.ime.theme.SizeSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Pixel splits measured on Gboard: 392.7 dp phone at 2.75 px/dp, 411 dp emulator at 2.625. */
class KeyboardGeometryTest {
    private fun ws(vararg w: Float) = w

    @Test fun columnsSplitLikeLinearLayout() {
        // Phone: 1080 - 2 x 6 px side padding = 1068 px for 10 columns of 106.8 px.
        assertArrayEquals(intArrayOf(106, 106, 107, 107, 107, 107, 107, 107, 107, 107), BuiltKeyboard.split(1068, FloatArray(10) { 1f }))
        // Bottom row: ?123, comma, emoji, space, period, enter.
        assertArrayEquals(intArrayOf(160, 106, 106, 428, 107, 161), BuiltKeyboard.split(1068, ws(1.5f, 1f, 1f, 4f, 1f, 1.5f)))
        // Row 2: the half-column indents belong to a and l.
        assertArrayEquals(intArrayOf(160, 106, 106, 107, 107, 107, 107, 107, 161),
            BuiltKeyboard.split(1068, ws(1.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.5f)))
        // Emulator: 1070 px, exactly 107 per column.
        assertArrayEquals(IntArray(10) { 107 }, BuiltKeyboard.split(1070, FloatArray(10) { 1f }))
    }

    @Test fun rowsSplitLikeLinearLayout() {
        val letters = ws(SizeSet.LETTER_ROW, SizeSet.LETTER_ROW, SizeSet.LETTER_ROW, SizeSet.BOTTOM_ROW)
        // Phone: body 580 px - 22 top padding - 11 bottom gap.
        assertArrayEquals(intArrayOf(138, 139, 139, 131), BuiltKeyboard.split(547, letters))
        // Emulator: body 641 - 21 - 10.
        assertArrayEquals(intArrayOf(154, 155, 155, 146), BuiltKeyboard.split(610, letters))
        // Phone with the number row: body 660 - 22 - 11.
        val nr = ws(SizeSet.NR_NUMBER_ROW, SizeSet.NR_LETTER_ROW, SizeSet.NR_LETTER_ROW, SizeSet.NR_LETTER_ROW, SizeSet.NR_BOTTOM_ROW)
        assertArrayEquals(intArrayOf(118, 126, 126, 127, 130), BuiltKeyboard.split(627, nr))
        // Phone number pad: four equal rows of 580 - 22.
        assertArrayEquals(intArrayOf(139, 139, 140, 140), BuiltKeyboard.split(558, FloatArray(4) { 1f }))
    }

    @Test fun sizeSetBySmallestWidth() {
        assertSame(SizeSet.SMALL, SizeSet.select(320, false))
        assertSame(SizeSet.PHONE, SizeSet.select(392, false))
        assertSame(SizeSet.LARGE, SizeSet.select(411, false))
        assertSame(SizeSet.LARGE_LAND, SizeSet.select(411, true))
        assertSame(SizeSet.PHONE_LAND, SizeSet.select(392, true))
        assertSame(SizeSet.TABLET, SizeSet.select(800, false))
    }
}
