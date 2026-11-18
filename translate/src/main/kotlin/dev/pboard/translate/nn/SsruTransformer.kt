package dev.pboard.translate.nn

import dev.pboard.translate.TranslateFormatException
import dev.pboard.translate.marian.MarianBinary
import dev.pboard.translate.marian.QMatrix
import kotlin.math.sqrt

/**
 * Inference for Marian "transformer" models with an SSRU (simpler simple recurrent unit) decoder: the architecture of
 * the Firefox Translations / Bergamot student models (tiny, base-memory, base). Post-norm transformer encoder,
 * decoder layers of SSRU → cross-attention → FFN, output layer tied to the target embeddings, greedy search.
 *
 * Re-implemented from the published architecture and marian's src/models/transformer.h and src/rnn/cells.h (MIT); see
 * NOTICE. Weights stay int8 as stored in the model file (≈ 1 byte per parameter in memory).
 */
class SsruTransformer(private val bin: MarianBinary) {
    val dim: Int
    val heads: Int
    val ffnDim: Int
    val encDepth: Int
    val decDepth: Int
    val srcVocabSize: Int
    val trgVocabSize: Int

    private val srcEmb: QMatrix
    private val trgEmb: QMatrix
    private val outW: QMatrix // rows = target words
    private val outB: FloatArray?

    private class Attn(val wq: QMatrix, val bq: FloatArray, val wk: QMatrix, val bk: FloatArray, val wv: QMatrix, val bv: FloatArray,
                       val wo: QMatrix, val bo: FloatArray, val lnScale: FloatArray, val lnBias: FloatArray)
    private class Ffn(val w1: QMatrix, val b1: FloatArray, val w2: QMatrix, val b2: FloatArray, val lnScale: FloatArray, val lnBias: FloatArray)
    private class Ssru(val w: QMatrix, val wf: QMatrix, val bf: FloatArray, val lnScale: FloatArray, val lnBias: FloatArray)

    private val enc: List<Pair<Attn, Ffn>>
    private val dec: List<Triple<Ssru, Attn, Ffn>>

    init {
        val cfg = bin.config()
        fun req(key: String, expected: String) {
            val v = cfg[key] ?: return
            if (v != expected) throw TranslateFormatException("unsupported model: $key=$v (need $expected)")
        }
        req("type", "transformer")
        req("transformer-decoder-autoreg", "rnn")
        req("dec-cell", "ssru")
        req("transformer-postprocess", "dan")
        req("transformer-preprocess", "")
        req("transformer-ffn-activation", "relu")
        req("transformer-ffn-depth", "2")
        req("transformer-train-position-embeddings", "false")
        req("transformer-no-projection", "false")
        dim = cfg["dim-emb"]?.toIntOrNull() ?: throw TranslateFormatException("model.yml lacks dim-emb")
        heads = cfg["transformer-heads"]?.toIntOrNull() ?: 8
        ffnDim = cfg["transformer-dim-ffn"]?.toIntOrNull() ?: 2048
        encDepth = cfg["enc-depth"]?.toIntOrNull() ?: 6
        decDepth = cfg["dec-depth"]?.toIntOrNull() ?: 2
        if (dim % heads != 0 || dim % 2 != 0) throw TranslateFormatException("bad dims")
        val tiedAll = cfg["tied-embeddings-all"] == "true"
        val tiedSrc = cfg["tied-embeddings-src"] == "true"
        val tied = cfg["tied-embeddings"] == "true" || tiedAll
        srcEmb = bin.embedding(if (tiedAll || tiedSrc) "Wemb" else if (bin.has("encoder_Wemb")) "encoder_Wemb" else "Wemb")
        trgEmb = bin.embedding(if (tiedAll || tiedSrc) "Wemb" else if (bin.has("decoder_Wemb")) "decoder_Wemb" else "Wemb")
        outW = if (tied) trgEmb else transposedOutput()
        outB = if (bin.has("decoder_ff_logit_out_b")) bin.floats("decoder_ff_logit_out_b") else null
        srcVocabSize = srcEmb.rows
        trgVocabSize = outW.rows
        if (srcEmb.cols != dim || trgEmb.cols != dim || outW.cols != dim) throw TranslateFormatException("embedding width != dim-emb")
        if (outB != null && outB.size != trgVocabSize) throw TranslateFormatException("output bias size")

        fun attn(p: String) = Attn(
            w(p + "_Wq", dim, dim), bin.floats(p + "_bq"), w(p + "_Wk", dim, dim), bin.floats(p + "_bk"),
            w(p + "_Wv", dim, dim), bin.floats(p + "_bv"), w(p + "_Wo", dim, dim), bin.floats(p + "_bo"),
            bin.floats(p + "_Wo_ln_scale"), bin.floats(p + "_Wo_ln_bias"))
        fun ffn(p: String) = Ffn(w(p + "_W1", dim, ffnDim), bin.floats(p + "_b1"), w(p + "_W2", ffnDim, dim), bin.floats(p + "_b2"),
            bin.floats(p + "_ffn_ln_scale"), bin.floats(p + "_ffn_ln_bias"))
        enc = (1..encDepth).map { attn("encoder_l${it}_self") to ffn("encoder_l${it}_ffn") }
        dec = (1..decDepth).map {
            val p = "decoder_l${it}_rnn"
            Triple(Ssru(w(p + "_W", dim, dim), w(p + "_Wf", dim, dim), bin.floats(p + "_bf"), bin.floats(p + "_ffn_ln_scale"), bin.floats(p + "_ffn_ln_bias")),
                attn("decoder_l${it}_context"), ffn("decoder_l${it}_ffn"))
        }
    }

