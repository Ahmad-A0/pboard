package dev.pboard.ime.emoji

import android.content.Context
import java.util.Locale

/**
 * Emoji names and keywords from Unicode CLDR (assets/emoji/annotations_*.txt, built by tools/emoji_annotations.py):
 * keyword search for the emoji panel and word-to-emoji candidates for the suggestion strip.
 * English (UK) uses CLDR's en_GB values ("colour", "savouring", ...) over the en base.
 * [popularity]: emoji occurrence counts (assets/emoji/frequency.txt, Emoji Sentiment Ranking 1.0, CC BY-SA 4.0,
 * built by tools/emoji_frequency.py) that rank a word's emoji for the strip.
 */
class EmojiAnnotations private constructor(private val entries: List<Entry>, private val popularity: Map<String, Int>) {
    class Entry(val emoji: String, val name: String, val keywords: List<String>, val order: Int) {
        val nameWords: List<String> = name.split(' ', '-', ':', ',', '’', '\'').filter { it.isNotEmpty() }
    }

    private val byKeyword = HashMap<String, MutableList<Entry>>().also { m ->
        for (e in entries) {
            for (k in e.keywords) m.getOrPut(k) { ArrayList() } += e
            m.getOrPut(e.name) { ArrayList() }.let { if (e !in it) it += e }
        }
    }

    /**
     * Emoji matching [query]: every query word must be the start of a keyword or of a word of the emoji's name.
     * Exact name and keyword matches rank first, then prefix matches; ties keep the keyboard's emoji order.
     */
    fun search(query: String, limit: Int = 200): List<String> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("\\s+"))
        val scored = ArrayList<Pair<Int, Entry>>()
        for (e in entries) {
            var score = if (e.name == q) 1000 else 0
            var all = true
            for (t in tokens) {
                val s = when {
                    e.keywords.any { it == t } -> 100
                    e.nameWords.any { it == t } -> 80
                    e.keywords.any { it.startsWith(t) } -> 40
                    e.nameWords.any { it.startsWith(t) } -> 30
                    e.name.startsWith(q) -> 20
                    else -> 0
                }
                if (s == 0) { all = false; break }
                score += s
            }
            if (all) scored += score to e
        }
        scored.sortWith(compareByDescending<Pair<Int, Entry>> { it.first }.thenBy { it.second.order })
        return scored.take(limit).map { it.second.emoji }
    }

    /** Up to [limit] emoji for a typed or committed [word] (exact name or keyword match), for the strip. */
    fun suggest(word: String, limit: Int = 2): List<String> {
        val w = word.trim().lowercase(Locale.ROOT)
        if (w.length < 2 || w in STOP || w.any { it.isDigit() }) return emptyList()
        val hits = byKeyword[w] ?: return emptyList()
        // The emoji named exactly by the word leads ("cat" -> 🐈). The other keyword matches follow by popularity
        // ("home" -> 🏡 🏠, "car" -> 🚗 🚨, not the nest and hut or the railway car that come first in keyboard
        // order). Emoji too new for the counts follow in keyboard order. A named emoji is joined only by a counted
        // one ("pizza" -> 🍕 alone, as on Gboard 18.6, not with the newer mushroom tagged "pizza").
        val named = hits.firstOrNull { it.name == w }
        val rest = hits.filter { it !== named && (named == null || it.emoji in popularity) }
            .sortedWith(compareByDescending<Entry> { popularity[it.emoji] ?: -1 }.thenBy { it.order })
        return (listOfNotNull(named) + rest).map { it.emoji }.distinct().take(limit)
    }

    companion object {
        /** Words too generic to suggest emoji for (CLDR keywords such as "face", "hand" or "you"). */
        private val STOP = setOf(
            "a", "an", "the", "i", "me", "my", "you", "your", "we", "us", "it", "is", "are", "be", "to", "of", "in",
            "on", "at", "for", "and", "or", "but", "no", "not", "so", "up", "out", "with", "as", "by", "this", "that",
            "face", "hand", "hands", "emoji", "sign", "symbol", "button", "mark", "flag", "person", "man", "woman",
            "people", "body", "type", "skin", "tone", "light", "dark", "medium", "small", "large", "big", "open",
            "closed", "white", "black", "red", "blue", "green", "yellow", "orange", "purple", "brown", "square",
            "circle", "other", "left", "right", "top", "bottom", "letter", "word", "with", "without",
            // Function words that are also the first letters of most words typed (Gboard 18.6 shows no emoji at
            // "Do" or "Can"; CLDR tags "do" on 📋 and ⛔, "can" on 🥫): no emoji flashing up mid-word.
            "do", "does", "did", "doing", "done", "don't", "can", "can't", "could", "will", "would", "shall",
            "should", "may", "might", "must", "have", "has", "had", "get", "got", "let", "was", "were", "been",
            "am", "he", "she", "they", "them", "his", "her", "its", "our", "their", "what", "when", "where", "who",
            "why", "how", "which", "there", "here", "then", "than", "if", "all", "any", "some", "just", "also",
            "very", "too", "more", "much", "list",
        )

        /**
         * Annotations from the lines of assets/emoji/annotations_*.txt (emoji, name, keywords), later files winning,
         * and the "emoji\tcount" lines of assets/emoji/frequency.txt ("#" comments).
         */
        fun parse(files: List<Sequence<String>>, frequency: Sequence<String> = emptySequence()): EmojiAnnotations {
            val popularity = HashMap<String, Int>()
            for (line in frequency) {
                if (line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size == 2) p[1].toIntOrNull()?.let { popularity[p[0]] = it }
            }
            val lines = LinkedHashMap<String, Pair<String, List<String>>>()
            for (seq in files) for (line in seq) {
                val p = line.split('\t')
                if (p.size < 3) continue
                lines[p[0]] = p[1] to p[2].split('|').map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            }
            var i = 0
            return EmojiAnnotations(lines.map { (e, v) -> Entry(e, v.first.lowercase(Locale.ROOT), v.second, i++) }, popularity)
        }

        @Volatile private var cached: Pair<String, EmojiAnnotations>? = null

        private fun keyFor(locale: Locale) =
            if (locale.language == "en" && locale.country.isNotEmpty() && locale.country != "US") "en_GB" else "en"

        /** The annotations for [locale] if already loaded (never blocks). */
        fun peek(locale: Locale): EmojiAnnotations? = cached?.takeIf { it.first == keyFor(locale) }?.second

        /** Annotations for [locale] (en_GB values for English outside the US), loaded once and cached. */
        fun get(context: Context, locale: Locale): EmojiAnnotations {
            val key = keyFor(locale)
            cached?.let { if (it.first == key) return it.second }
            val a = load(context, key)
            cached = key to a
            return a
        }

        private fun load(context: Context, key: String): EmojiAnnotations {
            fun read(file: String): Sequence<String> =
                runCatching { context.assets.open(file).bufferedReader().use { it.readLines() }.asSequence() }
                    .getOrDefault(emptySequence())
            val files = listOf("emoji/annotations_en.txt") + if (key != "en") listOf("emoji/annotations_$key.txt") else emptyList()
            return parse(files.map { read(it) }, read("emoji/frequency.txt"))
        }
    }
}
