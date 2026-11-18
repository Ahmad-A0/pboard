package dev.pboard.prefs

import androidx.annotation.StringRes
import dev.pboard.R

/**
 * The allowed values of [Prefs.theme]: ids, display names and the base colours of every built-in theme.
 *
 * Colours are ARGB ints measured from Gboard's theme thumbnails:
 * [ThemeSpec.background] is the keyboard background, [ThemeSpec.keySample] the colour of the space bar in the
 * thumbnail (keys with borders), [ThemeSpec.accent] the action (enter) key. The keyboard renderer derives the rest
 * of its palette from these. Gradient themes are generated locally (Gboard downloads them; pboard cannot).
 */
object ThemeCatalog {
    const val DYNAMIC = "dynamic"
    const val SYSTEM_AUTO = "system_auto"
    const val DEFAULT_LIGHT = "default_light"
    const val DEFAULT_DARK = "default_dark"
    const val CUSTOM_PREFIX = "custom_"

    enum class Section(@StringRes val title: Int) {
        DEFAULT(R.string.theme_section_default),
        COLORS(R.string.theme_section_colors),
        LIGHT_GRADIENT(R.string.theme_section_light_gradient),
        DARK_GRADIENT(R.string.theme_section_dark_gradient),
    }

    enum class Kind { DYNAMIC, SYSTEM_AUTO, SOLID, GRADIENT }

    data class ThemeSpec(
        val id: String,
        @StringRes val name: Int,
        val section: Section,
        val kind: Kind,
        val background: Int,
        val keySample: Int,
        val accent: Int,
        val isDark: Boolean,
        /** Second gradient stop (bottom) for [Kind.GRADIENT], else equal to [background]. */
        val backgroundEnd: Int = background,
        /** For gradient themes the ordinal shown in the name ("Light gradient 3"). */
        val number: Int = 0,
        /** Gboard 18.6's keyboard in this theme (null for Dynamic Color and System Auto, which follow the system). */
        val gb: Measured? = null,
    )

    /**
     * A theme's keyboard as Gboard 18.6 draws it, measured on the farm pool emulator (1080x2400 @ 420 dpi, theme
     * preview sheets, checked against the applied keyboard): letter-key and function-key fills (white with alpha over
     * the picture on gradient themes) and the colours of letter labels, function-key labels and icons, the enter
     * icon and the number hints. The enter key's fill is [ThemeSpec.accent], the background [ThemeSpec.background].
     */
    data class Measured(
        val key: Int,
        val functionalKey: Int,
        val keyLabel: Int,
        val functionalLabel: Int,
        val enterLabel: Int,
        val hintLabel: Int,
    )

    /** RRGGBB (opaque) or AARRGGBB (with alpha, e.g. 0x3CFFFFFF). */
    private fun argb(v: Long) = (if (v > 0xFFFFFF) v else 0xFF000000L or v).toInt()

    private fun gb(key: Long, fn: Long, label: Long, fnLabel: Long, enterLabel: Long, hint: Long) =
        Measured(argb(key), argb(fn), argb(label), argb(fnLabel), argb(enterLabel), argb(hint))

    private fun c(rgb: Long) = (0xFF000000L or rgb).toInt()

    private fun solid(id: String, name: Int, bg: Long, key: Long, accent: Long, dark: Boolean, gb: Measured) =
        ThemeSpec("color_$id", name, Section.COLORS, Kind.SOLID, c(bg), c(key), c(accent), dark, gb = gb)

