package dev.pboard.engine.eval

import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.decode.LanguageSlot
import dev.pboard.engine.decode.Lexicon
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.decode.TextRules
import dev.pboard.engine.devlm.DevData
import dev.pboard.engine.lm.DialectOverlay
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.LmPack
import dev.pboard.engine.lm.NgramLanguageModel
import dev.pboard.engine.lm.UserNgramModel
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Automated search over the decoder constants on the human TUNE splits: separable CMA-ES (Ros & Hansen 2008)
 * in a normalised [0, 1] box, candidates evaluated in parallel threads, each on fresh engines (one shared
 * lexicon per pack). TEST splits are never read here: confirm the result with the eval at the end.
 *
 * Modes and objectives (lower is better):
 *  - tap: mean over {TSI, VTAP (taps resolved at touch-up, as in the IME), typo corpus} of the word error rate
 *    + 10 × mean false-correction rate (correct words changed; on the typo corpus, correct words broken).
 *    Hard constraints (a candidate is infeasible when worse than the defaults beyond noise: in a paired
 *    per-participant bootstrap, fewer than 10 % of resamples show no regression): TSI WER, TSI and VTAP false
 *    corrections, typo-corpus broken rate, Wikipedia fix rate (its list has only misspellings, so nothing can
 *    be "broken" there). Infeasible candidates rank after every feasible one.
 *  - swipe: mean over {FUTO, HWS} of (1 − top-1) + 0.25 × (1 − top-3), with FUTO and HWS top-1 as constraints.
 *
 * ./gradlew :engine:tune -Ptune.args="--mode tap --lm-dir DIR --threads 16 --pop 16 --gens 30 --out log.tsv"
 */
fun main(args: Array<String>) {
    val a = args.flatMap { it.split(' ') }.filter { it.isNotBlank() }
    fun opt(name: String, def: String) = a.indexOf(name).takeIf { it >= 0 }?.let { a[it + 1] } ?: def
    val mode = opt("--mode", "tap")
    val root = DevData.projectRoot()
    val lmDir = File(opt("--lm-dir", File(root, "app/src/main/assets/lm").path))
    val threads = opt("--threads", Runtime.getRuntime().availableProcessors().toString()).toInt()
    val pop = opt("--pop", "16").toInt()
    val gens = opt("--gens", "30").toInt()
    val sigma0 = opt("--sigma", "0.2").toDouble()
    val limit = opt("--limit", Int.MAX_VALUE.toString()).toInt()
    val seed = opt("--seed", "1").toInt()
    val out = File(opt("--out", "build/tune-$mode.tsv"))
    val tag = opt("--lm-tag", "en-GB")
    // --init path=value,path=value: start the search there (e.g. the best feasible point of an earlier run).
    val init = opt("--init", "").split(',').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }

    val space = if (mode == "swipe") TuneSpace.SWIPE else TuneSpace.TAP
    val task = TuneTask(root, lmDir, tag, mode, limit)
    println("tune $mode: ${space.size} parameters, pop $pop, $gens generations, $threads threads, packs $lmDir ($tag)")
    out.parentFile?.mkdirs()
    out.writeText("gen\tcand\tobjective\t" + task.metricNames.joinToString("\t") + "\t" + space.joinToString("\t") { it.path } + "\n")

    val pool = Executors.newFixedThreadPool(threads)
    fun evaluate(batch: List<DoubleArray>, gen: Int): List<Pair<Double, Map<String, Double>>> {
        val futures: List<Future<Pair<Double, Map<String, Double>>>> = batch.map { x -> pool.submit<Pair<Double, Map<String, Double>>> { task.score(space.overrides(x)) } }
        return futures.mapIndexed { i, f ->
            f.get().also { (obj, m) ->
                synchronized(out) {
                    out.appendText("$gen\t$i\t${"%.5f".format(obj)}\t" + task.metricNames.joinToString("\t") { "%.5f".format(m[it] ?: Double.NaN) } +
                        "\t" + space.values(batch[i]).joinToString("\t") { "%.4f".format(it) } + "\n")
                }
            }
        }
    }

    val defaults = space.initial()
    val base = evaluate(listOf(defaults), 0)[0]
    task.setBaseline()
    println("baseline objective ${"%.5f".format(base.first)} ${task.describe(base.second)}")
    var best = defaults to base
    val x0 = DoubleArray(space.size) { i -> init[space[i].path]?.let { (it - space[i].lo) / (space[i].hi - space[i].lo) } ?: defaults[i] }
    if (init.isNotEmpty()) {
        val start = evaluate(listOf(x0), 0)[0]
        println("start objective ${"%.5f".format(start.first)} ${task.describe(start.second)}")
        if (start.first < best.second.first) best = x0 to start
    }

    val es = SepCmaEs(x0, sigma0, pop, Random(seed))
    for (g in 1..gens) {
        val xs = es.ask()
        val res = evaluate(xs, g)
        es.tell(xs, res.map { it.first })
        res.forEachIndexed { i, r -> if (r.first < best.second.first) best = xs[i] to r }
        println("gen $g: best of gen ${"%.5f".format(res.minOf { it.first })}, best ${"%.5f".format(best.second.first)} " +
            "${task.describe(best.second.second)}, sigma ${"%.3f".format(es.sigma)}")
    }
    pool.shutdown()
    println("best overrides: " + space.overrides(best.first).entries.joinToString(" ") { "--set ${it.key}=${it.value}" })
}

