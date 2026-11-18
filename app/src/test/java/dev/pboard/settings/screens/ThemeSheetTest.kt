package dev.pboard.settings.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme preview sheet in landscape: the full-width preview alone was taller than the screen and the sheet did not
 * scroll, so Key borders and Apply were out of reach.
 */
class ThemeSheetTest {
    // Sheet content below the preview (dp): spacer 16, Key borders row 64, spacer 16, buttons 40; paddings 32 + 16.
    private val controls = 16 + 64 + 16 + 40 + 32 + 16
    private val sheetMaxWidth = 640 // Material 3 bottom sheet
    private val sidePadding = 48

    private fun previewHeight(screenWidthDp: Int, screenHeightDp: Int): Float {
        val available = minOf(screenWidthDp, sheetMaxWidth) - sidePadding
        return minOf(available.toFloat(), sheetPreviewMaxWidthDp(screenHeightDp)) / PREVIEW_ASPECT
    }

    @Test
    fun landscapeSheetFitsOnScreen() {
        // Pixel 8 profile (the pool emulators, 1080x2400 @ 420 dpi) and a 1080x2424 @ 420 dpi phone in landscape,
        // less the status bar (24 dp) and the gesture navigation bar (24 dp).
        for ((w, h) in listOf(914 to 411, 923 to 411)) {
            val total = previewHeight(w, h) + controls
            assertTrue("landscape ${w}x$h: content $total dp", total <= h - 48)
        }
    }

    @Test
    fun portraitKeepsTheFullWidthPreview() {
        for ((w, h) in listOf(411 to 914, 411 to 923, 360 to 640)) {
            assertEquals((w - sidePadding) / PREVIEW_ASPECT, previewHeight(w, h), 0.01f)
        }
    }
}
