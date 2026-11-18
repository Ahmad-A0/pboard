package dev.pboard.ime.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import dev.pboard.ime.config.Config
import dev.pboard.prefs.ThemeCatalog
import dev.pboard.prefs.ThemeCatalog.CustomTheme

/**
 * Colour roles used by the keyboard, resolved from the system's Material You (dynamic colour) palette.
 *
 * Role mapping measured against Gboard 15 and 18.6 ("Default" / Material You theme):
 *  - keyboard background      = surfaceContainer
 *  - letter key               = surfaceContainerLowest (light) / surfaceBright (dark; 15.1 used surfaceContainerHighest)
 *  - functional key           = secondaryContainer, icon = onSecondaryContainer
 *  - key label                = onSurface, hint label = onSurfaceVariant
 *  - pressed letter key       = surfaceContainerHighest (light) / the dark key colour + 10 % onSurface
 *  - emoji panel              = surfaceContainerLow
 *  - divider                  = outlineVariant (colour themes: see [KeyboardTheme.divider])
 *  - selected popup item      = primary / onPrimary
 */
data class KeyboardColors(
    val isDark: Boolean,
    @ColorInt val background: Int,
    @ColorInt val key: Int,
    @ColorInt val keyPressed: Int,
    @ColorInt val keyLabel: Int,
    @ColorInt val hintLabel: Int,
    @ColorInt val functionalKey: Int,
    @ColorInt val functionalKeyPressed: Int,
    @ColorInt val functionalIcon: Int,
    @ColorInt val accentKey: Int,
    @ColorInt val accentKeyPressed: Int,
    @ColorInt val accentIcon: Int,
    /** The enter (action) key: the functional key colour in Dynamic Color, the theme's accent in colour themes. */
    @ColorInt val enterKey: Int,
    @ColorInt val enterKeyPressed: Int,
    @ColorInt val enterIcon: Int,
    /** Space bar of the theme thumbnails and with key borders off. */
    @ColorInt val spaceBar: Int,
    /** The ?123 / ABC key with key borders off (Gboard keeps it as a pill; other function keys lose their caps). */
    @ColorInt val borderlessSymbolKey: Int,
    @ColorInt val divider: Int,
    @ColorInt val barIcon: Int,
    @ColorInt val barIconDisabled: Int,
    @ColorInt val barButton: Int,
    @ColorInt val barButtonPressed: Int,
    @ColorInt val suggestionText: Int,
    @ColorInt val previewBackground: Int,
    @ColorInt val previewLabel: Int,
    @ColorInt val popupBackground: Int,
    @ColorInt val popupSelected: Int,
    @ColorInt val popupSelectedLabel: Int,
    @ColorInt val gestureTrail: Int,
    @ColorInt val primary: Int,
    @ColorInt val shadow: Int,
    /** Tooltips ("Drag here to replace"): tertiaryContainer / onTertiaryContainer. */
    @ColorInt val tooltip: Int = 0xFFFDD7FA.toInt(),
    @ColorInt val tooltipText: Int = 0xFF2A122C.toInt(),
    /** surfaceContainerLowest (emoji grid: white on light, near-black on dark); null = key colour. */
    @ColorInt val surfaceLowest: Int? = null,
    /** surfaceBright: the landscape extract editor's background. */
    @ColorInt val extractBackground: Int? = null,
    /** surfaceContainerLow: the emoji panel's background (Gboard 18.6); null = [surfaceLowest]. */
    @ColorInt val emojiBackground: Int? = null,
    /** Emoji panel (Gboard 18.6): back button, search pill and tab chips (surfaceContainerHigh). */
    @ColorInt val emojiChip: Int? = null,
    /** Emoji panel section headers and category icons. */
    @ColorInt val emojiSubtle: Int? = null,
    /** Selected emoji tab / category: primaryFixedDim with onPrimaryFixedVariant (the same in both themes). */
    @ColorInt val emojiSelected: Int? = null,
    @ColorInt val emojiSelectedIcon: Int? = null,
    /** Gradient themes: the bottom stop of the vertical background gradient ([background] is the top); null = solid. */
    @ColorInt val backgroundEnd: Int? = null,
    /** Header strip of the borderless preview in coloured themes (Gboard darkens it); null = [background]. */
    @ColorInt val borderlessHeader: Int? = null,
    /**
     * Image themes ("My themes"): the picture (a path under `filesDir`, see ThemeCatalog.customImagePath), drawn
     * centre-cropped over [background] under a black scrim of [imageScrim] alpha.
     */
    val backgroundImage: String? = null,
    val imageScrim: Float = 0f,
) {
    /** The background is one colour (not a gradient or a picture), so a key can be erased by painting it. */
    val solidBackground: Boolean get() = backgroundEnd == null && backgroundImage == null
}

