package dev.pboard.translate.spm

import dev.pboard.translate.TranslateFormatException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SentencePiece tokenizer (unigram model) reading the standard `.spm` model file (a serialised ModelProto).
 * Ported from google/sentencepiece (Apache-2.0): normalizer.cc (precompiled charsmap lookup in a Darts double-array
 * trie, whitespace handling), unigram_model.cc (optimised Viterbi with unknown-character penalty and user-defined
 * symbol scores) and sentencepiece_processor.cc (byte fallback, decoding). See NOTICE.
 */
class SentencePiece(bytes: ByteArray) {
    private val pieces = ArrayList<String>()
    private val scores = ArrayList<Float>()
    private val types = ArrayList<Int>()
    private var modelType = 1
    var unkId = 0; private set
    var eosId = -1; private set
    var bosId = -1; private set
    private var byteFallback = false
    private var addDummyPrefix = true
    private var removeExtraWhitespaces = true
    private var escapeWhitespaces = true
    private var charsmap: ByteArray? = null

    private val pieceIndex = HashMap<String, Int>()
    private val byteIds = IntArray(256) { -1 }
    private val userDefined = ArrayList<String>()
    private var minScore = Float.MAX_VALUE
    private var maxPieceBytes = 1
    private var trieUnits: IntArray = IntArray(0)
    private var trieNormalized: ByteArray = ByteArray(0)

    val size: Int get() = pieces.size

