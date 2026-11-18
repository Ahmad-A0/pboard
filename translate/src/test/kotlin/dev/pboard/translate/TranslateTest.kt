package dev.pboard.translate

import dev.pboard.translate.marian.MarianBinary
import dev.pboard.translate.marian.QMatrix
import dev.pboard.translate.marian.Shortlist
import dev.pboard.translate.nn.Ops
import dev.pboard.translate.nn.SsruTransformer
import dev.pboard.translate.spm.SentencePiece
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SentencePieceTest {
    private val tiny = SentencePiece(javaClass.getResourceAsStream("/spm/tiny.spm")!!.readBytes())

    // Reference ids from google/sentencepiece 0.2 (tools/translate/make_test_spm.py).
    private val cases = listOf(
        "Hello, how are you?" to intArrayOf(319, 260, 311, 284, 276, 305, 309, 261, 286, 270, 287),
        "  Ｈｅｌｌｏ  wörld…  ½ ﬁ" to intArrayOf(319, 260, 311, 284, 333, 198, 185, 272, 317, 263, 263, 263, 259, 52, 229, 132, 135, 53, 330, 280),
        "Tab\there nbsp and​zero-width" to intArrayOf(259, 416, 281, 389, 259, 332, 260, 259, 271, 389, 267, 283, 261, 275, 259, 125, 340, 284, 48, 309, 280, 274, 296),
        "Emoji 😀 and 中文" to intArrayOf(259, 72, 289, 284, 410, 280, 259, 243, 162, 155, 131, 261, 275, 259, 231, 187, 176, 233, 153, 138),
        "á and Å" to intArrayOf(259, 198, 164, 261, 275, 259, 198, 136),
        "__source__ x" to intArrayOf(259, 2, 259, 351),
        "   " to intArrayOf(),
        "line1\nline2" to intArrayOf(259, 338, 386, 260, 52, 259, 338, 386, 260, 53),
    )

    @Test fun matchesReferenceEncoder() {
        for ((text, ids) in cases) assertContentEquals(ids, tiny.encode(text), "encode '$text'")
    }

    @Test fun decodeRoundTrip() {
        assertEquals("Hello, how are you?", tiny.decode(tiny.encode("Hello, how are you?")))
        // byte-fallback pieces are reassembled into UTF-8
        assertEquals("Emoji 😀 and 中文", tiny.decode(tiny.encode("Emoji 😀 and 中文")))
        // NFKC + whitespace normalisation
        assertEquals("Hello wörld... 1⁄2 fi", tiny.decode(tiny.encode("  Ｈｅｌｌｏ  wörld…  ½ ﬁ")))
        assertEquals("", tiny.decode(intArrayOf(0))) // EOS (control) is dropped
    }

    @Test fun specialIds() {
        assertEquals(0, tiny.eosId)
        assertEquals(1, tiny.unkId)
        assertEquals(0, tiny.id("</s>"))
    }

    @Test fun rejectsGarbage() {
        assertFailsWith<TranslateFormatException> { SentencePiece(byteArrayOf(0x0a, 0x7f, 1, 2)) }
        assertFailsWith<TranslateFormatException> { SentencePiece(ByteArray(0)) }
    }
}

class PackManifestTest {
    private val json = """
        { "format": 1, "id": "es", "version": 3, "name": "Spanish", "license": "MPL-2.0",
          "attribution": "Firefox Translations", "extra": {"ignored": [1, 2.5e3, true, null, "é\n"]},
          "models": [
            { "from": "en", "to": "es", "type": "marian-ssru", "model": "en-es/model.bin", "vocab": "en-es/vocab.spm",
              "shortlist": "en-es/lex.bin", "size": 36576277 },
            { "from": "es", "to": "en", "model": "es-en/model.bin", "srcVocab": "es-en/s.spm", "trgVocab": "es-en/t.spm" } ] }
    """.trimIndent()

