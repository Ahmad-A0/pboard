package dev.pboard.prefs

import android.content.Context
import android.provider.UserDictionary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Words brought in from elsewhere when starting with pboard: a Gboard personal-dictionary export
 * ([GboardDictionary]) and Android's own user dictionary ([SystemUserDictionary]). Everything read here is
 * untrusted input: sizes are capped, zip entries are only ever read as text (their names are never used), and a
 * line that does not parse is skipped rather than failing the import.
 */
object GboardDictionary {
    /** An imported word; [languageTag] is a BCP-47 tag, or empty for "all languages". */
    data class Word(val word: String, val shortcut: String = "", val languageTag: String = "")

    enum class Error { TOO_LARGE, NOT_A_DICTIONARY, UNSUPPORTED_VERSION, UNREADABLE }

    /** [skipped] counts lines that looked like entries but were malformed; [truncated] when [MAX_WORDS] was reached. */
    class Result(val words: List<Word>, val skipped: Int = 0, val truncated: Boolean = false, val error: Error? = null)

    /** Cap on the picked file (a real export of thousands of words is a few hundred kB). */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    /** Cap on the text of all zip entries together, after decompression (zip bombs). */
    const val MAX_TEXT_BYTES = 16 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 16
    const val MAX_WORDS = 20_000
    /** Longest word or shortcut taken (characters); longer ones are skipped. */
    const val MAX_WORD_CHARS = 300

    private const val VERSION_PREFIX = "# Gboard Dictionary version:"
    private const val FORMAT_PREFIX = "# Gboard Dictionary format:"
    private val LANGUAGE_TAG = Regex("[A-Za-z0-9]{1,8}([-_][A-Za-z0-9]{1,8}){0,4}")

