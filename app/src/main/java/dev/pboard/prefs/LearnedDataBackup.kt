package dev.pboard.prefs

import dev.pboard.engine.decode.UserDataCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The backup file of Settings › Dictionary › "Back up learned data": the personal dictionary and everything the
 * engine has learned (UserDataCodec: learned words and phrases, touch offsets, removed suggestions, undone
 * corrections), in one file the user saves wherever they choose. pboard itself never sends it anywhere, and the data
 * stays excluded from Android's backup; this file is the only way it leaves app-private storage.
 *
 * Format [FORMAT_VERSION] 1 (big-endian): magic "PBBK", format version, section count, then per section a tag, a
 * byte length and the bytes; the file ends with the SHA-256 of everything before it.
 *   section 1 = learned data (UserDataCodec's own versioned bytes)
 *   section 2 = personal dictionary (UTF-8 lines `word \t shortcut \t languageTag`)
 * Every version ever written must stay readable. A restore file is untrusted: [decode] is strict (size cap, exact
 * lengths, known sections once each, checksum, the nested formats fully validated) and returns an error instead of a
 * partial result, so nothing is applied unless the whole file is sound.
 */
object LearnedDataBackup {
    const val FORMAT_VERSION = 1
    /** Cap on a restore file. Learned data is bounded (60 000 n-grams, a few MB at most). */
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_DICTIONARY_WORDS = 50_000
    /** Longest word (a shortcut's expansion can be a sentence) or shortcut in a backup. */
    const val MAX_ENTRY_CHARS = 4000
    const val FILE_EXTENSION = "pbbackup"

    private const val MAGIC = 0x5042424B // "PBBK"
    private const val SECTION_LEARNED = 1
    private const val SECTION_DICTIONARY = 2
    private const val HASH_BYTES = 32
    private const val MAX_SECTIONS = 8

    enum class Error { TOO_LARGE, NOT_A_BACKUP, NEWER_VERSION, CORRUPT }

    /** [learned] is UserDataCodec bytes, or null when the backup carries no learned data. */
    class Backup(val learned: ByteArray?, val dictionary: List<PersonalDictionary.Entry>)

    class Decoded(val backup: Backup?, val error: Error?)

    fun encode(learned: ByteArray?, dictionary: List<PersonalDictionary.Entry>): ByteArray {
        val sections = ArrayList<Pair<Int, ByteArray>>()
        if (learned != null && learned.isNotEmpty()) sections += SECTION_LEARNED to learned
        sections += SECTION_DICTIONARY to dictionary.joinToString("") { "${it.word}\t${it.shortcut}\t${it.languageTag}\n" }.toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(FORMAT_VERSION); out.writeInt(sections.size)
            for ((tag, data) in sections) { out.writeInt(tag); out.writeInt(data.size); out.write(data) }
        }
        val body = bytes.toByteArray()
        return body + sha256(body, body.size)
    }

    fun decode(bytes: ByteArray): Decoded {
        fun fail(e: Error) = Decoded(null, e)
        if (bytes.size > MAX_BYTES) return fail(Error.TOO_LARGE)
        if (bytes.size < 12 + HASH_BYTES) return fail(Error.NOT_A_BACKUP)
        val buf = ByteBuffer.wrap(bytes)
        if (buf.int != MAGIC) return fail(Error.NOT_A_BACKUP)
        val version = buf.int
        if (version > FORMAT_VERSION) return fail(Error.NEWER_VERSION)
        if (version < 1) return fail(Error.CORRUPT)
        val end = bytes.size - HASH_BYTES
        if (!sha256(bytes, end).contentEquals(bytes.copyOfRange(end, bytes.size))) return fail(Error.CORRUPT)
        val count = buf.int
        if (count !in 0..MAX_SECTIONS) return fail(Error.CORRUPT)
        var learned: ByteArray? = null
        var dictionary: List<PersonalDictionary.Entry>? = null
        repeat(count) {
            if (end - buf.position() < 8) return fail(Error.CORRUPT)
            val tag = buf.int
            val length = buf.int
            if (length < 0 || length > end - buf.position()) return fail(Error.CORRUPT)
            val data = ByteArray(length).also { buf.get(it) }
            when (tag) {
                SECTION_LEARNED -> {
                    if (learned != null) return fail(Error.CORRUPT)
                    val v = UserDataCodec.versionOf(data) ?: return fail(Error.CORRUPT)
                    if (v > UserDataCodec.FORMAT_VERSION) return fail(Error.NEWER_VERSION)
                    if (UserDataCodec.decodeStrict(data) == null) return fail(Error.CORRUPT)
                    learned = data
                }
                SECTION_DICTIONARY -> {
                    if (dictionary != null) return fail(Error.CORRUPT)
                    dictionary = parseDictionary(data) ?: return fail(Error.CORRUPT)
                }
                else -> return fail(Error.CORRUPT)
            }
        }
        if (buf.position() != end) return fail(Error.CORRUPT)
        return Decoded(Backup(learned, dictionary ?: emptyList()), null)
    }

    /** Strict on structure: every line is exactly `word \t shortcut \t languageTag` with a word; null otherwise. */
    private fun parseDictionary(data: ByteArray): List<PersonalDictionary.Entry>? {
        val text = String(data, Charsets.UTF_8)
        if ('�' in text) return null
        val out = LinkedHashMap<Pair<String, String>, PersonalDictionary.Entry>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            if (p.size != 3 || out.size >= MAX_DICTIONARY_WORDS) return null
            val (word, shortcut, tag) = p
            if (word.isBlank() || word.length > MAX_ENTRY_CHARS || shortcut.length > MAX_ENTRY_CHARS) return null
            if (tag != PersonalDictionary.ALL_LANGUAGES && !GboardDictionary.isLanguageTag(tag)) return null
            out.putIfAbsent(word to tag, PersonalDictionary.Entry(word, shortcut, tag))
        }
        return out.values.toList()
    }

    private fun sha256(bytes: ByteArray, length: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, length) }.digest()
}