    private fun w(name: String, k: Int, n: Int): QMatrix {
        val m = bin.weight(name)
        if (m.cols != k || m.rows != n) throw TranslateFormatException("$name: shape ${m.cols}×${m.rows}, expected $k×$n")
        return m
    }

    private fun transposedOutput(): QMatrix {
        // Untied output layer: decoder_ff_logit_out_W is [dim, vocab], stored transposed = vocab rows of dim.
        return bin.weight("decoder_ff_logit_out_W")
    }

    /** Scratch buffers, grown on demand; one set per thread that runs [translate]. */
    private class Work {
        var cap = 0
        lateinit var x: FloatArray; lateinit var y: FloatArray; lateinit var q: FloatArray; lateinit var k: FloatArray
        lateinit var v: FloatArray; lateinit var att: FloatArray; lateinit var hid: FloatArray
        lateinit var xq: ByteArray; lateinit var xInv: FloatArray; lateinit var hq: ByteArray
        lateinit var scores: FloatArray
        fun ensure(m: Int, d: Int, f: Int) {
            if (m <= cap) return
            cap = maxOf(m, cap * 2, 16)
            x = FloatArray(cap * d); y = FloatArray(cap * d); q = FloatArray(cap * d); k = FloatArray(cap * d)
            v = FloatArray(cap * d); att = FloatArray(cap * d); hid = FloatArray(cap * f)
            xq = ByteArray(cap * maxOf(d, f)); xInv = FloatArray(cap); hq = ByteArray(cap * f)
            scores = FloatArray(cap)
        }
    }
    private val work = ThreadLocal.withInitial { Work() }

    /** Encoder output plus the per-layer cross-attention keys/values computed from it (the decoder's cache). */
    class Encoded internal constructor(val length: Int, internal val keys: Array<FloatArray>, internal val values: Array<FloatArray>)

    /** Runs the encoder on source ids (EOS included). */
    fun encode(src: IntArray): Encoded {
        val m = src.size
        val d = dim
        val wk = work.get()
        wk.ensure(m, d, ffnDim)
        val x = FloatArray(m * d)
        val embScale = srcEmb.scale * sqrt(d.toFloat())
        for (i in 0 until m) {
            val id = src[i].coerceIn(0, srcVocabSize - 1)
            val o = i * d
            val eo = srcEmb.offset + id * d
            for (c in 0 until d) x[o + c] = srcEmb.data[eo + c] * embScale
            Ops.addPosition(x, o, d, i)
        }
        for ((a, f) in enc) {
            selfAttention(a, x, m, wk)
            feedForward(f, x, m, wk)
        }
        // cross-attention keys/values for every decoder layer, computed once per sentence
        Ops.quantizeRows(x, m, d, wk.xq, wk.xInv)
        val keys = Array(dec.size) { FloatArray(m * d) }
        val values = Array(dec.size) { FloatArray(m * d) }
        for ((l, layer) in dec.withIndex()) {
            val a = layer.second
            Ops.linear(wk.xq, wk.xInv, m, a.wk, a.bk, keys[l])
            Ops.linear(wk.xq, wk.xInv, m, a.wv, a.bv, values[l])
        }
        return Encoded(m, keys, values)
    }

    private fun selfAttention(a: Attn, x: FloatArray, m: Int, wk: Work) {
        val d = dim
        Ops.quantizeRows(x, m, d, wk.xq, wk.xInv)
        Ops.linear(wk.xq, wk.xInv, m, a.wq, a.bq, wk.q)
        Ops.linear(wk.xq, wk.xInv, m, a.wk, a.bk, wk.k)
        Ops.linear(wk.xq, wk.xInv, m, a.wv, a.bv, wk.v)
        Ops.attention(wk.q, m, wk.k, wk.v, m, d, heads, wk.att, wk.scores)
        Ops.quantizeRows(wk.att, m, d, wk.xq, wk.xInv)
        Ops.linear(wk.xq, wk.xInv, m, a.wo, a.bo, wk.y)
        Ops.addNorm(wk.y, x, m, d, a.lnScale, a.lnBias)
        System.arraycopy(wk.y, 0, x, 0, m * d)
    }

