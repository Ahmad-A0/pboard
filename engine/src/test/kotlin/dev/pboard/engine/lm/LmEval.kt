package dev.pboard.engine.lm

import java.io.File
import dev.pboard.engine.LanguageModel
import java.util.Locale

/**
 * Prediction benchmark of the shipped pack with the real Kotlin implementation.
 *
 *   ./gradlew :engine:lmEval -PlmArgs="<pack.lm> <evalDir> [overlay.lmo ...]"
 *
 * For every eval set (<evalDir>/<set>.txt: one tokenised sentence per line, as written by
 * tools/lm/evaluate.py) and every overlay (and none), it simulates typing each word token of each sentence:
 *  - next-word top-1 / top-3: is the word among the first 1 / 3 predictions before any letter is typed;
 *  - keystroke savings (KSR): letters are typed one at a time; as soon as the word is among the top-3
 *    completions the user taps it (1 keystroke, the space comes for free). Cost without prediction is
 *    length+1 (word + space). KSR = 1 - cost_with / cost_without.
 * Words are compared case-insensitively (the IME capitalises sentence starts). Out-of-vocabulary targets
 * count as misses. Punctuation tokens ',' are part of the context but not targets.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <pack.lm> <evalDir> [overlay.lmo ...]" }
    val t0 = System.nanoTime()
    val pack = LmPack.open(File(args[0]))
    val base = NgramLanguageModel(pack)
    base.logProb(listOf("hello"), "world")
    val loadMs = (System.nanoTime() - t0) / 1e6
    val t1 = System.nanoTime()
    val overlayFiles = args.drop(2).filter { it.endsWith(".lmo") }
    val again = NgramLanguageModel(LmPack.open(File(args[0])), overlayFiles.firstOrNull()?.let { DialectOverlay.read(File(it)) })
    again.logProb(listOf("good"), "morning")
    val warmMs = (System.nanoTime() - t1) / 1e6
    println("pack ${args[0]}: ${File(args[0]).length() / 1e6} MB, order ${pack.order}, ${pack.nLex} words, " +
        "n-grams ${(2..pack.order).joinToString("/") { pack.count(it).toString() }}; open+first query " +
        "${"%.1f".format(loadMs)} ms cold JVM, ${"%.1f".format(warmMs)} ms warm (incl. overlay)")
    // an .nnlm argument wraps every model in the neural rescorer
    val nn = args.drop(2).firstOrNull { it.endsWith(".nnlm") }?.let {
        val t = System.nanoTime()
        NeuralLm.read(File(it), (System.getenv("LM_EVAL_NN_THREADS") ?: "1").toInt()).also { m ->
            println("neural LM $it: ${File(it).length() / 1e6} MB, nv ${m.nv}, emb ${m.emb}, hid ${m.hid}; " +
                "load ${"%.1f".format((System.nanoTime() - t) / 1e6)} ms")
        }
    }
    fun env(k: String) = System.getenv(k)
    fun wrap(m: NgramLanguageModel): LanguageModel = if (nn == null) m else NeuralRescoringLm(
        m, nn, alpha = env("LM_EVAL_NN_ALPHA")?.toFloat() ?: nn.recommendedAlpha,
        rescoreN = env("LM_EVAL_RESCORE_N")?.toInt() ?: 32, nnExtra = env("LM_EVAL_NN_EXTRA")?.toInt() ?: 8)
    // LM_EVAL_SKIP_BASE=1: only the dialect rows (no "base" row without an overlay)
    val overlays = (if (env("LM_EVAL_SKIP_BASE") == "1") emptyList() else listOf<DialectOverlay?>(null)) +
        overlayFiles.map { DialectOverlay.read(File(it)) }
    val maxSentences = (System.getenv("LM_EVAL_SENTENCES") ?: "3000").toInt()
    val sets = File(args[1]).listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
    latency(wrap(base), File(args[1], "chat.txt"))
    if (nn != null) stepLatency(nn)
    if (maxSentences == 0) return  // LM_EVAL_SENTENCES=0: latency only
    println(String.format(Locale.ROOT, "%-12s %-6s %8s %7s %7s %7s %7s", "set", "dialect", "words", "top1", "top3", "KSR", "oov"))
    for (set in sets) {
        val sentences = set.readLines().filter { it.isNotBlank() }.take(maxSentences)
        for (o in overlays) {
            val lm = wrap(NgramLanguageModel(pack, o))
            val r = evaluate(lm, pack.rules, sentences)
            val d = env("LM_EVAL_DECIMALS") ?: "1"  // more decimals for close comparisons
            println(String.format(Locale.ROOT, "%-12s %-6s %8d %6.${d}f%% %6.${d}f%% %6.${d}f%% %6.2f%%",
                set.nameWithoutExtension, o?.locale ?: "base", r.words, 100.0 * r.top1 / r.words,
                100.0 * r.top3 / r.words, 100.0 * (1 - r.costWith.toDouble() / r.costWithout), 100.0 * r.oov / r.words))
        }
    }
}

class EvalResult {
    var words = 0; var top1 = 0; var top3 = 0; var oov = 0
    var costWith = 0L; var costWithout = 0L
}

private fun contextToken(t: String) = when (t) {
    "<num>" -> "7"
    "<unk>" -> "qqqq"
    else -> t
}

/** Per-commit neural cost: one LSTM step plus the output head (what a new context word costs). */
private fun stepLatency(nn: NeuralLm) {
    var s = nn.initialState()
    val rnd = java.util.Random(1)
    val toks = IntArray(20000) { 6 + rnd.nextInt(nn.nv - 6) }
    repeat(3000) { s = nn.step(s, toks[it]); nn.output(s) }  // JIT warm-up
    val xs = LongArray(toks.size) {
        val a = System.nanoTime()
        s = nn.step(if (it % 20 == 0) nn.initialState() else s, toks[it])
        nn.output(s).logProb(toks[it])
        System.nanoTime() - a
    }
    xs.sort()
    println(String.format(Locale.ROOT, "  neural step + head (per committed word): mean %.0f us, p50 %.0f us, p99 %.0f us",
        xs.average() / 1e3, xs[xs.size / 2] / 1e3, xs[xs.size * 99 / 100] / 1e3))
}

