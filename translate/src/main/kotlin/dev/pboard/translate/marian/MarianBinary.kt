package dev.pboard.translate.marian

import dev.pboard.translate.TranslateFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for Marian's binary model format (`*.bin`, BINARY_FILE_VERSION 1), as written by marian-conv and shipped
 * by Firefox Translations. Layout (all little-endian), following marian's src/common/binary.cpp (MIT):
 *
 *     u64 version (=1) · u64 n · n × {u64 nameLength, u64 type, u64 shapeLength, u64 dataLength}
 *     names (NUL-terminated) · shapes (i32 × shapeLength each) · u64 padding · padding bytes · item data
 *
 * Every offset is validated against the buffer, so a damaged or hostile file fails with [TranslateFormatException]
 * instead of an out-of-bounds read.
 */
class MarianBinary(val data: ByteArray) {
    class Item(val name: String, val type: Long, val shape: IntArray, val offset: Int, val length: Int) {
        val elements: Long get() = shape.fold(1L) { a, d -> a * d }
        override fun toString() = "$name ${type.toString(16)} ${shape.contentToString()} $length"
    }

    val items: Map<String, Item>

    init {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun need(pos: Long, n: Long) {
            if (pos < 0 || n < 0 || pos + n > data.size) throw TranslateFormatException("truncated Marian model")
        }
        need(0, 16)
        val version = bb.getLong(0)
        if (version != 1L) throw TranslateFormatException("unsupported Marian binary version $version")
        val n = bb.getLong(8)
        if (n <= 0 || n > 100_000) throw TranslateFormatException("bad item count $n")
        var pos = 16L
        need(pos, n * 32)
        val headers = Array(n.toInt()) { i ->
            val p = (pos + i * 32L).toInt()
            longArrayOf(bb.getLong(p), bb.getLong(p + 8), bb.getLong(p + 16), bb.getLong(p + 24))
        }
        pos += n * 32
        val names = Array(n.toInt()) { i ->
            val len = headers[i][0]
            need(pos, len)
            if (len < 1) throw TranslateFormatException("bad name length")
            val s = String(data, pos.toInt(), (len - 1).toInt(), Charsets.UTF_8)
            pos += len
            s
        }
        val shapes = Array(n.toInt()) { i ->
            val len = headers[i][2]
            if (len < 0 || len > 8) throw TranslateFormatException("bad shape length")
            need(pos, len * 4)
            val s = IntArray(len.toInt()) { d -> bb.getInt((pos + d * 4).toInt()) }
            pos += len * 4
            s
        }
        need(pos, 8)
        val pad = bb.getLong(pos.toInt())
        pos += 8 + pad
        val map = LinkedHashMap<String, Item>()
        for (i in 0 until n.toInt()) {
            val len = headers[i][3]
            need(pos, len)
            map[names[i]] = Item(names[i], headers[i][1], shapes[i], pos.toInt(), len.toInt())
            pos += len
        }
        items = map
    }

    operator fun get(name: String): Item = items[name] ?: throw TranslateFormatException("model has no tensor '$name'")
    fun has(name: String) = name in items

    /** float32 tensor contents. */
    fun floats(name: String): FloatArray {
        val it = get(name)
        if (it.type != TYPE_FLOAT32) throw TranslateFormatException("$name: expected float32, got ${it.type.toString(16)}")
        val n = it.elements.toInt()
        if (n * 4L > it.length) throw TranslateFormatException("$name: data too short")
        val out = FloatArray(n)
        ByteBuffer.wrap(data, it.offset, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    /** The single float of a 1×1 float32 tensor. */
    fun scalar(name: String): Float = floats(name).let { if (it.size == 1) it[0] else throw TranslateFormatException("$name is not a scalar") }

    /**
     * An `intgemm8` weight of logical shape [K, N] (input × output) as stored by marian: transposed, i.e. N rows of K
     * int8 values each, followed by the float quantisation multiplier (marian's prepareAndTransposeB / ARM path).
     */
    fun weight(name: String): QMatrix {
        val it = get(name)
        if (it.type != TYPE_INTGEMM8) throw TranslateFormatException("$name: expected intgemm8, got ${it.type.toString(16)}")
        if (it.shape.size != 2) throw TranslateFormatException("$name: expected a matrix")
        val k = it.shape[0]
        val n = it.shape[1]
        return QMatrix(data, it.offset, n, k, 1f / quantMult(it))
    }

    /**
     * An `intgemm8` embedding matrix [vocab, dim], stored row-major (not transposed) followed by its quantisation
     * multiplier (marian's unquantizeWemb). Rows are both input embeddings and, when tied, output-layer weights.
     */
    fun embedding(name: String): QMatrix {
        val it = get(name)
        if (it.type != TYPE_INTGEMM8) throw TranslateFormatException("$name: expected intgemm8, got ${it.type.toString(16)}")
        if (it.shape.size != 2) throw TranslateFormatException("$name: expected a matrix")
        return QMatrix(data, it.offset, it.shape[0], it.shape[1], 1f / quantMult(it))
    }

    private fun quantMult(it: Item): Float {
        val n = it.elements
        if (n + 4 > it.length) throw TranslateFormatException("${it.name}: no quantisation multiplier")
        val q = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat((it.offset + n).toInt())
        if (!(q > 0f) || q.isInfinite()) throw TranslateFormatException("${it.name}: bad quantisation multiplier $q")
        return q
    }

    /** The training configuration embedded as `special:model.yml` (flat `key: value` lines; lists are kept raw). */
    fun config(): Map<String, String> {
        val it = items["special:model.yml"] ?: return emptyMap()
        var end = it.offset + it.length
        while (end > it.offset && data[end - 1] == 0.toByte()) end--
        val text = String(data, it.offset, end - it.offset, Charsets.UTF_8)
        val out = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (line in text.lines()) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (line.startsWith(" ") || line.startsWith("-")) {
                val k = lastKey ?: continue
                out[k] = (out[k].orEmpty() + " " + line.trim()).trim()
                continue
            }
            val c = line.indexOf(':')
            if (c <= 0) continue
            lastKey = line.substring(0, c).trim()
            out[lastKey] = line.substring(c + 1).trim().removeSurrounding("\"")
        }
        return out
    }

    companion object {
        const val TYPE_INT8 = 0x0101L
        const val TYPE_FLOAT32 = 0x0404L
        const val TYPE_INTGEMM8 = 0x4101L
    }
}

/**
 * An int8 matrix with one float scale: element (r, c) = data[offset + r * cols + c] * scale. For weights, rows are
 * output units and cols the inputs (so every output is one contiguous dot product); for embeddings, rows are words.
 */
class QMatrix(val data: ByteArray, val offset: Int, val rows: Int, val cols: Int, val scale: Float) {
    init {
        if (rows <= 0 || cols <= 0 || offset < 0 || offset.toLong() + rows.toLong() * cols > data.size)
            throw TranslateFormatException("matrix $rows×$cols out of bounds")
    }
}
