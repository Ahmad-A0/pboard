package dev.pboard.ime.view

import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.SizeSet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Suggestion-strip dividers against Gboard 18.6 (1080 px wide, "Hel | Hello | Help"): 3 px wide, left pixels at
 * x 386 / 686 at 440 dpi (350-399 dp set) and 384 / 690 at 420 dpi (>=400 dp set).
 */
class StripDividerTest {
    private fun dividers(set: SizeSet, density: Float): Pair<Float, Float> {
        val left = set.stripLeft * density
        val right = 1080f - set.stripRight * density
        val side = TopBarView.sideSlotWidth(left, right, Dimens.STRIP_MIDDLE_FRACTION)
        val w = Math.round(density).toFloat() // 1 dp
        return TopBarView.dividerLeft(left + side, w) to TopBarView.dividerLeft(right - side, w)
    }

    @Test
    fun phoneSetAt440Dpi() = assertEquals(386f to 686f, dividers(SizeSet.PHONE, 2.75f))

    @Test
    fun largeSetAt420Dpi() = assertEquals(384f to 690f, dividers(SizeSet.LARGE, 2.625f))
}