    init {
        parseModel(bytes)
        if (modelType != 1) throw TranslateFormatException("only unigram SentencePiece models are supported (type $modelType)")
        for (i in pieces.indices) {
            val t = types[i]
            if (t == NORMAL || t == USER_DEFINED || t == UNUSED) {
                pieceIndex.putIfAbsent(pieces[i], i)
                maxPieceBytes = maxOf(maxPieceBytes, pieces[i].toByteArray(Charsets.UTF_8).size)
            }
            if (t == USER_DEFINED) userDefined.add(pieces[i])
            if (t == NORMAL) minScore = minOf(minScore, scores[i])
            if (t == BYTE) {
                val p = pieces[i]
                if (p.length == 6 && p.startsWith("<0x") && p.endsWith(">")) p.substring(3, 5).toIntOrNull(16)?.let { byteIds[it] = i }
            }
        }
        if (minScore == Float.MAX_VALUE) minScore = 0f
        charsmap?.let { cm ->
            if (cm.size < 4) throw TranslateFormatException("bad charsmap")
            val trieSize = ByteBuffer.wrap(cm, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (trieSize < 0 || trieSize % 4 != 0 || 4L + trieSize > cm.size) throw TranslateFormatException("bad charsmap trie")
            val ib = ByteBuffer.wrap(cm, 4, trieSize).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            trieUnits = IntArray(trieSize / 4).also { ib.get(it) }
            trieNormalized = cm.copyOfRange(4 + trieSize, cm.size)
        }
    }

    fun piece(id: Int): String = pieces[id]
    /** Id of [piece] (including control pieces such as `</s>`), or [unkId]. */
    fun id(piece: String): Int = pieceIndex[piece] ?: pieces.indexOf(piece).takeIf { it >= 0 } ?: unkId

    // ---------------------------------------------------------------- protobuf

    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun eof() = p >= end
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                if (p >= end) throw TranslateFormatException("truncated SentencePiece model")
                val c = b[p++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return r
                shift += 7
                if (shift > 63) throw TranslateFormatException("bad varint")
            }
        }
        fun fixed32(): Int {
            if (p + 4 > end) throw TranslateFormatException("truncated SentencePiece model")
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        fun bytes(): Reader {
            val n = varint()
            if (n < 0 || p + n > end) throw TranslateFormatException("truncated SentencePiece model")
            val r = Reader(b, p, (p + n).toInt())
            p += n.toInt()
            return r
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { if (p + 8 > end) throw TranslateFormatException("truncated"); p += 8 }
                2 -> bytes()
                5 -> fixed32()
                else -> throw TranslateFormatException("bad wire type $wire")
            }
        }
        fun string() = bytes().let { String(it.b, it.p, it.end - it.p, Charsets.UTF_8) }
        fun blob() = bytes().let { it.b.copyOfRange(it.p, it.end) }
    }

    private fun parseModel(bytes: ByteArray) {
        val r = Reader(bytes, 0, bytes.size)
        while (!r.eof()) {
            val tag = r.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 2 -> { // SentencePiece
                    val m = r.bytes()
                    var piece = ""; var score = 0f; var type = NORMAL
                    while (!m.eof()) {
                        val t = m.varint().toInt()
                        when {
                            t ushr 3 == 1 && t and 7 == 2 -> piece = m.string()
                            t ushr 3 == 2 && t and 7 == 5 -> score = Float.fromBits(m.fixed32())
                            t ushr 3 == 3 && t and 7 == 0 -> type = m.varint().toInt()
                            else -> m.skip(t and 7)
                        }
                    }
                    if (score.isNaN() || score.isInfinite()) throw TranslateFormatException("bad piece score")
                    pieces.add(piece); scores.add(score); types.add(type)
                }
                field == 2 && wire == 2 -> { // TrainerSpec
                    val m = r.bytes()
                    while (!m.eof()) {
                        val t = m.varint().toInt()
                        val f = t ushr 3
                        if (t and 7 != 0) { m.skip(t and 7); continue }
                        val v = m.varint()
                        when (f) {
                            3 -> modelType = v.toInt()
                            35 -> byteFallback = v != 0L
                            40 -> unkId = v.toInt()
                            41 -> bosId = v.toInt()
                            42 -> eosId = v.toInt()
                        }
                    }
                }
                field == 3 && wire == 2 -> { // NormalizerSpec
                    val m = r.bytes()
                    while (!m.eof()) {
                        val t = m.varint().toInt()
                        val f = t ushr 3
                        when {
                            f == 2 && t and 7 == 2 -> charsmap = m.blob()
                            f == 3 && t and 7 == 0 -> addDummyPrefix = m.varint() != 0L
                            f == 4 && t and 7 == 0 -> removeExtraWhitespaces = m.varint() != 0L
                            f == 5 && t and 7 == 0 -> escapeWhitespaces = m.varint() != 0L
                            else -> m.skip(t and 7)
                        }
                    }
                }
                else -> r.skip(wire)
            }
        }
        if (pieces.isEmpty()) throw TranslateFormatException("SentencePiece model has no pieces")
        if (unkId !in pieces.indices) throw TranslateFormatException("bad unk id")
        // A raw NUL piece is excluded from matching when byte fallback is on (sentencepiece issue 1308).
        if (byteFallback) for (i in pieces.indices) if (pieces[i] == "\u0000") types[i] = UNUSED
    }

    // ---------------------------------------------------------------- normalizer

    /** Longest charsmap rule matching [s] at [p]: (consumed bytes, replacement offset in [trieNormalized]) or null. */
    private fun charsmapPrefix(s: ByteArray, p: Int): Long? {
        val units = trieUnits
        if (units.isEmpty()) return null
        var nodePos = 0
        var unit = units[0]
        nodePos = nodePos xor offset(unit)
        var bestLen = 0
        var bestValue = 0
        for (i in p until s.size) {
            val c = s[i].toInt() and 0xFF
            nodePos = nodePos xor c
            if (nodePos < 0 || nodePos >= units.size) break
            unit = units[nodePos]
            if (label(unit) != c) break
            nodePos = nodePos xor offset(unit)
            if (nodePos < 0 || nodePos >= units.size) break
            if (hasLeaf(unit)) {
                bestLen = i + 1 - p
                bestValue = units[nodePos] and 0x7FFFFFFF
            }
        }
        if (bestLen == 0 || bestValue >= trieNormalized.size) return null
        return (bestLen.toLong() shl 32) or bestValue.toLong()
    }

    private fun hasLeaf(u: Int) = (u ushr 8) and 1 == 1
    private fun label(u: Int) = u and (0x80000000.toInt() or 0xFF)
    private fun offset(u: Int) = (u ushr 10) shl ((u and (1 shl 9)) ushr 6)

    /** One normalised prefix: the replacement bytes and how many input bytes it consumed. */
    private fun normalizePrefix(s: ByteArray, p: Int, out: ByteArrayOutputStream): Int {
        for (u in userDefinedBytes) if (startsWith(s, p, u)) { out.write(u, 0, u.size); return u.size }
        val hit = charsmapPrefix(s, p)
        if (hit != null) {
            val len = (hit ushr 32).toInt()
            var v = (hit and 0xFFFFFFFFL).toInt()
            while (v < trieNormalized.size && trieNormalized[v] != 0.toByte()) out.write(trieNormalized[v++].toInt())
            return len
        }
        val n = utf8Len(s, p)
        if (n <= 0) { out.write(REPLACEMENT, 0, 3); return 1 }
        out.write(s, p, n)
        return n
    }

    private val userDefinedBytes: List<ByteArray> by lazy { userDefined.map { it.toByteArray(Charsets.UTF_8) }.sortedByDescending { it.size } }

    private fun startsWith(s: ByteArray, p: Int, u: ByteArray): Boolean {
        if (u.isEmpty() || p + u.size > s.size) return false
        for (i in u.indices) if (s[p + i] != u[i]) return false
        return true
    }

    /** Length of a valid UTF-8 sequence at [p], or 0 if malformed. */
    private fun utf8Len(s: ByteArray, p: Int): Int {
        val c = s[p].toInt() and 0xFF
        val n = when {
            c < 0x80 -> return 1
            c in 0xC2..0xDF -> 2
            c in 0xE0..0xEF -> 3
            c in 0xF0..0xF4 -> 4
            else -> return 0
        }
        if (p + n > s.size) return 0
        for (i in 1 until n) if ((s[p + i].toInt() and 0xC0) != 0x80) return 0
        val cp = when (n) {
            2 -> ((c and 0x1F) shl 6) or (s[p + 1].toInt() and 0x3F)
            3 -> ((c and 0x0F) shl 12) or ((s[p + 1].toInt() and 0x3F) shl 6) or (s[p + 2].toInt() and 0x3F)
            else -> ((c and 0x07) shl 18) or ((s[p + 1].toInt() and 0x3F) shl 12) or ((s[p + 2].toInt() and 0x3F) shl 6) or (s[p + 3].toInt() and 0x3F)
        }
        if ((n == 3 && cp < 0x800) || (n == 4 && (cp < 0x10000 || cp > 0x10FFFF)) || cp in 0xD800..0xDFFF) return 0
        return n
    }

    /** SentencePiece normalisation (charsmap rules, whitespace collapse, ▁ escaping, dummy prefix), as UTF-8 bytes. */
    fun normalize(text: String): ByteArray {
        val s = text.toByteArray(Charsets.UTF_8)
        var p = 0
        val tmp = ByteArrayOutputStream()
        if (removeExtraWhitespaces) {
            while (p < s.size) {
                tmp.reset()
                val n = normalizePrefix(s, p, tmp)
                if (!(tmp.size() == 1 && tmp.toByteArray()[0] == SPACE)) break
                p += n
            }
        }
        if (p >= s.size) return ByteArray(0)
        val out = ByteArrayOutputStream(s.size * 3 / 2 + 4)
        val ws = if (escapeWhitespaces) WS else byteArrayOf(SPACE)
        if (addDummyPrefix) out.write(ws, 0, ws.size)
        var prevSpace = removeExtraWhitespaces
        while (p < s.size) {
            tmp.reset()
            val n = normalizePrefix(s, p, tmp)
            val piece = tmp.toByteArray()
            var start = 0
            if (prevSpace) while (start < piece.size && piece[start] == SPACE) start++
            if (start < piece.size) {
                for (i in start until piece.size) {
                    if (piece[i] == SPACE) out.write(ws, 0, ws.size) else out.write(piece[i].toInt())
                }
                prevSpace = piece[piece.size - 1] == SPACE
            }
            p += n
            if (!removeExtraWhitespaces) prevSpace = false
        }
        var res = out.toByteArray()
        if (removeExtraWhitespaces) {
            var end = res.size
            while (end >= ws.size && endsWith(res, end, ws)) end -= ws.size
            if (end != res.size) res = res.copyOf(end)
        }
        return res
    }

    private fun endsWith(b: ByteArray, end: Int, suf: ByteArray): Boolean {
        for (i in suf.indices) if (b[end - suf.size + i] != suf[i]) return false
        return true
    }

    // ---------------------------------------------------------------- encode / decode

    private val pieceBytes: HashMap<ByteArrayKey, Int> by lazy {
        val m = HashMap<ByteArrayKey, Int>(pieceIndex.size * 2)
        for ((k, v) in pieceIndex) m[ByteArrayKey(k.toByteArray(Charsets.UTF_8))] = v
        m
    }

    private class ByteArrayKey(val b: ByteArray, val from: Int = 0, val to: Int = b.size) {
        private val h: Int = run { var x = 1; for (i in from until to) x = 31 * x + b[i]; x }
        override fun hashCode() = h
        override fun equals(other: Any?): Boolean {
            if (other !is ByteArrayKey || other.to - other.from != to - from || other.h != h) return false
            for (i in 0 until to - from) if (b[from + i] != other.b[other.from + i]) return false
            return true
        }
    }

    /** Token ids for [text] (no EOS), as `spm_encode --output_format=id` would produce. */
    fun encode(text: String): IntArray {
        val s = normalize(text)
        if (s.isEmpty()) return IntArray(0)
        val n = s.size
        val bestScore = FloatArray(n + 1)
        val startsAt = IntArray(n + 1) { -1 }
        val ids = IntArray(n + 1) { -1 }
        val unkScore = minScore - UNK_PENALTY
        val index = pieceBytes
        var start = 0
        while (start < n) {
            val base = bestScore[start]
            val mblen = minOf(utf8Len(s, start).coerceAtLeast(1), n - start)
            var hasSingle = false
            val maxEnd = minOf(n, start + maxPieceBytes)
            for (end in start + 1..maxEnd) {
                val id = index[ByteArrayKey(s, start, end)] ?: continue
                if (types[id] == UNUSED) continue
                val len = end - start
                val score = if (types[id] == USER_DEFINED) 0.1f * (len - 1) else scores[id]
                val cand = score + base
                if (startsAt[end] == -1 || cand > bestScore[end]) {
                    bestScore[end] = cand; startsAt[end] = start; ids[end] = id
                }
                if (len == mblen) hasSingle = true
            }
            if (!hasSingle) {
                val end = start + mblen
                val cand = unkScore + base
                if (startsAt[end] == -1 || cand > bestScore[end]) {
                    bestScore[end] = cand; startsAt[end] = start; ids[end] = unkId
                }
            }
            start += mblen
        }
        val rev = ArrayList<Int>()
        var end = n
        while (end > 0) {
            val st = startsAt[end]
            if (st < 0) break
            val id = ids[end]
            if (id == unkId && byteFallback) {
                for (i in end - 1 downTo st) rev.add(byteIds[s[i].toInt() and 0xFF].takeIf { it >= 0 } ?: unkId)
            } else rev.add(id)
            end = st
        }
        rev.reverse()
        return rev.toIntArray()
    }

    /** Text for token ids (control tokens such as EOS are dropped), like `spm_decode --input_format=id`. */
    fun decode(ids: IntArray): String {
        val out = ByteArrayOutputStream()
        var first = true
        for (id in ids) {
            if (id < 0 || id >= pieces.size) continue
            val t = types[id]
            if (t == CONTROL) continue
            if (t == BYTE) {
                val p = pieces[id]
                p.substring(3, 5).toIntOrNull(16)?.let { out.write(it) }
                first = false
                continue
            }
            var b = (if (t == UNKNOWN) UNK_SURFACE else pieces[id]).toByteArray(Charsets.UTF_8)
            if (first && addDummyPrefix && startsWith(b, 0, WS)) b = b.copyOfRange(WS.size, b.size)
            first = false
            out.write(b, 0, b.size)
        }
        // decode bytes as UTF-8 (malformed sequences become U+FFFD) and unescape whitespace
        return String(out.toByteArray(), Charsets.UTF_8).replace('▁', ' ')
    }

    companion object {
        const val NORMAL = 1
        const val UNKNOWN = 2
        const val CONTROL = 3
        const val USER_DEFINED = 4
        const val BYTE = 6
        const val UNUSED = 5
        private const val UNK_PENALTY = 10f
        private const val SPACE = ' '.code.toByte()
        private val WS = "▁".toByteArray(Charsets.UTF_8)
        private val REPLACEMENT = "�".toByteArray(Charsets.UTF_8)
        private const val UNK_SURFACE = " ⁇ "
    }
}
