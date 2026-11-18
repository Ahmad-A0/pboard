package dev.pboard.prefs

import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.LocaleDisplayNames
import android.icu.util.ULocale
import android.os.LocaleList
import android.view.inputmethod.InputMethodManager
import androidx.annotation.StringRes
import dev.pboard.R
import java.util.Locale

/**
 * Languages and layouts offered in Settings › Languages, and the encoding of [Prefs.enabledLanguages]
 * (`languageTag:layoutId`, e.g. `en-US:qwerty`).
 *
 * The list is intentionally generic (Latin-script layouts for every locale the platform knows); the engine may
 * restrict it later to the languages it has models for.
 */
object LanguageCatalog {

    enum class Layout(override val id: String, @StringRes val label: Int) : StableId {
        QWERTY("qwerty", R.string.layout_qwerty),
        QWERTZ("qwertz", R.string.layout_qwertz),
        AZERTY("azerty", R.string.layout_azerty),
        DVORAK("dvorak", R.string.layout_dvorak),
        COLEMAK("colemak", R.string.layout_colemak),
        PC("pc", R.string.layout_pc),
    }

    data class Entry(val languageTag: String, val layout: Layout) {
        val locale: Locale get() = Locale.forLanguageTag(languageTag)
        fun encode() = "$languageTag:${layout.id}"

        companion object {
            fun decode(s: String): Entry? {
                val i = s.lastIndexOf(':')
                if (i <= 0) return null
                val layout = Layout.entries.firstOrNull { it.id == s.substring(i + 1) } ?: return null
                return Entry(s.substring(0, i), layout)
            }
        }
    }

    /** Layouts offered for a language, default first (German-speaking → QWERTZ, French/Belgian → AZERTY). */
    fun layoutsFor(languageTag: String): List<Layout> {
        val lang = Locale.forLanguageTag(languageTag).language
        val first = when (lang) {
            "de", "cs", "sk", "hu", "sl", "hr", "bs", "sq", "lb", "rm" -> Layout.QWERTZ
            "fr" -> if (languageTag.endsWith("CA")) Layout.QWERTY else Layout.AZERTY
            else -> Layout.QWERTY
        }
        return listOf(first) + Layout.entries.filter { it != first }
    }

    /** System locales first (the "Suggested languages" section). */
    fun suggested(): List<String> {
        val list = LocaleList.getDefault()
        return (0 until list.size()).map { list[it].toLanguageTag() }.distinct()
    }

    /** All language tags (language + optional region) the platform can name, sorted by display name. */
    fun all(): List<String> =
        Locale.getAvailableLocales()
            .filter { it.language.isNotEmpty() && it.variant.isEmpty() && it.script.isEmpty() }
            .filter { it.country !in PSEUDO_REGIONS }
            .map { it.toLanguageTag() }
            .distinct()
            .sortedBy { displayName(it).lowercase() }

    /** Short name in the language itself, as Gboard shows it: "English (US)", "Español (EE.UU.)". */
    fun displayName(languageTag: String): String {
        val l = Locale.forLanguageTag(languageTag)
        val names = LocaleDisplayNames.getInstance(
            ULocale.forLocale(l), DisplayContext.LENGTH_SHORT, DisplayContext.CAPITALIZATION_FOR_UI_LIST_OR_MENU,
        )
        val short = SHORT_REGIONS[l.language + "_" + l.country]
        val name = if (short != null) "${names.languageDisplayName(l.language)} ($short)" else names.localeDisplayName(l)
        return name.replaceFirstChar { it.titlecase(l) }
    }

    /** Pseudo-locales used for UI testing (en-XA, ar-XB…). */
    private val PSEUDO_REGIONS = setOf("XA", "XB", "XC")

    /** CLDR "short" region names the platform's ICU data does not apply for these common locales. */
    private val SHORT_REGIONS = mapOf(
        "en_US" to "US", "en_GB" to "UK", "es_US" to "EE.UU.", "fr_US" to "É.-U.", "fr_GB" to "R.-U.",
    )

    @Volatile private var availableCache: List<String>? = null

    /** Languages the keyboard can type in (a layout file each); see [KeyboardLanguages]. */
    fun available(context: Context): List<String> = availableCache
        ?: KeyboardLanguages.availableFromLayoutFiles(context.assets.list("layouts")?.toList().orEmpty())
            .also { availableCache = it }

    /** The usable enabled keyboards (Settings › Languages), seeding the setting from the system on first use. */
    fun enabled(context: Context): List<Entry> {
        seedIfUnset(context)
        return KeyboardLanguages.enabled(PboardPrefs.get(context)[Prefs.enabledLanguages], available(context))
    }

    /** The keyboard in use. */
    fun active(context: Context): Entry? =
        KeyboardLanguages.active(enabled(context), PboardPrefs.get(context)[Prefs.activeLanguage])

    /**
     * Before the setting drove the keyboard, the system's subtypes did: the first time, copy the ones in use (current
     * first) so nobody's keyboard changes language on update ([KeyboardLanguages.seed]).
     */
    fun seedIfUnset(context: Context) {
        val prefs = PboardPrefs.get(context)
        if (prefs.isSet(Prefs.enabledLanguages)) return
        val imm = context.getSystemService(InputMethodManager::class.java)
        val me = imm?.inputMethodList?.firstOrNull { it.packageName == context.packageName }
        val defaultIme = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        val current = imm?.currentInputMethodSubtype?.takeIf { me != null && defaultIme == me.id }?.languageTag
        val enabled = if (me == null) emptyList() else
            imm.getEnabledInputMethodSubtypeList(me, true).map { it.languageTag }.filter { it.isNotEmpty() }
        val seed = KeyboardLanguages.seed(current, enabled, available(context))
        prefs.edit {
            this[Prefs.enabledLanguages] = seed
            if (!prefs.isSet(Prefs.activeLanguage)) this[Prefs.activeLanguage] = seed.firstOrNull()?.substringBefore(':').orEmpty()
        }
    }

    fun summary(context: Context, entries: List<Entry>): String =
        entries.joinToString(", ") { "${displayName(it.languageTag)} (${context.getString(it.layout.label)})" }
}
