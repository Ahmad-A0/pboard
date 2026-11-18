package dev.pboard.engine.bench

import dev.pboard.engine.Context
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.eval.HitTester
import dev.pboard.engine.eval.HumanSets
import dev.pboard.engine.eval.contextOf
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.LmPack
import dev.pboard.engine.lm.NeuralLm
import dev.pboard.engine.lm.UserNgramModel
import java.io.File

/**
 * Engine latency on a real device without building an app: run with app_process (see tools/bench/bench_engine_device.sh)
 * or on a JVM. Replays human tap words and gestures (TUNE traces) through engines with the n-gram LM only, the
 * neural LM re-ranking the final top-N, and the neural LM everywhere, and prints per-call latency percentiles:
 *  - keystroke: suggestForTaps after each tap (incremental, as the IME calls it);
 *  - commit: the word's final decode (the autocorrect decision) + onWordCommitted + predictNext for the next word,
 *    broken down: "after space" (what the IME's engine thread runs before the next word's first keystroke), the
 *    LSTM step inside onWordCommitted, the n-gram prediction list and the neural rescoring inside predictNext;
 *  - swipe final: suggestForSwipe at gesture end; swipe preview: suggestForSwipePreview on partial paths.
 *
 * args: <dir with en.lm, en_GB.lmo, en.nnlm, tap_tsi.tune.jsonl.gz, swipe_futo.tune.jsonl.gz, gboard_pixel8.json>
 *       [words] [gestures] [configs: comma list of ngram, rerank<N>, rerank<N>bg (background prefetch), full]
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val words = args.getOrNull(1)?.toInt() ?: 300
    val gestures = args.getOrNull(2)?.toInt() ?: 150
    val configs = (args.getOrNull(3) ?: "ngram,rerank8,rerank16,rerank32,full").split(',')
    val geometry = HumanSets.geometry(File(dir, "gboard_pixel8.json"))
    val taps = HumanSets.traces(File(dir, "tap_tsi.tune.jsonl.gz"), geometry).records
    val swipes = HumanSets.traces(File(dir, "swipe_futo.tune.jsonl.gz"), geometry).records
    val base = LmPack.open(File(dir, "en.lm"))
    val overlay = File(dir, "en_GB.lmo").readBytes()
    val nn by lazy { NeuralLm.read(File(dir, "en.nnlm")) }
    val hit = HitTester(geometry)
    println("device bench: ${words} words, ${gestures} gestures; ${Runtime.getRuntime().availableProcessors()} cpus")

    for (name in configs) {
        // A "c<N>" suffix sets the number of context predictions (the next word's completion list), e.g. rerank16c32.
        // "-cold": each word / glide starts in a context the neural LM has no state for (field opened, cursor moved);
        // "-prep": the same, after prepareContext (what the IME calls on such a change, off the input path).
        val mode = name.substringAfter("-", "")
        val name0 = name.substringBefore("-")
        val ctxPreds = Regex("c(\\d+)$").find(name0)?.groupValues?.get(1)?.toInt()
        val base0 = name0.replace(Regex("c\\d+$"), "")
        val config = when (base0) {
            "ngram" -> EngineConfig(neuralRerankTopN = 0)
            "full" -> EngineConfig(neuralFullSearch = true)
            // rerankN: the next context's LSTM step runs in onWordCommitted; rerankNbg: on a background thread
            else -> EngineConfig(neuralRerankTopN = base0.removePrefix("rerank").removeSuffix("bg").toInt(), neuralPrefetchInBackground = base0.endsWith("bg"))
        }.let { c -> if (ctxPreds == null) c else c.copy(tap = c.tap.copy(contextPredictions = ctxPreds)) }
        val t0 = System.nanoTime()
        val pack = EnglishPack.load("en-GB", base, overlay, UserNgramModel(), if (base0 == "ngram") null else nn)
        val e = PboardEngine.create(listOf(pack.spec()), config).also { en ->
            // The neural gesture model: a classpath resource on a JVM, a pushed file under app_process (no resources).
            if (en.swipeModel == null && config.swipe.neural) File(dir, "qwerty.pbsw").takeIf { it.exists() }?.let { en.swipeModel = dev.pboard.engine.swipe.SwipeModel.read(it) }
            en.setGeometry(geometry); en.warmUp()
        }
        /** The state the IME leaves before a word / glide: after a commit (predictions shown), or a cold / prepared context. */
        fun enter(ctx: Context) = when (mode) {
            "cold" -> e.forgetNeuralStates()
            "prep" -> { e.forgetNeuralStates(); e.prepareContext(ctx) }
            else -> { e.predictNext(ctx); Unit }
        }
        val loadMs = (System.nanoTime() - t0) / 1e6

        // Per-phase time of the call being measured (engine phase hooks), for the commit breakdown.
        val phases = HashMap<String, Long>()
        e.phaseTimer = { ph, ns -> phases[ph] = (phases[ph] ?: 0L) + ns }
        fun take(ph: String): Long = phases.remove(ph) ?: 0L

        fun replayWords(n: Int, rec: Rec?) {
            var done = 0
            for (r in taps) {
                if (done >= n) break
                val pts = if (r.terminator != null) r.points.dropLast(1) else r.points
                if (pts.isEmpty()) continue
                val ctx = contextOf(r.context)
                val typed = pts.mapIndexed { i, p -> hit.hit(p.x, p.y).let { if (i == 0 && ctx.sentenceStart) it.uppercase() else it } }.joinToString("")
                // The IME asked for predictions in this context right after the previous commit (counted there).
                enter(ctx)
                for (k in 1..pts.size) {
                    val s0 = System.nanoTime()
                    e.suggestForTaps(ctx, pts.subList(0, k), typed.substring(0, k))
                    rec?.add("keystroke", System.nanoTime() - s0)
                }
                phases.clear()
                // Space: the IME commits the last keystroke's result (here: decoded again, counted apart), then the
                // engine thread runs onWordCommitted and predictNext before the next word's first keystroke.
                val c0 = System.nanoTime()
                val s = e.suggestForTaps(ctx, pts, typed)
                val word = if (s.autoCorrectIndex >= 0) s.candidates[s.autoCorrectIndex].text else typed
                val c1 = System.nanoTime()
                e.onWordCommitted(ctx, word, s.autoCorrectIndex < 0)
                val c2 = System.nanoTime()
                e.predictNext(Context(ctx.words + word, false))
                val c3 = System.nanoTime()
                rec?.add("commit", c3 - c0)
                rec?.add(" final decode", c1 - c0)
                rec?.add(" after space", c3 - c1)
                rec?.add("  onWordCommit", c2 - c1)
                rec?.add("   lstm step", take("commit-lstm"))
                rec?.add("  predictNext", c3 - c2)
                rec?.add("   ngram list", take("predict-ngram"))
                rec?.add("   rescore", take("predict-rescore"))
                done++
            }
        }
        fun replayGestures(n: Int, final: MutableList<Long>?, preview: MutableList<Long>?) {
            for (r in swipes.take(n)) {
                val ctx = contextOf(r.context)
                enter(ctx)
                for (frac in listOf(0.5, 0.75)) {
                    val part: List<TouchPoint> = r.points.subList(0, maxOf(2, (r.points.size * frac).toInt()))
                    val p0 = System.nanoTime()
                    e.suggestForSwipePreview(ctx, part)
                    preview?.add(System.nanoTime() - p0)
                }
                val f0 = System.nanoTime()
                e.suggestForSwipe(ctx, r.points)
                final?.add(System.nanoTime() - f0)
            }
        }
        // Warm-up (JIT on a JVM; profile-guided compilation on ART), then measure.
        replayWords(minOf(100, words), null)
        replayGestures(minOf(40, gestures), null, null)
        val rec = Rec()
        val final = ArrayList<Long>(); val preview = ArrayList<Long>()
        replayWords(words, rec)
        replayGestures(gestures, final, preview)
        e.phaseTimer = null
        rec.lists["swipe final"] = final; rec.lists["swipe preview"] = preview
        println("== $name (load ${"%.0f".format(loadMs)} ms)")
        for ((label, xs) in rec.lists) {
            if (xs.isEmpty()) continue
            val s = xs.sorted()
            fun q(f: Double) = s[minOf(s.size - 1, (f * s.size).toInt())] / 1e6
            println("  %-14s n=%5d  p50 %6.2f  p90 %6.2f  p95 %6.2f  p99 %6.2f  max %6.2f ms".format(label, s.size, q(0.5), q(0.9), q(0.95), q(0.99), s.last() / 1e6))
        }
    }
}

/** Named latency samples, printed in insertion order. */
private class Rec {
    val lists = LinkedHashMap<String, MutableList<Long>>()
    fun add(label: String, ns: Long) { lists.getOrPut(label) { ArrayList() }.add(ns) }
}
