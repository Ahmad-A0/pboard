package dev.pboard.engine.devlm

import dev.pboard.engine.LanguageModel
import dev.pboard.engine.decode.LexiconEntry
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * DEV-ONLY stand-in language model (test source set): interpolated absolute-discounting bigram over
 * canonical word forms. The real n-gram LM lives in dev.pboard.engine.lm; the decoders only use the
 * [LanguageModel] interface so they are agnostic to which one is plugged in.
 *
 * An empty context means "start of sentence".
 */
class CountBigramLM private constructor(
    private val words: Array<String>,
    private val uniLogP: FloatArray,
    private val ctxStart: IntArray,
    private val succ: IntArray,
    private val cnt: IntArray,
    private val ctxTotal: IntArray,
    private val topSucc: Array<IntArray>,
    private val bos: Int,
    private val discount: Double,
    private val unkLogP: Float,
) : LanguageModel {
    private val index = HashMap<String, Int>(words.size * 2)
    private val lowerIndex = HashMap<String, Int>(words.size * 2)
    private val byUnigram: IntArray

    init {
        for (i in words.indices) {
            if (i == bos) continue
            index[words[i]] = i
            val l = words[i].lowercase()
            val o = lowerIndex[l]
            if (o == null || uniLogP[i] > uniLogP[o]) lowerIndex[l] = i
        }
        byUnigram = words.indices.filter { it != bos }.sortedByDescending { uniLogP[it] }.toIntArray()
    }

    val size: Int get() = words.size - 1

    fun idOf(word: String): Int = index[word] ?: lowerIndex[word.lowercase()] ?: -1

    fun vocabulary(): List<LexiconEntry> = words.indices.filter { it != bos }.map { LexiconEntry(words[it], uniLogP[it]) }

    private fun count(v: Int, w: Int): Int {
        var lo = ctxStart[v]; var hi = ctxStart[v + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = succ[mid]
            if (s == w) return cnt[mid]
            if (s < w) lo = mid + 1 else hi = mid - 1
        }
        return 0
    }

    private fun prob(v: Int, w: Int): Double {
        val pu = exp(uniLogP[w].toDouble())
        if (v < 0) return pu
        val tot = ctxTotal[v]
        if (tot == 0) return pu
        val types = ctxStart[v + 1] - ctxStart[v]
        val c = count(v, w)
        return max(c - discount, 0.0) / tot + discount * types / tot * pu
    }

    /** Empty context or a sentence-ending token ("." "!" "?") means a sentence start. */
    private fun ctxId(context: List<String>): Int = context.lastOrNull().let { if (it == null || it.all { c -> c == '.' || c == '!' || c == '?' }) bos else idOf(it) }

    override fun logProb(context: List<String>, word: String): Float {
        val w = idOf(word)
        if (w < 0) return unkLogP
        return ln(prob(ctxId(context), w)).toFloat()
    }

    override fun predict(context: List<String>, prefix: String, limit: Int): List<Pair<String, Float>> {
        val v = ctxId(context)
        val cands = LinkedHashSet<Int>()
        val p = prefix.lowercase()
        if (v >= 0) for (s in topSucc[v]) if (words[s].lowercase().startsWith(p)) cands.add(s)
        var added = 0
        for (id in byUnigram) {
            if (added >= 100) break
            if (words[id].lowercase().startsWith(p)) { cands.add(id); added++ }
        }
        return cands.map { it to prob(v, it) }.sortedByDescending { it.second }.take(limit)
            .map { words[it.first] to ln(it.second).toFloat() }
    }

    /** Known = has real frequency mass (singleton corpus junk only gets the smoothing floor). */
    override fun isKnown(word: String): Boolean = idOf(word).let { it >= 0 && uniLogP[it] > KNOWN_MIN }

    /** Accumulates counts; ids are assigned on first sight. */
    class Builder(private val discount: Double = 0.75, private val unkLogP: Float = -16f) {
        private val ids = HashMap<String, Int>()
        private val names = ArrayList<String>()
        private var uni = DoubleArray(1024)
        private val bigrams = LongIntMap(1 shl 20)
        val bos: Int = id(BOS)

        fun id(word: String): Int = ids.getOrPut(word) {
            names.add(word)
            if (names.size > uni.size) uni = uni.copyOf(uni.size * 2)
            names.size - 1
        }

        fun has(word: String) = ids.containsKey(word)

        /** Adds unigram probability mass (any scale; normalised at build). */
        fun addUnigram(word: String, weight: Double) { val i = id(word); uni[i] += weight }

        fun addBigram(prev: Int, word: Int, count: Int = 1) {
            bigrams.add((prev.toLong() shl 32) or word.toLong(), count)
        }

        fun build(): CountBigramLM {
            val v = names.size
            var total = 0.0
            for (i in 0 until v) if (i != bos) total += uni[i]
            // Small floor so every vocabulary word has a finite prior.
            val floor = total * 1e-9
            total += floor * v
            val uniLogP = FloatArray(v) { if (it == bos) -99f else ln((uni[it] + floor) / total).toFloat() }
            // CSR by context.
            val counts = IntArray(v + 1)
            bigrams.forEach { k, _ -> counts[(k ushr 32).toInt()]++ }
            val start = IntArray(v + 1)
            for (i in 0 until v) start[i + 1] = start[i] + counts[i]
            val fill = start.copyOf()
            val succ = IntArray(start[v]); val cnt = IntArray(start[v])
            val tot = IntArray(v)
            bigrams.forEach { k, c ->
                val a = (k ushr 32).toInt(); val b = (k and 0xffffffffL).toInt()
                val pos = fill[a]++
                succ[pos] = b; cnt[pos] = c; tot[a] += c
            }
            val top = Array(v) { IntArray(0) }
            for (a in 0 until v) {
                val s = start[a]; val e = start[a + 1]
                if (e == s) continue
                val order = (s until e).sortedBy { succ[it] }
                val sortedSucc = IntArray(e - s) { succ[order[it]] }
                val sortedCnt = IntArray(e - s) { cnt[order[it]] }
                System.arraycopy(sortedSucc, 0, succ, s, e - s)
                System.arraycopy(sortedCnt, 0, cnt, s, e - s)
                top[a] = (s until e).sortedByDescending { cnt[it] }.take(40).map { succ[it] }.toIntArray()
            }
            return CountBigramLM(names.toTypedArray(), uniLogP, start, succ, cnt, tot, top, bos, discount, unkLogP)
        }
    }

    companion object { const val BOS = "<s>"; const val KNOWN_MIN = -19f }
}