    // Background, thumbnail space bar ([ThemeSpec.keySample], also Gboard's borderless space bar) and enter key, then
    // Gboard's keyboard: gb(key, function key, key label, function label, enter icon, hint).
    val defaults = listOf(
        // Dynamic colours are resolved at runtime from the wallpaper; these are the fallback (pre-Android 12) colours.
        ThemeSpec(DYNAMIC, R.string.theme_dynamic, Section.DEFAULT, Kind.DYNAMIC, c(0xEEEDF4), c(0xFFFFFF), c(0xDCE2F9), false),
        ThemeSpec(SYSTEM_AUTO, R.string.theme_system_auto, Section.DEFAULT, Kind.SYSTEM_AUTO, c(0xF0F4F9), c(0xFFFFFF), c(0xA8C7FA), false),
        ThemeSpec(DEFAULT_LIGHT, R.string.theme_default_light, Section.DEFAULT, Kind.SOLID, c(0xF0F4F9), c(0xFFFFFF), c(0x0B57D0), false,
            gb = gb(0xFFFFFF, 0xE1E3E1, 0x1F1F1F, 0x1F1F1F, 0xFFFFFF, 0x444746)),
        ThemeSpec(DEFAULT_DARK, R.string.theme_default_dark, Section.DEFAULT, Kind.SOLID, c(0x1E1F20), c(0x444746), c(0xA8C7FA), true,
            gb = gb(0x37393B, 0x444746, 0xE3E3E3, 0xE3E3E3, 0x062E6F, 0xC4C7C5)),
    )

    val colors = listOf(
        solid("material_light", R.string.theme_material_light, 0xECEFF1, 0xC9CED1, 0x5CB5AB, false,
            gb(0xFBFBFC, 0xDFE2E4, 0x061D28, 0x465760, 0xFFFFFF, 0x727D82)),
        solid("material_dark", R.string.theme_material_dark, 0x263238, 0x3B464C, 0x6EACA8, true,
            gb(0x404A50, 0x313C42, 0xD9DBDC, 0xC2C5C7, 0xFFFFFF, 0xB2B6B9)),
        solid("google_light", R.string.theme_google_light, 0xE8EAED, 0xE1E2E2, 0x1A73E8, false,
            gb(0xFFFFFF, 0xCCCED5, 0x000000, 0x3D3D3F, 0xFFFFFF, 0x666666)),
        solid("google_dark", R.string.theme_google_dark, 0x292E33, 0x565A5E, 0x5E97F6, true,
            gb(0x484C4F, 0x373C41, 0xFFFFFF, 0xC3C5C6, 0xFFFFFF, 0xB5B7B8)),
        solid("red", R.string.theme_red, 0xC62828, 0xDF8888, 0xF44336, true,
            gb(0xD04E4E, 0xCA3838, 0xFFFFFF, 0xEFC4C4, 0xFFFFFF, 0xF6DBDB)),
        solid("green", R.string.theme_green, 0x43A047, 0x97CA99, 0x2D7D32, true,
            gb(0x64B168, 0x51A755, 0xFFFFFF, 0xCBE5CC, 0xFFFFFF, 0xE0EFE1)),
        solid("teal", R.string.theme_teal, 0x00695C, 0x72ACA5, 0x4DB6AC, true,
            gb(0x2E8479, 0x147468, 0xFFFFFF, 0xB9D6D2, 0xFFFFFF, 0xD5E6E4)),
        solid("blue", R.string.theme_blue, 0x1565C0, 0x7EAADC, 0x2196F3, true,
            gb(0x3F80CB, 0x2771C4, 0xFFFFFF, 0xBFD5ED, 0xFFFFFF, 0xD8E6F5)),
        solid("cyan", R.string.theme_cyan, 0x80DEEA, 0xB9EDF3, 0x00ACC1, false,
            gb(0xACE9F1, 0x99E4EE, 0x333333, 0x51676A, 0xFFFFFF, 0x4B5859)),
        solid("deep_purple", R.string.theme_deep_purple, 0x4527A0, 0x9888CA, 0x9575CD, true,
            gb(0x664DB1, 0x5337A7, 0xFFFFFF, 0xCCC3E5, 0xFFFFFF, 0xE0DBEF)),
        solid("pink", R.string.theme_pink, 0xAD1457, 0xD27DA2, 0xF50057, true,
            gb(0xBB3E75, 0xB32664, 0xFFFFFF, 0xE8BED1, 0xFFFFFF, 0xF1D8E3)),
        solid("light_pink", R.string.theme_light_pink, 0xF8BBD0, 0xFBD9E5, 0xE36087, false,
            gb(0xFAD2E0, 0xF9C8D9, 0x333333, 0x6E5F64, 0xFFFFFF, 0x5B5356)),
        solid("sand", R.string.theme_sand, 0xDACDC5, 0xEBE3DF, 0xFF7276, false,
            gb(0xF0EBE7, 0xE7DFDA, 0x1E1E1E, 0x595756, 0xFFFFFF, 0x484746)),
        solid("brown", R.string.theme_brown, 0x4E342E, 0x9D8F8B, 0x8D6E63, true,
            gb(0x6D5853, 0x5B433E, 0xFFFFFF, 0xCEC7C5, 0xFFFFFF, 0xE2DDDC)),
        solid("blue_grey", R.string.theme_blue_grey, 0x37474F, 0x90999E, 0x78909C, true,
            gb(0x5B686E, 0x46555C, 0xFFFFFF, 0xC8CCCE, 0xFFFFFF, 0xDEE1E2)),
        solid("black", R.string.theme_black, 0x000000, 0x727272, 0x80CBC4, true,
            gb(0x2E2E2E, 0x141414, 0xFFFFFF, 0xB9B9B9, 0xFFFFFF, 0xD5D5D5)),
        solid("holo_blue", R.string.theme_holo_blue, 0x161616, 0x7E7E7E, 0x003D5C, true,
            gb(0x5C5C5C, 0x282828, 0xFFFFFF, 0xBFBFBF, 0xFFFFFF, 0xDEDEDE)),
        solid("holo_white", R.string.theme_holo_white, 0x161616, 0x7E7E7E, 0x282828, true,
            gb(0x5C5C5C, 0x282828, 0xFFFFFF, 0xBFBFBF, 0xFFFFFF, 0xDEDEDE)),
    )

