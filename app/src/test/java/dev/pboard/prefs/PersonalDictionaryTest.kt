package dev.pboard.prefs

import dev.pboard.prefs.PersonalDictionary.Entry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The personal dictionary file survives updates: a frozen format 1 file (as older builds wrote it) still loads. */
class PersonalDictionaryTest {
    private val dir: File = Files.createTempDirectory("pdict").toFile().also { it.deleteOnExit() }
    private fun frozenV1(): ByteArray = javaClass.getResourceAsStream("/userdata/personal_dictionary_v1.tsv")!!.readBytes()

    @Test
    fun format1Loads() {
        assertEquals(1, PersonalDictionary.FORMAT_VERSION)
        val f = File(dir, "personal_dictionary.tsv").apply { writeBytes(frozenV1()) }
        val d = PersonalDictionary.forFile(f)
        assertEquals(
            listOf(
                Entry("brb", "be right back", "*"),
                Entry("HeLLo wörld", "", "de-DE"),
                Entry("Kalamazoo", "", "en-US"),
                Entry("omw", "on my way", PersonalDictionary.ALL_LANGUAGES),
                Entry("zorbix", "", PersonalDictionary.ALL_LANGUAGES),
            ),
            d.entries.value.sortedBy { it.word.lowercase() },
        )
        // An edit rewrites the file in the same format, keeping every entry.
        d.put(Entry("new", "", "en-GB"))
        assertEquals(6, PersonalDictionary.forFile(f).entries.value.size)
        assertTrue(f.readLines().all { it.split('\t').size == 3 })
    }

    @Test
    fun anUnreadableFileIsNeverOverwritten() {
        // A directory where the file should be makes every read fail (like locked credential storage).
        val f = File(dir, "locked.tsv").apply { mkdirs() }
        val d = PersonalDictionary.forFile(f)
        assertTrue(d.entries.value.isEmpty())
        d.put(Entry("word"))
        assertTrue("nothing written over the unreadable file", f.isDirectory)
        assertEquals(listOf("word"), d.entries.value.map { it.word }) // kept in memory
    }
}