    @Test fun parses() {
        val m = PackManifest.parse(json)
        assertEquals("es", m.id); assertEquals(3, m.version); assertEquals("MPL-2.0", m.license)
        assertEquals(setOf("en", "es"), m.languages)
        val enEs = m.model("en", "es")!!
        assertEquals(listOf("en-es/model.bin", "en-es/vocab.spm", "en-es/lex.bin"), enEs.files)
        assertEquals(36576277L, enEs.size)
        val esEn = m.model("es", "en")!!
        assertEquals("es-en/t.spm", esEn.trgVocab); assertNull(esEn.shortlist); assertEquals(ModelSpec.TYPE_MARIAN, esEn.type)
        assertNull(m.model("en", "fr"))
    }

    @Test fun rejectsUnsafeOrBroken() {
        for (bad in listOf(
            json.replace("en-es/model.bin", "../model.bin"),
            json.replace("en-es/model.bin", "/etc/passwd"),
            json.replace("en-es/model.bin", "a//b"),
            json.replace("\"format\": 1", "\"format\": 2"),
            json.replace("\"id\": \"es\"", "\"id\": \"../x\""),
            json.replace("\"from\": \"en\"", "\"from\": \"es\""),
            json.dropLast(1), "[]", "{\"format\":1,\"id\":\"x\",\"models\":[]}", "{} x",
        )) assertFailsWith<TranslateFormatException>(bad) { PackManifest.parse(bad) }
    }
}

class SentenceSplitterTest {
    private fun split(s: String) = SentenceSplitter.split(s)

    @Test fun splitsSentencesKeepingWhitespace() {
        val segs = split("Hello there. How are you?  I'm fine!\nThanks")
        assertEquals(listOf("Hello there.", "How are you?", "I'm fine!", "Thanks"), segs.map { it.text })
        assertEquals(listOf(" ", "  ", "\n", ""), segs.map { it.trailing })
    }

    @Test fun keepsAbbreviationsAndInitials() {
        assertEquals(1, split("Mr. Smith met Dr. Jones at 5 p.m. today.").count { it.text.isNotEmpty() })
        assertEquals(1, split("J. R. R. Tolkien wrote it.").count { it.text.isNotEmpty() })
        assertEquals(1, split("Prices rose by 3.5 percent.").count { it.text.isNotEmpty() })
        assertEquals(2, split("¿Qué tal? ¡Muy bien!").count { it.text.isNotEmpty() })
        assertEquals(1, split("it is fine. and lowercase continues").count { it.text.isNotEmpty() })
    }

    @Test fun roundTrips() {
        for (s in listOf("", "   ", "  lead", "trail  ", "a.\n\nB. c", "One. Two.\r\nThree…  Four?! \"Five.\" Six", "\n\nx\n")) {
            assertEquals(s, split(s).joinToString("") { it.text + it.trailing }, "round trip of '$s'")
        }
    }
}

class OpsTest {
    private val r = Random(7)

    @Test fun int8LinearApproximatesFloat() {
        val m = 3; val k = 64; val n = 10
        val w = ByteArray(n * k) { (r.nextInt(255) - 127).toByte() }
        val scale = 0.01f
        val qm = QMatrix(w, 0, n, k, scale)
        val x = FloatArray(m * k) { r.nextFloat() - 0.5f }
        val bias = FloatArray(n) { it.toFloat() }
        val xq = ByteArray(m * k); val inv = FloatArray(m); val y = FloatArray(m * n)
        Ops.quantizeRows(x, m, k, xq, inv)
        Ops.linear(xq, inv, m, qm, bias, y)
        for (i in 0 until m) for (j in 0 until n) {
            var exact = bias[j]
            for (c in 0 until k) exact += x[i * k + c] * w[j * k + c] * scale
            assertEquals(exact, y[i * n + j], 0.05f)
        }
    }

