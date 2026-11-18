package dev.pboard.engine.textintel

import dev.pboard.engine.LanguageModel
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.NeuralLm
import dev.pboard.engine.lm.NeuralRescoringLm
import java.io.File
import java.util.Locale

/**
 * Offline evaluation of Smart Compose-style inline completion ([InlineCompleter]) on held-out text.
 *
 *   ./gradlew :engine:composeEval -PcomposeArgs="<dialect> <set.txt> [more sets]"
 *
 * Every sentence is typed word by word. At each word boundary (after a space) and after the first 1 and 2 letters of
 * every word of 3+ letters, the completer's greedy path is computed once; then, for each probability threshold, the
 * completion it would show is the longest prefix of the path whose joint probability reaches the threshold (and that
 * has at least `minWords` words counting the word being completed; with minWords 2 a single word of 9+ letters also counts, the shipped policy). A shown completion is "acceptance-worthy" when it equals the
 * next words of the sentence exactly (case-insensitive).
 *
 * Reported per threshold: coverage (share of positions showing a completion), exact-match rate of what is shown,
 * mean words shown, and keystroke savings of a user who accepts every exact completion with one gesture (it inserts
 * the words and a space) and otherwise types letter by letter. Env: COMPOSE_LM_DIR, COMPOSE_SENTENCES (default 1500),
 * COMPOSE_NEURAL=0 (n-gram only) | 1 (rescorer only) | both.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <dialect> <set.txt>..." }
    val dir = File(System.getenv("COMPOSE_LM_DIR") ?: "app/src/main/assets/lm")
    val limit = System.getenv("COMPOSE_SENTENCES")?.toInt() ?: 1500
    val mode = System.getenv("COMPOSE_NEURAL") ?: "both"
    val pack = EnglishPack.load(args[0], dir)
    val ngram = pack.model
    val nn = if (mode != "0") NeuralLm.read(File(dir, "en.nnlm")) else null
    val models = buildList<Pair<String, () -> LanguageModel>> {
        if (mode != "1") add("n-gram" to { ngram })
        if (nn != null) add("n-gram+LSTM" to { NeuralRescoringLm(ngram, nn, cacheSize = 64) })
    }
    val thresholds = floatArrayOf(0.05f, 0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f, 0.5f, 0.6f, 0.7f)
    for (setPath in args.drop(1)) {
        val sentences = File(setPath).readLines().map { it.trim() }.filter { it.isNotEmpty() }.take(limit)
        for ((name, make) in models) {
            val completer = InlineCompleter(make(), ngram)
            val nT = thresholds.size
            // [minWords-1][kind 0 = after space, 1 = mid-word][threshold]
            val shown = Array(2) { Array(2) { IntArray(nT) } }
            val exact = Array(2) { Array(2) { IntArray(nT) } }
            val wordsShown = Array(2) { Array(2) { IntArray(nT) } }
            val keysWith = Array(2) { LongArray(nT) }
            var keysWithout = 0L
            val positions = IntArray(2)
            val lat = ArrayList<Double>()
            for (s in sentences) {
                val words = ArrayList<String>()
                for (t in s.split(' ')) {
                    if (t.isEmpty()) continue
                    if (t.all { it in ".!?…" }) break
                    if (t == "," || t.any { it.isLetter() }) words += t
                }
                if (words.isEmpty()) continue
                keysWithout += words.sumOf { it.length + 1 }
                // paths[j][p]: the path at word j after p typed letters (p = 0, 1, 2), and how many of its words match
                val paths = Array(words.size) { arrayOfNulls<Pair<List<InlineCompleter.Step>, Int>>(3) }
                for (j in words.indices) {
                    val truth = words[j]
                    if (truth == ",") continue
                    val letters = truth.all { it.isLetter() || it == '\'' }
                    for (p in 0..2) {
                        if (p > 0 && !(letters && truth.length >= 3 && p < truth.length)) continue
                        val prefix = truth.substring(0, p)
                        positions[if (p == 0) 0 else 1]++
                        val t0 = System.nanoTime()
                        val path = completer.path(words.subList(0, j), prefix, 4, 0.04f)
                        lat += (System.nanoTime() - t0) / 1e6
                        var m = 0
                        while (m < path.size && j + m < words.size && path[m].word.equals(words[j + m], ignoreCase = true)) m++
                        paths[j][p] = path to m
                        for ((ti, th) in thresholds.withIndex()) for (mn in 1..2) {
                            val c = completer.choose(path, prefix, th, mn, if (mn == 2) 9 else Int.MAX_VALUE) ?: continue
                            val k = if (p == 0) 0 else 1
                            shown[mn - 1][k][ti]++
                            wordsShown[mn - 1][k][ti] += c.words.size
                            if (m >= c.words.size) exact[mn - 1][k][ti]++
                        }
                    }
                }
                // a user who accepts exact completions
                for ((ti, th) in thresholds.withIndex()) for (mn in 1..2) {
                    var keys = 0L
                    var j = 0
                    while (j < words.size) {
                        val w = words[j]
                        if (w == ",") { keys += 1; j++; continue }
                        var accepted = false
                        for (p in 0..2) {
                            val (path, m) = paths[j][p] ?: continue
                            val c = completer.choose(path, w.substring(0, p), th, mn, if (mn == 2) 9 else Int.MAX_VALUE) ?: continue
                            if (m >= c.words.size) {
                                keys += p + 1L // letters typed, then the accept gesture (adds the words and a space)
                                j += c.words.size
                                accepted = true
                                break
                            }
                        }
                        if (!accepted) { keys += w.length + 1L; j++ }
                    }
                    keysWith[mn - 1][ti] += keys
                }
            }
            val ls = lat.drop(minOf(300, lat.size / 5)).sorted()
            println(String.format(Locale.ROOT, "== %s  %s  (%d sentences; %d after-space + %d mid-word positions)  path latency mean %.2f ms p50 %.2f p95 %.2f p99 %.2f",
                File(setPath).name, name, sentences.size, positions[0], positions[1], ls.average(), ls[ls.size / 2], ls[(ls.size * 0.95).toInt()], ls[(ls.size * 0.99).toInt()]))
            println("minWords thresh | after space: shown%  exact%  words | mid-word: shown%  exact%  words | KSR%")
            fun pct(a: Int, b: Int) = if (b == 0) 0.0 else 100.0 * a / b
            for (mn in 0..1) for ((ti, th) in thresholds.withIndex()) {
                println(String.format(Locale.ROOT, "  %d    %.2f  |        %6.2f  %6.1f  %5.2f |     %6.2f  %6.1f  %5.2f | %5.2f",
                    mn + 1, th,
                    pct(shown[mn][0][ti], positions[0]), pct(exact[mn][0][ti], shown[mn][0][ti]),
                    if (shown[mn][0][ti] == 0) 0.0 else wordsShown[mn][0][ti].toDouble() / shown[mn][0][ti],
                    pct(shown[mn][1][ti], positions[1]), pct(exact[mn][1][ti], shown[mn][1][ti]),
                    if (shown[mn][1][ti] == 0) 0.0 else wordsShown[mn][1][ti].toDouble() / shown[mn][1][ti],
                    100.0 * (1.0 - keysWith[mn][ti].toDouble() / keysWithout)))
            }
        }
    }
}
