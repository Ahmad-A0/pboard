package dev.pboard.engine.decode

import dev.pboard.engine.Candidate
import dev.pboard.engine.CandidateKind
import dev.pboard.engine.Context
import dev.pboard.engine.PersonalWord
import dev.pboard.engine.devlm.DevData
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.UserNgramModel
import dev.pboard.engine.testutil.Fixtures
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches.committed
import dev.pboard.engine.testutil.Touches.type
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starting from nothing (a new install, a switch from another keyboard): the personal dictionary reaches the engine,
 * a word kept once is learned, and learned data can be merged back from a backup.
 */
class ColdStartTest {
    private val lmDir = File(DevData.projectRoot(), "app/src/main/assets/lm")
    private val mid = Context(listOf("so"), false)
    /** Stamp of the test user models: the present, as merging decays counts to the real clock. */
    private val now = System.currentTimeMillis()

    private fun prodEngine(user: UserNgramModel = UserNgramModel()): PboardEngine {
        assumeTrue(File(lmDir, "en.lm").exists())
        return PboardEngine.create(listOf(EnglishPack.load("en-GB", lmDir, user).spec())).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }

    // ------------------------------------------------------------------ personal dictionary

    private fun personalDictionaryBehaves(e: PboardEngine) {
        val corrected = committed(type(e, "hellp", mid), "hellp")
        assertTrue(corrected != "hellp", "corrected before it is in the dictionary")
        assertFalse(type(e, "zorb", mid).candidates.any { it.text == "zorbix" })

        e.setPersonalDictionary(listOf(PersonalWord("hellp"), PersonalWord("zorbix"), PersonalWord("on my way", "omw"), PersonalWord("me@example.org", "mymail")))
        // Takes effect at once: never autocorrected away, offered from its first letters.
        assertEquals("hellp", committed(type(e, "hellp", mid), "hellp"))
        assertEquals("zorbix", committed(type(e, "zorbix", mid), "zorbix"))
        assertTrue(type(e, "zorb", mid).candidates.take(3).any { it.text == "zorbix" }, type(e, "zorb", mid).toString())
        // A shortcut offers its word on the strip (visible slots) and is never what space commits.
        val s = type(e, "omw", mid)
        assertTrue(s.candidates.take(3).any { it.text == "on my way" }, s.toString())
        assertTrue(committed(s, "omw") != "on my way")
        assertTrue(type(e, "mymail", mid).candidates.take(3).any { it.text == "me@example.org" })
        // Nothing of it is learned data.
        val fresh = UserDataCodec.decode(e.exportUserData())!!
        assertTrue(fresh.learnedWords.isEmpty() && fresh.rejected.isEmpty())

        // Removing the entries removes their effect.
        e.setPersonalDictionary(emptyList())
        assertEquals(corrected, committed(type(e, "hellp", mid), "hellp"))
        assertFalse(type(e, "omw", mid).candidates.any { it.text == "on my way" })
    }

    @Test fun `personal dictionary words are valid words and shortcuts offer their word`() = personalDictionaryBehaves(Fixtures.englishEngine())

    @Test fun `personal dictionary with the production language model`() = personalDictionaryBehaves(prodEngine())

    // ------------------------------------------------------------------ CO-07 from a fresh install

    /** CO-02/CO-07: a word kept once as typed is learned and no longer corrected. */
    private fun keptOnceIsLearned(fresh: () -> PboardEngine) {
        // Undoing the correction once (backspace revert, or retyping the original).
        var e = fresh()
        val corrected = committed(type(e, "hellp", mid), "hellp")
        assertTrue(corrected != "hellp")
        e.onWordCommitted(mid, corrected, false)
        e.onAutoCorrectReverted(mid, "hellp", corrected)
        e.onWordCommitted(mid, "hellp", true)
        assertEquals("hellp", committed(type(e, "hellp", mid), "hellp"))
        assertEquals("hellp", committed(type(e, "hellp", Context(listOf("I", "said"), false)), "hellp"), "in another context too")
        assertTrue(type(e, "hel", mid).candidates.any { it.text == "hellp" }, "offered from its first letters")
        // ... and after a restart.
        val restarted = fresh().also { assertTrue(it.importUserData(e.exportUserData())) }
        assertEquals("hellp", committed(type(restarted, "hellp", mid), "hellp"))

        // Picking the typed word from the strip once.
        e = fresh()
        e.onSuggestionPicked(mid, Candidate("hellp", 1f, CandidateKind.TYPED), "hellp")
        assertEquals("hellp", committed(type(e, "hellp", mid), "hellp"))

        // Leaving it in the field as typed once (sent without a space).
        e = fresh()
        e.onWordAbandoned(mid, "hellp")
        assertEquals("hellp", committed(type(e, "hellp", mid), "hellp"))

        // Nothing is learned where learning is off.
        e = fresh()
        val noLearn = mid.copy(learningAllowed = false)
        e.onAutoCorrectReverted(noLearn, "hellp", corrected)
        e.onWordAbandoned(noLearn, "hellp")
        assertEquals(corrected, committed(type(e, "hellp", mid), "hellp"))
    }

