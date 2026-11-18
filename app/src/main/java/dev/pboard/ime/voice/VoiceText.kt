package dev.pboard.ime.voice

/**
 * Turns the recogniser's text for one utterance into what goes into the field (docs/voice-eval.md, "Phase 2"):
 *
 * - [clean]: the English pack's model is bilingual (zh-en) and occasionally emits Chinese characters or full-width
 *   punctuation for noisy English; those are dropped. Hesitation fillers ("um", "uh") are dropped as Gboard does.
 * - [fit]: numbers, times, money and dates are written as digits and symbols ([VoiceItn]). The model capitalises and
 *   punctuates each utterance on its own. Fitted to the text before the cursor, an
 *   utterance gets a separating space, a capital at a sentence start, and loses the model's utterance-initial capital
 *   when it continues a sentence after a pause ("I went to the store" … "and bought milk"), except for "I" forms and
 *   all-caps words.
 */
object VoiceText {
    private val fillers = setOf("um", "umm", "uh", "uhh", "uhm", "erm", "er", "hmm", "mm", "mhm")
    private val keepCapital = setOf("I", "I'm", "I'll", "I've", "I'd")
    private const val SENTENCE_END = ".!?"
    private const val NO_SPACE_BEFORE = ".,!?;:)]}%"

    /** Code points the English field should never receive from the recogniser (CJK, full-width forms). */
    private fun foreign(cp: Int): Boolean =
        cp in 0x2E80..0x9FFF || cp in 0xF900..0xFAFF || cp in 0xFF00..0xFFEF || cp in 0x3000..0x303F ||
            cp in 0x20000..0x2FFFF || cp in 0xAC00..0xD7AF

    fun clean(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            sb.append(if (foreign(cp)) " " else String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        val words = sb.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (w in words) {
            val core = w.trimEnd { !it.isLetterOrDigit() }.trimStart { !it.isLetterOrDigit() }
            val trailing = w.substring(w.indexOf(core) + core.length).takeIf { core.isNotEmpty() } ?: w
            if (core.isEmpty()) {
                // stray punctuation (e.g. left behind by dropped characters): attach to the previous word if it has none
                val p = w.filter { it in SENTENCE_END || it in ",;:" }
                if (p.isNotEmpty() && out.isNotEmpty() && out.last().last().isLetterOrDigit()) out[out.size - 1] += p.take(1)
                continue
            }
            if (core.lowercase() in fillers) {
                // "um." ends a sentence: keep the full stop on the previous word
                val end = trailing.firstOrNull { it in SENTENCE_END }
                if (end != null && out.isNotEmpty()) {
                    val last = out.last()
                    if (last.last().isLetterOrDigit()) out[out.size - 1] = last + end
                    else if (last.last() in ",;:") out[out.size - 1] = last.dropLast(1) + end
                }
                continue
            }
            out += w
        }
        return out.joinToString(" ").replace(Regex(" ([,.!?;:])"), "$1").trim()
    }

    /**
     * [utterance] (already [clean]ed or raw) as it should be inserted after [before], the text left of the cursor.
     * [autoCaps]: capitalise at sentence starts (off for fields that ask for no capitalisation).
     */
    fun fit(utterance: String, before: CharSequence?, autoCaps: Boolean = true): String {
        val u = VoiceItn.apply(clean(utterance))
        if (u.isEmpty()) return ""
        val prev = before?.toString().orEmpty()
        val tail = prev.trimEnd(' ', '\t')
        val sentenceStart = tail.isEmpty() || tail.last() in SENTENCE_END || tail.last() == '\n'
        val firstWord = u.substringBefore(' ')
        val body = when {
            sentenceStart && autoCaps -> u.replaceFirstChar { it.uppercaseChar() }
            !sentenceStart && firstWord.trimEnd { !it.isLetter() } !in keepCapital &&
                firstWord.length > 1 && firstWord[0].isUpperCase() && firstWord.drop(1).none { it.isUpperCase() } ->
                u.replaceFirstChar { it.lowercaseChar() }
            else -> u
        }
        val space = prev.isNotEmpty() && !prev.last().isWhitespace() && prev.last() !in "([{\"'/" &&
            body.first() !in NO_SPACE_BEFORE
        return if (space) " $body" else body
    }
}