    /** Number of colour themes shown before the section is expanded. */
    const val COLORS_COLLAPSED = 9

    private class Gradient(val top: Long, val bottom: Long, val enter: Long, val gb: Measured)

    // Gboard 18.6 (downloaded on first use there, generated here): stops at the keyboard's top and bottom edge.
    private val lightGradientSpecs = listOf(
        Gradient(0xFCB8B9, 0xC0D8FE, 0xEA6671, gb(0x3CFFFFFF, 0x3CFFFFFF, 0x1E1E1E, 0x2C2B2F, 0xFFFFFF, 0x484143)),
        Gradient(0xE3E0D7, 0xC7D6D9, 0xC65B2F, gb(0x4CFFFFFF, 0x48FFFFFF, 0x000000, 0x2D2E2D, 0xFFFFFF, 0x2F2F2D)),
        Gradient(0xDDC9C8, 0xB4C5CF, 0xBC729D, gb(0x4CFFFFFF, 0x4BFFFFFF, 0x000000, 0x2B2B2C, 0xFFFFFF, 0x2F2C2C)),
        Gradient(0xE5EDFA, 0xB1B1B1, 0x249DD6, gb(0x4AFFFFFF, 0x43FFFFFF, 0x000000, 0x2A2A2A, 0xFFFFFF, 0x2D2E30)),
        Gradient(0xF7C2D4, 0xF590B2, 0xE15383, gb(0x4BFFFFFF, 0x4CFFFFFF, 0x000000, 0x312529, 0xFFFFFF, 0x32292C)),
        Gradient(0xFBE3D7, 0xB3EAFF, 0x249DD6, gb(0x3DFFFFFF, 0x3BFFFFFF, 0x1E1E1E, 0x2C2F30, 0xFFFFFF, 0x484746)),
        Gradient(0xFFE7A7, 0xD9D7FE, 0x8C85D8, gb(0x3BFFFFFF, 0x38FFFFFF, 0x1E1E1E, 0x2F2D2E, 0xFFFFFF, 0x4A4741)),
        Gradient(0xB2DEA1, 0xEAC2DA, 0x6B9E81, gb(0x3DFFFFFF, 0x3EFFFFFF, 0x1E1E1E, 0x2D2C2A, 0xFFFFFF, 0x41463E)),
        Gradient(0xFEC59A, 0xFFE9B7, 0xF56C4A, gb(0x3DFFFFFF, 0x3BFFFFFF, 0x1E1E1E, 0x332D26, 0xFFFFFF, 0x4B433C)),
    )
    private val darkGradientSpecs = listOf(
        Gradient(0x1C1269, 0xB950A2, 0xF93FA5, gb(0x3FFFFFFF, 0x3CFFFFFF, 0xFFFFFF, 0xEEE2EE, 0xFFFFFF, 0xE2DDEA)),
        Gradient(0x5C5F64, 0x010101, 0x80CBC4, gb(0x2DFFFFFF, 0x1EFFFFFF, 0xFFFFFF, 0xDBDCDC, 0xFFFFFF, 0xE1E2E3)),
        Gradient(0x604742, 0x3E2622, 0x8D6E63, gb(0x2CFFFFFF, 0x2AFFFFFF, 0xFFFFFF, 0xE1DDDC, 0xFFFFFF, 0xE3E0DF)),
        Gradient(0x51636D, 0x283339, 0x78909C, gb(0x2DFFFFFF, 0x2DFFFFFF, 0xFFFFFF, 0xDEE0E1, 0xFFFFFF, 0xE1E3E5)),
        Gradient(0x017B6C, 0x004D51, 0x4DB6AC, gb(0x2EFFFFFF, 0x2EFFFFFF, 0xFFFFFF, 0xD5E4E4, 0xFFFFFF, 0xD5E8E6)),
        Gradient(0x1571D8, 0x0F48A2, 0x2196F3, gb(0x2DFFFFFF, 0x27FFFFFF, 0xFFFFFF, 0xD8E3F3, 0xFFFFFF, 0xD8E6F7)),
        Gradient(0x35608A, 0x0C131B, 0x355F87, gb(0x2DFFFFFF, 0x2DFFFFFF, 0xFFFFFF, 0xDDE2E6, 0xFFFFFF, 0xDEE4EA)),
        Gradient(0x55585D, 0x2A2F33, 0x5E97F6, gb(0x2DFFFFFF, 0x28FFFFFF, 0xFFFFFF, 0xDEDFE0, 0xFFFFFF, 0xE1E2E3)),
        Gradient(0x615654, 0x1A1112, 0xB35736, gb(0x28FFFFFF, 0x2BFFFFFF, 0xFFFFFF, 0xDDDCDC, 0xFFFFFF, 0xE4E3E2)),
    )

