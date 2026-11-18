package dev.pboard.ime.translate

import java.util.Locale

/** A translation direction (from, to) as language codes. */
typealias LangPair = Pair<String, String>

/** A row of the language picker ([code] null for the "get more languages" action). */
data class LanguageRow(val code: String?, val label: String, val selected: Boolean = false)

/**
 * Language-choice rules of the translate bar (pure logic, unit-tested). [directions] are the installed models as
 * (from, to). Gboard's behaviour (docs/translate.md "UI"): the target is remembered between sessions, an explicit
 * source resets to the keyboard language on every open, swap exchanges the two, the pickers list the current choice
 * and recent ones first. pboard can only offer pairs it has a model for, so every choice is kept to an installed pair.
 */
object TranslateLanguages {
    fun sources(directions: Set<LangPair>): Set<String> = directions.mapTo(LinkedHashSet()) { it.first }
    fun targets(directions: Set<LangPair>, from: String): Set<String> = directions.filter { it.first == from }.mapTo(LinkedHashSet()) { it.second }

    /** The pair to open with, or null when no model is installed. */
    fun initial(directions: Set<LangPair>, keyboardLanguage: String, savedTarget: String): LangPair? {
        if (directions.isEmpty()) return null
        val sources = sources(directions)
        val from = keyboardLanguage.takeIf { it in sources } ?: sources.sorted().first()
        val to = savedTarget.takeIf { (from to it) in directions } ?: targets(directions, from).sorted().first()
        return from to to
    }

    /** A new source: keep the target if that pair exists, else the old source (a swap), else any target. */
    fun withSource(directions: Set<LangPair>, current: LangPair, from: String): LangPair {
        val (oldFrom, oldTo) = current
        return when {
            (from to oldTo) in directions -> from to oldTo
            (from to oldFrom) in directions -> from to oldFrom
            else -> from to (targets(directions, from).sorted().firstOrNull() ?: oldTo)
        }
    }

    /** A new target (only targets reachable from the current source are offered). */
    fun withTarget(directions: Set<LangPair>, current: LangPair, to: String): LangPair =
        if ((current.first to to) in directions) current.first to to else current

    /** The swapped pair, or null when the reverse model is not installed. */
    fun swapped(directions: Set<LangPair>, current: LangPair): LangPair? =
        (current.second to current.first).takeIf { it in directions }

    /** Most-recent-first list with [code] in front, without duplicates, at most [max] long. */
    fun remember(recent: List<String>, code: String, max: Int = 4): List<String> =
        (listOf(code) + recent.filter { it != code }).take(max)

    /**
     * Picker rows: the current choice, then up to three recent ones that are still available, then every other
     * available language alphabetically by display name.
     */
    fun pickerRows(available: Set<String>, current: String, recent: List<String>, name: (String) -> String):
        kotlin.Pair<List<LanguageRow>, List<LanguageRow>> {
        val top = buildList {
            if (current in available) add(LanguageRow(current, name(current), selected = true))
            recent.filter { it != current && it in available }.take(3).forEach { add(LanguageRow(it, name(it))) }
        }
        val shown = top.mapTo(HashSet()) { it.code }
        val rest = available.filter { it !in shown }.map { LanguageRow(it, name(it)) }.sortedBy { it.label.lowercase() }
        return top to rest
    }

    /**
     * Whether an open translate session survives an onStartInput/onStartInputView (Gboard 18.6, measured): an
     * app-initiated restart (restarting = true) of the same field (package, field id and name, still not a password
     * field) keeps Translate open with an empty box; hiding the keyboard, another field or another app close it.
     */
    fun keepOnRestart(restarting: Boolean, samePackage: Boolean, sameFieldId: Boolean, sameFieldName: Boolean, password: Boolean): Boolean =
        restarting && samePackage && sameFieldId && sameFieldName && !password

    /** Gboard inserts a separating space before a new translation only after a letter. */
    fun needsSeparator(before: CharSequence?): Boolean = !before.isNullOrEmpty() && Character.isLetter(before[before.length - 1])

    /** Language name in the UI language ("Spanish", "Español" on a Spanish system). */
    fun displayName(code: String, ui: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayLanguage(ui).ifEmpty { code }.replaceFirstChar { it.titlecase(ui) }
}
