package dev.pboard.prefs

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's personal dictionary (Settings › Dictionary › Personal dictionary): words with an optional shortcut,
 * per language (`languageTag`, or [ALL_LANGUAGES]).
 *
 * Personal data: stored as a TSV file in credential-protected, no-backup storage (`noBackupFilesDir`). Reads are
 * served from memory; writes go to disk synchronously (the file is tiny). The engine should observe [entries].
 *
 * File format [FORMAT_VERSION] 1 (`personal_dictionary.tsv`): UTF-8 lines `word \t shortcut \t languageTag`; lines
 * with only a word, or a word and a shortcut, are accepted (language [ALL_LANGUAGES]). The format has no header, so a
 * future format 2 must use a NEW file name and migrate from this file on first load (leaving it in place, so a
 * downgrade still finds its data); PersonalDictionaryTest loads a frozen format 1 file.
 * If the file exists but cannot be read, nothing is written until it can be: a failed read must never turn into an
 * empty dictionary that the next edit saves over the user's words.
 */
class PersonalDictionary private constructor(private val file: File) {

    data class Entry(val word: String, val shortcut: String = "", val languageTag: String = ALL_LANGUAGES)

    @Volatile private var loaded = false
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = state.asStateFlow()

    fun forLanguage(languageTag: String) = state.value.filter { it.languageTag == languageTag }

    /** Adds or replaces (same word + language) an entry. Blank words are ignored. */
    @Synchronized
    fun put(entry: Entry, replacing: Entry? = null) {
        ensureLoaded()
        val clean = entry.copy(word = entry.word.trim().clean(), shortcut = entry.shortcut.trim().clean())
        val rest = state.value.filterNot {
            it == replacing || (it.word == clean.word && it.languageTag == clean.languageTag)
        }
        state.value = if (clean.word.isEmpty()) rest else (rest + clean).sortedBy { it.word.lowercase() }
        save()
    }

    @Synchronized
    fun remove(entry: Entry) {
        ensureLoaded()
        state.value = state.value - entry
        save()
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        state.value = emptyList()
        save()
    }

    /**
     * Adds [new] entries (an import): one whose word + language is already there is left alone, except that an
     * existing entry without a shortcut takes the imported one. Returns the number of words added.
     */
    @Synchronized
    fun addAll(new: List<Entry>): Int {
        ensureLoaded()
        val merged = merge(state.value, new)
        val added = merged.size - state.value.size
        state.value = merged
        save()
        return added
    }

    /** Replaces the whole dictionary (restoring a backup). */
    @Synchronized
    fun replaceAll(new: List<Entry>) {
        ensureLoaded()
        state.value = merge(emptyList(), new)
        save()
    }

    /** Export as UTF-8 TSV: `word \t shortcut \t languageTag` per line. */
    fun exportTsv(out: OutputStream) = out.bufferedWriter().use { w ->
        state.value.forEach { w.write("${it.word}\t${it.shortcut}\t${it.languageTag}\n") }
    }

    /** Import a TSV produced by [exportTsv] (or `word[\tshortcut[\tlanguage]]` lines). Returns entries added. */
    @Synchronized
    fun importTsv(input: InputStream, defaultLanguage: String = ALL_LANGUAGES): Int {
        ensureLoaded()
        val parsed = input.bufferedReader().readLines().mapNotNull { parse(it, defaultLanguage) }
        val merged = (state.value + parsed).distinctBy { it.word to it.languageTag }
        val added = merged.size - state.value.size
        state.value = merged.sortedBy { it.word.lowercase() }
        save()
        return added
    }

    private fun load(): List<Entry> =
        try {
            (if (file.exists()) file.readLines().mapNotNull { parse(it, ALL_LANGUAGES) } else emptyList()).also { loaded = true }
        } catch (_: Exception) {
            emptyList() // e.g. credential storage still locked (direct boot): retried before the next edit
        }

    /** Retries a failed initial read, keeping edits made meanwhile (in memory) on top of the stored entries. */
    private fun ensureLoaded() {
        if (loaded) return
        val stored = load()
        if (loaded) state.value = (stored + state.value).distinctBy { it.word to it.languageTag }.sortedBy { it.word.lowercase() }
    }

    private fun save() {
        if (!loaded) return // the stored file could not be read: never overwrite it
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                state.value.forEach { w.write("${it.word}\t${it.shortcut}\t${it.languageTag}\n") }
            }
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ')

    companion object {
        /** Language tag of entries that apply to every language. */
        const val ALL_LANGUAGES = "*"

        /** Version of the file format (see the class comment); a new version needs a new file name and a migration. */
        const val FORMAT_VERSION = 1

        internal fun forFile(file: File) = PersonalDictionary(file)

        /** Most entries the dictionary takes from imports and restores. */
        const val MAX_ENTRIES = 50_000

        /** [base] plus the entries of [new] it lacks (by word + language), sorted; see [addAll]. */
        internal fun merge(base: List<Entry>, new: List<Entry>): List<Entry> {
            val out = LinkedHashMap<Pair<String, String>, Entry>()
            for (e in base) out[e.word to e.languageTag] = e
            for (raw in new) {
                val e = raw.copy(word = raw.word.trim().replace('\t', ' ').replace('\n', ' '), shortcut = raw.shortcut.trim().replace('\t', ' ').replace('\n', ' '))
                if (e.word.isEmpty()) continue
                val key = e.word to e.languageTag
                val have = out[key]
                when {
                    have != null -> if (have.shortcut.isEmpty() && e.shortcut.isNotEmpty()) out[key] = have.copy(shortcut = e.shortcut)
                    out.size < MAX_ENTRIES -> out[key] = e
                }
            }
            return out.values.sortedBy { it.word.lowercase() }
        }

        /**
         * The entries the engine uses while typing in [activeTag]: those for all languages and those of the active
         * language (any region: an "en-US" word also applies when typing en-GB), as (word, shortcut) without duplicates.
         */
        fun forTyping(entries: List<Entry>, activeTag: String): List<Pair<String, String>> {
            val language = activeTag.substringBefore('-').lowercase()
            return entries.filter { it.languageTag == ALL_LANGUAGES || it.languageTag.substringBefore('-').lowercase() == language }
                .map { it.word to it.shortcut }.distinct()
        }

        private fun parse(line: String, defaultLanguage: String): Entry? {
            val p = line.split('\t')
            val word = p.getOrNull(0)?.trim().orEmpty()
            if (word.isEmpty()) return null
            return Entry(word, p.getOrNull(1)?.trim().orEmpty(), p.getOrNull(2)?.trim()?.ifEmpty { null } ?: defaultLanguage)
        }

        @Volatile
        private var instance: PersonalDictionary? = null

        fun get(context: Context): PersonalDictionary =
            instance ?: synchronized(this) {
                instance ?: PersonalDictionary(File(context.applicationContext.noBackupFilesDir, "personal_dictionary.tsv"))
                    .also { instance = it }
            }
    }
}
