package dev.pboard.translate

import dev.pboard.translate.spm.SentencePiece
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tests against real Firefox Translations models; skipped unless the model directory is given:
 * `./gradlew :translate:test -PtranslateModels=<dir>` with <dir>/en-es/{model.enes.intgemm.alphas.bin,
 * vocab.enes.spm, lex.50.50.enes.s2t.bin} (the base-memory en-es release, see docs/translate.md).
 */
class TranslatorModelTest {
    private val root = System.getProperty("pboard.translateModels")?.let(::File)
    private fun dir(pair: String): File {
        val d = root?.let { File(it, pair) }
        assumeTrue("set -PtranslateModels to run", d != null && d.isDirectory)
        return d!!
    }

    @Test fun realVocabularyMatchesSentencePiece() {
        val spm = SentencePiece(File(dir("en-es"), "vocab.enes.spm").readBytes())
        // Reference ids from google/sentencepiece 0.2 for the en-es vocabulary.
        val cases = listOf(
            "Hello, how are you?" to intArrayOf(20099, 263, 538, 306, 298, 307),
            "  Ｈｅｌｌｏ  wörld…  ½ ﬁ" to intArrayOf(20099, 4849, 202, 189, 326, 5635, 366, 391, 233, 136, 139, 482, 4655),
            "Tab\there nbsp and​zero-width" to intArrayOf(21741, 709, 3510, 471, 6355, 272, 11269, 287, 5392, 341, 595),
            "Emoji 😀 and 中文 text" to intArrayOf(484, 779, 3048, 278, 247, 166, 159, 135, 272, 278, 235, 191, 180, 237, 157, 142, 2880),
            "¿Dónde está la estación? Ça va très bien, merci!" to intArrayOf(347, 6569, 364, 269, 3527, 307, 278, 202, 142, 321, 922, 278, 5865, 202, 175, 266, 613, 263, 15027, 449, 372),
            "Straße, Ärger, Œuvre — “quotes” «guillemets»" to intArrayOf(7078, 202, 166, 340, 263, 278, 202, 139, 326, 2332, 263, 278, 204, 153, 11323, 369, 729, 320, 5308, 8354, 266, 387, 827, 4123, 5062, 4359, 266, 1593),
            "á combining and Å angstrom" to intArrayOf(7623, 18461, 272, 278, 202, 140, 268, 2419, 4159, 374),
            "line1\nline2\r\nline3" to intArrayOf(1271, 626, 1271, 482, 1271, 671),
            "__source__ special __target__" to intArrayOf(278, 2, 1340, 278, 3),
        )
        for ((text, ids) in cases) assertContentEquals(ids, spm.encode(text), "encode '$text'")
        assertEquals("Hello wörld... 1⁄2 fi", spm.decode(spm.encode("  Ｈｅｌｌｏ  wörld…  ½ ﬁ")))
    }

    @Test fun translatesEnglishToSpanish() {
        val t = loadModelDir(dir("en-es"))
        assertEquals("Me gustaría reservar una mesa para dos personas esta noche.",
            t.translate("I would like to book a table for two people tonight."))
        assertEquals("¿Puedes enviarme la dirección del restaurante? Estaré allí en diez minutos.",
            t.translate("Can you send me the address of the restaurant? I'll be there in ten minutes."))
    }
}