    @Test fun normAndSoftmax() {
        val x = floatArrayOf(1f, 2f, 3f, 4f); val zero = FloatArray(4)
        Ops.addNorm(x, zero, 1, 4, FloatArray(4) { 1f }, FloatArray(4))
        assertEquals(0f, x.sum(), 1e-5f)
        assertEquals(4f, x.sumOf { (it * it).toDouble() }.toFloat(), 1e-3f)
        val s = floatArrayOf(1f, 1f, 1000f)
        Ops.softmax(s, 0, 3)
        assertEquals(1f, s[2], 1e-6f)
    }
}

class ModelTest {
    private val vocab = SentencePiece(javaClass.getResourceAsStream("/spm/tiny.spm")!!.readBytes())

    private fun translator(shortlist: Shortlist? = null): MarianTranslator {
        val model = SsruTransformer(MarianBinary(MarianWriter.randomSsru(vocab.size)))
        return MarianTranslator("en", "xx", model, vocab, vocab, shortlist)
    }

    @Test fun readsMarianBinary() {
        val bin = MarianBinary(MarianWriter.randomSsru(vocab.size, dim = 16, ffn = 32))
        val w1 = bin.weight("encoder_l1_ffn_W1")
        assertEquals(32, w1.rows); assertEquals(16, w1.cols); assertEquals(0.3f / 127f, w1.scale, 1e-9f)
        assertEquals(vocab.size, bin.embedding("Wemb").rows)
        assertEquals("ssru", bin.config()["dec-cell"])
        assertEquals("", bin.config()["transformer-preprocess"])
        assertFailsWith<TranslateFormatException> { bin.weight("decoder_ff_logit_out_b") }
        assertFailsWith<TranslateFormatException> { bin.floats("nope") }
    }

    @Test fun rejectsTruncatedOrForeignModels() {
        val good = MarianWriter.randomSsru(vocab.size)
        assertFailsWith<TranslateFormatException> { MarianBinary(good.copyOf(good.size / 2)) }
        assertFailsWith<TranslateFormatException> { MarianBinary(ByteArray(64)) }
        val aan = MarianWriter().text("special:model.yml", "type: transformer\ntransformer-decoder-autoreg: average-attention\ndim-emb: 16").build()
        assertFailsWith<TranslateFormatException> { SsruTransformer(MarianBinary(aan)) }
    }

    @Test fun translatesDeterministicallyAndCaches() {
        val t = translator()
        val a = t.translate("Hello, how are you? See you tomorrow.")
        assertNotNull(a)
        assertEquals(a, translator().translate("Hello, how are you? See you tomorrow."))
        assertEquals(a, t.translate("Hello, how are you? See you tomorrow."))
        assertEquals("", t.translate(""))
        assertEquals("  \n", t.translate("  \n"))
    }

    @Test fun cancellationStopsDecoding() {
        val t = translator()
        assertNull(t.translate("Where are you right now?") { true })
        assertNotNull(t.translate("Where are you right now?") { false })
    }

    @Test fun outputIsLimitedToShortlist() {
        val sl = Shortlist(MarianWriter.shortlist(firstNum = 3, lists = List(vocab.size) { intArrayOf(300 + it % 5) }))
        val allowed = sl.select(intArrayOf(10, 11, 0), vocab.size, sharedVocab = false)
        assertContentEquals(intArrayOf(0, 1, 2, 300, 301), allowed)
        assertContentEquals(intArrayOf(0, 1, 2, 10, 300, 301), sl.select(intArrayOf(10, 11), vocab.size, sharedVocab = true).filter { it != 11 }.toIntArray())
        val t = translator(sl)
        val src = vocab.encode("Hi!") + 0
        val out = t.model.greedy(t.model.encode(src), sl.select(src, vocab.size, false), 0, 20)!!
        val ok = sl.select(src, vocab.size, false).toSet()
        assertTrue(out.all { it in ok })
        assertFailsWith<TranslateFormatException> { Shortlist(ByteArray(48)) }
    }
}
