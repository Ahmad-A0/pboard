package dev.pboard.translate.marian

import dev.pboard.translate.TranslateFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Marian's binary lexical shortlist (`lex.*.s2t.bin`): for every source vocabulary id the target ids it most likely
 * translates to. The output layer then scores only the union of those words plus the [firstNum] most frequent target
 * words, instead of the whole vocabulary. Format (little-endian), following marian's src/data/shortlist.{h,cpp} (MIT):
 *
 *     u64 magic · u64 checksum · u64 firstNum · u64 bestNum · u64 wordToOffsetSize · u64 shortListsSize
 *     u64 wordToOffset[wordToOffsetSize] · u32 shortLists[shortListsSize]
 */
class Shortlist(data: ByteArray) {
    val firstNum: Int
    val bestNum: Int
    private val offsets: LongArray
    private val lists: IntArray

    init {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 48) throw TranslateFormatException("shortlist too short")
        if (bb.getLong(0) != MAGIC) throw TranslateFormatException("not a binary shortlist")
        firstNum = bb.getLong(16).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        bestNum = bb.getLong(24).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val nOff = bb.getLong(32)
        val nList = bb.getLong(40)
        if (nOff < 1 || nList < 0 || 48 + nOff * 8 + nList * 4 != data.size.toLong())
            throw TranslateFormatException("shortlist size mismatch")
        offsets = LongArray(nOff.toInt()) { bb.getLong(48 + it * 8) }
        lists = IntArray(nList.toInt()) { bb.getInt((48 + nOff * 8 + it * 4L).toInt()) }
        for (i in 1 until offsets.size) {
            if (offsets[i] < offsets[i - 1] || offsets[i] > lists.size) throw TranslateFormatException("bad shortlist offsets")
        }
    }

    /** Number of source words covered. */
    val sourceVocabSize: Int get() = offsets.size - 1

    /**
     * Sorted target ids allowed for a sentence with source ids [src]: the [firstNum] most frequent target words, the
     * listed translations of each source word, and (for a shared vocabulary) the source words themselves.
     */
    fun select(src: IntArray, trgVocabSize: Int, sharedVocab: Boolean): IntArray {
        val mark = BooleanArray(trgVocabSize)
        for (i in 0 until minOf(firstNum, trgVocabSize)) mark[i] = true
        for (s in src) {
            if (sharedVocab && s in 0 until trgVocabSize) mark[s] = true
            if (s < 0 || s >= sourceVocabSize) continue
            for (j in offsets[s] until offsets[s + 1]) {
                val t = lists[j.toInt()]
                if (t in 0 until trgVocabSize) mark[t] = true
            }
        }
        var n = 0
        for (m in mark) if (m) n++
        val out = IntArray(n)
        var k = 0
        for (i in mark.indices) if (mark[i]) out[k++] = i
        return out
    }

    companion object {
        const val MAGIC = -0x0ee5b72afecbe80bL // 0xF11A48D5013417F5
    }
}
