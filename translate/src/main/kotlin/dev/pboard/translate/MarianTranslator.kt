package dev.pboard.translate

import dev.pboard.translate.marian.MarianBinary
import dev.pboard.translate.marian.Shortlist
import dev.pboard.translate.nn.SsruTransformer
import dev.pboard.translate.spm.SentencePiece
import java.io.InputStream

/**
 * [Translator] for Marian SSRU models (Firefox Translations format): sentence splitting, SentencePiece, the int8
 * transformer with lexical shortlist and greedy search (Firefox's settings: beam 1, max length 2 × source).
 * Translations are cached per sentence, so retranslating a growing text only decodes the sentence being typed.
 */
class MarianTranslator(
    override val from: String,
    override val to: String,
    val model: SsruTransformer,
    private val srcVocab: SentencePiece,
    private val trgVocab: SentencePiece,
    private val shortlist: Shortlist?,
) : Translator {
    private val sharedVocab = srcVocab === trgVocab
    private val eos = trgVocab.id("</s>").takeIf { it != trgVocab.unkId } ?: trgVocab.eosId.coerceAtLeast(0)
    private val srcEos = srcVocab.id("</s>").takeIf { it != srcVocab.unkId } ?: srcVocab.eosId.coerceAtLeast(0)
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    init {
        if (model.trgVocabSize > trgVocab.size || model.srcVocabSize > srcVocab.size)
            throw TranslateFormatException("vocabulary smaller than the model's embeddings")
    }

    override fun translate(text: String, cancelled: () -> Boolean): String? {
        val sb = StringBuilder()
        for (seg in SentenceSplitter.split(text)) {
            if (seg.text.isNotEmpty()) sb.append(translateSentence(seg.text, cancelled) ?: return null)
            sb.append(seg.trailing)
        }
        return sb.toString()
    }

    /** Translates one sentence (no splitting). */
    fun translateSentence(sentence: String, cancelled: () -> Boolean = { false }): String? {
        cache[sentence]?.let { return it }
        val ids = srcVocab.encode(sentence)
        if (ids.isEmpty()) return ""
        // Like marian's max-length-break: very long inputs are translated in chunks of MAX_SRC tokens.
        val sb = StringBuilder()
        var p = 0
        while (p < ids.size) {
            val end = minOf(ids.size, p + MAX_SRC)
            val src = ids.copyOfRange(p, end) + srcEos
            val allowed = shortlist?.select(src, model.trgVocabSize, sharedVocab) ?: IntArray(model.trgVocabSize) { it }
            val enc = model.encode(src)
            val out = model.greedy(enc, allowed, eos, maxLength = 2 * src.size, cancelled) ?: return null
            val piece = trgVocab.decode(out)
            if (sb.isNotEmpty() && piece.isNotEmpty()) sb.append(' ')
            sb.append(piece)
            p = end
        }
        val res = sb.toString()
        cache[sentence] = res
        return res
    }

    companion object {
        const val MAX_SRC = 128

        fun load(storage: PackStorage, spec: ModelSpec): MarianTranslator {
            val bin = MarianBinary(readFully(storage, spec.model))
            val model = SsruTransformer(bin)
            val src = SentencePiece(readFully(storage, spec.srcVocab))
            val trg = if (spec.trgVocab == spec.srcVocab) src else SentencePiece(readFully(storage, spec.trgVocab))
            val sl = spec.shortlist?.let { Shortlist(readFully(storage, it)) }
            return MarianTranslator(spec.from, spec.to, model, src, trg, sl)
        }

        /** Reads a stream into an exactly sized array when the stream knows its length (files, assets). */
        internal fun readFully(storage: PackStorage, path: String): ByteArray = storage.open(path).use { readFully(it) }

        internal fun readFully(input: InputStream): ByteArray {
            var buf = ByteArray(maxOf(input.available(), 8192))
            var n = 0
            while (true) {
                if (n == buf.size) {
                    val b = input.read()
                    if (b < 0) return buf
                    buf = buf.copyOf(buf.size * 2)
                    buf[n++] = b.toByte()
                }
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) return if (n == buf.size) buf else buf.copyOf(n)
                n += r
            }
        }
    }
}
