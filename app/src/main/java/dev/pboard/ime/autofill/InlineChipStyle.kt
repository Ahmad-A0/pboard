package dev.pboard.ime.autofill

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.os.Build
import android.os.LocaleList
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.ImageView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.pboard.R
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import kotlin.math.roundToInt

/**
 * The inline-suggestion request pboard sends the autofill framework (CX-14), with Gboard 18.6's chip look, measured on
 * the emulator with a test autofill service:
 *
 * * up to 9 suggestions, each presented in a view exactly as tall as the strip (44 dp) and between one toolbar button
 *   (34 dp) and 240 dp wide;
 * * the visible chip is a pill as tall as a toolbar button, centred vertically (inset (header - button) / 2, clamped
 *   to 2..8 dp) with a 4 dp transparent margin on each side, in the letter-key colour;
 * * 12 dp of padding inside the pill, a 20 dp start/end icon with 4 dp margins tinted like the title, the title in
 *   the suggestion text colour and the subtitle in the secondary (hint) colour, both 14 sp;
 * * a pinned "single icon" chip (the service's own button) has no background and a 24 dp icon in a fixed grey
 *   (white in dark mode), with a 6 dp end margin.
 *
 * The autofill service renders the chips with this style in its own process; pboard only lays out the views.
 */
@RequiresApi(Build.VERSION_CODES.R)
object InlineChipStyle {
    const val MAX_SUGGESTIONS = 9
    private const val MAX_WIDTH_DP = 240f
    private const val SIDE_INSET_DP = 4f
    private const val CHIP_PADDING_DP = 12f
    private const val ICON_DP = 20f
    private const val ICON_MARGIN_DP = 4f
    private const val TEXT_SP = 14f
    private const val PINNED_ICON_DP = 24f
    private const val PINNED_ICON_END_DP = 6f
    private const val PINNED_ICON_LIGHT = 0xFF3C4043.toInt()
    private const val PINNED_ICON_DARK = 0xFFFFFFFF.toInt()

    private val BACKGROUNDS = intArrayOf(
        R.drawable.inline_chip_2, R.drawable.inline_chip_3, R.drawable.inline_chip_4, R.drawable.inline_chip_5,
        R.drawable.inline_chip_6, R.drawable.inline_chip_7, R.drawable.inline_chip_8,
    )

    /** Vertical inset of the pill in the strip-tall view, in whole dp (Gboard: 2..8). */
    fun verticalInsetDp(d: Dimens): Int =
        (((d.barHeight - d.barButtonDiameter) / 2f) / d.density).roundToInt().coerceIn(2, 8)

    fun request(context: Context, d: Dimens, c: KeyboardColors, rtl: Boolean, locales: LocaleList): InlineSuggestionsRequest {
        val dm = context.resources.displayMetrics
        fun px(dp: Float) = (dp * dm.density).roundToInt()
        val height = d.barHeight.roundToInt()
        val min = Size(d.barButtonDiameter.roundToInt(), height)
        val max = Size(px(MAX_WIDTH_DP), height)

        val iconMargin = px(ICON_MARGIN_DP)
        // Content padding measured from the pill's edge: the transparent side inset, 12 dp, minus the icon's margin.
        val chipPad = (px(CHIP_PADDING_DP) + px(SIDE_INSET_DP) - iconMargin).coerceAtLeast(0)
        val textSize = TEXT_SP
        val title = c.suggestionText
        val bg = Icon.createWithResource(context, BACKGROUNDS[verticalInsetDp(d) - 2]).setTintList(ColorStateList.valueOf(c.key))
        val icon = ImageViewStyle.Builder()
            .setMaxWidth(px(ICON_DP)).setMaxHeight(px(ICON_DP))
            .setTintList(ColorStateList.valueOf(title))
            .setLayoutMargin(iconMargin, 0, iconMargin, 0)
            .build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setLayoutDirection(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
            .setChipStyle(ViewStyle.Builder().setBackground(bg).setPadding(chipPad, 0, chipPad, 0).build())
            .setStartIconStyle(icon)
            .setEndIconStyle(icon)
            .setTitleStyle(
                TextViewStyle.Builder().setTextColor(title).setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                    .setPadding(0, 0, 0, 0).build(),
            )
            .setSubtitleStyle(
                TextViewStyle.Builder().setTextColor(c.hintLabel).setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                    .setPadding(iconMargin, 0, 0, 0).build(),
            )
            .setSingleIconChipStyle(ViewStyle.Builder().setBackgroundColor(0).setPadding(0, 0, 0, 0).build())
            .setSingleIconChipIconStyle(
                ImageViewStyle.Builder().setMaxWidth(px(PINNED_ICON_DP)).setMaxHeight(px(PINNED_ICON_DP))
                    .setScaleType(ImageView.ScaleType.FIT_CENTER)
                    .setTintList(ColorStateList.valueOf(if (c.isDark) PINNED_ICON_DARK else PINNED_ICON_LIGHT))
                    .setLayoutMargin(0, 0, px(PINNED_ICON_END_DP), 0)
                    .build(),
            )
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = InlinePresentationSpec.Builder(min, max).setStyle(styles).build()
        return InlineSuggestionsRequest.Builder(List(MAX_SUGGESTIONS) { spec })
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .setSupportedLocales(locales)
            .build()
    }
}
