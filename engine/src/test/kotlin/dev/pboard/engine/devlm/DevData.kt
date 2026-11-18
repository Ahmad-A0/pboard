package dev.pboard.engine.devlm

import dev.pboard.engine.testutil.En
import dev.pboard.engine.decode.Lexicon
import dev.pboard.engine.decode.LexiconEntry
import java.io.File

/**
 * DEV-ONLY data loader for the stand-in language model and the evaluation corpus.
 *
 * Expects (gitignored) files fetched by tools/dev/fetch_devdata.sh into tools/data/raw/:
 *  - en_50k.txt: hermitdave/FrequencyWords 2018 English top-50k (OpenSubtitles, CC-BY-SA 4.0)
 *  - eng_sentences.tsv: Tatoeba English sentences (CC-BY 2.0 FR)
 *
 * Tatoeba sentences are split deterministically by hash: ~3% are held out for evaluation and never
 * contribute bigram counts. Canonical spellings (capitals, apostrophes) are learned from Tatoeba
 * mid-sentence usage; the unigram is a mixture of Tatoeba and the subtitle frequencies.
 */
class DevData private constructor(val lm: CountBigramLM, val entries: List<LexiconEntry>, val lexicon: Lexicon, val heldOut: List<String>) {
    private val dialects = HashMap<String, DialectFlavour>()

    /** en-GB / en-US flavours of the stand-in model (see [DialectFlavour]). */
    @Synchronized
    fun dialect(tag: String): DialectFlavour = dialects.getOrPut(tag) { DialectFlavour(lm, entries, tag) }


    companion object {
        private val TOKEN = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
        private val FRAGMENTS = setOf(
            "don", "isn", "doesn", "didn", "wasn", "weren", "couldn", "wouldn", "shouldn", "hasn", "haven", "hadn",
            "aren", "mustn", "needn", "ain", "re", "ll", "ve", "s", "t", "m", "d",
        )

        fun projectRoot(): File {
            var f: File? = File(System.getProperty("user.dir")).absoluteFile
            while (f != null) {
                if (File(f, "settings.gradle.kts").exists()) return f
                f = f.parentFile
            }
            error("project root not found from ${System.getProperty("user.dir")}")
        }

        fun rawDir(): File = File(projectRoot(), "tools/data/raw")

        fun available(): Boolean = File(rawDir(), "en_50k.txt").exists() && File(rawDir(), "eng_sentences.tsv").exists()

        @Volatile private var cached: DevData? = null

        @Synchronized
        fun load(): DevData {
            cached?.let { return it }
            check(available()) { "Dev data missing: run tools/dev/fetch_devdata.sh (downloads into ${rawDir()})" }
            return build().also { cached = it }
        }

        /** Tokenizes a sentence into word tokens (apostrophes normalised to '). */
        fun tokens(sentence: String): List<String> =
            TOKEN.findAll(sentence.replace('’', '\'')).map { it.value }.toList()

        fun isHeldOut(sentence: String): Boolean {
            val h = sentence.hashCode() * -0x61c88647
            return Math.floorMod(h ushr 7, 100) < 3
        }

        private fun build(): DevData {
            val t0 = System.nanoTime()
            val sentences = ArrayList<String>(2_100_000)
            val dedupe = HashSet<String>()
            File(rawDir(), "eng_sentences.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.lastIndexOf('\t')
                    val s = line.substring(tab + 1).trim()
                    if (s.isNotEmpty() && dedupe.add(s)) sentences.add(s)
                }
            }
            val train = ArrayList<List<String>>(sentences.size)
            val heldOut = ArrayList<String>()
            for (s in sentences) if (isHeldOut(s)) heldOut.add(s) else train.add(tokens(s))

            // Canonical forms from mid-sentence casing.
            val formCounts = HashMap<String, HashMap<String, Int>>() // lower -> form -> count
            val lowerTotal = HashMap<String, Int>()
            for (toks in train) for ((i, t) in toks.withIndex()) {
                val l = t.lowercase()
                lowerTotal.merge(l, 1, Int::plus)
                if (i > 0) formCounts.getOrPut(l) { HashMap() }.merge(t, 1, Int::plus)
            }
            val canonical = HashMap<String, List<String>>() // lower -> forms, dominant first
            for ((l, total) in lowerTotal) {
                val forms = formCounts[l]
                if (forms == null || forms.isEmpty()) { canonical[l] = listOf(l); continue }
                val sorted = forms.entries.sortedByDescending { it.value }
                val mid = sorted.sumOf { it.value }
                val keep = sorted.filter { it.value >= 0.3 * mid }.map { it.key }.ifEmpty { listOf(sorted[0].key) }
                // An all-caps form seen only at sentence starts or rarely is not a separate word.
                canonical[l] = keep.filter { !(it.length > 1 && it.all { c -> c.isUpperCase() } && total < 20) }.ifEmpty { listOf(l) }
            }
            fun canon(token: String): String {
                val forms = canonical[token.lowercase()] ?: return token.lowercase()
                return if (token in forms) token else forms[0]
            }

            val b = CountBigramLM.Builder()
            val tatUni = HashMap<String, Int>()
            var tatTotal = 0L
            for (toks in train) {
                var prev = b.bos
                for (t in toks) {
                    val c = canon(t)
                    tatUni.merge(c, 1, Int::plus); tatTotal++
                    val id = b.id(c)
                    b.addBigram(prev, id)
                    prev = id
                }
            }
            // Subtitle frequencies (lowercase, contractions split): drop fragments and apostrophe-less junk
            // like "dont"/"im" whose apostrophe form dominates in the clean corpus.
            val apostropheForms = HashMap<String, Int>() // keys -> count of forms with apostrophes
            for ((w, c) in tatUni) if ('\'' in w) apostropheForms.merge(En.keys(w) ?: continue, c, Int::plus)
            val osCounts = HashMap<String, Long>()
            var osTotal = 0L
            File(rawDir(), "en_50k.txt").forEachLine { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEachLine
                val w = line.substring(0, sp)
                val c = line.substring(sp + 1).trim().toLongOrNull() ?: return@forEachLine
                if (!w.all { it in 'a'..'z' }) return@forEachLine
                if (w in FRAGMENTS) return@forEachLine
                if (w.length == 1 && w != "a" && w != "i") return@forEachLine
                val apo = apostropheForms[w]
                if (apo != null) {
                    val plain = canonical[w]?.sumOf { tatUni[it] ?: 0 } ?: 0
                    if (plain < maxOf(3.0, 0.01 * apo)) return@forEachLine
                }
                val cw = canonical[w]?.get(0) ?: w
                osCounts.merge(cw, c, Long::plus); osTotal += c
            }
            val alpha = 0.6
            for ((w, c) in tatUni) {
                if (c < 2 && !osCounts.containsKey(w)) continue
                b.addUnigram(w, alpha * c / tatTotal)
            }
            for ((w, c) in osCounts) b.addUnigram(w, (1 - alpha) * c / osTotal)
            val lm = b.build()
            val entries = lm.vocabulary().filter { it.logPrior > CountBigramLM.KNOWN_MIN }
            val lexicon = Lexicon.build(entries, En.rules)
            System.err.printf("DevData: %d sentences (%d held out), vocab %d, lexicon %d words / %d nodes, %.1fs%n",
                sentences.size, heldOut.size, lm.size, lexicon.size, lexicon.nodeCount, (System.nanoTime() - t0) / 1e9)
            return DevData(lm, entries, lexicon, heldOut)
        }
    }
}