/** One tunable constant: its config path (as for --set) and search box. */
class TuneParam(val path: String, val init: Double, val lo: Double, val hi: Double)

object TuneSpace {
    val TAP = listOf(
        TuneParam("spatial.sigmaX", 0.42, 0.25, 0.70),
        TuneParam("spatial.sigmaY", 0.46, 0.25, 0.70),
        TuneParam("spatial.biasY", 0.0, -0.20, 0.20),
        TuneParam("tap.lmWeight", 1.0, 0.6, 1.6),
        TuneParam("tap.omission", 4.0, 2.0, 7.0),
        TuneParam("tap.omissionSameLetter", 2.6, 1.0, 5.0),
        TuneParam("tap.insertion", 5.5, 3.0, 8.0),
        TuneParam("tap.insertionSameLetter", 3.2, 1.5, 6.0),
        TuneParam("tap.transposition", 4.6, 2.5, 7.0),
        TuneParam("tap.vowelSubstitution", 6.0, 3.0, 9.0),
        TuneParam("tap.missedSpacePenalty", 3.0, 1.0, 6.0),
        TuneParam("autocorrect.oovLogPriorBase", -5.5, -9.0, -2.0),
        TuneParam("autocorrect.oovLogPriorPerChar", -2.4, -3.5, -1.4),
        TuneParam("autocorrect.marginOov", 1.0, 0.0, 3.0),
        TuneParam("autocorrect.marginValid", 7.0, 4.0, 10.0),
        TuneParam("autocorrect.shortExtraMargin", 2.5, 0.5, 5.0),
        TuneParam("autocorrect.marginVariantContextFree", 3.0, 1.0, 5.0),
        TuneParam("tapResolve.priorWeight", 0.25, 0.10, 0.50),
        TuneParam("tapResolve.centreZone", 0.15, 0.05, 0.30),
        TuneParam("tapResolve.contextWeight", 0.5, 0.0, 0.9),
    )
    val SWIPE = listOf(
        TuneParam("swipe.lmWeight", 1.0, 0.5, 1.8),
        TuneParam("swipe.locationWeight", 4.0, 1.0, 8.0),
        TuneParam("swipe.shapeWeight", 6.0, 0.3, 12.0),
        TuneParam("swipe.keyWeight", 2.5, 1.0, 5.0),
        TuneParam("swipe.startWeight", 1.2, 0.4, 6.0),
        TuneParam("swipe.endWeight", 0.35, 0.1, 3.0),
        TuneParam("swipe.endOvershoot", 0.3, 0.0, 0.8),
        TuneParam("swipe.coarsePriorWeight", 0.7, 0.3, 1.6),
        TuneParam("swipe.longWordLocationDecay", 0.15, 0.0, 1.0),
        TuneParam("swipe.pathFitWeight", 20.0, 8.0, 40.0),
        TuneParam("swipe.keySkipCost", 1.5, 0.8, 4.0),
    )
}

