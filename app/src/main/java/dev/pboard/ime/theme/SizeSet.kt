package dev.pboard.ime.theme

import android.content.Context
import android.content.res.Configuration

/**
 * Gboard's keyboard size sets: the keyboard's dimensions come from one of a few sets chosen
 * by the screen's smallest width, not by the app version or the exact device. Every length is in dp.
 *
 * Keyboard body = [body] (+ a 4 dp bottom gap on screens at least 5.2" tall) times the user's height ratio. Inside it:
 * [topPad], then the rows: three letter rows of 0.254 and a bottom row of 0.238 of the space left (the bottom gap goes
 * to the bottom row). A key fills its row cell minus [keyBottomInset] below it and [keySideInset] on each side; the
 * 10-column grid spans the width minus [sidePad] on each side.
 */
data class SizeSet(
    val name: String,
    /** Header (toolbar / suggestion strip) height. */
    val header: Float,
    /** Base body height "A" without and with the number row. */
    val body: Float,
    val bodyNumberRow: Float,
    val topPad: Float,
    val sidePad: Float,
    val letterLabel: Float,
    val numberHint: Float,
    val functionLabel: Float,
    val keySideInset: Float,
    val keyBottomInset: Float,
    /** With the number row on: the letter rows' and the number row's bottom insets (the bottom row keeps [keyBottomInset]). */
    val keyBottomInsetNumberRow: Float,
    val numberRowBottomInset: Float,
    /** Number / phone pads: pill keys on equal rows. */
    val padSideInset: Float,
    val padBottomInset: Float,
    val keyRadius: Float,
    val previewDiameter: Float,
    val previewLabel: Float,
    /** Long-press panel: item cell (also the selection circle's diameter), padding, corner radius, item label. */
    val popupCell: Float,
    val popupPadding: Float,
    val popupRadius: Float,
    val popupLabel: Float,
    /**
     * Suggestion strip: its left edge and right inset; the middle slot takes [Dimens.stripMiddleFraction] of it and the
     * side slots split the rest. Gboard 18.6 dividers (centres) at x 387 / 687 px on the 350-399 set (1080 px, 440 dpi)
     * and 385 / 691 px on the >=400 set (420 dpi); other sets keep the 15.1 values (110.5 / 114.5 px at 420 dpi).
     */
    val stripLeft: Float = 42.1f,
    val stripRight: Float = 43.6f,
) {
    companion object {
        /** Added below the bottom row when the physical screen is at least [BOTTOM_GAP_MIN_INCHES] tall. */
        const val BOTTOM_GAP = 4f
        const val BOTTOM_GAP_MIN_INCHES = 5.2f
        const val BOTTOM_GAP_MIN_DIAGONAL = 5.4f

        // Row shares of the body below the top padding (bottom gap excluded). With the number row on, the upper four
        // rows share 0.794 in the same 0.238 : 0.254 proportions (the number row taking the smaller share) and the
        // bottom row gets 0.206 (fits every row edge measured on the 350-399 phone).
        const val LETTER_ROW = 0.254f
        const val BOTTOM_ROW = 0.238f
        const val NR_NUMBER_ROW = 0.238f * 0.794f
        const val NR_LETTER_ROW = 0.254f * 0.794f
        const val NR_BOTTOM_ROW = 0.206f

        /** smallestScreenWidth < 350 dp: no side padding, smaller header and labels. */
        val SMALL = SizeSet(
            name = "<350", header = 38f, body = 197f, bodyNumberRow = 221f, topPad = 8f, sidePad = 0f,
            letterLabel = 21f, numberHint = 9f, functionLabel = 15f,
            keySideInset = 2.5f, keyBottomInset = 10f, keyBottomInsetNumberRow = 8f, numberRowBottomInset = 7f,
            padSideInset = 2.5f, padBottomInset = 6f, keyRadius = 6f,
            previewDiameter = 54f, previewLabel = 27f,
            popupCell = 40f, popupPadding = 10f, popupRadius = 30f, popupLabel = 21f,
        )

        /** 350-399 dp (most current phones at default display size, e.g. 392.7 dp). */
        val PHONE = SMALL.copy(name = "350-399", header = 44f, body = 207f, bodyNumberRow = 236f, sidePad = 2f,
            letterLabel = 23f, numberHint = 10f, stripLeft = 43.64f, stripRight = 45.45f)

        /** 400 dp and wider phones (e.g. the 411 dp Pixel emulators). */
        val LARGE = PHONE.copy(name = ">=400", body = 240f, bodyNumberRow = 274f, letterLabel = 27f, numberHint = 11f,
            functionLabel = 18f, keySideInset = 3f, keyBottomInset = 12f, numberRowBottomInset = 8f, keyRadius = 7f,
            previewDiameter = 58f, previewLabel = 30f,
            popupCell = 44f, popupPadding = 13f, popupRadius = 35f, popupLabel = 23f, stripLeft = 42.67f, stripRight = 43.81f)

        /** Tablets (smallest width >= 600 dp): a shorter body with wider key gaps. */
        val TABLET = PHONE.copy(name = "tablet", body = 228f, bodyNumberRow = 262f, letterLabel = 24f, numberHint = 13f,
            functionLabel = 18f, keySideInset = 5f, keyBottomInset = 12f, keyBottomInsetNumberRow = 12f,
            numberRowBottomInset = 10f, padSideInset = 5f, padBottomInset = 10f, keyRadius = 7f,
            previewDiameter = 70f, previewLabel = 34f,
            popupCell = 44f, popupPadding = 13f, popupRadius = 35f, popupLabel = 23f, stripLeft = 42.1f, stripRight = 43.6f)

        // Landscape variants: 2 dp top padding, smaller bottom insets and bodies.
        val SMALL_LAND = SMALL.copy(name = "<350 land", body = 158f, bodyNumberRow = 181f, topPad = 2f,
            letterLabel = 18f, keyBottomInset = 6f, keyBottomInsetNumberRow = 6f, numberRowBottomInset = 3.5f,
            padBottomInset = 3.5f)
        val PHONE_LAND = SMALL_LAND.copy(name = "350-399 land", body = 162f, bodyNumberRow = 194f, letterLabel = 20f,
            numberHint = 10f)
        val LARGE_LAND = LARGE.copy(name = ">=400 land", header = 41f, body = 172f, bodyNumberRow = 206f, topPad = 2f,
            letterLabel = 24f, keyBottomInset = 4f, keyBottomInsetNumberRow = 4f, numberRowBottomInset = 2.5f,
            padBottomInset = 3.5f, stripLeft = 42.1f, stripRight = 43.6f)

        /** Gboard's choice: by smallest width (tablets from 600 dp), then orientation. */
        fun select(smallestWidthDp: Int, landscape: Boolean): SizeSet = when {
            smallestWidthDp >= 600 -> TABLET
            smallestWidthDp >= 400 -> if (landscape) LARGE_LAND else LARGE
            smallestWidthDp >= 350 -> if (landscape) PHONE_LAND else PHONE
            else -> if (landscape) SMALL_LAND else SMALL
        }

        fun of(context: Context): SizeSet {
            val c = context.resources.configuration
            return select(c.smallestScreenWidthDp, c.orientation == Configuration.ORIENTATION_LANDSCAPE)
        }

        /**
         * The 4 dp bottom gap applies when the screen, in its current orientation, is at least 5.2" tall (real pixels /
         * ydpi) and its diagonal at least 5.4" - so not in landscape on phones (Gboard 15.1 landscape measured: the
         * bottom row is 0.238 of the body with no gap). Tablets (the tablet set, smallest width >= 600 dp) have none
         * either (Gboard 18.6 on a 10" tablet, split layout, portrait: rows 2024-2447 of 2560 px, 8 px lower than with
         * the gap).
         */
        @Suppress("DEPRECATION")
        fun bottomGap(context: Context): Float {
            if (context.resources.configuration.smallestScreenWidthDp >= 600) return 0f
            val wm = context.getSystemService(android.view.WindowManager::class.java)
            val dm = android.util.DisplayMetrics()
            val display = wm?.defaultDisplay
            if (display != null) display.getRealMetrics(dm) else dm.setTo(context.resources.displayMetrics)
            if (dm.xdpi <= 0f || dm.ydpi <= 0f) return 0f
            val w = dm.widthPixels / dm.xdpi
            val h = dm.heightPixels / dm.ydpi
            val tall = h >= BOTTOM_GAP_MIN_INCHES && kotlin.math.hypot(w, h) >= BOTTOM_GAP_MIN_DIAGONAL
            return if (tall) BOTTOM_GAP else 0f
        }
    }
}
