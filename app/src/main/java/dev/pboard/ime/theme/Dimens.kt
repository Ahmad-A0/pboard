package dev.pboard.ime.theme

import android.content.Context
import kotlin.math.floor

/**
 * Keyboard metrics. The keyboard's own geometry and type sizes come from Gboard's size set for this screen ([SizeSet]);
 * other values were measured from Gboard on a 420dpi (2.625 px/dp) Pixel-class screen and are
 * kept in those measured pixels ([m]) so they can be checked directly against screenshots; they scale with density.
 */
class Dimens(context: Context, heightScale: Float = 1f, scale: Float = 1f, fontScale: Float = 1f) {
    /** Gboard 18.6 "Font size" (85..200 %): scales key labels, hints and previews, not the key geometry. */
    private val fs = fontScale
    /** [scale] shrinks everything uniformly (one-handed mode draws the whole keyboard at its width ratio). */
    val density = context.resources.displayMetrics.density * scale

    val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val hs = heightScale

    /** Gboard's dimension set for this screen (smallest width, orientation). */
    val set: SizeSet = SizeSet.of(context)
    private val gapDp: Float = SizeSet.bottomGap(context)

    /** Converts a length measured on the 420dpi reference device to this device's pixels. */
    fun m(px: Float) = px * density / REF_DENSITY

    fun dp(v: Float): Float = v * density
    /** Gboard reads sizes as rounded pixels and offsets (paddings, insets) as truncated pixels. */
    private fun size(v: Float): Float = Math.round(v * density).toFloat()
    private fun offset(v: Float): Float = floor(v * density)

    // Vertical structure: [header][body: top pad, rows]. Each row cell holds a key and the inset below it; the
    // bottom gap belongs to the bottom row.
    val barHeight = size(set.header)
    /** Vertical centre of the header's content (Gboard centres on the pixel row above the middle). */
    val barCenterY get() = (barHeight - 1f) / 2f
    val keysTopPad = offset(set.topPad)
    /** The 4 dp bottom gap (screens at least 5.2" tall), scaled with the body. */
    val bottomGap: Float = gapDp * hs * density

    /** Body height: (A + bottom gap) times the user's height ratio; A is taller with the number row. */
    fun bodyHeight(numberRow: Boolean = false): Float =
        Math.round(((if (numberRow) set.bodyNumberRow else set.body) + gapDp) * hs * density).toFloat()

    /** Height of the letters keyboard below the bar. */
    val lettersKeysHeight get() = bodyHeight(false)

    val keyBottomInset = offset(set.keyBottomInset)
    val keyBottomInsetNumberRow = offset(set.keyBottomInsetNumberRow)
    val numberRowBottomInset = offset(set.numberRowBottomInset)
    val padBottomInset = offset(set.padBottomInset)
    /** Letter-row pitch and key height without the number row. */
    val rowPitch = SizeSet.LETTER_ROW * (bodyHeight(false) - keysTopPad - bottomGap)
    val keyHeight = rowPitch - keyBottomInset

    // Horizontal: a 10-column grid between the side paddings; keys inset in their cells.
    val sidePad = size(set.sidePad)
    val hInset = offset(set.keySideInset)
    val padHInset = offset(set.padSideInset)

    // Shapes
    var keyRadius = dp(set.keyRadius)

    /** Top edge shadow drawn above the keyboard. */
    val topShadow = m(8f)

    // Text (Gboard sizes are in dp and ignore the system font scale). Values tuned against screenshots of the >= 400
    // set scale with the set's label sizes (they are `var` so the debug receiver can adjust them live).
    private val letterRatio = set.letterLabel / SizeSet.LARGE.letterLabel
    /** Letter size of the reference set (>= 400 dp; 22 dp measured in landscape). */
    private val refLetter = if (landscape) m(57.75f) else dp(SizeSet.LARGE.letterLabel)
    var letterSize = (if (landscape && set == SizeSet.LARGE_LAND) m(57.75f) else dp(set.letterLabel)) * fs
    var letterDy = m(1f)
    /** Comma / period beside the space bar. */
    var punctuationSize = letterSize * (if (landscape) 52f / 57.75f else 63f / 70.875f)
    var punctuationDy = 0f
    var functionTextSize = dp(set.functionLabel) * fs   // ?123, ABC, =\<
    var functionTextWeight = 400
    var functionTextSpacing = 0.02f    // em
    var functionTextDy = m(-1.5f)

