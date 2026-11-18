package dev.pboard.engine.lm

import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Sanity checks on the shipped English pack (skipped when the asset is absent). */
class ShippedPackTest {
    private val dir = File(System.getProperty("pboard.lmDir") ?: "../app/src/main/assets/lm")
    private fun model(dialect: String? = null): NgramLanguageModel {
        assumeTrue(File(dir, "en.lm").exists())
        val pack = LmPack.open(File(dir, "en.lm"))
        return NgramLanguageModel(pack, dialect?.let { DialectOverlay.read(File(dir, "$it.lmo")) })
    }

    private fun NgramLanguageModel.top(ctx: String, prefix: String = "", k: Int = 3) =
        predict(if (ctx.isEmpty()) emptyList() else ctx.split(' '), prefix, k).map { it.first }

    /**
     * Guard against corrupted back-off weights (b80e983: after quantisation the back-off weight of <s> collapsed
     * and "dashing", "Valium" and ~42k other word classes lost ~11 nats at the start of a sentence only).
     *
     * For EVERY lexicon word and each context below, log P(word | context) must stay within [BOUND_START] (sentence
     * start) or [BOUND_MID] nats of the word's context-free estimate log P_unigram(class) + log P(word | class).
     * A Kneser-Ney back-off model puts a word never seen after a context about log gamma(context) below its
     * unigram: -3.7 to -4.6 nats for <s>, up to -5.6 for a frequent but narrow context such as "I". Results are
     * reported per frequency band, including words of the <rare> class.
     */
    @Test fun everyWordStaysNearItsUnigramAtSentenceStartAndMidSentence() {
        val lm = model()
        val pack = lm.pack
        val contexts = listOf(emptyList(), listOf("the"), listOf("and"), listOf("I"), listOf("my"), listOf("it", "is"),
                              listOf("I", "want", "to"), listOf("Thanks", "for", "the"))
        fun band(lex: Int): String = when {
            pack.ngramIdOf(lex) == LmPack.RARE -> "<rare> class"
            pack.logFreq(lex) >= -10f -> "frequent"
            pack.logFreq(lex) >= -14f -> "mid"
            else -> "infrequent"
        }
        val failures = ArrayList<String>()
        val bandCounts = HashMap<String, Int>()
        for (ctx in contexts) {
            val worst = HashMap<String, Pair<Float, String>>()
            var bad = 0
            val bound = if (ctx.isEmpty()) BOUND_START else BOUND_MID
            for (lex in 0 until pack.nLex) {
                val word = pack.word(lex)
                if (word.any { it.isDigit() }) continue  // numbers are scored as <num>
                val used = lm.lexIdOf(word, sentenceInitial = ctx.isEmpty())
                val unigram = pack.unigramLogProb(pack.ngramIdOf(used)) + pack.inClassLogProb(used)
                val gap = lm.staticLogProb(ctx, word) - unigram
                val b = band(used)
                if (ctx.isEmpty()) bandCounts.merge(b, 1, Int::plus)
                if (gap < (worst[b]?.first ?: Float.MAX_VALUE)) worst[b] = gap to word
                if (gap < -bound) bad++
            }
            val summary = worst.entries.sortedBy { it.key }.joinToString { (b, w) -> "$b ${"%.2f".format(w.first)} (${w.second})" }
            println("context ${ctx.ifEmpty { listOf("<s>") }}: worst gap per band: $summary")
            if (bad > 0) failures.add("context ${ctx.ifEmpty { listOf("<s>") }}: $bad words more than $bound nats below their unigram; $summary")
        }
        assertTrue(bandCounts.getOrDefault("<rare> class", 0) > 1000 && bandCounts.size == 4, "bands sampled: $bandCounts")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * predict() prunes its search with per-class score bounds; it must still return exactly the top k. Compared with
     * a brute-force ranking of every lexicon word (class probability in the context + the word's in-class/overlay
     * term), on the shipped pack, for every dialect, with and without a prefix.
     */
    @Test fun predictReturnsTheExactTopK() {
        val pack = model().pack
        val contexts = listOf(emptyList(), listOf("the"), listOf("thank"), listOf("how", "are"), listOf("see", "you"),
                              listOf("it", "is", "a"), listOf("so", "we", "want", "to"), listOf("ok", "."))
        for (dl in listOf(null, "en_GB", "en_US")) {
            val lm = model(dl)
            for (ctx in contexts) for (prefix in listOf("", "th")) {
                val k = 40
                val got = lm.predict(ctx, prefix, k)
                val all = lm.sentenceIds(ctx)
                val ids = all.copyOfRange(maxOf(0, all.size - (pack.order - 1)), all.size)
                val folded = pack.rules.fold(prefix)
                val classLp = HashMap<Int, Float>()
                val brute = ArrayList<Pair<String, Float>>()
                for (lex in if (prefix.isEmpty()) 0 until pack.nLex else lm.prefixRange(prefix)) {
                    val g = pack.ngramIdOf(lex)
                    // specials are never proposed; <rare> words only as completions of a typed prefix
                    if (g < LmPack.NSPECIAL && (g != LmPack.RARE || prefix.isEmpty())) continue
                    val w = pack.word(lex)
                    if (lm.isOffensiveId(lex) && pack.rules.fold(w) != folded) continue
                    brute.add(w to classLp.getOrPut(g) { lm.ngramLogProb(ids, ids.size, g) } + lm.wordTerm(lex))
                }
                brute.sortByDescending { it.second }
                val what = "$dl $ctx '$prefix'"
                assertEquals(minOf(k, brute.size), got.size, what)
                for (i in got.indices) assertTrue(abs(got[i].second - brute[i].second) < 1e-3f, "$what rank $i: $got vs ${brute.take(k)}")
                val cut = brute[got.size - 1].second
                val gotWords = got.map { it.first }.toSet()
                for ((w, s) in brute) if (s > cut + 1e-3f) assertTrue(w in gotWords, "$what: $w ($s) missing")
            }
        }
    }

    @Test fun sentenceStartRegressionWords() {
        val lm = EnglishPack.load("en-GB", dir).lm
        for (w in listOf("dashing", "Valium", "valium", "calcium", "fishing")) {
            val start = lm.logProb(emptyList(), w)
            val unknownCtx = lm.logProb(listOf("qqzzx"), w)  // back-off to the unigram through an unknown word
            println("$w: sentence start $start, after an unknown word $unknownCtx, mid-sentence ${lm.logProb(listOf("a"), w)}")
            assertTrue(start > unknownCtx - BOUND_START, "$w: sentence start $start vs context-free $unknownCtx")
        }
    }

    @Test fun opensQuickly() {
        assumeTrue(File(dir, "en.lm").exists())
        val t = System.nanoTime()
        val lm = NgramLanguageModel(LmPack.open(File(dir, "en.lm")), DialectOverlay.read(File(dir, "en_GB.lmo")))
        lm.logProb(listOf("how", "are"), "you")
        val ms = (System.nanoTime() - t) / 1e6
        // Wall-clock bound under a loaded CI host (the gate shares its host with emulators); real latency is measured on the
        // phone. This only catches a pathological regression such as reading the whole pack eagerly.
        assertTrue(ms < 2000, "open + overlay + first query took $ms ms")
    }

    @Test fun conversationalPredictions() {
        val lm = model("en_US")
        assertTrue("you" in lm.top("how are"))
        assertTrue("you" in lm.top("thank"))
        assertTrue("you" in lm.top("see"), lm.top("see").toString())
        assertTrue(lm.top("I").any { it in setOf("am", "have", "don't", "was", "think") }, lm.top("I").toString())
        assertTrue("tomorrow" in lm.top("see you", "tom"))
        assertTrue("I'm" in lm.top("", "im", 5) || "I'm" in lm.top("", "i'm", 5))
        assertEquals("London", lm.word(lm.lexIdOf("london")))
        assertTrue(lm.isKnown("don't") && !lm.isKnown("dont"))
    }

    @Test fun dialectsPickTheirSpelling() {
        val gb = model("en_GB")
        val us = model("en_US")
        assertEquals("colour", gb.top("what", "colo", 1).single())
        assertEquals("color", us.top("what", "colo", 1).single())
        assertTrue(gb.logProb(listOf("my"), "favourite") > gb.logProb(listOf("my"), "favorite"))
        assertTrue(us.logProb(listOf("my"), "favorite") > us.logProb(listOf("my"), "favourite"))
        assertTrue(gb.logProb(listOf("I"), "realise") > gb.logProb(listOf("I"), "realize"))
        assertTrue(gb.isKnown("color") && us.isKnown("colour"))
        assertTrue(gb.logProb(listOf("my"), "mum") > us.logProb(listOf("my"), "mum"))
    }

    @Test fun offensiveWordsAreNotSuggested() {
        val lm = model("en_GB")
        for (ctx in listOf("what the", "oh", "holy", "you little")) {
            assertFalse(lm.top(ctx, "", 20).any { lm.isOffensive(it) }, "$ctx -> ${lm.top(ctx, "", 20)}")
            assertFalse(lm.top(ctx, "f", 20).any { lm.isOffensive(it) })
        }
        assertTrue("fuck" in lm.top("what the", "fuck", 3))
    }

    private companion object {
        /** nats below the unigram; correct packs (775f407 and the fixed build) measure at most 4.6 at <s> */
        const val BOUND_START = 6f
        /** correct packs measure at most 5.6 (after "I") in the mid-sentence contexts tested */
        const val BOUND_MID = 8f
    }
}
