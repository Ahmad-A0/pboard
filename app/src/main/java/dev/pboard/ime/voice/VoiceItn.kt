package dev.pboard.ime.voice

/**
 * Inverse text normalisation for English voice typing: the recogniser writes numbers as words ("five thirty pm",
 * "twenty five dollars"); this turns the common written forms back into digits and symbols, the way dictation on
 * Gboard reads. pboard's own rules (no grammar files, no FST runtime):
 *
 * - cardinals: "twenty five" → 25, "one hundred and five" → 105, "twelve thousand five hundred" → 12,500; a lone word
 *   below ten stays a word ("three kids"); "five million" → 5 million; "three point five" → 3.5
 * - digit strings: "five five five one two three four" → 5551234; years: "nineteen eighty four" → 1984,
 *   "twenty twenty four" → 2024, "two thousand and five" → 2005
 * - ordinals: "twenty first" → 21st (a lone ordinal below ten stays a word unless it is a date: "March third" →
 *   March 3rd)
 * - times: "five thirty pm" → 5:30 pm, "seven oh five a m" → 7:05 am, "ten pm" → 10 pm, "six o'clock" → 6 o'clock,
 *   "at five thirty" → at 5:30
 * - money: "twenty five dollars" → $25, "three dollars and fifty cents" → $3.50, "ten pounds" → £10, "five euros" →
 *   €5, "two million dollars" → $2 million; percent: "fifty percent" → 50%
 * - dates: "March third twenty twenty four" → March 3rd, 2024
 */
