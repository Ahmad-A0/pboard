package dev.pboard.engine.decode

import dev.pboard.engine.Context
import dev.pboard.engine.devlm.DevData
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.UserNgramModel
import dev.pboard.engine.testutil.Layouts
import dev.pboard.engine.testutil.Touches
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
 * Durability of the learned data across app updates: every format version ever written must stay readable.
 *
 * engine/src/test/resources/userdata/ holds FROZEN files, one per format version, written by the code of that version
 * (format 1 by its original encoder, kept below as [encodeV1]; format 2 and user model 1 by the engine itself, see
 * [writeSamples]). Never regenerate or edit an existing sample: when a format changes, bump its FORMAT_VERSION, add a
 * migration, and add the new version's sample next to the old ones; `every format version has a frozen sample`
 * enforces the last step.
 */
class UserDataFormatTest {
    private val lmDir = File(DevData.projectRoot(), "app/src/main/assets/lm")
    private val mid = Context(listOf("I", "saw", "a"), false)

    private fun sample(name: String): ByteArray =
        javaClass.getResourceAsStream("/userdata/$name")?.readBytes() ?: error("missing sample /userdata/$name")

    /** A user model whose clock is frozen at the samples' time stamp, so decayed counts are exact. */
    private fun frozenUser() = UserNgramModel(clock = { STAMP })

    private fun prodEngine(user: UserNgramModel): PboardEngine {
        assumeTrue(File(lmDir, "en.lm").exists())
        return PboardEngine.create(listOf(EnglishPack.load("en-GB", lmDir, user).spec())).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }

    @Test fun `every format version has a frozen sample`() {
        for (v in 1..UserDataCodec.FORMAT_VERSION) assertNotNull(javaClass.getResource("/userdata/user_data_v$v.bin"), "user_data_v$v.bin")
        for (v in 1..UserNgramModel.FORMAT_VERSION) assertNotNull(javaClass.getResource("/userdata/user_model_v$v.bin"), "user_model_v$v.bin")
    }

    @Test fun `format 1 decodes`() {
        val bytes = sample("user_data_v1.bin")
        assertEquals(1, UserDataCodec.versionOf(bytes))
        val d = assertNotNull(UserDataCodec.decode(bytes))
        assertEquals(V1_WORDS, d.learnedWords)
        assertEquals(setOf('h', 'e'), d.touchOffsets.keys)
        assertEquals(0.25f, d.touchOffsets.getValue('h')[0])
        assertEquals(setOf("tomorrow"), d.suppressed)
        assertEquals(mapOf("hellp" to setOf("help")), d.rejected)
        assertNull(d.lmUser)
    }

    @Test fun `format 2 decodes and survives a re-encode`() {
        val bytes = sample("user_data_v2.bin")
        assertEquals(2, UserDataCodec.versionOf(bytes))
        val d = assertNotNull(UserDataCodec.decode(bytes))
        assertTrue("tomorrow" in d.suppressed)
        assertEquals(setOf("help"), d.rejected["hellp"])
        assertTrue(d.touchOffsets.isNotEmpty())
        val lm = assertNotNull(d.lmUser)
        val again = assertNotNull(UserDataCodec.decode(UserDataCodec.encode(d)))
        assertEquals(d.learnedWords, again.learnedWords)
        assertEquals(d.suppressed, again.suppressed)
        assertEquals(d.rejected, again.rejected)
        assertTrue(lm.contentEquals(again.lmUser))
    }

    @Test fun `user model format 1 loads`() {
        val u = frozenUser()
        u.load(ByteArrayInputStream(sample("user_model_v1.bin")))
        assertEquals(3.0, u.count("zorbix"), 1e-9)
        assertEquals(2.0, u.count("flerbington"), 1e-9)
        assertTrue(u.prob(listOf("I", "saw"), "zorbix") > u.prob(listOf("I", "saw"), "flerbington"))
        assertEquals(listOf("zorbix"), u.candidates(listOf("saw"), 3))
    }

