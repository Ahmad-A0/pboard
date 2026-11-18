package dev.pboard.prefs

import dev.pboard.engine.decode.UserDataCodec
import dev.pboard.prefs.GboardDictionary.Error
import dev.pboard.prefs.GboardDictionary.Word
import dev.pboard.prefs.PersonalDictionary.Entry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importing words from a Gboard export, merging them into the personal dictionary, and the backup file. */
class DictionaryImportTest {
    private val v2Header = "# Gboard Dictionary version:2\n# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n"

    private fun parse(text: String) = GboardDictionary.parse(text.byteInputStream())
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
    }.toByteArray()

    // ------------------------------------------------------------------ Gboard export

    @Test
    fun gboard186ExportZip() {
        // As PersonalDictionaryExportFileCreator writes it: dictionary.txt in a zip, version 2, four columns, "# From OS".
        val text = v2Header + "omw\ton my way\ten-US\t\n\tZorbix\t\t\nbrb\tbe right back\t\t\n# From OS\n\tKalamazoo\ten-GB\t\n"
        val r = GboardDictionary.parse(zip("dictionary.txt" to text.toByteArray()).inputStream())
        assertNull(r.error)
        assertEquals(
            listOf(Word("on my way", "omw", "en-US"), Word("Zorbix"), Word("be right back", "brb"), Word("Kalamazoo", "", "en-GB")),
            r.words,
        )
        assertEquals(0, r.skipped)
        // The same text as a bare .txt, with a BOM and CRLF line ends.
        val bare = parse("﻿" + text.replace("\n", "\r\n"))
        assertEquals(r.words, bare.words)
    }

    @Test
    fun olderAndHeaderlessFormats() {
        // Version 2 without pos_tag, version 1 (three fixed columns), and no header (shortcut, word[, pos_tag]).
        assertEquals(listOf(Word("on my way", "omw", "en-US")),
            parse("# Gboard Dictionary version:2\n# Gboard Dictionary format:shortcut\tword\tlanguage_tag\nomw\ton my way\ten-US\n").words)
        assertEquals(listOf(Word("on my way", "omw", "en-US"), Word("Zorbix")), parse("# Gboard Dictionary version:1\nomw\ton my way\ten-US\n\tZorbix\t\n").words)
        assertEquals(listOf(Word("on my way", "omw"), Word("Zorbix")), parse("omw\ton my way\n\tZorbix\tnoun\n").words)
        // Columns are taken by name, whatever their order.
        assertEquals(listOf(Word("Zorbix", "zx", "de")), parse("# Gboard Dictionary version:2\n# Gboard Dictionary format:word\tlanguage_tag\tshortcut\nZorbix\tde\tzx\n").words)
        // An empty dictionary is a valid export.
        assertTrue(parse(v2Header).let { it.error == null && it.words.isEmpty() })
    }

    @Test
    fun malformedLinesAreSkippedNotFatal() {
        val long = "x".repeat(GboardDictionary.MAX_WORD_CHARS + 1)
        val r = parse(v2Header +
            "a\tAlpha\ten\t\n" +          // ok
            "too\tfew\n" +                // wrong column count
            "x\ty\tz\tw\textra\n" +       // wrong column count
            "\t\ten\t\n" +                // no word
            "s\t$long\ten\t\n" +          // word too long
            "s\tbad\u0007bell\ten\t\n" +  // control character
            "a comment without tabs\n\n" + // not an entry: ignored, not counted
            "b\tBeta\tnot a tag!\t\n" +   // unusable language tag: the word is kept for all languages
            "$long\tGamma\ten_GB\t\n" +   // unusable shortcut dropped; underscore tag normalised
            "Delta\tDelta\t\t\n")         // a shortcut equal to its word is no shortcut
        assertNull(r.error)
        assertEquals(listOf(Word("Alpha", "a", "en"), Word("Beta", "b"), Word("Gamma", "", "en-GB"), Word("Delta")), r.words)
        assertEquals(5, r.skipped)
        // Bytes that are not UTF-8 only cost their own line.
        val bytes = v2Header.toByteArray() + "a\tAlpha\t\t\n".toByteArray() + byteArrayOf('b'.code.toByte(), 9, 0xC3.toByte(), 0x28, 9, 9, 10) + "c\tGamma\t\t\n".toByteArray()
        val u = GboardDictionary.parse(bytes.inputStream())
        assertEquals(listOf("Alpha", "Gamma"), u.words.map { it.word })
        assertEquals(1, u.skipped)
    }

    @Test
    fun duplicatesCollapse() {
        val r = parse(v2Header + "\tZorbix\ten\t\nzx\tZorbix\tEN\t\nother\tZorbix\ten\t\n\tZorbix\t\t\n\tzorbix\ten\t\n")
        // Same word + language once (the first shortcut seen fills in); another language or another case is another entry.
        assertEquals(listOf(Word("Zorbix", "zx", "en"), Word("Zorbix"), Word("zorbix", "", "en")), r.words)
    }

    @Test
    fun filesThatAreNotExportsAreRefused() {
        assertEquals(Error.NOT_A_DICTIONARY, parse("").error)
        assertEquals(Error.NOT_A_DICTIONARY, parse("just some prose\nwith no tabs at all\n").error)
        assertEquals(Error.NOT_A_DICTIONARY, GboardDictionary.parse(ByteArray(2048) { (it % 7).toByte() }.inputStream()).error)
        assertEquals(Error.NOT_A_DICTIONARY, parse("# Gboard Dictionary version:2\nomw\ton my way\ten\t\n").error) // no format line
        assertEquals(Error.NOT_A_DICTIONARY, parse("# Gboard Dictionary version:2\n# Gboard Dictionary format:shortcut\tlanguage_tag\n").error)
        assertEquals(Error.UNSUPPORTED_VERSION, parse("# Gboard Dictionary version:3\nwhatever\tx\n").error)
        // A zip with nothing in it, a truncated zip, a zip of binary data.
        assertEquals(Error.NOT_A_DICTIONARY, GboardDictionary.parse(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0).inputStream()).error)
        val good = zip("dictionary.txt" to (v2Header + "a\tAlpha\t\t\n").toByteArray())
        assertNotNull(GboardDictionary.parse(good.copyOf(good.size / 2).inputStream()).error)
        assertEquals(Error.NOT_A_DICTIONARY, GboardDictionary.parse(zip("dictionary.txt" to ByteArray(512)).inputStream()).error)
        // A stream that fails while being read.
        val failing = object : InputStream() { override fun read(): Int = throw java.io.IOException("gone") }
        assertEquals(Error.UNREADABLE, GboardDictionary.parse(failing).error)
    }

    @Test
    fun zipEntryNamesAreNeverUsed() {
        // Path-traversal names, directories and odd extensions: every entry is only ever read as text.
        val text = (v2Header + "a\tAlpha\t\t\n").toByteArray()
        val r = GboardDictionary.parse(zip("../../../data/data/dev.pboard/files/evil" to text, "dir/" to ByteArray(0), "/abs/b.bin" to (v2Header + "b\tBeta\t\t\n").toByteArray()).inputStream())
        assertNull(r.error)
        assertEquals(listOf("Alpha", "Beta"), r.words.map { it.word })
    }

    @Test
    fun oversizedInputIsRefused() {
        // More bytes than a dictionary export can be: refused without reading on.
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int { read++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; b.fill('a'.code.toByte(), off, off + len); return len }
        }
        assertEquals(Error.TOO_LARGE, GboardDictionary.parse(endless).error)
        assertTrue(read <= GboardDictionary.MAX_FILE_BYTES + 64 * 1024L)
        // A zip bomb: small file, entries that inflate past the text budget.
        val zeros = ByteArray(GboardDictionary.MAX_TEXT_BYTES / 2 + 1024) { 'a'.code.toByte() }
        val bomb = zip("a.txt" to zeros, "b.txt" to zeros)
        assertTrue(bomb.size < GboardDictionary.MAX_FILE_BYTES / 10)
        assertEquals(Error.TOO_LARGE, GboardDictionary.parse(bomb.inputStream()).error)
        // Too many entries.
        val many = zip(*Array(GboardDictionary.MAX_ZIP_ENTRIES + 1) { "e$it.txt" to (v2Header + "\tword$it\t\t\n").toByteArray() })
        assertEquals(Error.TOO_LARGE, GboardDictionary.parse(many.inputStream()).error)
        // More words than the cap: the first MAX_WORDS are taken and the result says so.
        val big = StringBuilder(v2Header).also { sb -> repeat(GboardDictionary.MAX_WORDS + 50) { sb.append("\tword").append(it).append("\t\t\n") } }.toString()
        val r = parse(big)
        assertNull(r.error)
        assertEquals(GboardDictionary.MAX_WORDS, r.words.size)
        assertTrue(r.truncated)
    }

    // ------------------------------------------------------------------ into the personal dictionary

    @Test
    fun languagesMapToEnabledOnes() {
        val words = listOf(Word("a", "", "en-GB"), Word("b", "", "en-AU"), Word("c", "", "EN-us"), Word("d", "", "ja-JP"), Word("e"))
        assertEquals(
            listOf(Entry("a", "", "en-GB"), Entry("b", "", "en-GB"), Entry("c", "", "en-US"), Entry("d", "", "*"), Entry("e", "", "*")),
            GboardDictionary.toEntries(words, listOf("en-GB", "en-US")),
        )
        // What the engine gets while typing en-GB: all-language words and every English one, once.
        val entries = listOf(Entry("a", "x", "en-GB"), Entry("b", "", "en-US"), Entry("c", "", "de-DE"), Entry("d", "", "*"), Entry("a", "x", "*"))
        assertEquals(listOf("a" to "x", "b" to "", "d" to ""), PersonalDictionary.forTyping(entries, "en-GB"))
    }

    @Test
    fun importMergesWithoutDuplicates() {
        val dir = Files.createTempDirectory("pdict-import").toFile().also { it.deleteOnExit() }
        val f = File(dir, "personal_dictionary.tsv")
        val d = PersonalDictionary.forFile(f)
        d.put(Entry("Zorbix", "", "*"))
        d.put(Entry("omw", "on my way", "*"))
        val imported = listOf(Entry("Zorbix", "zx", "*"), Entry("omw", "other", "*"), Entry("Kalamazoo", "", "en-GB"), Entry("Zorbix", "", "en-GB"), Entry("  ", "", "*"),
            Entry("tab\tbed", "a\nb", "*"))
        assertEquals(3, d.addAll(imported))
        // Importing the same again adds nothing.
        assertEquals(0, d.addAll(imported))
        val expected = listOf(Entry("Kalamazoo", "", "en-GB"), Entry("omw", "on my way", "*"), Entry("tab bed", "a b", "*"), Entry("Zorbix", "zx", "*"), Entry("Zorbix", "", "en-GB"))
        assertEquals(expected.toSet(), d.entries.value.toSet())
        // Stored: a new instance reads the same entries.
        assertEquals(expected.toSet(), PersonalDictionary.forFile(f).entries.value.toSet())
        d.replaceAll(listOf(Entry("only", "", "*")))
        assertEquals(listOf(Entry("only", "", "*")), PersonalDictionary.forFile(f).entries.value)
    }

    // ------------------------------------------------------------------ backup file

    private val learned: ByteArray = UserDataCodec.encode(UserDataCodec.Data(mapOf("zorbix" to 3), mapOf('a' to floatArrayOf(1f, 2f, 3f)), setOf("damn"), mapOf("hellp" to setOf("help"))))
    private val dictionary = listOf(Entry("Zorbix", "zx", "*"), Entry("on my way, see you at 5", "omw", "en-GB"), Entry("naïve café", "", "*"))

    @Test
    fun backupRoundTrips() {
        val bytes = LearnedDataBackup.encode(learned, dictionary)
        val d = LearnedDataBackup.decode(bytes)
        assertNull(d.error)
        assertArrayEquals(learned, d.backup!!.learned)
        assertEquals(dictionary, d.backup!!.dictionary)
        // Nothing learned yet, nothing in the dictionary: still a valid backup.
        val empty = LearnedDataBackup.decode(LearnedDataBackup.encode(null, emptyList()))
        assertNull(empty.error)
        assertNull(empty.backup!!.learned)
        assertTrue(empty.backup!!.dictionary.isEmpty())
        // The format is versioned: a frozen file of version 1 must keep decoding (see backupFormat1IsFrozen).
        assertEquals(1, LearnedDataBackup.FORMAT_VERSION)
    }

    @Test
    fun backupFormat1IsFrozen() {
        // Written by format 1's encoder: magic, version 1, one section (dictionary "a\tb\t*\n"), SHA-256.
        val body = byteArrayOf(0x50, 0x42, 0x42, 0x4B, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 6) + "a\tb\t*\n".toByteArray()
        val file = body + java.security.MessageDigest.getInstance("SHA-256").digest(body)
        assertArrayEquals(file, LearnedDataBackup.encode(null, listOf(Entry("a", "b", "*"))))
        assertEquals(listOf(Entry("a", "b", "*")), LearnedDataBackup.decode(file).backup!!.dictionary)
    }

    @Test
    fun damagedBackupsAreRefusedWhole() {
        val good = LearnedDataBackup.encode(learned, dictionary)
        fun error(b: ByteArray) = LearnedDataBackup.decode(b).also { assertNull(it.backup) }.error
        // Not a backup at all.
        assertEquals(LearnedDataBackup.Error.NOT_A_BACKUP, error(ByteArray(0)))
        assertEquals(LearnedDataBackup.Error.NOT_A_BACKUP, error("hello, this is a text file that is long enough to pass the size check".toByteArray()))
        assertEquals(LearnedDataBackup.Error.NOT_A_BACKUP, error(learned + ByteArray(64)))
        // Any single flipped bit and any truncation is caught.
        for (i in listOf(8, 11, 15, 19, 40, good.size / 2, good.size - 40, good.size - 1)) {
            assertEquals("flip at $i", LearnedDataBackup.Error.CORRUPT, error(good.copyOf().also { it[i] = (it[i].toInt() xor 0x10).toByte() }))
        }
        for (n in listOf(12, 43, 44, good.size / 2, good.size - 1)) assertNotNull("cut at $n", error(good.copyOf(n)))
        assertEquals(LearnedDataBackup.Error.CORRUPT, error(good + 0))
        // A newer format version is named as such (even though its checksum cannot be checked).
        assertEquals(LearnedDataBackup.Error.NEWER_VERSION, error(good.copyOf().also { it[7] = 2 }))
        // Too large.
        assertEquals(LearnedDataBackup.Error.TOO_LARGE, error(ByteArray(LearnedDataBackup.MAX_BYTES + 1)))

        // Well-formed containers (valid checksum) with bad contents: built by hand.
        fun container(vararg sections: Pair<Int, ByteArray>, count: Int = sections.size): ByteArray {
            val out = ByteArrayOutputStream()
            java.io.DataOutputStream(out).apply {
                writeInt(0x5042424B); writeInt(1); writeInt(count)
                for ((tag, data) in sections) { writeInt(tag); writeInt(data.size); write(data) }
            }
            val body = out.toByteArray()
            return body + java.security.MessageDigest.getInstance("SHA-256").digest(body)
        }
        assertNull(LearnedDataBackup.decode(container(1 to learned, 2 to "a\t\t*\n".toByteArray())).error)
        val corrupt = LearnedDataBackup.Error.CORRUPT
        assertEquals(corrupt, error(container(1 to learned.copyOf(learned.size - 2))))        // learned data truncated
        assertEquals(corrupt, error(container(1 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))))    // learned data garbage
        assertEquals(corrupt, error(container(1 to learned, 1 to learned)))                    // a section twice
        assertEquals(corrupt, error(container(9 to ByteArray(4))))                             // unknown section
        assertEquals(corrupt, error(container(2 to "a\t\t*\n".toByteArray(), count = 2)))     // fewer sections than declared
        assertEquals(corrupt, error(container(2 to "a\t\t*\n".toByteArray(), count = -1)))
        assertEquals(corrupt, error(container(2 to "a\t\t*\n".toByteArray(), count = Int.MAX_VALUE)))
        assertEquals(corrupt, error(container(2 to "only a word\n".toByteArray())))            // dictionary line without fields
        assertEquals(corrupt, error(container(2 to "a\tb\t*\textra\n".toByteArray())))
        assertEquals(corrupt, error(container(2 to "\tb\t*\n".toByteArray())))                 // no word
        assertEquals(corrupt, error(container(2 to "a\tb\t../../etc\n".toByteArray())))        // language tag is not a tag
        assertEquals(corrupt, error(container(2 to byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 0x28, 9, 9, '*'.code.toByte(), 10)))) // not UTF-8
        assertEquals(LearnedDataBackup.Error.NEWER_VERSION, error(container(1 to learned.copyOf().also { it[7] = (UserDataCodec.FORMAT_VERSION + 1).toByte() })))
        // A section length pointing past the end.
        val lying = ByteArrayOutputStream().also { java.io.DataOutputStream(it).apply { writeInt(0x5042424B); writeInt(1); writeInt(1); writeInt(2); writeInt(1_000_000) } }.toByteArray()
        assertEquals(corrupt, error(lying + java.security.MessageDigest.getInstance("SHA-256").digest(lying)))
        assertFalse(LearnedDataBackup.decode(good).backup == null)
    }
}