/**
 * Theme colours: the ONE place a theme id ([ThemeCatalog]) becomes colours. The keyboard ([colors]) and the settings
 * previews and thumbnails (dev.pboard.settings.ui.paletteFor) both go through [resolve], so a theme looks the same in
 * the picker and on the keyboard.
 */
object KeyboardTheme {
    fun systemDark(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Debug builds only (the keyboard's debug broadcast): colours shown instead of the selected theme's. */
    @Volatile var debugOverride: KeyboardColors? = null

    /** Colours of the selected theme ([Config.current]) in [context]: its night mode and its Material You palette. */
    fun colors(context: Context): KeyboardColors = debugOverride ?: Config.current.let { cfg ->
        resolve(cfg.themeId, systemDark(context), { dark -> Palette.resolve(context, dark) }, cfg.customThemes)
    }

    fun isDark(context: Context): Boolean =
        isDark(Config.current.themeId, systemDark(context), Config.current.customThemes)

    fun isDark(themeId: String, systemDark: Boolean, customThemes: List<CustomTheme> = emptyList()): Boolean = when {
        customThemes.any { it.id == themeId } -> true // white labels over the dimmed picture
        else -> specFor(themeId, systemDark).let { if (it.kind == ThemeCatalog.Kind.DYNAMIC) systemDark else it.isDark }
    }

    /**
     * The theme the keyboard draws for the stored [themeId]: the theme itself when it is in the catalogue or one of
     * [customThemes], else Dynamic Color (the keyboard's fallback, e.g. for a removed image theme). The picker marks
     * this one as selected, so its check mark is always on the theme the keyboard shows.
     */
    fun renderedThemeId(themeId: String, customThemes: List<CustomTheme>): String =
        if (ThemeCatalog.byId(themeId) != null || customThemes.any { it.id == themeId }) themeId else ThemeCatalog.DYNAMIC

    /** The catalogue theme [themeId] renders as: System Auto is Default or Default Dark by the night mode. */
    private fun specFor(themeId: String, systemDark: Boolean): ThemeCatalog.ThemeSpec {
        val spec = ThemeCatalog.byId(themeId) ?: ThemeCatalog.byId(ThemeCatalog.DYNAMIC)!!
        if (spec.kind != ThemeCatalog.Kind.SYSTEM_AUTO) return spec
        return ThemeCatalog.byId(if (systemDark) ThemeCatalog.DEFAULT_DARK else ThemeCatalog.DEFAULT_LIGHT)!!
    }

    /**
     * Colours of theme [themeId] with the system in dark mode or not; [dynamic] supplies the Material You palette
     * (light or dark) that Dynamic Color uses and [customThemes] the user's image themes (Prefs.customThemes).
     * Pure: no Android calls, so it is unit-tested on the JVM.
     */
    fun resolve(
        themeId: String,
        systemDark: Boolean,
        dynamic: (dark: Boolean) -> Palette,
        customThemes: List<CustomTheme> = emptyList(),
    ): KeyboardColors {
        customThemes.firstOrNull { it.id == themeId }?.let { return fromImage(it) }
        val spec = specFor(themeId, systemDark)
        return if (spec.kind == ThemeCatalog.Kind.DYNAMIC) fromPalette(dynamic(systemDark), systemDark) else fromSpec(spec)
    }

    /**
     * Image themes: translucent white keys over the user's picture, dimmed by the theme's brightness (100 = as is,
     * 0 = 80 % black), white labels; popups and toolbar buttons opaque.
     */
    private fun fromImage(t: CustomTheme): KeyboardColors {
        val bg = 0xFF303030.toInt()
        val key = withAlpha(WHITE, 0.18f)
        val fn = withAlpha(WHITE, 0.10f)
        val enter = withAlpha(WHITE, 0.35f)
        val solidKey = blend(bg, WHITE, 0.18f)
        return KeyboardColors(
            isDark = true, background = bg, key = key, keyPressed = pressed(key, WHITE), keyLabel = WHITE,
            hintLabel = withAlpha(WHITE, 0.75f), functionalKey = fn, functionalKeyPressed = pressed(fn, WHITE),
            functionalIcon = WHITE, accentKey = enter, accentKeyPressed = pressed(enter, WHITE), accentIcon = WHITE,
            enterKey = enter, enterKeyPressed = pressed(enter, WHITE), enterIcon = WHITE,
            spaceBar = withAlpha(WHITE, 0.4f), borderlessSymbolKey = withAlpha(WHITE, 0.4f), divider = DIVIDER_WHITE, barIcon = WHITE,
            barIconDisabled = withAlpha(WHITE, 0.38f), barButton = solidKey, barButtonPressed = pressed(solidKey, WHITE),
            suggestionText = WHITE, previewBackground = solidKey, previewLabel = WHITE, popupBackground = solidKey,
            popupSelected = WHITE, popupSelectedLabel = INK, gestureTrail = WHITE, primary = WHITE, shadow = 0x66000000,
            backgroundImage = ThemeCatalog.customImagePath(t.id), imageScrim = (1f - t.brightness / 100f) * 0.8f,
        )
    }

    private fun fromPalette(p: Palette, dark: Boolean): KeyboardColors {
        val fnPressed = blend(p.secondaryContainer, p.onSecondaryContainer, 0.10f)
        return if (!dark) KeyboardColors(
            isDark = false,
            background = p.surfaceContainer,
            key = p.surfaceContainerLowest,
            keyPressed = p.surfaceContainerHighest,
            keyLabel = p.onSurface,
            hintLabel = p.onSurfaceVariant,
            functionalKey = p.secondaryContainer,
            functionalKeyPressed = fnPressed,
            functionalIcon = p.onSecondaryContainer,
            accentKey = p.primaryFixedDim,
            accentKeyPressed = blend(p.primaryFixedDim, p.onPrimaryContainer, 0.12f),
            accentIcon = p.onPrimaryContainer,
            enterKey = p.secondaryContainer,
            enterKeyPressed = fnPressed,
            enterIcon = p.onSecondaryContainer,
            spaceBar = p.surfaceContainerLowest,
            borderlessSymbolKey = p.secondaryContainer,
            divider = p.outlineVariant,
            barIcon = p.onSurface,
            barIconDisabled = withAlpha(p.onSurface, 0.38f),
            barButton = p.surfaceContainerLowest,
            barButtonPressed = p.surfaceContainerHighest,
            suggestionText = p.onSurface,
            previewBackground = p.surfaceContainerLowest,
            previewLabel = p.onSurface,
            popupBackground = p.surfaceContainerLowest,
            popupSelected = p.primary,
            popupSelectedLabel = p.onPrimary,
            gestureTrail = p.primary,
            primary = p.primary,
            shadow = 0x40000000,
            tooltip = p.tertiaryContainer,
            tooltipText = p.onTertiaryContainer,
            surfaceLowest = p.surfaceContainerLowest,
            extractBackground = p.surfaceBright,
            emojiBackground = p.surfaceContainerLow,
            emojiChip = p.surfaceContainerHigh,
            emojiSubtle = p.outline,
            emojiSelected = p.primaryFixedDim,
            emojiSelectedIcon = p.onPrimaryFixedVariant,
        ) else KeyboardColors(
            isDark = true,
            background = p.surfaceContainer,
            key = p.surfaceBright,
            keyPressed = blend(p.surfaceBright, p.onSurface, 0.10f),
            keyLabel = p.onSurface,
            hintLabel = p.onSurfaceVariant,
            functionalKey = p.secondaryContainer,
            functionalKeyPressed = fnPressed,
            functionalIcon = p.onSecondaryContainer,
            accentKey = p.primaryContainer,
            accentKeyPressed = blend(p.primaryContainer, p.onPrimaryContainer, 0.12f),
            accentIcon = p.onPrimaryContainer,
            enterKey = p.secondaryContainer,
            enterKeyPressed = fnPressed,
            enterIcon = p.onSecondaryContainer,
            spaceBar = p.surfaceBright,
            borderlessSymbolKey = p.secondaryContainer,
            divider = p.outlineVariant,
            barIcon = p.onSurface,
            barIconDisabled = withAlpha(p.onSurface, 0.38f),
            barButton = p.surfaceBright,
            barButtonPressed = blend(p.surfaceBright, p.onSurface, 0.10f),
            suggestionText = p.onSurface,
            previewBackground = p.surfaceBright,
            previewLabel = p.onSurface,
            popupBackground = p.surfaceBright,
            popupSelected = p.primary,
            popupSelectedLabel = p.onPrimary,
            gestureTrail = p.primary,
            primary = p.primary,
            shadow = 0x66000000,
            tooltip = p.tertiaryContainer,
            tooltipText = p.onTertiaryContainer,
            surfaceLowest = p.surfaceContainerLowest,
            extractBackground = p.surfaceBright,
            emojiBackground = p.surfaceContainerLow,
            emojiChip = p.surfaceContainerHigh,
            // Measured #6D7779 on the phone: darker than the dark outline role.
            emojiSubtle = blend(p.surfaceContainerLow, p.onSurfaceVariant, 0.645f),
            emojiSelected = p.primaryFixedDim,
            emojiSelectedIcon = p.onPrimaryFixedVariant,
        )
    }

    /**
     * Colour themes (solid and gradient): Gboard 18.6's colours for the theme ([ThemeCatalog.Measured]), measured
     * on the pool emulator. Gradient themes have translucent white keys over the gradient. The catalogue's key sample
     * is the thumbnails' space bar and Gboard's borderless space bar ([KeyboardColors.spaceBar]).
     */
    private fun fromSpec(s: ThemeCatalog.ThemeSpec): KeyboardColors {
        val m = checkNotNull(s.gb) { "theme ${s.id} has no measured colours" }
        val dark = s.isDark
        val gradient = s.kind == ThemeCatalog.Kind.GRADIENT
        val label = m.keyLabel
        val enter = s.accent
        // Toolbar icons (measured on the keyboard): the label colour in Default / Default Dark and gradient themes,
        // 60 % of it over the background in colour themes (Black #999999, Material Light #627178).
        val barIcon = if (s.section == ThemeCatalog.Section.COLORS) blend(s.background, label, 0.6f) else label
        // Popups, key previews and toolbar buttons stay opaque on gradient themes (their sample key colour).
        val solidKey = if (gradient) s.keySample else m.key
        val bg = Color(s.background)
        // Light gradients: Gboard's borderless space bar is white at about 44 % over the picture.
        val spaceBar = if (gradient) withAlpha(WHITE, if (dark) 0.3f else 0.44f) else s.keySample
        return KeyboardColors(
            isDark = dark, background = s.background, backgroundEnd = s.backgroundEnd.takeIf { gradient },
            key = m.key, keyPressed = pressed(m.key, label), keyLabel = label, hintLabel = m.hintLabel,
            functionalKey = m.functionalKey, functionalKeyPressed = pressed(m.functionalKey, m.functionalLabel),
            functionalIcon = m.functionalLabel,
            accentKey = enter, accentKeyPressed = pressed(enter, m.enterLabel), accentIcon = m.enterLabel,
            enterKey = enter, enterKeyPressed = pressed(enter, m.enterLabel), enterIcon = m.enterLabel,
            spaceBar = spaceBar,
            // Borderless ?123: the function-key colour in Default / Default Dark, the space bar's elsewhere.
            borderlessSymbolKey = if (s.section == ThemeCatalog.Section.DEFAULT) m.functionalKey else spaceBar,
            divider = divider(s), barIcon = barIcon, barIconDisabled = withAlpha(barIcon, 0.38f),
            barButton = solidKey, barButtonPressed = pressed(solidKey, label), suggestionText = label,
            previewBackground = solidKey, previewLabel = label, popupBackground = solidKey,
            popupSelected = enter, popupSelectedLabel = m.enterLabel,
            gestureTrail = enter, primary = enter, shadow = if (dark) 0x66000000 else 0x40000000,
            borderlessHeader = if (dark && !gradient && luminance(s.background) > 0.03f) {
                lerp(bg, Color.Black, 0.3f).toArgb()
            } else null,
        )
    }

    /**
     * Suggestion-strip divider, as Gboard 18.6. Dynamic Color uses outline_variant
     * ([fromPalette]); Default / Default Dark use the fixed GM3 palette's outline_variant; the colour themes a
     * translucent black or white over the background.
     */
    private fun divider(s: ThemeCatalog.ThemeSpec): Int = when (s.id) {
        ThemeCatalog.DEFAULT_LIGHT -> 0xFFC4C7C5.toInt()
        ThemeCatalog.DEFAULT_DARK -> 0xFF444746.toInt()
        "color_material_light" -> 0x3337474F
        "color_google_light" -> 0x33303030
        "color_sand" -> DIVIDER_BLACK_12
        "color_cyan", "color_light_pink" -> DIVIDER_BLACK
        // Gradient themes: light ones share Sand's colours (label #1E1E1E, black 12 % separator), dark ones keep
        // the white-label themes' white 10 %.
        else -> if (s.kind == ThemeCatalog.Kind.GRADIENT && !s.isDark) DIVIDER_BLACK_12 else DIVIDER_WHITE
    }

    /** White 10 %: themes with white labels (dark colour themes, image themes). */
    private const val DIVIDER_WHITE = 0x1AFFFFFF
    /** Black 10 %: Cyan, Light pink. */
    private const val DIVIDER_BLACK = 0x1A000000
    /** Black 12 %: the colour-set template's color_set_b4 (Sand). */
    private const val DIVIDER_BLACK_12 = 0x1F000000

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF1F1F1F.toInt()

    /** Approximate relative luminance of the gamma-encoded channels (enough to pick label colours). */
    private fun luminance(@ColorInt c: Int): Float =
        (0.2126f * ((c shr 16) and 0xff) + 0.7152f * ((c shr 8) and 0xff) + 0.0722f * (c and 0xff)) / 255f

    /** Pressed state: 12 % toward the label; translucent keys (gradient themes) also become more opaque. */
    private fun pressed(@ColorInt c: Int, @ColorInt label: Int): Int {
        val a = c ushr 24
        val mixed = blend(c, label, 0.12f)
        return if (a == 0xff) mixed else (mixed and 0xffffff) or (minOf(0xff, a + 31) shl 24)
    }

    /** Opaque mix of [a] and [b] ([t] = share of [b]). */
    fun blend(@ColorInt a: Int, @ColorInt b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xff) * (1 - t) + ((b shr s) and 0xff) * t + 0.5f).toInt()
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun withAlpha(@ColorInt c: Int, a: Float): Int = (c and 0xffffff) or ((a * 255 + 0.5f).toInt() shl 24)
}

/** The Material 3 colour roles the keyboard needs. */
data class Palette(
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val surfaceBright: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outlineVariant: Int,
    val outline: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val primaryFixedDim: Int,
    val onPrimaryFixedVariant: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
) {
    companion object {
        fun resolve(context: Context, dark: Boolean): Palette = when {
            Build.VERSION.SDK_INT >= 34 -> fromRoles(context, dark)
            Build.VERSION.SDK_INT >= 31 -> fromTones(context, dark)
            else -> baseline(dark)
        }

        /** The static fallback scheme (Android 10/11, no dynamic colour); also the tests' Material You input. */
        fun baseline(dark: Boolean): Palette = if (dark) BASELINE_DARK else BASELINE_LIGHT

        /** Android 14+: the platform publishes the Material 3 roles directly. */
        private fun fromRoles(c: Context, dark: Boolean): Palette {
            fun col(light: Int, darkId: Int) = c.getColor(if (dark) darkId else light)
            return Palette(
                surfaceContainerLowest = col(android.R.color.system_surface_container_lowest_light, android.R.color.system_surface_container_lowest_dark),
                surfaceContainerLow = col(android.R.color.system_surface_container_low_light, android.R.color.system_surface_container_low_dark),
                surfaceContainer = col(android.R.color.system_surface_container_light, android.R.color.system_surface_container_dark),
                surfaceContainerHigh = col(android.R.color.system_surface_container_high_light, android.R.color.system_surface_container_high_dark),
                surfaceContainerHighest = col(android.R.color.system_surface_container_highest_light, android.R.color.system_surface_container_highest_dark),
                surfaceBright = col(android.R.color.system_surface_bright_light, android.R.color.system_surface_bright_dark),
                onSurface = col(android.R.color.system_on_surface_light, android.R.color.system_on_surface_dark),
                onSurfaceVariant = col(android.R.color.system_on_surface_variant_light, android.R.color.system_on_surface_variant_dark),
                outlineVariant = col(android.R.color.system_outline_variant_light, android.R.color.system_outline_variant_dark),
                outline = col(android.R.color.system_outline_light, android.R.color.system_outline_dark),
                primary = col(android.R.color.system_primary_light, android.R.color.system_primary_dark),
                onPrimary = col(android.R.color.system_on_primary_light, android.R.color.system_on_primary_dark),
                primaryContainer = col(android.R.color.system_primary_container_light, android.R.color.system_primary_container_dark),
                onPrimaryContainer = col(android.R.color.system_on_primary_container_light, android.R.color.system_on_primary_container_dark),
                primaryFixedDim = c.getColor(android.R.color.system_primary_fixed_dim),
                onPrimaryFixedVariant = c.getColor(android.R.color.system_on_primary_fixed_variant),
                secondaryContainer = col(android.R.color.system_secondary_container_light, android.R.color.system_secondary_container_dark),
                onSecondaryContainer = col(android.R.color.system_on_secondary_container_light, android.R.color.system_on_secondary_container_dark),
                tertiaryContainer = col(android.R.color.system_tertiary_container_light, android.R.color.system_tertiary_container_dark),
                onTertiaryContainer = col(android.R.color.system_on_tertiary_container_light, android.R.color.system_on_tertiary_container_dark),
            )
        }

        /** Android 12/13: derive roles from the tonal palettes (tones per the M3 role definitions). */
        private fun fromTones(c: Context, dark: Boolean): Palette {
            fun n1(t: Int) = c.getColor(tone(NEUTRAL1, t))
            fun n2(t: Int) = c.getColor(tone(NEUTRAL2, t))
            fun a1(t: Int) = c.getColor(tone(ACCENT1, t))
            fun a2(t: Int) = c.getColor(tone(ACCENT2, t))
            fun a3(t: Int) = c.getColor(tone(ACCENT3, t))
            // Palette stops are 0,10,50,100..900,1000 = M3 tones 100,99,95,90..10,0.
            val n94 = KeyboardTheme.blend(n1(50), n1(100), 0.2f)
            val n96 = KeyboardTheme.blend(n1(50), n1(10), 0.25f)
            val n92 = KeyboardTheme.blend(n1(100), n1(50), 0.4f)
            val n17 = KeyboardTheme.blend(n1(900), n1(800), 0.7f)
            val n12 = KeyboardTheme.blend(n1(900), n1(800), 0.2f)
            val n22 = KeyboardTheme.blend(n1(800), n1(700), 0.2f)
            val n24 = KeyboardTheme.blend(n1(800), n1(700), 0.4f)
            return if (!dark) Palette(
                surfaceContainerLowest = n1(0), surfaceContainerLow = n96, surfaceContainer = n94,
                surfaceContainerHigh = n92, surfaceContainerHighest = n1(100), outline = n2(500),
                surfaceBright = n1(10), onSurface = n1(900), onSurfaceVariant = n2(700), outlineVariant = n2(200),
                primary = a1(600), onPrimary = a1(0), primaryContainer = a1(100), onPrimaryContainer = a1(700),
                primaryFixedDim = a1(200), onPrimaryFixedVariant = a1(700), secondaryContainer = a2(100), onSecondaryContainer = a2(700),
                tertiaryContainer = a3(100), onTertiaryContainer = a3(700),
            ) else Palette(
                surfaceContainerLowest = n1(1000), surfaceContainerLow = n1(900), surfaceContainer = n12,
                surfaceContainerHigh = n17, surfaceContainerHighest = n22, outline = n2(400),
                surfaceBright = n24, onSurface = n1(100), onSurfaceVariant = n2(200), outlineVariant = n2(700),
                primary = a1(200), onPrimary = a1(800), primaryContainer = a1(700), onPrimaryContainer = a1(100),
                primaryFixedDim = a1(200), onPrimaryFixedVariant = a1(700), secondaryContainer = a2(700), onSecondaryContainer = a2(100),
                tertiaryContainer = a3(700), onTertiaryContainer = a3(100),
            )
        }

        private const val NEUTRAL1 = 0
        private const val NEUTRAL2 = 1
        private const val ACCENT1 = 2
        private const val ACCENT2 = 3
        private const val ACCENT3 = 4
        private val STOPS = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

        private fun tone(palette: Int, stop: Int): Int {
            val i = STOPS.indexOf(stop)
            val ids = when (palette) {
                NEUTRAL1 -> intArrayOf(
                    android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
                    android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
                    android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
                    android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
                    android.R.color.system_neutral1_1000,
                )
                NEUTRAL2 -> intArrayOf(
                    android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
                    android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
                    android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
                    android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
                    android.R.color.system_neutral2_1000,
                )
                ACCENT1 -> intArrayOf(
                    android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
                    android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                    android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
                    android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
                    android.R.color.system_accent1_1000,
                )
                ACCENT3 -> intArrayOf(
                    android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50,
                    android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300,
                    android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600,
                    android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900,
                    android.R.color.system_accent3_1000,
                )
                else -> intArrayOf(
                    android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50,
                    android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300,
                    android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600,
                    android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900,
                    android.R.color.system_accent2_1000,
                )
            }
            return ids[i]
        }

        // Static fallback for Android 10/11 (no dynamic colour): a neutral blue-grey scheme.
        private val BASELINE_LIGHT = Palette(
            surfaceContainerLowest = 0xffffffff.toInt(), surfaceContainerLow = 0xfff4f3fa.toInt(),
            surfaceContainer = 0xffeeedf4.toInt(), surfaceContainerHigh = 0xffe8e7ef.toInt(),
            surfaceContainerHighest = 0xffe2e2e9.toInt(), surfaceBright = 0xfffaf8ff.toInt(), outline = 0xff757780.toInt(),
            onSurface = 0xff1a1b20.toInt(), onSurfaceVariant = 0xff44464f.toInt(), outlineVariant = 0xffc5c6d0.toInt(),
            primary = 0xff475d92.toInt(), onPrimary = 0xffffffff.toInt(), primaryContainer = 0xffd9e2ff.toInt(),
            onPrimaryContainer = 0xff2f4578.toInt(), primaryFixedDim = 0xffb0c6ff.toInt(), onPrimaryFixedVariant = 0xff2f4578.toInt(),
            secondaryContainer = 0xffdce2f9.toInt(), onSecondaryContainer = 0xff404659.toInt(),
            tertiaryContainer = 0xfffdd7fa.toInt(), onTertiaryContainer = 0xff5b3d5d.toInt(),
        )
        private val BASELINE_DARK = Palette(
            surfaceContainerLowest = 0xff0c0e13.toInt(), surfaceContainerLow = 0xff1a1b21.toInt(),
            surfaceContainer = 0xff1e1f25.toInt(), surfaceContainerHigh = 0xff282a2f.toInt(),
            surfaceContainerHighest = 0xff33343a.toInt(), surfaceBright = 0xff38393f.toInt(), outline = 0xff8f9099.toInt(),
            onSurface = 0xffe2e2e9.toInt(), onSurfaceVariant = 0xffc5c6d0.toInt(), outlineVariant = 0xff44464f.toInt(),
            primary = 0xffb0c6ff.toInt(), onPrimary = 0xff152e60.toInt(), primaryContainer = 0xff2f4578.toInt(),
            onPrimaryContainer = 0xffd9e2ff.toInt(), primaryFixedDim = 0xffb0c6ff.toInt(), onPrimaryFixedVariant = 0xff2f4578.toInt(),
            secondaryContainer = 0xff404659.toInt(), onSecondaryContainer = 0xffdce2f9.toInt(),
            tertiaryContainer = 0xff5b3d5d.toInt(), onTertiaryContainer = 0xfffdd7fa.toInt(),
        )
    }
}
