package dev.pboard.translate

/**
 * Splits text into sentences for translation, keeping the exact whitespace between them so the translated text can be
 * reassembled with the same layout. Line breaks always separate sentences. Inside a line, a sentence ends after
 * . ! ? … (and closing quotes/brackets) followed by whitespace and an uppercase letter, digit, opening quote or ¿ ¡,
 * unless the word before the period is a common abbreviation or an initial.
 */
object SentenceSplitter {
    /** A sentence and the whitespace that followed it in the input. */
    data class Segment(val text: String, val trailing: String)

    private val abbreviations = setOf(
        // English
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "approx", "dept", "est", "fig",
        "inc", "ltd", "co", "corp", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "no", "vol", "p", "pp", "ave", "rd", "mt", "gen", "gov", "lt", "col", "capt", "sgt", "rev", "u.s", "a.m", "p.m",
        // Spanish, French, German, Italian, Portuguese
        "sra", "srta", "ud", "uds", "dra", "pág", "mme", "mlle", "m", "z.b", "bzw", "usw", "ca", "nr", "str", "sig",
        "dott", "ecc", "av", "exmo", "exma",
    )

    private const val TERMINATORS = ".!?…。！？"
    private const val CLOSERS = "\"'”’»)]}"

    fun split(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        val n = text.length
        // leading whitespace is attached to an empty first segment so the layout survives
        var i = endOfWhitespace(text, 0)
        if (i > 0) out.add(Segment("", text.substring(0, i)))
        var start = i
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                emit(text, start, i, out)
                val e = endOfWhitespace(text, i)
                addWs(text, out, i, e)
                start = e
                i = e
                continue
            }
            if (c in TERMINATORS) {
                var j = i + 1
                while (j < n && (text[j] in TERMINATORS || text[j] in CLOSERS)) j++
                if (j < n && text[j].isWhitespace() && text[j] != '\n') {
                    val k = endOfWhitespace(text, j)
                    if (k < n && startsSentence(text[k]) && !(c == '.' && isAbbreviation(text, start, i))) {
                        emit(text, start, j, out)
                        addWs(text, out, j, k)
                        start = k
                        i = k
                        continue
                    }
                }
                i = j
                continue
            }
            i++
        }
        emit(text, start, n, out)
        return out
    }

    private fun endOfWhitespace(text: String, from: Int): Int {
        var k = from
        while (k < text.length && text[k].isWhitespace()) k++
        return k
    }

    /** Adds the sentence text[from, to) (trailing spaces moved into the whitespace part). */
    private fun emit(text: String, from: Int, to: Int, out: MutableList<Segment>) {
        if (to <= from) return
        var e = to
        while (e > from && text[e - 1].isWhitespace()) e--
        out.add(Segment(text.substring(from, e), text.substring(e, to)))
    }

    private fun addWs(text: String, out: MutableList<Segment>, from: Int, to: Int) {
        if (to <= from) return
        val ws = text.substring(from, to)
        if (out.isEmpty()) out.add(Segment("", ws))
        else out[out.size - 1] = out.last().let { it.copy(trailing = it.trailing + ws) }
    }

    private fun startsSentence(c: Char) = c.isUpperCase() || c.isDigit() || c in "\"'“‘«¿¡([" || Character.isIdeographic(c.code)

    private fun isAbbreviation(text: String, start: Int, dot: Int): Boolean {
        var b = dot
        while (b > start && !text[b - 1].isWhitespace() && text[b - 1] !in "(\"'“‘«") b--
        val word = text.substring(b, dot).lowercase()
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isLetter()) return true // initials: "J. Smith"
        return word in abbreviations
    }
}