object VoiceItn {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90,
    )
    private val scales = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val ordinalUnits = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13,
        "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18,
        "nineteenth" to 19,
    )
    private val ordinalTens = mapOf(
        "twentieth" to 20, "thirtieth" to 30, "fortieth" to 40, "fiftieth" to 50, "sixtieth" to 60, "seventieth" to 70,
        "eightieth" to 80, "ninetieth" to 90,
    )
    private val months = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november",
        "december",
    )
    private val currencies = mapOf(
        "dollar" to "$", "dollars" to "$", "bucks" to "$", "pound" to "£", "pounds" to "£", "euro" to "€", "euros" to "€",
    )
    private val timeLead = setOf("at", "by", "until", "till", "from", "around", "about", "before", "after")

    private class Tok(val lead: String, val core: String, val trail: String) {
        val low = core.lowercase()
        fun bare() = lead.isEmpty() && trail.isEmpty()
    }

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        for (raw in text.split(' ')) {
            if (raw.isEmpty()) continue
            val a = raw.indexOfFirst { it.isLetterOrDigit() }
            if (a < 0) { out += Tok(raw, "", ""); continue }
            val b = raw.indexOfLast { it.isLetterOrDigit() }
            val lead = raw.substring(0, a)
            val core = raw.substring(a, b + 1)
            val trail = raw.substring(b + 1)
            // "o'clock" keeps its apostrophe; split hyphenated number words ("twenty-five")
            if (core.contains('-')) {
                val parts = core.split('-')
                if (parts.all { isNumberWord(it.lowercase()) || ordinalValue(it.lowercase()) != null }) {
                    for ((i, p) in parts.withIndex()) out += Tok(if (i == 0) lead else "", p, if (i == parts.lastIndex) trail else "")
                    continue
                }
            }
            out += Tok(lead, core, trail)
        }
        return out
    }

    private fun isNumberWord(w: String) = w in units || w in tens || w in scales
    private fun ordinalValue(w: String): Int? = ordinalUnits[w] ?: ordinalTens[w]

    /** A run of number words: its groups (separately spoken numbers), whether it ended with an ordinal word. */
    private class Span(val start: Int, var end: Int, val groups: MutableList<Long>, val ordinal: Boolean,
                       val roundScale: String?, val words: Int)

    /** Parses number words from [i]; null if [i] does not start a number. */
    private fun parse(t: List<Tok>, i: Int): Span? {
        val groups = ArrayList<Long>()
        var total = 0L
        var current = 0L
        var last = ""   // kind of the previous word: unit, teen, tens, hundred, scale
        var any = false
        var j = i
        var ordinal = false
        var roundScale: String? = null
        fun close() { if (any) groups += total + current; total = 0; current = 0; any = false; last = "" }
        while (j < t.size) {
            val w = t[j].low
            // a comma or full stop inside ends the number
            if (j > i && t[j].lead.isNotEmpty()) break
            val u = units[w]
            val te = tens[w]
            val sc = scales[w]
            val ord = ordinalValue(w)
            when {
                w == "a" && j + 1 < t.size && t[j + 1].low in scales && !any && t[j].bare() -> {
                    current = 1; any = true; last = "unit"; j++; continue   // "a hundred"
                }
                w == "and" && any && (last == "hundred" || last == "scale") && j + 1 < t.size &&
                    (t[j + 1].low in units || t[j + 1].low in tens || ordinalValue(t[j + 1].low) != null) -> { j++; continue }
                u != null -> {
                    if (any && (last == "unit" || last == "teen" || (last == "tens" && u >= 10))) close()
                    current += u; any = true; last = if (u < 10) "unit" else "teen"
                }
                te != null -> {
                    if (any && last != "hundred" && last != "scale") close()
                    current += te; any = true; last = "tens"
                }
                sc != null -> {
                    if (!any) break
                    if (sc == 100L) { current = (if (current == 0L) 1 else current) * 100; last = "hundred" }
                    else { total += (if (current == 0L) 1 else current) * sc; current = 0; last = "scale" }
                    if (sc >= 1_000_000L) roundScale = w
                }
                ord != null -> {
                    if (any && !(last == "tens" && ord < 10) && last != "hundred" && last != "scale") close()
                    current += ord; any = true; ordinal = true; j++; break
                }
                else -> break
            }
            if (sc == null) roundScale = null
            j++
            if (t[j - 1].trail.isNotEmpty()) break
        }
        close()
        if (groups.isEmpty()) return null
        return Span(i, j, groups, ordinal, roundScale, j - i)
    }

    private fun suffix(n: Long): String {
        val r100 = n % 100
        if (r100 in 11..13) return "th"
        return when (n % 10) { 1L -> "st"; 2L -> "nd"; 3L -> "rd"; else -> "th" }
    }

    private fun digits(n: Long): String =
        if (n >= 10_000) "%,d".format(java.util.Locale.US, n) else n.toString()

    /** Value with a trailing "million"/"billion" kept as a word: 5000000 → "5 million". */
    private fun withScale(n: Long, scale: String?): String {
        val s = scales[scale ?: return digits(n)] ?: return digits(n)
        return if (n % s == 0L && n / s < 1000) "${n / s} $scale" else digits(n)
    }

    private fun isAm(t: List<Tok>, k: Int): Int {   // number of tokens forming "am"/"pm" at k, 0 if none
        if (k >= t.size) return 0
        val w = t[k].low
        if (w == "am" || w == "pm" || w == "a.m" || w == "p.m") return 1
        if ((w == "a" || w == "p") && k + 1 < t.size && t[k + 1].low == "m") return 2
        return 0
    }

    private fun amText(t: List<Tok>, k: Int): String = if (t[k].low.startsWith("a")) "am" else "pm"

    fun apply(text: String): String {
        if (text.isBlank()) return text
        val t = tokenize(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < t.size) {
            val span = parse(t, i)
            if (span == null) { out += t[i].lead + t[i].core + t[i].trail; i++; continue }
            val first = t[span.start]
            var end = span.end            // first token after what we consume
            val g = span.groups
            val prev = if (span.start > 0) t[span.start - 1].low else ""
            val prevRaw = if (span.start > 0) t[span.start - 1].core else ""
            var text1: String? = null
            // decimal: N point d d d
            var decimal: String? = null
            if (end < t.size && t[end].low == "point" && t[end - 1].trail.isEmpty() && g.size == 1) {
                val sb = StringBuilder()
                var k = end + 1
                while (k < t.size && units[t[k].low]?.let { it < 10 } == true) {
                    sb.append(units[t[k].low]); k++
                    if (t[k - 1].trail.isNotEmpty()) break
                }
                if (sb.isNotEmpty()) { decimal = "${g[0]}.$sb"; end = k }
            }
            val next = if (end < t.size && t[end - 1].trail.isEmpty()) t[end].low else ""
            val month = months.indexOf(prev).takeIf { it >= 0 && (prev != "may" || prevRaw == "May") }
            when {
                // money
                next in currencies && g.size == 1 -> {
                    val sym = currencies.getValue(next)
                    var amount = decimal ?: withScale(g[0], span.roundScale)
                    var k = end + 1
                    // "... dollars and fifty cents"
                    if (decimal == null && span.roundScale == null && k + 2 < t.size + 1 && k < t.size &&
                        t[end].trail.isEmpty() && t[k].low == "and") {
                        val c = parse(t, k + 1)
                        if (c != null && c.groups.size == 1 && c.groups[0] in 0..99 && c.end < t.size &&
                            t[c.end].low in setOf("cents", "cent", "pence", "p")) {
                            amount = "${g[0]}.${"%02d".format(c.groups[0])}"; k = c.end + 1
                        }
                    }
                    text1 = sym + amount
                    end = k
                }
                next == "percent" || (next == "per" && end + 1 < t.size && t[end + 1].low == "cent") -> {
                    text1 = (decimal ?: digits(g.sum())) + "%"
                    end += if (next == "per") 2 else 1
                }
                next in setOf("cents", "cent") && g.size == 1 && decimal == null -> {
                    text1 = "${g[0]} $next"; end += 1
                }
                // dates: <Month> <day> [year]
                month != null && g.isNotEmpty() && g[0] in 1..31 && decimal == null -> {
                    val day = g[0]
                    text1 = if (span.ordinal && g.size == 1) "$day${suffix(day)}" else "$day"
                    val rest = g.drop(1)
                    val year = when {
                        rest.size == 2 && rest[0] in 10..99 && rest[1] in 0..99 -> rest[0] * 100 + rest[1]
                        rest.size == 1 && rest[0] in 1000..2999 -> rest[0]
                        else -> null
                    }
                    if (year != null) text1 += ", $year"
                    else if (rest.isEmpty() && end < t.size && t[end - 1].trail.isEmpty()) {
                        val y = parse(t, end)
                        val yv = y?.groups?.let { yy ->
                            when {
                                yy.size == 2 && yy[0] in 10..99 && yy[1] in 0..99 -> yy[0] * 100 + yy[1]
                                yy.size == 1 && yy[0] in 1000..2999 -> yy[0]
                                else -> null
                            }
                        }
                        if (y != null && yv != null) { text1 += ", $yv"; end = y.end }
                    } else if (rest.isNotEmpty()) text1 += " " + rest.joinToString(" ") { digits(it) }
                }
                // times: h [mm | oh m] (am|pm|o'clock)
                decimal == null && !span.ordinal && g[0] in 1..12 && g.size <= 3 && timeOf(t, span, end) != null -> {
                    val (s, e) = timeOf(t, span, end)!!
                    text1 = s; end = e
                }
                // years and digit strings
                decimal == null && !span.ordinal && g.size == 2 && g[0] in 11..20 && g[1] in 10..99 ->
                    text1 = "${g[0] * 100 + g[1]}"
                decimal == null && !span.ordinal && g.size >= 2 && g.all { it in 0..9 } ->
                    text1 = g.joinToString("")
                decimal != null -> text1 = if (span.roundScale != null) "$decimal ${span.roundScale}" else decimal
                g.size == 1 && span.words == 1 && g[0] < 10 -> text1 = null   // "three kids", "first"
                g.size == 1 && span.ordinal -> text1 = "${g[0]}${suffix(g[0])}"
                else -> text1 = g.joinToString(" ") { withScale(it, span.roundScale) }
            }
            if (text1 == null) {
                for (k in span.start until span.end) out += t[k].lead + t[k].core + t[k].trail
                i = span.end
                continue
            }
            out += first.lead + text1 + t[end - 1].trail
            i = end
        }
        return out.joinToString(" ")
    }

    /** "5 30 pm" style times starting at [span]: the written time and the index after it, or null. */
    private fun timeOf(t: List<Tok>, span: Span, end0: Int): Pair<String, Int>? {
        val g = span.groups
        val h = g[0]
        var end = end0
        var minutes: Long? = null
        when (g.size) {
            1 -> {
                // "seven oh five"
                if (end + 1 < t.size && t[end - 1].trail.isEmpty() && t[end].low == "oh") {
                    val m = units[t[end + 1].low]
                    if (m != null && m in 0..9) { minutes = m.toLong(); end += 2 }
                }
            }
            2 -> if (g[1] in 10..59) minutes = g[1] else return null
            3 -> if (g[1] in 20..50 && g[1] % 10 == 0L && g[2] in 1..9) minutes = g[1] + g[2] else return null
        }
        val am = if (t[end - 1].trail.isEmpty()) isAm(t, end) else 0
        val clock = am == 0 && end < t.size && t[end - 1].trail.isEmpty() && t[end].low == "o'clock"
        val lead = span.start > 0 && t[span.start - 1].low in timeLead && t[span.start - 1].trail.isEmpty()
        val mm = minutes?.let { ":%02d".format(it) } ?: ""
        return when {
            am > 0 -> "$h$mm ${amText(t, end)}" to end + am
            clock && minutes == null -> "$h o'clock" to end + 1
            lead && minutes != null -> "$h$mm" to end
            else -> null
        }
    }
}
