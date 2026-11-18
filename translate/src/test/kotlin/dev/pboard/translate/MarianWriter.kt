package dev.pboard.translate

import dev.pboard.translate.marian.MarianBinary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Writes Marian binary models for tests (same layout as marian's saveItems). */
class MarianWriter {
    private class Item(val name: String, val type: Long, val shape: IntArray, val bytes: ByteArray)
    private val items = ArrayList<Item>()

    fun floats(name: String, shape: IntArray, v: FloatArray) = apply {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        items.add(Item(name, MarianBinary.TYPE_FLOAT32, shape, pad(b.array())))
    }

    /** int8 values exactly as stored (already transposed for weights), followed by the quantisation multiplier. */
    fun int8(name: String, shape: IntArray, v: ByteArray, quantMult: Float) = apply {
        val b = ByteBuffer.allocate(v.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put(v); b.putFloat(quantMult)
        items.add(Item(name, MarianBinary.TYPE_INTGEMM8, shape, pad(b.array())))
    }

    fun text(name: String, s: String) = apply {
        val b = s.toByteArray() + 0
        items.add(Item(name, MarianBinary.TYPE_INT8, intArrayOf(b.size), b))
    }

    private fun pad(b: ByteArray): ByteArray = b.copyOf((b.size + 255) / 256 * 256)

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        val bb = { n: Int -> ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN) }
        out.write(bb(16).putLong(1).putLong(items.size.toLong()).array())
        for (it in items) out.write(bb(32).putLong(it.name.length + 1L).putLong(it.type).putLong(it.shape.size.toLong()).putLong(it.bytes.size.toLong()).array())
        for (it in items) { out.write(it.name.toByteArray()); out.write(0) }
        for (it in items) { val b = bb(it.shape.size * 4); it.shape.forEach { d -> b.putInt(d) }; out.write(b.array()) }
        val pos = out.size().toLong()
        val next = ((pos + 8) / 256 + 1) * 256
        val pad = next - pos - 8
        out.write(bb(8).putLong(pad).array())
        out.write(ByteArray(pad.toInt()))
        for (it in items) out.write(it.bytes)
        return out.toByteArray()
    }

    companion object {
        /** A random SSRU transformer with the Firefox Translations layout (tied embeddings, post-norm). */
        fun randomSsru(vocab: Int, dim: Int = 16, heads: Int = 2, ffn: Int = 32, enc: Int = 2, dec: Int = 2, seed: Int = 1): ByteArray {
            val r = Random(seed)
            val w = MarianWriter()
            fun q(n: Int) = ByteArray(n) { (r.nextInt(255) - 127).toByte() }
            fun f(n: Int, scale: Float = 0.1f) = FloatArray(n) { (r.nextFloat() - 0.5f) * 2 * scale }
            fun mat(name: String, k: Int, n: Int) { w.int8(name, intArrayOf(k, n), q(k * n), 127f / 0.3f) }
            fun vec(name: String, n: Int, base: Float = 0f) { w.floats(name, intArrayOf(1, n), f(n).map { it + base }.toFloatArray()) }
            w.text("special:model.yml", """
                type: transformer
                dim-emb: $dim
                transformer-heads: $heads
                transformer-dim-ffn: $ffn
                enc-depth: $enc
                dec-depth: $dec
                dec-cell: ssru
                transformer-decoder-autoreg: rnn
                transformer-postprocess: dan
                transformer-preprocess: ""
                transformer-ffn-activation: relu
                transformer-ffn-depth: 2
                tied-embeddings-all: true
                dim-vocabs:
                  - $vocab
                  - $vocab
            """.trimIndent())
            w.int8("Wemb", intArrayOf(vocab, dim), q(vocab * dim), 127f / 0.5f)
            vec("decoder_ff_logit_out_b", vocab)
            fun attn(p: String) {
                for (x in listOf("q", "k", "v", "o")) { mat("${p}_W$x", dim, dim); vec("${p}_b$x", dim) }
                vec("${p}_Wo_ln_scale", dim, 1f); vec("${p}_Wo_ln_bias", dim)
            }
            fun ffn(p: String) {
                mat("${p}_W1", dim, ffn); vec("${p}_b1", ffn); mat("${p}_W2", ffn, dim); vec("${p}_b2", dim)
                vec("${p}_ffn_ln_scale", dim, 1f); vec("${p}_ffn_ln_bias", dim)
            }
            for (l in 1..enc) { attn("encoder_l${l}_self"); ffn("encoder_l${l}_ffn") }
            for (l in 1..dec) {
                val p = "decoder_l${l}_rnn"
                mat("${p}_W", dim, dim); mat("${p}_Wf", dim, dim); vec("${p}_bf", dim)
                vec("${p}_ffn_ln_scale", dim, 1f); vec("${p}_ffn_ln_bias", dim)
                attn("decoder_l${l}_context"); ffn("decoder_l${l}_ffn")
            }
            return w.build()
        }

        /** A binary shortlist (marian's format): firstNum frequent words, then per-source-word target lists. */
        fun shortlist(firstNum: Int, lists: List<IntArray>): ByteArray {
            val total = lists.sumOf { it.size }
            val b = ByteBuffer.allocate(48 + (lists.size + 1) * 8 + total * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putLong(dev.pboard.translate.marian.Shortlist.MAGIC).putLong(0).putLong(firstNum.toLong()).putLong(50)
            b.putLong(lists.size + 1L).putLong(total.toLong())
            var o = 0L
            for (l in lists) { b.putLong(o); o += l.size }
            b.putLong(o)
            for (l in lists) l.forEach { b.putInt(it) }
            return b.array()
        }
    }
}