    @Test fun `CO-07 from a fresh install a word kept once as typed is not autocorrected the next time`() = keptOnceIsLearned { Fixtures.englishEngine() }

    @Test fun `CO-07 a word kept once is learned with the production language model`() {
        assumeTrue(File(lmDir, "en.lm").exists())
        keptOnceIsLearned { prodEngine() }
    }

    // ------------------------------------------------------------------ backup: strict decoding and merging

    private fun userModel(vararg words: Pair<String, Int>): ByteArray {
        val m = UserNgramModel(clock = { now })
        for ((w, n) in words) repeat(n) { m.observe(listOf("the"), w) }
        return ByteArrayOutputStream().also { m.save(it) }.toByteArray()
    }

    private fun count(lm: ByteArray?, word: String): Double =
        UserNgramModel(clock = { now }).also { it.load(ByteArrayInputStream(lm!!)) }.count(word)

    private fun data(words: Map<String, Int> = emptyMap(), offsets: Map<Char, FloatArray> = emptyMap(), suppressed: Set<String> = emptySet(),
                     rejected: Map<String, Set<String>> = emptyMap(), lm: ByteArray? = null) = UserDataCodec.Data(words, offsets, suppressed, rejected, lm)

    @Test fun `merging a backup unites both sides and is idempotent`() {
        val device = data(mapOf("zorbix" to 2, "quaxle" to 5), mapOf('a' to floatArrayOf(1f, 1f, 2f), 'b' to floatArrayOf(0f, 0f, 9f)),
            setOf("damn"), mapOf("hellp" to setOf("help")), userModel("zorbix" to 2, "quaxle" to 5))
        val backup = data(mapOf("zorbix" to 7, "wibble" to 1), mapOf('a' to floatArrayOf(3f, 3f, 5f), 'b' to floatArrayOf(4f, 4f, 1f)),
            setOf("heck"), mapOf("hellp" to setOf("hello"), "teh" to setOf("the")), userModel("zorbix" to 7, "wibble" to 1))
        val m = assertNotNull(UserDataCodec.merge(device, backup))
        assertEquals(mapOf("zorbix" to 7, "quaxle" to 5, "wibble" to 1), m.learnedWords)
        assertEquals(5f, m.touchOffsets.getValue('a')[2])
        assertEquals(9f, m.touchOffsets.getValue('b')[2])
        assertEquals(setOf("damn", "heck"), m.suppressed)
        assertEquals(mapOf("hellp" to setOf("help", "hello"), "teh" to setOf("the")), m.rejected)
        assertEquals(7.0, count(m.lmUser, "zorbix"), 1e-3)
        assertEquals(5.0, count(m.lmUser, "quaxle"), 1e-3)
        assertEquals(1.0, count(m.lmUser, "wibble"), 1e-3)
        // The same backup again changes nothing.
        val again = assertNotNull(UserDataCodec.merge(m, backup))
        assertEquals(m.learnedWords, again.learnedWords)
        assertEquals(m.suppressed, again.suppressed)
        assertEquals(m.rejected, again.rejected)
        assertEquals(7.0, count(again.lmUser, "zorbix"), 1e-3)
        // Encoded form, as the IME uses it; an empty base is "nothing learned yet".
        val bytes = assertNotNull(UserDataCodec.mergeEncoded(UserDataCodec.encode(device), UserDataCodec.encode(backup)))
        assertEquals(m.learnedWords, UserDataCodec.decode(bytes)!!.learnedWords)
        assertEquals(backup.learnedWords, UserDataCodec.decode(UserDataCodec.mergeEncoded(ByteArray(0), UserDataCodec.encode(backup))!!)!!.learnedWords)
    }