fun evaluate(lm: LanguageModel, rules: LocaleRules, sentences: List<String>): EvalResult {
    val r = EvalResult()
    for (line in sentences) {
        val toks = line.split(' ')
        val ctx = ArrayList<String>()
        for (t in toks) {
            if (t == "," || t == "<num>" || t == "<unk>") { ctx.add(contextToken(t)); continue }
            r.words++
            val target = rules.lower(t)
            if (!lm.isKnown(t)) r.oov++
            val next = lm.predict(ctx, "", 3)
            if (next.isNotEmpty() && rules.lower(next[0].first) == target) r.top1++
            if (next.any { rules.lower(it.first) == target }) r.top3++
            // keystroke simulation over the letters of the word
            val n = t.length
            var cost = n + 1
            if (next.any { rules.lower(it.first) == target }) cost = 1 else {
                for (i in 1..n) {
                    if (i == n) break  // typing the whole word: no saving left
                    val sug = lm.predict(ctx, t.substring(0, i).lowercase(), 3)
                    if (sug.any { rules.lower(it.first) == target }) { cost = i + 1; break }
                }
            }
            r.costWith += cost
            r.costWithout += n + 1
            ctx.add(t)
        }
    }
    return r
}

private fun latency(lm: LanguageModel, file: File) {
    if (!file.exists()) return
    val sentences = file.readLines().filter { it.isNotBlank() }.take(if (lm is NeuralRescoringLm) 1500 else 4000)
    // contexts grow word by word through each sentence, exactly as while typing
    val queries = ArrayList<Pair<List<String>, String>>()
    for (s in sentences) {
        val toks = s.split(' ').map(::contextToken)
        for (i in toks.indices) queries.add(toks.subList(0, i) to toks[i])
    }
    var sink = 0f
    // logProb: a decoder scores many candidates in one context, so time both a fresh context per call and
    // 20 candidate words per context
    repeat(2) { for ((c, w) in queries) sink += lm.logProb(c, w) }  // warm-up (JIT)
    var t = System.nanoTime()
    for ((c, w) in queries) sink += lm.logProb(c, w)
    val lpUs = (System.nanoTime() - t) / 1e3 / queries.size
    val cands = queries.take(2000).map { it.second }
    t = System.nanoTime()
    var n = 0
    for ((c, _) in queries.take(2000)) for (w in cands.subList(0, 20)) { sink += lm.logProb(c, w); n++ }
    val lpSameCtxUs = (System.nanoTime() - t) / 1e3 / n
    // the same, with the context already scored once (a decoder rescoring candidates after the first)
    var warmNs = 0L
    for ((c, _) in queries.take(2000)) {
        sink += lm.logProb(c, cands[0])
        val a = System.nanoTime()
        for (w in cands.subList(1, 21)) sink += lm.logProb(c, w)
        warmNs += System.nanoTime() - a
    }
    val lpWarmUs = warmNs / 1e3 / (2000 * 20)
    val ctxs = queries.map { it.first }.take(20000)
    val prefixes = queries.take(20000).map { (c, w) -> c to w.take(1 + (w.length - 1).coerceAtMost(2)) }
    repeat(2) {
        for (c in ctxs) sink += lm.predict(c, "", 8).size
        for ((c, p) in prefixes) sink += lm.predict(c, p, 8).size + lm.predict(c, p.take(1), 8).size
    }
    fun timed(f: () -> Unit): Long { val a = System.nanoTime(); f(); return System.nanoTime() - a }
    fun stats(xs: LongArray): String {
        xs.sort()
        return String.format(Locale.ROOT, "mean %.0f us, p50 %.0f us, p99 %.0f us, max %.2f ms",
            xs.average() / 1e3, xs[xs.size / 2] / 1e3, xs[xs.size * 99 / 100] / 1e3, xs.last() / 1e6)
    }
    // the typing sequence per word: commit -> next-word prediction (new context) -> 1 letter -> up to 3 letters
    val next = LongArray(prefixes.size)
    val one = LongArray(prefixes.size)
    val pref = LongArray(prefixes.size)
    for (i in prefixes.indices) {
        val (c, p) = prefixes[i]
        next[i] = timed { sink += lm.predict(c, "", 8).size }
        one[i] = timed { sink += lm.predict(c, p.take(1), 8).size }
        pref[i] = timed { sink += lm.predict(c, p, 8).size }
    }
    val vm = System.getProperty("java.vm.name") ?: "JVM"  // "Dalvik" on Android (ART)
    println(String.format(Locale.ROOT, "latency (%s, single caller thread) [%s]", vm, if (sink.isNaN()) "" else "ok"))
    println(String.format(Locale.ROOT, "  logProb: %.2f us (new context each call), %.2f us (same context, 20 candidates), " +
        "%.2f us (context already scored)", lpUs, lpSameCtxUs, lpWarmUs))
    println("  predict next word after a commit (k=8): " + stats(next))
    println("  predict 1-letter prefix (k=8):          " + stats(one))
    println("  predict 1-3 letter prefix (k=8):        " + stats(pref))
}