fun List<TuneParam>.initial() = DoubleArray(size) { (this[it].init - this[it].lo) / (this[it].hi - this[it].lo) }
fun List<TuneParam>.values(x: DoubleArray) = DoubleArray(size) { this[it].lo + x[it].coerceIn(0.0, 1.0) * (this[it].hi - this[it].lo) }
fun List<TuneParam>.overrides(x: DoubleArray): Map<String, String> {
    val v = values(x)
    return indices.associate { this[it].path to "%.4f".format(Locale.ROOT, v[it]) }
}

/** Loads the TUNE sets and one lexicon per pack once; scores a configuration on fresh engines. */
class TuneTask(root: File, lmDir: File, private val tag: String, private val mode: String, private val limit: Int) {
    private val geometry = HumanSets.geometry(HumanSets.defaultGeometry(root))
    private val splits = File(root, "tools/data/human/splits/v1")
    private val base = LmPack.open(File(lmDir, "en.lm"))
    private val overlay = File(lmDir, "${EnglishPack.dialectOf(tag)}.lmo").readBytes()
    private val lexicon: Lexicon = Lexicon.build(EnglishPack.load(tag, base, overlay).entries().asIterable(), TextRules.forLanguageTag(tag))
    private fun traces(name: String) = HumanSets.traces(File(splits, "$name.tune.jsonl.gz"), geometry).records.take(limit)
    private val tsi by lazy { traces("tap_tsi") }
    private val vtap by lazy { traces("tap_vtap") }
    private val typos by lazy { HumanSets.text(File(splits, "text_typos.tune.jsonl.gz")).take(limit) }
    private val futo by lazy { traces("swipe_futo") }
    private val hws by lazy { traces("swipe_hws") }

    private val wiki by lazy { HumanSets.text(File(splits, "text_wikipedia.tune.jsonl.gz")).take(limit) }

    val metricNames: List<String> = if (mode == "swipe") listOf("feasible", "futoTop1", "futoTop3", "hwsTop1", "hwsTop3")
        else listOf("feasible", "tsiWer", "tsiFc", "vtapWer", "vtapFc", "typoErr", "typoBroken", "wikiFixed")

    /** Per-unit counts of the defaults on each set: the constraint reference. */
    @Volatile private var baseUnits: Map<String, Map<String, IntArray>>? = null

    /** Call right after scoring the defaults (same thread): their per-unit counts are the constraint reference. */
    fun setBaseline() { baseUnits = lastUnitsOfBaseline }
    @Volatile private var lastUnitsOfBaseline: Map<String, Map<String, IntArray>>? = null

    /** (set, numerator index, denominator index) of each constrained rate; lower is better. */
    private val constraints = if (mode == "swipe") listOf(Triple("futo", 1, 0), Triple("hws", 1, 0))
        else listOf(Triple("tsi", 1, 0), Triple("tsi", 3, 2), Triple("vtap", 3, 2), Triple("typos", 3, 2), Triple("wiki", 1, 0))