    /**
     * Parses a Gboard export: the `.zip` Gboard writes (every entry is read as a dictionary text) or a bare `.txt`,
     * told apart by content, not by name or MIME type.
     *
     * Text format (Gboard 18.6, PersonalDictionaryImporter / PersonalDictionaryExportFileCreator; UTF-8):
     *  - `# Gboard Dictionary version:2`, then `# Gboard Dictionary format:` + the tab-separated column names
     *    (`shortcut word language_tag pos_tag`, or without `pos_tag`), then one tab-separated entry per line. This is
     *    what 18.6 exports, as `dictionary.txt` inside `PersonalDictionary-*.zip`.
     *  - `# Gboard Dictionary version:1`, then entries `shortcut \t word \t language_tag` (older exports).
     *  - no version line: entries `shortcut \t word [\t pos_tag]`, no language.
     * A line without a tab is not an entry (blank lines, comments such as `# From OS`). An empty `language_tag` means
     * all languages. Gboard aborts on the first malformed line; here it is skipped and counted.
     */
    fun parse(input: InputStream): Result {
        val bytes = try { readCapped(input, MAX_FILE_BYTES) } catch (_: Exception) { return Result(emptyList(), error = Error.UNREADABLE) }
            ?: return Result(emptyList(), error = Error.TOO_LARGE)
        val sink = Sink()
        val error = try {
            if (isZip(bytes)) parseZip(bytes, sink) else parseText(bytes, sink)
        } catch (_: Exception) {
            Error.UNREADABLE
        }
        // Whatever parsed before an error is dropped: an import is all of one readable file or nothing.
        if (error != null) return Result(emptyList(), error = error)
        if (sink.words.isEmpty() && sink.skipped == 0 && !sink.sawHeader) return Result(emptyList(), error = Error.NOT_A_DICTIONARY)
        return Result(sink.words.values.toList(), sink.skipped, sink.truncated)
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4

    private fun parseZip(bytes: ByteArray, sink: Sink): Error? {
        var budget = MAX_TEXT_BYTES
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) return Error.TOO_LARGE
                if (entry.isDirectory) continue
                // The declared size is not trusted: the entry is read up to what is left of the budget.
                val text = readCapped(zip, budget) ?: return Error.TOO_LARGE
                budget -= text.size
                parseText(text, sink)?.let { return it }
            }
        }
        return if (entries == 0) Error.NOT_A_DICTIONARY else null
    }

    private fun parseText(bytes: ByteArray, sink: Sink): Error? {
        if (bytes.any { it.toInt() == 0 }) return Error.NOT_A_DICTIONARY // binary
        // Malformed UTF-8 becomes U+FFFD, and a line carrying it is skipped below.
        val lines = String(bytes, Charsets.UTF_8).removePrefix("﻿").lineSequence().map { it.removeSuffix("\r") }.iterator()
        if (!lines.hasNext()) return null
        val first = lines.next()
        var columns = listOf("shortcut", "word", "pos_tag")
        var exactColumns = false
        if (first.startsWith(VERSION_PREFIX)) {
            sink.sawHeader = true
            when (first.substring(VERSION_PREFIX.length).trim().toIntOrNull()) {
                1 -> { columns = listOf("shortcut", "word", "language_tag"); exactColumns = true }
                2 -> {
                    val format = if (lines.hasNext()) lines.next().trim() else ""
                    if (!format.startsWith(FORMAT_PREFIX)) return Error.NOT_A_DICTIONARY
                    columns = format.substring(FORMAT_PREFIX.length).split('\t').map { it.trim() }
                    if ("word" !in columns) return Error.NOT_A_DICTIONARY
                    exactColumns = true
                }
                // Gboard reads a version it cannot parse as "no version line"; a newer version is refused, as there.
                null -> {}
                else -> return Error.UNSUPPORTED_VERSION
            }
        } else {
            entry(first, columns, false, sink)
        }
        for (line in lines) {
            if (sink.truncated) break
            entry(line, columns, exactColumns, sink)
        }
        return null
    }

    private fun entry(line: String, columns: List<String>, exactColumns: Boolean, sink: Sink) {
        if (line.isBlank() || '\t' !in line) return
        val fields = line.split('\t')
        val wordAt = columns.indexOf("word")
        val ok = if (exactColumns) fields.size == columns.size else fields.size in 2..columns.size
        if (!ok || wordAt !in fields.indices) { sink.skipped++; return }
        fun field(name: String) = columns.indexOf(name).let { if (it in fields.indices) fields[it] else "" }
        val w = clean(fields[wordAt], field("shortcut"), field("language_tag"))
        if (w == null) sink.skipped++ else sink.add(w)
    }

    /**
     * A word as it may enter the dictionary, or null when it is unusable: blank, too long, or with control characters
     * or undecodable bytes. An unusable shortcut or language tag is dropped, the word kept.
     */
    fun clean(word: String, shortcut: String?, languageTag: String?): Word? {
        fun usable(s: String) = s.length <= MAX_WORD_CHARS && s.none { it.isISOControl() || it == '�' }
        val w = word.trim()
        if (w.isEmpty() || !usable(w)) return null
        val s = shortcut.orEmpty().trim().takeIf { it != w && usable(it) }.orEmpty()
        val tag = languageTag.orEmpty().trim().takeIf { LANGUAGE_TAG.matches(it) }?.replace('_', '-').orEmpty()
        return Word(w, s, tag)
    }

    fun isLanguageTag(tag: String) = LANGUAGE_TAG.matches(tag)

    /** Collects words, de-duplicated by word + language (the first wins; a later shortcut fills an empty one). */
    internal class Sink {
        val words = LinkedHashMap<Pair<String, String>, Word>()
        var skipped = 0
        var truncated = false
        var sawHeader = false

        fun add(w: Word) {
            val key = w.word to w.languageTag.lowercase()
            val have = words[key]
            when {
                have != null -> if (have.shortcut.isEmpty() && w.shortcut.isNotEmpty()) words[key] = have.copy(shortcut = w.shortcut)
                words.size >= MAX_WORDS -> truncated = true
                else -> words[key] = w
            }
        }
    }

    /** Reads [input] to its end; null when it holds more than [max] bytes (nothing more is read then). */
    fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
    }

    /**
     * Imported words as personal-dictionary entries. A word's language becomes the enabled language with that tag,
     * else an enabled one of the same language ("en-AU" with only en-GB enabled), else "all languages", so that
     * every imported word is listed in Settings and reaches the keyboard.
     */
    fun toEntries(words: List<Word>, enabledTags: List<String>): List<PersonalDictionary.Entry> = words.map { w ->
        fun language(tag: String) = tag.substringBefore('-').lowercase()
        val tag = when {
            w.languageTag.isEmpty() -> null
            else -> enabledTags.firstOrNull { it.equals(w.languageTag, ignoreCase = true) }
                ?: enabledTags.firstOrNull { language(it) == language(w.languageTag) }
        }
        PersonalDictionary.Entry(w.word, w.shortcut, tag ?: PersonalDictionary.ALL_LANGUAGES)
    }
}

/**
 * Android's user dictionary (`UserDictionary.Words`: words added in system settings or by other keyboards). The
 * provider needs no permission but only answers the enabled input methods and the spell checker; anyone else gets
 * an empty result, which cannot be told apart from an empty dictionary.
 */
object SystemUserDictionary {
    /** The stored words (possibly none), or null when the provider is missing or refused the query. */
    fun read(context: Context): List<GboardDictionary.Word>? = try {
        val columns = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT, UserDictionary.Words.LOCALE)
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI, columns, null, null, null)?.use { c ->
            val sink = GboardDictionary.Sink()
            while (c.moveToNext() && !sink.truncated) {
                val word = c.getString(0) ?: continue
                GboardDictionary.clean(word, c.getString(1), c.getString(2))?.let(sink::add)
            }
            sink.words.values.toList()
        }
    } catch (_: Exception) {
        null
    }
}