    @Test fun `the engine imports format 1 and moves its learned words into the LM user model`() {
        val user = frozenUser()
        val e = prodEngine(user)
        assertTrue(e.importUserData(sample("user_data_v1.bin")))
        assertEquals(4.0, user.count("zorbix"), 1e-9)
        assertEquals(2.0, user.count("flerbington"), 1e-9)
        assertTrue(e.userVocabulary.snapshot().isEmpty(), "migrated words leave the legacy store")
        assertTrue(type(e, "zorb", mid).candidates.take(3).any { it.text == "zorbix" })
        assertFalse(type(e, "tomorow", mid).candidates.any { it.text == "tomorrow" }, "removed suggestion stays removed")
        // Idempotent: the re-export carries the words only in the LM state, and importing it again changes nothing.
        val reexport = e.exportUserData()
        assertEquals(UserDataCodec.FORMAT_VERSION, UserDataCodec.versionOf(reexport))
        val user2 = frozenUser()
        assertTrue(prodEngine(user2).importUserData(reexport))
        assertEquals(4.0, user2.count("zorbix"), 1e-9)
    }

    @Test fun `the engine imports format 2`() {
        val user = frozenUser()
        val e = prodEngine(user)
        assertTrue(e.importUserData(sample("user_data_v2.bin")))
        assertEquals(4.0, user.count("zorbix"), 1e-9)
        assertTrue(type(e, "zorb", mid).candidates.take(3).any { it.text == "zorbix" })
        assertFalse(type(e, "tomorow", mid).candidates.any { it.text == "tomorrow" })
    }

    /** docs/app-compat.md #3: data written before sentence-start capitals stopped being learned is repaired once. */
    @Test fun `format 2 data has its sentence-start capitals folded once`() {
        fun export(version: Int, fill: (UserNgramModel) -> Unit): ByteArray {
            val u = frozenUser().also(fill)
            val lm = ByteArrayOutputStream().also { u.save(it) }.toByteArray()
            return UserDataCodec.encode(UserDataCodec.Data(emptyMap(), emptyMap(), emptySet(), emptyMap(), lm))
                .also { it[7] = version.toByte() }
        }
        val bos = listOf(UserNgramModel.BOS)
        val old = export(2) { u ->
            repeat(5) { u.observe(bos, "Hello"); u.observe(listOf(UserNgramModel.BOS, "Hello"), "world") }
            repeat(2) { u.observe(listOf("I", "say"), "hello") }
            repeat(3) { u.observe(bos, "Monday") }                       // no lower-case word: kept
            u.observe(bos, "Bill"); u.observe(listOf("met"), "Bill")     // seen mid-sentence: kept
            repeat(2) { u.observe(bos, "Mark") }                         // only at sentence starts: folded ("mark")
        }
        val user = frozenUser()
        val e = prodEngine(user)
        assertTrue(e.importUserData(old))
        assertEquals(0.0, user.count("Hello"))
        assertEquals(7.0, user.count("hello"), 1e-9)
        assertEquals(3.0, user.count("Monday"), 1e-9)
        assertEquals(2.0, user.count("Bill"), 1e-9)
        assertEquals(0.0, user.count("Mark"))
        assertEquals(2.0, user.count("mark"), 1e-9)
        assertEquals("hello", committed(type(e, "hello", Context(listOf("Because"), false)), "hello"))
        // The re-export is format 3 and is not folded again; nor is data already in format 3.
        assertEquals(UserDataCodec.FORMAT_VERSION, UserDataCodec.versionOf(e.exportUserData()))
        val user3 = frozenUser()
        assertTrue(prodEngine(user3).importUserData(export(3) { u -> u.observe(bos, "Hello") }))
        assertEquals(1.0, user3.count("Hello"), 1e-9)
    }