    /** Worse than the defaults beyond noise: paired bootstrap over units, < 10 % of resamples without regression. */
    private fun regression(set: String, num: Int, den: Int, cand: Map<String, IntArray>): Double {
        val base = baseUnits?.get(set) ?: return 0.0
        val keys = base.keys.filter { it in cand }
        fun rate(m: Map<String, IntArray>, ks: List<String>): Double {
            var n = 0L; var d = 0L
            for (k in ks) { val u = m.getValue(k); n += u[num]; d += u[den] }
            return if (d == 0L) 0.0 else n.toDouble() / d
        }
        val diff = rate(cand, keys) - rate(base, keys)
        if (diff <= 0.0) return 0.0
        val rnd = Random(17)
        var noWorse = 0
        val b = 400
        repeat(b) {
            val ks = List(keys.size) { keys[rnd.nextInt(keys.size)] }
            if (rate(cand, ks) - rate(base, ks) <= 0.0) noWorse++
        }
        return if (noWorse < 0.1 * b) diff else 0.0
    }

    private fun constrained(obj: Double, m: MutableMap<String, Double>, units: Map<String, Map<String, IntArray>>): Pair<Double, Map<String, Double>> {
        lastUnitsOfBaseline = units
        val violations = constraints.map { (set, num, den) -> regression(set, num, den, units.getValue(set)) }.filter { it > 0.0 }
        m["feasible"] = if (violations.isEmpty()) 1.0 else 0.0
        return (if (violations.isEmpty()) obj else 1.0 + violations.size + 10 * violations.sum()) to m
    }

    private fun engine(config: EngineConfig): PboardEngine {
        val lm = NgramLanguageModel(base, DialectOverlay.read(overlay), UserNgramModel())
        return PboardEngine(listOf(LanguageSlot(tag, lm, lexicon)), config).also { it.setGeometry(geometry) }
    }

    fun score(overrides: Map<String, String>): Pair<Double, Map<String, Double>> {
        val config = ConfigOverrides.apply(EngineConfig(), overrides)
        val quiet: (String) -> Unit = {}
        return if (mode == "swipe") {
            val f = SwipeEval(engine(config)).run(futo, 0, quiet)
            val h = SwipeEval(engine(config)).run(hws, 0, quiet)
            val m = mutableMapOf("futoTop1" to (f["top1"] as Double), "futoTop3" to (f["top3"] as Double),
                "hwsTop1" to (h["top1"] as Double), "hwsTop3" to (h["top3"] as Double))
            val obj = ((1 - m.getValue("futoTop1")) + (1 - m.getValue("hwsTop1"))) / 2 + 0.25 * ((1 - m.getValue("futoTop3")) + (1 - m.getValue("hwsTop3"))) / 2
            @Suppress("UNCHECKED_CAST")
            constrained(obj, m, mapOf("futo" to (f["units"] as Map<String, IntArray>), "hws" to (h["units"] as Map<String, IntArray>)))
        } else {
            val t = TapEval(engine(config), geometry, resolveTaps = true, finalOnly = true).run(tsi, 0, quiet)
            val v = TapEval(engine(config), geometry, resolveTaps = true, finalOnly = true).run(vtap, 0, quiet)
            val x = TextEval(engine(config)).run(typos, 0, quiet)
            val missp = (x["misspelt"] as Number).toDouble(); val correct = (x["correct"] as Number).toDouble()
            val fixed = (x["fixed"] as Double?) ?: 0.0; val broken = (x["broken"] as Double?) ?: 0.0
            val typoErr = (missp * (1 - fixed) + correct * broken) / (missp + correct)
            val w = TextEval(engine(config)).run(wiki, 0, quiet)
            val m = mutableMapOf("tsiWer" to (t["wer"] as Double), "tsiFc" to (t["falseCorrectionRate"] as Double),
                "vtapWer" to (v["wer"] as Double), "vtapFc" to (v["falseCorrectionRate"] as Double),
                "typoErr" to typoErr, "typoBroken" to broken, "wikiFixed" to ((w["fixed"] as Double?) ?: 0.0))
            val obj = (m.getValue("tsiWer") + m.getValue("vtapWer") + typoErr) / 3 +
                10 * (m.getValue("tsiFc") + m.getValue("vtapFc") + broken) / 3
            @Suppress("UNCHECKED_CAST")
            constrained(obj, m, mapOf("tsi" to (t["units"] as Map<String, IntArray>), "vtap" to (v["units"] as Map<String, IntArray>),
                "typos" to (x["units"] as Map<String, IntArray>), "wiki" to (w["units"] as Map<String, IntArray>)))
        }
    }