    @Test fun `a word removed on either side stays removed after a merge`() {
        val gone = UserNgramModel(clock = { now }).also { it.observe(listOf("the"), "wibble"); it.forget("wibble") }
        val device = data(lm = ByteArrayOutputStream().also { gone.save(it) }.toByteArray())
        val m = assertNotNull(UserDataCodec.merge(device, data(lm = userModel("wibble" to 3, "zorbix" to 1))))
        assertEquals(0.0, count(m.lmUser, "wibble"), 1e-9)
        assertEquals(1.0, count(m.lmUser, "zorbix"), 1e-3)
    }

    @Test fun `unreadable or hostile data is refused whole`() {
        val good = UserDataCodec.encode(data(mapOf("zorbix" to 2), lm = userModel("zorbix" to 2)))
        assertNotNull(UserDataCodec.decodeStrict(good))
        // Truncated anywhere, trailing bytes, random bytes.
        for (n in listOf(0, 3, 8, 20, good.size / 2, good.size - 1)) assertNull(UserDataCodec.decodeStrict(good.copyOf(n)), "truncated at $n")
        assertNull(UserDataCodec.decodeStrict(good + 0))
        assertNull(UserDataCodec.decodeStrict(ByteArray(4096) { (it * 31).toByte() }))
        // A newer format version.
        assertNull(UserDataCodec.decodeStrict(good.copyOf().also { it[7] = (UserDataCodec.FORMAT_VERSION + 1).toByte() }))
        // An absurd section count must not allocate or loop: refused.
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(0x50425544); writeInt(3); writeInt(Int.MAX_VALUE) } }.toByteArray()
        assertNull(UserDataCodec.decodeStrict(huge))
        // Bad numbers: a NaN touch offset; a nested LM state that is garbage, truncated, or carries a NaN / negative / huge count.
        assertNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(offsets = mapOf('a' to floatArrayOf(Float.NaN, 0f, 1f))))))
        val lm = userModel("zorbix" to 2)
        assertNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(lm = byteArrayOf(1, 2, 3)))))
        assertNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(lm = lm.copyOf(lm.size - 5)))))
        assertNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(lm = lm + 0))))
        fun lmWith(count: Double, entries: Int = 1) = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(0x5042554D); writeInt(1)
                writeInt(entries); writeUTF("w"); writeDouble(count); writeLong(0)
                writeInt(0); writeInt(0); writeInt(0)
            }
        }.toByteArray()
        assertNotNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(lm = lmWith(2.0)))))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1e300)) assertNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(lm = lmWith(bad)))), "$bad")
        assertNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(lm = lmWith(2.0, entries = -1)))))
        assertNull(UserDataCodec.decodeStrict(UserDataCodec.encode(data(lm = lmWith(2.0, entries = Int.MAX_VALUE)))))
        // Merging with an unreadable side changes nothing (null), whichever side it is.
        assertNull(UserDataCodec.mergeEncoded(good, good.copyOf(good.size - 1)))
        assertNull(UserDataCodec.mergeEncoded(good.copyOf(good.size - 1), good))
        assertNull(UserDataCodec.merge(data(lm = byteArrayOf(9)), data(lm = lm)))
    }

    @Test fun `a refused import leaves the engine as it was and a merged backup is used at once`() {
        val e = prodEngine()
        e.onAutoCorrectReverted(mid, "hellp", "help")
        val before = e.exportUserData()
        assertFalse(e.importUserData(before.copyOf(before.size - 3)))
        assertEquals("hellp", committed(type(e, "hellp", mid), "hellp"))

        // A backup from another device, merged: both devices' words are kept as typed.
        val other = prodEngine().also { it.onAutoCorrectReverted(mid, "becuase", "because") }
        val merged = assertNotNull(UserDataCodec.mergeEncoded(e.exportUserData(), other.exportUserData()))
        assertTrue(e.importUserData(merged))
        assertEquals("hellp", committed(type(e, "hellp", mid), "hellp"))
        assertEquals("becuase", committed(type(e, "becuase", mid), "becuase"))
        // Replacing instead leaves only the backup's.
        assertTrue(e.importUserData(other.exportUserData()))
        assertTrue(committed(type(e, "hellp", mid), "hellp") != "hellp")
        assertEquals("becuase", committed(type(e, "becuase", mid), "becuase"))
    }
}