/** Minimal open-addressing Long -> Int counter (avoids boxing millions of bigram keys). */
class LongIntMap(initial: Int) {
    private var keys = LongArray(initial)
    private var vals = IntArray(initial)
    private var used = BooleanArray(initial)
    var size = 0; private set

    fun add(key: Long, delta: Int) {
        if ((size + 1) * 2 > keys.size) rehash()
        var i = slot(key, keys.size)
        while (used[i] && keys[i] != key) i = (i + 1) and (keys.size - 1)
        if (!used[i]) { used[i] = true; keys[i] = key; size++ }
        vals[i] += delta
    }

    fun get(key: Long): Int {
        var i = slot(key, keys.size)
        while (used[i]) { if (keys[i] == key) return vals[i]; i = (i + 1) and (keys.size - 1) }
        return 0
    }

    inline fun forEach(f: (Long, Int) -> Unit) {
        val k = keysArray(); val v = valsArray(); val u = usedArray()
        for (i in k.indices) if (u[i]) f(k[i], v[i])
    }

    fun keysArray() = keys
    fun valsArray() = vals
    fun usedArray() = used

    private fun slot(key: Long, cap: Int): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return (h.toInt()) and (cap - 1)
    }

    private fun rehash() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = IntArray(ok.size * 2); used = BooleanArray(ok.size * 2); size = 0
        for (i in ok.indices) if (ou[i]) {
            var j = slot(ok[i], keys.size)
            while (used[j]) j = (j + 1) and (keys.size - 1)
            used[j] = true; keys[j] = ok[i]; vals[j] = ov[i]; size++
        }
    }
}
