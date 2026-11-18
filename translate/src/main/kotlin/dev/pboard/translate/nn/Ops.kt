package dev.pboard.translate.nn

import dev.pboard.translate.marian.QMatrix
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Numeric kernels. Matrix products are int8 × int8 → int32 dot products: activations are quantised per row (absmax
 * scaling), weights are the model's int8 tensors. The inner loops are written as plain `s += a[i] * b[j]` over byte
 * arrays on purpose: Android's ART compiler recognises that idiom and emits SDOT/SMLAL vector code on arm64, and
 * HotSpot vectorises it on x86. Everything else (attention, layer norm, softmax) is small and runs in float.
 */
internal object Ops {
    /** Quantises rows of [x] ([m] × [k]) to int8 in [q]; [inv] receives each row's dequantisation factor. */
    fun quantizeRows(x: FloatArray, m: Int, k: Int, q: ByteArray, inv: FloatArray) {
        for (i in 0 until m) {
            val o = i * k
            var mx = 0f
            for (c in 0 until k) mx = max(mx, abs(x[o + c]))
            if (mx == 0f || !mx.isFinite()) {
                for (c in 0 until k) q[o + c] = 0
                inv[i] = 0f
                continue
            }
            val s = 127f / mx
            for (c in 0 until k) q[o + c] = Math.round(x[o + c] * s).toByte()
            inv[i] = mx / 127f
        }
    }

    /**
     * y[m × n] = x · W + bias, with x given quantised ([xq], per-row factors [xInv], row length k = w.cols) and W as
     * stored by Marian (n rows of k). Column range [j0, j1) only, so callers can split the work across threads.
     */
    fun linear(xq: ByteArray, xInv: FloatArray, m: Int, w: QMatrix, bias: FloatArray?, y: FloatArray, j0: Int = 0, j1: Int = w.rows) {
        val k = w.cols
        val n = w.rows
        val wd = w.data
        val ws = w.scale
        for (j in j0 until j1) {
            val bo = w.offset + j * k
            val b = bias?.get(j) ?: 0f
            for (i in 0 until m) {
                val ao = i * k
                var s = 0
                for (c in 0 until k) s += xq[ao + c] * wd[bo + c]
                y[i * n + j] = s * (xInv[i] * ws) + b
            }
        }
    }

    /** One dot product of a quantised vector with row [r] of [w] (used for shortlist logits). */
    fun dotRow(xq: ByteArray, w: QMatrix, r: Int): Int {
        val k = w.cols
        val wd = w.data
        val bo = w.offset + r * k
        var s = 0
        for (c in 0 until k) s += xq[c] * wd[bo + c]
        return s
    }

    /** In place: x = LN(x + residual) * gamma + beta, per row of length [d] (Marian: eps 1e-6). */
    fun addNorm(x: FloatArray, residual: FloatArray, m: Int, d: Int, gamma: FloatArray, beta: FloatArray) {
        for (i in 0 until m) {
            val o = i * d
            var sum = 0f
            for (c in 0 until d) {
                val v = x[o + c] + residual[o + c]
                x[o + c] = v
                sum += v
            }
            val mean = sum / d
            var sq = 0f
            for (c in 0 until d) {
                val v = x[o + c] - mean
                sq += v * v
            }
            val inv = 1f / sqrt(sq / d + 1e-6f)
            for (c in 0 until d) x[o + c] = (x[o + c] - mean) * inv * gamma[c] + beta[c]
        }
    }

    fun relu(x: FloatArray, n: Int) {
        for (i in 0 until n) if (x[i] < 0f) x[i] = 0f
    }

    fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /** Softmax of x[o until o+n] in place. */
    fun softmax(x: FloatArray, o: Int, n: Int) {
        var mx = Float.NEGATIVE_INFINITY
        for (i in o until o + n) mx = max(mx, x[i])
        var sum = 0f
        for (i in o until o + n) {
            val e = exp(x[i] - mx)
            x[i] = e
            sum += e
        }
        val inv = 1f / sum
        for (i in o until o + n) x[i] *= inv
    }

    /**
     * Multi-head dot-product attention for [mq] queries over [mk] keys/values (all [d] wide, [heads] heads), scale
     * 1/sqrt(d/heads). [scores] needs room for mk floats. Result rows go to [out].
     */
    fun attention(q: FloatArray, mq: Int, k: FloatArray, v: FloatArray, mk: Int, d: Int, heads: Int, out: FloatArray, scores: FloatArray) {
        val dh = d / heads
        val scale = 1f / sqrt(dh.toFloat())
        for (i in 0 until mq) {
            for (h in 0 until heads) {
                val qo = i * d + h * dh
                for (j in 0 until mk) {
                    val ko = j * d + h * dh
                    var s = 0f
                    for (c in 0 until dh) s += q[qo + c] * k[ko + c]
                    scores[j] = s * scale
                }
                softmax(scores, 0, mk)
                val oo = i * d + h * dh
                for (c in 0 until dh) out[oo + c] = 0f
                for (j in 0 until mk) {
                    val p = scores[j]
                    val vo = j * d + h * dh
                    for (c in 0 until dh) out[oo + c] += p * v[vo + c]
                }
            }
        }
    }

    /** Marian's sinusoidal position embedding for position [pos], added to [x] at offset [o]: sin half, then cos half. */
    fun addPosition(x: FloatArray, o: Int, d: Int, pos: Int) {
        val half = d / 2
        val inc = Math.log(10000.0).toFloat() / (half - 1f)
        for (i in 0 until half) {
            val v = pos * exp(i * -inc)
            x[o + i] += kotlin.math.sin(v)
            x[o + half + i] += kotlin.math.cos(v)
        }
    }
}
