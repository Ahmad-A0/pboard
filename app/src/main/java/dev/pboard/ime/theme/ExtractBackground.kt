package dev.pboard.ime.theme

/**
 * The landscape extract editor's background for a theme: the theme's colour ([KeyboardColors.extractBackground],
 * Gboard's surfaceBright in Dynamic Color) or, where the theme has none, the platform's own background. Applied when
 * the framework creates the view and again on every theme change (the framework recreates it only on a rotation).
 */
class ExtractBackground(private val setColor: (Int) -> Unit, private val restorePlatform: () -> Unit) {
    fun apply(colors: KeyboardColors) {
        val bg = colors.extractBackground
        if (bg != null) setColor(bg) else restorePlatform()
    }
}
