package dev.pboard.prefs

import dev.pboard.prefs.LanguageCatalog.Entry
import dev.pboard.prefs.LanguageCatalog.Layout

/**
 * The keyboard languages, with Settings › Languages as the single source of truth.
 *
 * [Prefs.enabledLanguages] lists the enabled keyboards (`tag:layout`, in switching order) and [Prefs.activeLanguage]
 * names the one in use. The keyboard follows them live; the system's subtypes (res/xml/method.xml) only mirror them:
 * the IME makes the system's current subtype (and, on Android 14+, its enabled subtypes) match, and a subtype picked
 * in the system's switcher becomes the active language when it is enabled here.
 *
 * Only languages the app can type in are offered: those with a layout file (`assets/layouts/<tag>.json`, which also
 * selects the language-model dialect overlay). Each layout file is one QWERTY layout, so QWERTY is the only layout until
 * other layouts get files of their own. Everything here is pure: callers pass the available tags.
 */
object KeyboardLanguages {
    private const val LAYOUT_DIR_COMMON = "common.json"

    /** Language tags with a layout file, from the names in `assets/layouts` ("en_GB.json" -> "en-GB"). */
    fun availableFromLayoutFiles(names: List<String>): List<String> =
        names.filter { it.endsWith(".json") && it != LAYOUT_DIR_COMMON }
            .map { it.removeSuffix(".json").replace('_', '-') }
            .sorted()

    /** Layouts offered for [tag]: those with a layout file (QWERTY only for now), none for an unavailable language. */
    fun layoutsFor(tag: String, available: List<String>): List<Layout> =
        if (tag in available) listOf(Layout.QWERTY) else emptyList()

    /**
     * The enabled keyboards that can actually be used: stored entries of available languages (a layout without a file
     * falls back to QWERTY), one per language, in stored order; never empty (the first available language).
     */
    fun enabled(stored: List<String>, available: List<String>): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (e in stored.mapNotNull(Entry::decode)) {
            if (e.languageTag !in available || e.languageTag in out) continue
            val layout = if (e.layout in layoutsFor(e.languageTag, available)) e.layout else Layout.QWERTY
            out[e.languageTag] = Entry(e.languageTag, layout)
        }
        if (out.isEmpty()) available.firstOrNull()?.let { out[it] = Entry(it, Layout.QWERTY) }
        return out.values.toList()
    }

    /** The keyboard in use: [activeTag] if it is enabled, else the first enabled one. */
    fun active(enabled: List<Entry>, activeTag: String): Entry? =
        enabled.firstOrNull { it.languageTag == activeTag } ?: enabled.firstOrNull()

    /** The language switch (globe key, Shift+Space): the next enabled keyboard after [activeTag], wrapping around. */
    fun next(enabled: List<Entry>, activeTag: String): Entry? {
        if (enabled.isEmpty()) return null
        val i = enabled.indexOfFirst { it.languageTag == activeTag }
        return enabled[(i + 1).mod(enabled.size)]
    }

    /** Languages "Add keyboard" offers: available and not enabled yet. */
    fun addable(enabled: List<Entry>, available: List<String>): List<String> =
        available.filter { tag -> enabled.none { it.languageTag == tag } }

    /**
     * First-run value of [Prefs.enabledLanguages] (nothing stored yet): the system subtypes in use before the setting
     * drove the keyboard, current one first, so an update changes neither the language nor the switch order.
     */
    fun seed(currentTag: String?, systemEnabled: List<String>, available: List<String>): List<String> =
        (listOfNotNull(currentTag) + systemEnabled).filter { it in available }.distinct()
            .ifEmpty { available.take(1) }
            .map { Entry(it, Layout.QWERTY).encode() }
}
