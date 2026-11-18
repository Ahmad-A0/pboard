package dev.pboard.ime.translate

import android.content.Context
import android.os.Build
import androidx.annotation.ColorInt
import dev.pboard.ime.config.Config
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.prefs.ThemeCatalog

/**
 * Colours of the translate bar and language picker. Measured on Gboard 18.6 (Material You, docs/translate.md §UI):
 * chips = primaryContainer / onPrimaryContainer, input outline and caret = primary, input fill =
 * surfaceContainerLowest, hint = outline, swap icon = onSurfaceVariant, active toolbar pill = secondary / onSecondary,
 * picker divider = secondaryContainer. Fixed (non-dynamic) themes derive the same roles from their key colours.
 */
data class TranslateColors(
    @ColorInt val background: Int,
    @ColorInt val backButton: Int,
    @ColorInt val backButtonPressed: Int,
    @ColorInt val icon: Int,
    @ColorInt val chip: Int,
    @ColorInt val chipPressed: Int,
    @ColorInt val chipText: Int,
    @ColorInt val swapIcon: Int,
    @ColorInt val boxFill: Int,
    @ColorInt val boxOutline: Int,
    @ColorInt val text: Int,
    @ColorInt val hint: Int,
    @ColorInt val statusFill: Int,
    @ColorInt val activePill: Int,
    @ColorInt val activeIcon: Int,
    @ColorInt val dialog: Int,
    @ColorInt val divider: Int,
    @ColorInt val pressed: Int,
    val isDark: Boolean,
) {
    companion object {
        fun resolve(context: Context, k: KeyboardColors = KeyboardTheme.colors(context)): TranslateColors {
            val dynamic = Config.current.themeId == ThemeCatalog.DYNAMIC && Build.VERSION.SDK_INT >= 34
            if (dynamic) {
                val dark = k.isDark
                fun col(light: Int, darkId: Int) = context.getColor(if (dark) darkId else light)
                val primaryContainer = col(android.R.color.system_primary_container_light, android.R.color.system_primary_container_dark)
                val onPrimaryContainer = col(android.R.color.system_on_primary_container_light, android.R.color.system_on_primary_container_dark)
                val outline = col(android.R.color.system_outline_light, android.R.color.system_outline_dark)
                return TranslateColors(
                    background = k.background, backButton = k.barButton, backButtonPressed = k.barButtonPressed,
                    icon = k.barIcon, chip = primaryContainer,
                    chipPressed = KeyboardTheme.blend(primaryContainer, onPrimaryContainer, 0.10f), chipText = onPrimaryContainer,
                    swapIcon = k.hintLabel, boxFill = k.surfaceLowest ?: k.key, boxOutline = k.primary, text = k.keyLabel,
                    hint = outline,
                    statusFill = col(android.R.color.system_surface_dim_light, android.R.color.system_surface_dim_dark),
                    activePill = col(android.R.color.system_secondary_light, android.R.color.system_secondary_dark),
                    activeIcon = col(android.R.color.system_on_secondary_light, android.R.color.system_on_secondary_dark),
                    dialog = k.background, divider = k.functionalKey, pressed = k.keyPressed, isDark = dark,
                )
            }
            return TranslateColors(
                background = k.background, backButton = k.barButton, backButtonPressed = k.barButtonPressed, icon = k.barIcon,
                chip = k.accentKey, chipPressed = k.accentKeyPressed, chipText = k.accentIcon, swapIcon = k.hintLabel,
                boxFill = k.surfaceLowest ?: k.key, boxOutline = k.primary, text = k.keyLabel, hint = k.hintLabel,
                statusFill = k.functionalKey, activePill = k.accentKey, activeIcon = k.accentIcon,
                dialog = k.background, divider = k.functionalKey, pressed = k.keyPressed, isDark = k.isDark,
            )
        }
    }
}

/** Material Symbols path data (Apache-2.0, Rounded, weight 400, 24 px) used only by the translate UI. */
internal object TranslateSymbols {
    /** swap_horiz */
    const val SWAP = "m233-320 75 75q11 11 11 27.5T308-189q-12 12-28.5 12T251-189L108-332q-6-6-8.5-13T97-360q0-8 2.5-15t8.5-13l144-144q12-12 28-11.5t28 12.5q11 12 11.5 28T308-475l-75 75h247q17 0 28.5 11.5T520-360q0 17-11.5 28.5T480-320H233Zm494-240H480q-17 0-28.5-11.5T440-600q0-17 11.5-28.5T480-640h247l-75-75q-11-11-11-27.5t11-28.5q12-12 28.5-12t28.5 12l143 143q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L708-428q-12 12-28 11.5T652-429q-11-12-11.5-28t11.5-28l75-75Z"

    /** download */
    const val DOWNLOAD = "M480-337q-8 0-15-2.5t-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T480-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z"
}