    private fun feedForward(f: Ffn, x: FloatArray, m: Int, wk: Work) {
        val d = dim
        Ops.quantizeRows(x, m, d, wk.xq, wk.xInv)
        Ops.linear(wk.xq, wk.xInv, m, f.w1, f.b1, wk.hid)
        Ops.relu(wk.hid, m * ffnDim)
        Ops.quantizeRows(wk.hid, m, ffnDim, wk.hq, wk.xInv)
        Ops.linear(wk.hq, wk.xInv, m, f.w2, f.b2, wk.y)
        Ops.addNorm(wk.y, x, m, d, f.lnScale, f.lnBias)
        System.arraycopy(wk.y, 0, x, 0, m * d)
    }

    /**
     * Greedy decoding. [allowed] are the candidate target ids (shortlist), sorted; the search stops at [eosId] or after
     * [maxLength] tokens. [cancelled] is polled once per step; when it returns true the search stops and returns null.
     */
    fun greedy(e: Encoded, allowed: IntArray, eosId: Int, maxLength: Int, cancelled: () -> Boolean = { false }): IntArray? {
        val d = dim
        val wk = work.get()
        wk.ensure(maxOf(e.length, 1), d, ffnDim)
        val cells = Array(dec.size) { FloatArray(d) }
        val x = FloatArray(d)
        val res = FloatArray(d)
        val xw = FloatArray(d)
        val fg = FloatArray(d)
        val qv = FloatArray(d)
        val att = FloatArray(d)
        val y = FloatArray(d)
        val hid = FloatArray(ffnDim)
        val xq = ByteArray(maxOf(d, ffnDim))
        val inv = FloatArray(1)
        val scores = FloatArray(e.length)
        val embScale = trgEmb.scale * sqrt(d.toFloat())
        val out = ArrayList<Int>()
        var prev = -1
        for (t in 0 until maxLength) {
            if (cancelled()) return null
            // input: previous target embedding (zeros at t = 0) scaled, plus the position signal
            if (prev < 0) java.util.Arrays.fill(x, 0f)
            else {
                val eo = trgEmb.offset + prev * d
                for (c in 0 until d) x[c] = trgEmb.data[eo + c] * embScale
            }
            Ops.addPosition(x, 0, d, t)
            for ((l, layer) in dec.withIndex()) {
                val (s, a, f) = layer
                // SSRU: c = σ(f)·c_prev + (1-σ(f))·(x W);  out = relu(c);  x = LN(out + x)
                Ops.quantizeRows(x, 1, d, xq, inv)
                Ops.linear(xq, inv, 1, s.w, null, xw)
                Ops.linear(xq, inv, 1, s.wf, s.bf, fg)
                val c = cells[l]
                for (i in 0 until d) {
                    val g = Ops.sigmoid(fg[i])
                    val cv = g * c[i] + (1f - g) * xw[i]
                    c[i] = cv
                    y[i] = if (cv > 0f) cv else 0f
                }
                Ops.addNorm(y, x, 1, d, s.lnScale, s.lnBias)
                System.arraycopy(y, 0, x, 0, d)
                // cross-attention over the encoder output
                Ops.quantizeRows(x, 1, d, xq, inv)
                Ops.linear(xq, inv, 1, a.wq, a.bq, qv)
                Ops.attention(qv, 1, e.keys[l], e.values[l], e.length, d, heads, att, scores)
                Ops.quantizeRows(att, 1, d, xq, inv)
                Ops.linear(xq, inv, 1, a.wo, a.bo, y)
                Ops.addNorm(y, x, 1, d, a.lnScale, a.lnBias)
                System.arraycopy(y, 0, x, 0, d)
                // feed-forward
                Ops.quantizeRows(x, 1, d, xq, inv)
                Ops.linear(xq, inv, 1, f.w1, f.b1, hid)
                Ops.relu(hid, ffnDim)
                Ops.quantizeRows(hid, 1, ffnDim, xq, inv)
                Ops.linear(xq, inv, 1, f.w2, f.b2, y)
                Ops.addNorm(y, x, 1, d, f.lnScale, f.lnBias)
                System.arraycopy(y, 0, x, 0, d)
                System.arraycopy(x, 0, res, 0, d)
            }
            // output layer restricted to the shortlist
            Ops.quantizeRows(x, 1, d, xq, inv)
            val sc = inv[0] * outW.scale
            var best = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (id in allowed) {
                val s = Ops.dotRow(xq, outW, id) * sc + (outB?.get(id) ?: 0f)
                if (s > bestScore) { bestScore = s; best = id }
            }
            if (best < 0 || best == eosId) break
            out.add(best)
            prev = best
        }
        return out.toIntArray()
    }
}
