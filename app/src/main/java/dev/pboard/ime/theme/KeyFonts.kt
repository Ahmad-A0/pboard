package dev.pboard.ime.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import dev.pboard.R
import dev.pboard.ime.config.Config

/**
 * The key-label typeface. Gboard draws labels in Google Sans Text, which is proprietary and never bundled here:
 *  1. if the system ships Google Sans (Pixels expose it as a named system family), use the system copy;
 *  2. otherwise use the bundled Google Sans Flex instance (SIL OFL 1.1, res/font/key_sans.ttf, see NOTICE),
 *     with the system font as fallback for characters outside its subset;
 *  3. otherwise the default system sans.
 * Preferences › Font › System default (Config.current.useSystemFont) uses the system's default sans instead.
 */
object KeyFonts {
    enum class Source { SYSTEM_DEFAULT, SYSTEM_GOOGLE_SANS, BUNDLED }

    /** Which font the labels use for the Font setting and what the system ships. */
    fun source(useSystemFont: Boolean, systemHasGoogleSans: Boolean): Source = when {
        useSystemFont -> Source.SYSTEM_DEFAULT
        systemHasGoogleSans -> Source.SYSTEM_GOOGLE_SANS
        else -> Source.BUNDLED
    }

    private val cached = HashMap<Boolean, Typeface>()

    /** The typeface for the current Font setting; the keyboard rebuilds its views when the setting changes. */
    fun regular(context: Context): Typeface = regular(context, Config.current.useSystemFont)

    @Synchronized
    fun regular(context: Context, useSystemFont: Boolean): Typeface =
        cached.getOrPut(useSystemFont) { resolve(context, useSystemFont) }

    private fun resolve(context: Context, useSystemFont: Boolean): Typeface {
        val googleSans = if (useSystemFont) null else systemGoogleSans()
        return when (source(useSystemFont, googleSans != null)) {
            Source.SYSTEM_DEFAULT -> Typeface.create("sans-serif", Typeface.NORMAL)
            Source.SYSTEM_GOOGLE_SANS -> googleSans!!
            Source.BUNDLED -> runCatching {
                val font = Font.Builder(context.resources, R.font.key_sans).build()
                Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                    .setSystemFallback("sans-serif")
                    .build()
            }.getOrElse { Typeface.create("sans-serif", Typeface.NORMAL) }
        }
    }

    private fun systemGoogleSans(): Typeface? {
        val present = runCatching {
            SystemFonts.getAvailableFonts().any { it.file?.name?.startsWith("GoogleSans") == true }
        }.getOrDefault(false)
        if (!present) return null
        for (family in listOf("google-sans-text", "google-sans")) {
            val tf = Typeface.create(family, Typeface.NORMAL)
            if (tf != Typeface.DEFAULT && tf != Typeface.create("sans-serif", Typeface.NORMAL)) return tf
        }
        return null
    }
}