    /**
     * In smaller size sets Gboard keeps lower-case letters, punctuation and function labels at the reference set's
     * baseline offset below the key centre, so they sit lower than if centred at their own size (measured on the 18.6
     * phone: lower-case letters, ?123 and the period 3 px lower; capitals and digits stay centred). Those labels are
     * centred on their x-height / cap height scaled by these ratios to the reference set's sizes.
     */
    val letterBaselineScale get() = refLetter * fs / letterSize
    val functionBaselineScale get() = dp(SizeSet.LARGE.functionLabel) * fs / functionTextSize
    /** Number hints: the set's size, drawn 31/28.9 larger to match the measured glyph height. */
    var hintSize = dp(set.numberHint) * (31f / 28.875f) * fs
    var hintBold = false
    var hintRightInset = m(6f)
    var hintTopInset = m(10f)
    val numpadSize = m(78f) * letterRatio * fs
    val numpadSubSize = m(35f) * letterRatio * fs
    /** Number row (Gboard 18.6: letter-style keys with letter-size digits). */
    val numberRowSize get() = letterSize
    val stackedSize = m(36f) * letterRatio * fs

    // Icons: box sizes (Material Symbols' 960-unit viewport mapped onto this many pixels) and offsets from the key
    // centre, fitted to Gboard 18.6 for the variants chosen in Symbols.kt (tools/icons/score.py).
    val iconShiftWidth = m(58.8f)
    val iconShiftDx = m(-0.5f)
    val iconDeleteBox = m(85f)
    val iconDeleteDx = m(-2.5f)
    val iconEnterBox = m(79.9f)
    val iconEnterDx = m(1f)
    val iconEmojiBox = m(61.6f)
    val iconEmojiDy = m(-0.5f)
    val iconSpaceWidth = m(41f)

    // Bar: the suggestion strip's edges come from the size set; the middle slot is 35.9 % of it (Gboard 18.6, both sets).
    val barIconBox = m(63f)
    val barButtonDiameter = m(89f)
    val barSideInset = m(16f)
    val dividerHeight = m(63f)
    val dividerWidth = m(2.625f)
    val suggestionTextSize = m(47.25f) // 18 dp
    val stripLeft = dp(set.stripLeft)
    val stripRightInset = dp(set.stripRight)
    val stripMiddleFraction = STRIP_MIDDLE_FRACTION

    // Key preview bubble (circle) and long-press panel
    val previewDiameter = dp(set.previewDiameter)
    val previewGap = m(15.75f)         // bubble bottom 6 dp above the key top
    val previewTextSize = dp(set.previewLabel) * fs
    /** Long-press item cell; the selected item's circle has the same diameter. */
    val popupCell = size(set.popupCell)
    val popupRadius = dp(set.popupRadius)
    val popupSelectedDiameter = dp(set.popupCell)
    val popupTextSize = dp(set.popupLabel) * fs
    val popupPadding = size(set.popupPadding)
    val popupGap = m(15.75f)           // panel bottom 6 dp above the key top
    val popupElevation = m(10.5f)      // 4 dp

    // Gesture trail (Gboard 18.6): primary at 80 % opacity, about 11 dp wide at the head, narrowing and fading over
    // 1 s (15.1: 13 dp, nearly opaque).
    val trailWidth = dp(11f)

    // Gesture thresholds (Gboard: 8 pt start/step for cursor; delete swipe 23 pt, 41 pt, then +16 pt per word)
    val pt = density * 160f / 72f
    val scrubStart = 8f * pt
    val scrubStep = 8f * pt
    val scrubBand = 4f / 25.4f * density * 160f
    val deleteSwipeFirst = 23f * pt
    val deleteSwipeSecond = 41f * pt
    val deleteSwipeStep = 16f * pt
    /**
     * Travel after which a touch on a letter becomes a glide. Gboard 18.6 (emulator farm, 150 ms slides from `t` at 440 and
     * 420 dpi): 20.9 dp still types `t`, 22.1 dp is a glide (`ty`); 15.1 needed 60-80 px at 420 dpi. 23 dp here:
     * the platform's touch resampling extrapolates a moving finger ~1 dp past the last sample (at 22 dp a 20.9 dp
     * slide glided).
     */
    val glideThreshold = dp(23f)
    /** Cancel distance for long-press before it fires (0.3 inch). */
    val longPressSlop = 0.3f * density * 160f

    companion object {
        const val REF_DENSITY = 2.625f
        const val STRIP_MIDDLE_FRACTION = 0.359f
    }
}