    fun describe(m: Map<String, Double>) = m.entries.joinToString(" ") { "${it.key}=${"%.4f".format(it.value)}" }
}

/** Separable CMA-ES (diagonal covariance) on [0, 1]^n; candidates are clipped to the box when evaluated. */
class SepCmaEs(x0: DoubleArray, sigma0: Double, private val lambda: Int, private val rnd: Random) {
    private val n = x0.size
    private val mu = lambda / 2
    private val w = DoubleArray(mu) { ln(mu + 0.5) - ln(it + 1.0) }.let { raw -> val s = raw.sum(); DoubleArray(mu) { raw[it] / s } }
    private val muEff = 1.0 / w.sumOf { it * it }
    private val cs = (muEff + 2) / (n + muEff + 5)
    private val ds = 1 + 2 * max(0.0, sqrt((muEff - 1) / (n + 1)) - 1) + cs
    private val cc = (4 + muEff / n) / (n + 4 + 2 * muEff / n)
    private val c1 = min(1.0, 2.0 / ((n + 1.3) * (n + 1.3) + muEff) * (n + 2) / 3)
    private val cmu = min(1 - c1, 2 * (muEff - 2 + 1 / muEff) / ((n + 2) * (n + 2) + muEff) * (n + 2) / 3)
    private val chiN = sqrt(n.toDouble()) * (1 - 1.0 / (4 * n) + 1.0 / (21 * n * n))
    private val m = x0.copyOf()
    private val c = DoubleArray(n) { 1.0 }
    private val ps = DoubleArray(n)
    private val pc = DoubleArray(n)
    var sigma = sigma0
        private set
    private var zs: List<DoubleArray> = emptyList()

    fun ask(): List<DoubleArray> {
        zs = List(lambda) { DoubleArray(n) { gauss() } }
        return zs.map { z -> DoubleArray(n) { (m[it] + sigma * sqrt(c[it]) * z[it]).coerceIn(0.0, 1.0) } }
    }

    fun tell(xs: List<DoubleArray>, f: List<Double>) {
        val order = f.indices.sortedBy { f[it] }.take(mu)
        // Steps actually taken (after clipping), in units of sigma.
        val ys = order.map { i -> DoubleArray(n) { (xs[i][it] - m[it]) / sigma } }
        val yw = DoubleArray(n) { j -> ys.indices.sumOf { k -> w[k] * ys[k][j] } }
        for (j in 0 until n) m[j] = (m[j] + sigma * yw[j]).coerceIn(0.0, 1.0)
        for (j in 0 until n) ps[j] = (1 - cs) * ps[j] + sqrt(cs * (2 - cs) * muEff) * yw[j] / sqrt(c[j])
        val psNorm = sqrt(ps.sumOf { it * it })
        val hs = if (psNorm / chiN < 1.4 + 2.0 / (n + 1)) 1.0 else 0.0
        for (j in 0 until n) pc[j] = (1 - cc) * pc[j] + hs * sqrt(cc * (2 - cc) * muEff) * yw[j]
        for (j in 0 until n) {
            val rankMu = ys.indices.sumOf { k -> w[k] * ys[k][j] * ys[k][j] }
            c[j] = (1 - c1 - cmu) * c[j] + c1 * (pc[j] * pc[j] + (1 - hs) * cc * (2 - cc) * c[j]) + cmu * rankMu
        }
        sigma *= exp((cs / ds) * (psNorm / chiN - 1))
        sigma = sigma.coerceIn(1e-3, 0.5)
    }

    private fun gauss(): Double {
        var u: Double
        var v: Double
        var s: Double
        do { u = rnd.nextDouble() * 2 - 1; v = rnd.nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
        return u * sqrt(-2 * ln(s) / s)
    }
}