    val lightGradients = lightGradientSpecs.mapIndexed { i, g ->
        ThemeSpec("gradient_light_${i + 1}", R.string.theme_light_gradient_n, Section.LIGHT_GRADIENT, Kind.GRADIENT,
            c(g.top), c(0xFFFFFF), c(g.enter), false, c(g.bottom), i + 1, g.gb)
    }
    val darkGradients = darkGradientSpecs.mapIndexed { i, g ->
        ThemeSpec("gradient_dark_${i + 1}", R.string.theme_dark_gradient_n, Section.DARK_GRADIENT, Kind.GRADIENT,
            c(g.top), c(0x5C5C5C), c(g.enter), true, c(g.bottom), i + 1, g.gb)
    }

    val all: List<ThemeSpec> = defaults + colors + lightGradients + darkGradients

    fun byId(id: String): ThemeSpec? = all.firstOrNull { it.id == id }

    fun isCustom(id: String) = id.startsWith(CUSTOM_PREFIX)

    /** A user image theme from [Prefs.customThemes]. */
    data class CustomTheme(val id: String, val brightness: Int) {
        fun encode() = "$id|$brightness"

        companion object {
            const val DEFAULT_BRIGHTNESS = 50
            fun decode(s: String): CustomTheme? {
                val parts = s.split('|')
                if (parts.size != 2 || !parts[0].startsWith(CUSTOM_PREFIX)) return null
                return CustomTheme(parts[0], parts[1].toIntOrNull()?.coerceIn(0, 100) ?: DEFAULT_BRIGHTNESS)
            }
        }
    }

    /** Relative path (under `Context.filesDir`) of a custom theme's image. */
    fun customImagePath(id: String) = "themes/$id.jpg"
}
