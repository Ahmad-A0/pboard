package dev.pboard.engine.devlm

import dev.pboard.engine.LanguageModel
import dev.pboard.engine.decode.Lexicon
import dev.pboard.engine.decode.LexiconEntry
import dev.pboard.engine.decode.TextRules

/**
 * DEV-ONLY: simulates the per-dialect English models the real LM will provide. British/American spelling
 * groups are found in the base vocabulary with [TextRules.variantKey]; in a dialect, its own spelling
 * receives the group's probability mass and the other spelling stays known but [penalty] nats lower.
 */
class DialectFlavour(base: CountBigramLM, baseLexiconEntries: List<LexiconEntry>, val tag: String, private val penalty: Float = 4f) {
    val rules: TextRules = TextRules.forLanguageTag(tag)
    private val british = tag.equals("en-GB", ignoreCase = true)

    /** Lower-case word -> all lower-case spellings in its group (only words that have a variant). */
    val groups: Map<String, List<String>>
    /** Lower-case word -> the spelling this dialect prefers. */
    val preferred: Map<String, String>

    init {
        val byKey = HashMap<String, MutableSet<String>>()
        for (e in baseLexiconEntries) {
            val l = rules.lowercase(e.word)
            if (l != e.word && e.word[0].isUpperCase()) continue // names etc.
            byKey.getOrPut(rules.variantKey(l)) { LinkedHashSet() }.add(l)
        }
        val g = HashMap<String, List<String>>(); val p = HashMap<String, String>()
        for ((key, members) in byKey) {
            if (members.size < 2) continue
            // Keys are American-normalised: the member equal to the key is the American spelling.
            val us = members.firstOrNull { it == key } ?: continue
            val gbForms = members.filter { it != us }
            val choice = if (british) gbForms.maxByOrNull { base.logProb(emptyList(), it) }!! else us
            for (m in members) { g[m] = members.toList(); p[m] = choice }
        }
        groups = g; preferred = p
    }

    val lm: LanguageModel = object : LanguageModel {
        override fun logProb(context: List<String>, word: String): Float {
            val l = rules.lowercase(word)
            val members = groups[l] ?: return base.logProb(context, word)
            val mass = members.maxOf { base.logProb(context, it) }
            return if (preferred[l] == l) mass else mass - penalty
        }

        override fun predict(context: List<String>, prefix: String, limit: Int): List<Pair<String, Float>> {
            val out = LinkedHashMap<String, Float>()
            for ((w, _) in base.predict(context, prefix, limit + 4)) {
                val l = rules.lowercase(w)
                val pw = if (groups.containsKey(l)) preferred[l]!!.takeIf { it.startsWith(prefix.lowercase()) } ?: w else w
                if (!out.containsKey(pw)) out[pw] = logProb(context, pw)
            }
            return out.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
        }

        override fun isKnown(word: String): Boolean = base.isKnown(word) || groups.containsKey(rules.lowercase(word))
    }

    val lexicon: Lexicon = run {
        val prior = HashMap<String, Float>()
        for (e in baseLexiconEntries) prior[e.word] = e.logPrior
        Lexicon.build(baseLexiconEntries.map { e ->
            val l = rules.lowercase(e.word)
            val members = groups[l]
            if (members == null) e else {
                val best = members.maxOf { m -> prior[m] ?: -30f }
                LexiconEntry(e.word, if (preferred[l] == l) best else best - penalty)
            }
        }, rules)
    }
}