    @Test fun `newer or corrupt data is refused and leaves the engine unchanged`() {
        val user = frozenUser()
        val e = prodEngine(user)
        assertTrue(e.importUserData(sample("user_data_v2.bin")))
        val before = e.exportUserData()
        val newer = sample("user_data_v2.bin").copyOf().also { it[7] = (UserDataCodec.FORMAT_VERSION + 1).toByte() }
        assertEquals(UserDataCodec.FORMAT_VERSION + 1, UserDataCodec.versionOf(newer))
        assertFalse(e.importUserData(newer))
        assertFalse(e.importUserData(byteArrayOf(1, 2, 3)))
        // A known container around a user model from the future: refused as a whole, nothing half-applied.
        val d = assertNotNull(UserDataCodec.decode(sample("user_data_v2.bin")))
        val futureLm = d.lmUser!!.copyOf().also { it[7] = (UserNgramModel.FORMAT_VERSION + 1).toByte() }
        assertFalse(e.importUserData(UserDataCodec.encode(d.copy(learnedWords = mapOf("qwzx" to 9), suppressed = emptySet(), lmUser = futureLm))))
        val a = UserDataCodec.decode(before)!!
        val b = UserDataCodec.decode(e.exportUserData())!!
        assertEquals(a.learnedWords, b.learnedWords)
        assertEquals(a.suppressed, b.suppressed)
        assertEquals(a.rejected, b.rejected)
        assertEquals(4.0, user.count("zorbix"), 1e-9)
        assertEquals(0.0, user.count("qwzx"))
    }

    /**
     * Writes the CURRENT version's samples if they do not exist yet (older ones are never touched):
     * PBOARD_WRITE_USERDATA_SAMPLES=engine/src/test/resources/userdata ./gradlew :engine:test --tests '*UserDataFormatTest*'
     */
    @Test fun writeSamples() {
        val dir = System.getenv("PBOARD_WRITE_USERDATA_SAMPLES")?.let { File(DevData.projectRoot(), it) } ?: return
        dir.mkdirs()
        fun write(name: String, bytes: () -> ByteArray) {
            val f = File(dir, name)
            if (!f.exists()) f.writeBytes(bytes())
        }
        write("user_data_v1.bin") {
            encodeV1(V1_WORDS, linkedMapOf('h' to floatArrayOf(0.25f, -0.5f, 7f), 'e' to floatArrayOf(-0.125f, 0.75f, 3f)),
                linkedSetOf("tomorrow"), linkedMapOf("hellp" to linkedSetOf("help")))
        }
        write("user_model_v${UserNgramModel.FORMAT_VERSION}.bin") {
            val u = frozenUser()
            repeat(3) { u.observe(listOf("I", "saw"), "zorbix") }
            repeat(2) { u.observe(listOf(UserNgramModel.BOS), "flerbington") }
            ByteArrayOutputStream().also { u.save(it) }.toByteArray()
        }
        write("user_data_v${UserDataCodec.FORMAT_VERSION}.bin") {
            // What the production engine exports after some typing.
            val e = prodEngine(frozenUser())
            repeat(4) { e.onWordCommitted(mid, "zorbix", true) }
            repeat(30) {
                e.suggestForTaps(mid, Touches.taps("hello", dx = 0.3f), "hello")
                e.onWordCommitted(mid, "hello", true)
            }
            e.removeSuggestion("tomorrow")
            e.onAutoCorrectReverted(mid, "hellp", "help")
            e.exportUserData()
        }
    }

    companion object {
        /** Time stamp of the samples' user-model entries (2026-11-08T00:00:00Z). */
        const val STAMP = 1_794_096_000_000L
        val V1_WORDS = linkedMapOf("zorbix" to 4, "flerbington" to 2)

        /** The original format 1 encoder (commit 1c5bf41), frozen here because the codec no longer writes it. */
        fun encodeV1(words: Map<String, Int>, offsets: Map<Char, FloatArray>, suppressed: Set<String>, rejected: Map<String, Set<String>>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x50425544); out.writeInt(1)
                out.writeInt(words.size)
                for ((w, c) in words) { out.writeUTF(w); out.writeInt(c) }
                out.writeInt(offsets.size)
                for ((k, v) in offsets) { out.writeChar(k.code); out.writeFloat(v[0]); out.writeFloat(v[1]); out.writeFloat(v[2]) }
                out.writeInt(suppressed.size)
                for (w in suppressed) out.writeUTF(w)
                out.writeInt(rejected.size)
                for ((o, cs) in rejected) { out.writeUTF(o); out.writeInt(cs.size); for (c in cs) out.writeUTF(c) }
            }
            return bytes.toByteArray()
        }
    }
}
