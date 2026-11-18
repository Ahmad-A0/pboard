package dev.pboard.ime.textintel

import android.content.Context
import dev.pboard.engine.textintel.TextTokens
import dev.pboard.prefs.PersonalDictionary
import java.util.Locale

/**
 * Process-wide access to the text-intelligence models, shared by the keyboard ([GrammarAssist], [ComposeController])
 * and the spell checker service ([PboardSpellCheckerService]), which run in the same process.
 */
object TextIntel {
    private val cache = HashMap<String, TextIntelModels?>()

    /** Debug log tag (events and counts only, never text); off unless `setprop log.tag.PboardTextIntel DEBUG`. */
    const val TAG = "PboardTextIntel"
    val debug: Boolean get() = android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)

    /** Models for [locale] (loaded on first use: call off the main thread). English only, else null. */
    fun models(context: Context, locale: Locale): TextIntelModels? {
        val key = if (locale.language == "en") dev.pboard.engine.lm.EnglishPack.dialectOf(locale.toLanguageTag()) else locale.language
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val m = runCatching { TextIntelModels.load(context.applicationContext, locale) }.getOrNull()
        synchronized(cache) { cache[key] = m }
        return m
    }

    /**
     * Drops the calling thread to background priority, so text-intelligence work yields the CPU to the keyboard's UI
     * thread and its decoding thread. Returns the previous priority (for binder threads, which are shared).
     */
    fun backgroundPriority(): Int {
        val tid = android.os.Process.myTid()
        val old = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(android.os.Process.THREAD_PRIORITY_DEFAULT)
        if (old < android.os.Process.THREAD_PRIORITY_BACKGROUND) {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        }
        return old
    }

    /** Restores a priority saved by [backgroundPriority]. */
    fun restorePriority(old: Int) {
        runCatching { android.os.Process.setThreadPriority(old) }
    }

    /** A range to underline: a grammar issue with its fix, or a misspelling. */
    class Mark(val start: Int, val end: Int, val suggestion: String?, val grammar: Boolean)

    /** Spelling and grammar marks for [text] (one or more sentences), in text order. */
    fun marks(context: Context, models: TextIntelModels, text: String, locale: Locale, spell: Boolean, grammar: Boolean): List<Mark> {
        val out = ArrayList<Mark>()
        if (spell) {
            for (t in TextTokens.tokenize(text)) if (t.isWord && isTypo(context, models, t.text, locale)) out += Mark(t.start, t.end, null, false)
        }
        if (grammar) {
            for (issue in models.grammar.check(text)) {
                val d = models.grammar.displayEdit(text, issue)
                if (d.end <= d.start) continue
                // a misspelled word inside: its correction comes first (Gboard checks grammar on spell-corrected text)
                if (out.any { !it.grammar && it.start < d.end && d.start < it.end }) continue
                out += Mark(d.start, d.end, d.replacement, true)
            }
        }
        return out.sortedBy { it.start }
    }

    /**
     * A word the keyboard's vocabulary does not know: not in the language model's lexicon (any case), not learned
     * from the user, not in the personal dictionary. Acronyms, words with digits or inner capitals ("iPhone") and
     * single letters are never flagged.
     */
    fun isTypo(context: Context, models: TextIntelModels, word: String, locale: Locale): Boolean {
        val w = word.trim().replace('’', '\'')
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return false
        if (w.all { it.isUpperCase() || it == '\'' }) return false
        if (w.drop(1).any { it.isUpperCase() } && !w.all { it.isUpperCase() }) return false
        val base = w.removeSuffix("'s").removeSuffix("'")
        if (models.knows(w) || models.knows(base)) return false
        return base.lowercase(locale) !in personalWords(context)
    }

    @Volatile private var personal: Pair<List<PersonalDictionary.Entry>, Set<String>>? = null

    private fun personalWords(context: Context): Set<String> {
        val entries = PersonalDictionary.get(context).entries.value
        personal?.let { if (it.first === entries) return it.second }
        val set = entries.map { it.word.lowercase() }.toHashSet()
        personal = entries to set
        return set
    }
}
