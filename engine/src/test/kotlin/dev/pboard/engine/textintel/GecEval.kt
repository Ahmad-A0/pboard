package dev.pboard.engine.textintel

import dev.pboard.engine.LanguageModel
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.NeuralLm
import dev.pboard.engine.lm.NeuralRescoringLm
import java.io.File
import java.util.Locale

/**
 * Grammar-check evaluation dump: runs every candidate rule over each sentence of the sets built by
 * tools/textintel/gec_data.py / gec_synth.py and writes, per sentence, every candidate fix with its LM score from
 * the n-gram alone ("ng") and from the n-gram + LSTM rescorer ("nn"). tools/textintel/gec_tune.py then applies
 * thresholds and scores with gec_score.py, so thresholds can be swept without re-running the models.
 *
 *   ./gradlew :engine:gecEval -PgecArgs="<out.jsonl> <dialect en-GB|en-US> <set.jsonl> [more sets...]"
 *
 * Also prints the latency of a full [GrammarChecker.check] per sentence (default thresholds) for both models.
 * Env: GEC_LM_DIR (default app/src/main/assets/lm), GEC_LIMIT (sentences per set), GEC_NEURAL=0 to skip the LSTM.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: <out.jsonl> <dialect> <set.jsonl>..." }
    val dir = File(System.getenv("GEC_LM_DIR") ?: "app/src/main/assets/lm")
    val dialect = args[1]
    val limit = System.getenv("GEC_LIMIT")?.toInt() ?: Int.MAX_VALUE
    val useNeural = System.getenv("GEC_NEURAL") != "0"
    val morph = EnglishMorphology.read(File(dir.parentFile, "textintel/en_morph.txt").inputStream())
    val pack = EnglishPack.load(dialect, dir)
    val ngram = pack.model
    val nn = if (useNeural) NeuralLm.read(File(dir, "en.nnlm")) else null
    val ngChecker = GrammarChecker(ngram, ngram, morph)
    val nnChecker = nn?.let { GrammarChecker(NeuralRescoringLm(ngram, it, cacheSize = 128), ngram, morph) }
    println("dialect $dialect, ${morph.verbCount} verbs, neural ${nn != null}")

    // the shipped thresholds, for tools/textintel/gec_tune.py apply --thresholds
    File(args[0].removeSuffix(".jsonl") + ".thresholds.json").writeText(
        GrammarConfig.DEFAULT_THRESHOLDS.entries.sortedBy { it.key }.joinToString(",\n", "{\n", "\n}\n") { (k, v) ->
            " " + quote(k) + ": " + when (v) { Float.POSITIVE_INFINITY -> "\"inf\""; Float.NEGATIVE_INFINITY -> "\"-inf\""; else -> v.toString() }
        })
    val out = File(args[0]).bufferedWriter()
    val latNg = ArrayList<Double>()
    val latNn = ArrayList<Double>()
    for (setPath in args.drop(2)) {
        var n = 0
        var cands = 0
        File(setPath).useLines { lines ->
            for (line in lines) {
                if (n >= limit) break
                val id = jsonString(line, "id") ?: continue
                // each line is a finished sentence or utterance (the trailing line break ends it; offsets unchanged)
                val text = (jsonString(line, "text") ?: continue) + "\n"
                n++
                // latency of the production call (default thresholds) on a sentence the LSTM has not seen yet
                var t = System.nanoTime(); ngChecker.check(text); latNg += (System.nanoTime() - t) / 1e6
                if (nnChecker != null) { t = System.nanoTime(); nnChecker.check(text); latNn += (System.nanoTime() - t) / 1e6 }
                val a = ngChecker.scoredCandidates(text)
                val b = nnChecker?.scoredCandidates(text)
                val sb = StringBuilder()
                sb.append("{\"id\":").append(quote(id)).append(",\"cands\":[")
                for ((k, p) in a.withIndex()) {
                    val (issue, c) = p
                    if (k > 0) sb.append(',')
                    sb.append("{\"cs\":").append(issue.start).append(",\"ce\":").append(issue.end)
                        .append(",\"cor\":").append(quote(issue.replacement)).append(",\"rule\":").append(quote(c.rule))
                        .append(",\"family\":").append(quote(c.family)).append(",\"ng\":").append(fmt(c.delta))
                    if (b != null) sb.append(",\"nn\":").append(fmt(b[k].second.delta))
                    sb.append('}')
                    cands++
                }
                sb.append("]}\n")
                out.write(sb.toString())
            }
        }
        println("${File(setPath).name}: $n sentences, $cands candidates")
    }
    out.close()
    fun report(name: String, l: List<Double>) {
        if (l.isEmpty()) return
        // drop the first 200 calls (JIT warm-up)
        val s = l.drop(minOf(200, l.size / 4)).sorted()
        println(String.format(Locale.ROOT, "check() latency %-8s n=%d mean %.3f ms  p50 %.3f  p95 %.3f  p99 %.3f  max %.2f",
            name, s.size, s.average(), s[s.size / 2], s[(s.size * 0.95).toInt()], s[(s.size * 0.99).toInt()], s.last()))
    }
    report("n-gram", latNg)
    report("n+lstm", latNn)
}

private fun fmt(v: Float) = if (v.isNaN()) "null" else String.format(Locale.ROOT, "%.3f", v)

private fun quote(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when (c) {
        '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
    }
    return sb.append('"').toString()
}

/** The string value of top-level-ish key [key] in a one-line JSON object (enough for the eval files). */
internal fun jsonString(line: String, key: String): String? {
    val k = "\"$key\": \""
    var i = line.indexOf(k)
    var skip = k.length
    if (i < 0) { val k2 = "\"$key\":\""; i = line.indexOf(k2); skip = k2.length }
    if (i < 0) return null
    val sb = StringBuilder()
    var j = i + skip
    while (j < line.length) {
        val c = line[j]
        if (c == '"') return sb.toString()
        if (c == '\\') {
            j++
            when (val e = line[j]) {
                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                'u' -> { sb.append(line.substring(j + 1, j + 5).toInt(16).toChar()); j += 4 }
                else -> sb.append(e)
            }
        } else sb.append(c)
        j++
    }
    return null
}
