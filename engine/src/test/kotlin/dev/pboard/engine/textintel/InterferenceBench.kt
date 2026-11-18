package dev.pboard.engine.textintel

import dev.pboard.engine.Context
import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.eval.HitTester
import dev.pboard.engine.eval.HumanSets
import dev.pboard.engine.eval.contextOf
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.LmPack
import dev.pboard.engine.lm.NeuralLm
import dev.pboard.engine.lm.NeuralRescoringLm
import dev.pboard.engine.lm.NgramLanguageModel
import dev.pboard.engine.lm.UserNgramModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Does text intelligence slow typing down? Replays human tap words (TUNE traces) through the keyboard engine as the
 * IME drives it (keystroke = suggestForTaps after each tap; commit = final decode + onWordCommitted + predictNext) and
 * measures their latency with, and without, the text-intelligence work running at the same time on its own
 * background-priority thread, the way the app runs it:
 *  - off: nothing else runs;
 *  - grammar: after every committed word, a grammar check of the sentence so far (the spell checker's request after a
 *    space), latest request only (a newer one replaces a waiting one);
 *  - grammar+compose: also a Smart Compose completion after every keystroke and commit.
 * Run back to back without pauses between keystrokes (the worst case: the background thread always has work).
 * Also reports how long the background checks themselves took.
 *
 * args: <dir with en.lm, en_GB.lmo, en.nnlm, en_morph.txt, tap_tsi.tune.jsonl.gz, gboard_pixel8.json> [words] [rounds]
 *   (see tools/textintel/bench_interference.sh for the device run)
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val words = args.getOrNull(1)?.toInt() ?: 300
    val rounds = args.getOrNull(2)?.toInt() ?: 2
    val geometry = HumanSets.geometry(File(dir, "gboard_pixel8.json"))
    val taps = HumanSets.traces(File(dir, "tap_tsi.tune.jsonl.gz"), geometry).records
    val base = LmPack.open(File(dir, "en.lm"))
    val overlay = File(dir, "en_GB.lmo").readBytes()
    val nn = NeuralLm.read(File(dir, "en.nnlm"))
    val hit = HitTester(geometry)
    val pack = EnglishPack.load("en-GB", base, overlay, UserNgramModel(), nn)
    val engine = PboardEngine.create(listOf(pack.spec()), EngineConfig()).also { it.setGeometry(geometry); it.warmUp() }
    // the text-intelligence models as the app builds them (TextIntelModels): own LSTM state caches
    val static = NgramLanguageModel(pack.model.pack, pack.model.overlay)
    val grammar = GrammarChecker(NeuralRescoringLm(static, nn, cacheSize = 64), static,
        EnglishMorphology.read(File(dir, "en_morph.txt").inputStream()))
    val completer = InlineCompleter(NeuralRescoringLm(pack.model, nn, cacheSize = 24), pack.model)
    println("interference bench: $words words x $rounds rounds; ${Runtime.getRuntime().availableProcessors()} cpus")

    // the background worker: one thread, background priority, newest request wins
    val pending = AtomicReference<(() -> Unit)?>(null)
    val checkTimes = ArrayList<Long>()
    val composeTimes = ArrayList<Long>()
    val stop = AtomicBoolean(false)
    val worker = Thread({
        lowerPriority()
        while (!stop.get()) {
            val job = pending.getAndSet(null)
            if (job == null) { Thread.sleep(1); continue }
            job()
        }
    }, "textintel-bench").apply { isDaemon = true; start() }

    fun replay(n: Int, mode: String, rec: MutableMap<String, MutableList<Long>>?) {
        var done = 0
        for (r in taps) {
            if (done >= n) break
            val pts = if (r.terminator != null) r.points.dropLast(1) else r.points
            if (pts.isEmpty()) continue
            val ctx = contextOf(r.context)
            val typed = pts.mapIndexed { i, p -> hit.hit(p.x, p.y).let { if (i == 0 && ctx.sentenceStart) it.uppercase() else it } }.joinToString("")
            engine.predictNext(ctx)
            for (k in 1..pts.size) {
                val s0 = System.nanoTime()
                engine.suggestForTaps(ctx, pts.subList(0, k), typed.substring(0, k))
                rec?.getOrPut("keystroke") { ArrayList() }?.add(System.nanoTime() - s0)
                if (mode == "grammar+compose") {
                    val c = ArrayList(ctx.words); val p = typed.substring(0, k)
                    pending.set { val t = System.nanoTime(); completer.complete(c, p); synchronized(composeTimes) { composeTimes += System.nanoTime() - t } }
                }
            }
            val c0 = System.nanoTime()
            val s = engine.suggestForTaps(ctx, pts, typed)
            val word = if (s.autoCorrectIndex >= 0) s.candidates[s.autoCorrectIndex].text else typed
            engine.onWordCommitted(ctx, word, s.autoCorrectIndex < 0)
            engine.predictNext(Context(ctx.words + word, false))
            rec?.getOrPut("commit") { ArrayList() }?.add(System.nanoTime() - c0)
            if (mode != "off") {
                val sentence = (ctx.words.takeLastWhile { it.none { ch -> ch in ".!?" } } + word).joinToString(" ") + " "
                val c = ArrayList(ctx.words + word)
                pending.set {
                    val t = System.nanoTime(); grammar.check(sentence); synchronized(checkTimes) { checkTimes += System.nanoTime() - t }
                    if (mode == "grammar+compose") {
                        val t2 = System.nanoTime(); completer.complete(c, ""); synchronized(composeTimes) { composeTimes += System.nanoTime() - t2 }
                    }
                }
            }
            done++
        }
        // let the background thread drain before the next mode starts
        while (pending.get() != null) Thread.sleep(5)
        Thread.sleep(50)
    }

    replay(minOf(100, words), "grammar+compose", null) // warm-up (JIT / ART profile), all code paths
    synchronized(checkTimes) { checkTimes.clear() }; synchronized(composeTimes) { composeTimes.clear() }
    val results = LinkedHashMap<String, MutableMap<String, MutableList<Long>>>()
    repeat(rounds) {
        for (mode in listOf("off", "grammar", "grammar+compose")) {
            replay(words, mode, results.getOrPut(mode) { LinkedHashMap() })
        }
    }
    stop.set(true)
    worker.join(1000)
    fun line(label: String, xs: List<Long>) {
        if (xs.isEmpty()) return
        val s = xs.sorted()
        fun q(f: Double) = s[minOf(s.size - 1, (f * s.size).toInt())] / 1e6
        println("  %-22s n=%6d  p50 %6.2f  p90 %6.2f  p95 %6.2f  p99 %6.2f  max %7.2f ms".format(label, s.size, q(0.5), q(0.9), q(0.95), q(0.99), s.last() / 1e6))
    }
    for ((mode, rec) in results) {
        println("== typing with text intelligence: $mode")
        for ((label, xs) in rec) line(label, xs)
    }
    println("== background work (its own thread)")
    line("grammar check()", checkTimes)
    line("compose complete()", composeTimes)
}

/** Background priority for this thread: android.os.Process on ART (app_process), Thread priority on a JVM. */
private fun lowerPriority() {
    try {
        val p = Class.forName("android.os.Process")
        p.getMethod("setThreadPriority", Int::class.javaPrimitiveType).invoke(null, 10) // THREAD_PRIORITY_BACKGROUND
    } catch (_: Throwable) {
        Thread.currentThread().priority = Thread.MIN_PRIORITY
    }
}
