package dev.pboard.engine.bench

import dev.pboard.engine.Context
import dev.pboard.engine.Suggestions
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.devlm.DevData
import dev.pboard.engine.eval.EvalRecord
import dev.pboard.engine.eval.HumanSets
import dev.pboard.engine.eval.HitTester
import dev.pboard.engine.eval.contextOf
import dev.pboard.engine.testutil.En
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Engine latency benchmarks on real human input: TSI tap traces and FUTO swipes from the TUNE splits
 * (tools/data/human/splits/v1, Gboard Pixel 8 geometry) and the shipped production language pack (en-US).
 * Run with `./gradlew :engine:jmh` (filter: `-Pjmh.includes=Tap`). SampleTime mode reports the mean plus
 * p50/p99/p99.9.
 */
private object Shared {
    private val root = DevData.projectRoot()
    /** The shipped English pack (app/src/main/assets/lm), as the IME runs it. */
    private val base by lazy { dev.pboard.engine.lm.LmPack.open(File(root, "app/src/main/assets/lm/en.lm")) }
    private val splits = File(root, "tools/data/human/splits/v1")
    val geometry by lazy { HumanSets.geometry(HumanSets.defaultGeometry(root)) }
    val taps: List<EvalRecord> by lazy { HumanSets.traces(File(splits, "tap_tsi.tune.jsonl.gz"), geometry).records }
    val swipes: List<EvalRecord> by lazy { HumanSets.traces(File(splits, "swipe_futo.tune.jsonl.gz"), geometry).records }

    fun engine(): PboardEngine {
        val overlay = File(root, "app/src/main/assets/lm/en_US.lmo").readBytes()
        // -Pjmh.neural=true: with the LSTM rescorer (en.nnlm) over the n-gram model; -Pjmh.rerank=<N> re-ranks the
        // final top N (default: EngineConfig's), -Pjmh.rerank=full uses the neural LM inside the search too.
        val nn = if (System.getProperty("pboard.neural") == "true") dev.pboard.engine.lm.NeuralLm.read(File(root, "app/src/main/assets/lm/en.nnlm")) else null
        val pack = dev.pboard.engine.lm.EnglishPack.load("en-US", base, overlay, dev.pboard.engine.lm.UserNgramModel(), nn)
        val config = when (val r = System.getProperty("pboard.rerank")) {
            null, "" -> EngineConfig()
            "full" -> EngineConfig(neuralFullSearch = true)
            else -> EngineConfig(neuralRerankTopN = r.toInt())
        }
        return PboardEngine.create(listOf(pack.spec()), config).also { it.setGeometry(geometry); it.warmUp() }
    }

    class Word(val ctx: Context, val points: List<TouchPoint>, val typed: String)

    /** Words of exactly [letters] letter taps (the terminator touch is dropped). */
    fun tapWords(letters: Int): List<Word> {
        val hit = HitTester(geometry)
        return taps.map { it to it.points.dropLast(1) }.filter { (r, pts) -> val n = En.keys(r.target)?.length ?: 0; (n == letters || (letters >= 10 && n >= 10)) && pts.size == n }.map { (r, pts) ->
            val ctx = contextOf(r.context)
            val typed = pts.mapIndexed { i, p -> hit.hit(p.x, p.y).let { if (i == 0 && ctx.sentenceStart) it.uppercase() else it } }.joinToString("")
            Word(ctx, pts, typed)
        }
    }
}

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgsAppend = ["-Xmx3g"])
open class TapKeystrokeBenchmark {
    /** Word length in letters; every invocation is one keystroke of such a word, with earlier keystrokes cached. */
    @Param("3", "6", "10")
    var letters: Int = 0

    private lateinit var engine: PboardEngine
    private lateinit var words: List<Shared.Word>
    private var w = 0
    private var k = 0

    @Setup(Level.Trial)
    fun setup() {
        engine = Shared.engine()
        words = Shared.tapWords(letters)
        check(words.isNotEmpty())
    }

    @Benchmark
    fun keystroke(): Suggestions {
        val word = words[w]
        k++
        val s = engine.suggestForTaps(word.ctx, word.points.subList(0, k), word.typed.substring(0, k))
        if (k == word.points.size) { k = 0; w = (w + 1) % words.size }
        return s
    }
}

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgsAppend = ["-Xmx3g"])
open class CommitBenchmark {
    private lateinit var engine: PboardEngine
    private lateinit var words: List<Shared.Word>
    private var w = 0

    @Setup(Level.Trial)
    fun setup() {
        engine = Shared.engine()
        words = (4..9).flatMap { Shared.tapWords(it) }.shuffled(kotlin.random.Random(1))
    }

    /** Space pressed on a whole word with nothing cached (worst case): full decode + autocorrect decision + learning hook. */
    @Benchmark
    fun autocorrectOnCommit(): String {
        val word = words[w]; w = (w + 1) % words.size
        val s = engine.suggestForTaps(word.ctx, word.points, word.typed)
        val committed = if (s.autoCorrectIndex >= 0) s.candidates[s.autoCorrectIndex].text else word.typed
        engine.onWordCommitted(word.ctx, committed, s.autoCorrectIndex < 0)
        return committed
    }

    /** Next-word prediction in the context before a word (what the strip shows after the previous commit). */
    @Benchmark
    fun predictNext(): Suggestions {
        val word = words[w]; w = (w + 1) % words.size
        return engine.predictNext(word.ctx)
    }
}

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgsAppend = ["-Xmx3g"])
open class SwipeBenchmark {
    /** short = 2–3 letters, medium = 4–6, long = 7+. */
    @Param("short", "medium", "long")
    var length: String = ""

    private lateinit var engine: PboardEngine
    private lateinit var gestures: List<EvalRecord>
    private var i = 0

    @Setup(Level.Trial)
    fun setup() {
        engine = Shared.engine()
        val range = when (length) { "short" -> 2..3; "medium" -> 4..6; else -> 7..40 }
        gestures = Shared.swipes.filter { (En.keys(it.target)?.length ?: 0) in range }
        check(gestures.isNotEmpty())
    }

    @Benchmark
    fun decode(): Suggestions {
        val r = gestures[i]; i = (i + 1) % gestures.size
        return engine.suggestForSwipe(contextOf(r.context), r.points)
    }
}

/**
 * TF-01 tap-time key resolution, called on the UI thread at every touch-up: one call per letter tap of
 * human TSI words (prefix = the letters before it), with the context's predictions published as after a
 * commit. Must stay well under 0.2 ms at p99 and allocate nothing (check with -prof gc).
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgsAppend = ["-Xmx3g"])
@State(Scope.Thread)
open class TapResolveBenchmark {
    private lateinit var engine: PboardEngine
    private lateinit var ctx: Array<Context>
    private lateinit var prefix: Array<String>
    private lateinit var touch: Array<TouchPoint>
    private lateinit var rect: Array<String>
    private var i = 0

    @Setup(Level.Trial)
    fun setup() {
        engine = Shared.engine()
        val c = ArrayList<Context>(); val p = ArrayList<String>(); val t = ArrayList<TouchPoint>(); val r = ArrayList<String>()
        for (n in 3..9) for (w in Shared.tapWords(n)) {
            for (k in w.points.indices) { c.add(w.ctx); p.add(w.typed.substring(0, k)); t.add(w.points[k]); r.add(w.typed.substring(k, k + 1)) }
        }
        ctx = c.toTypedArray(); prefix = p.toTypedArray(); touch = t.toTypedArray(); rect = r.toTypedArray()
        // One context's predictions are published (the IME calls predictNext after each commit).
        engine.predictNext(ctx[0])
    }

    @Benchmark
    fun resolve(): String {
        val k = i; i = (i + 1) % prefix.size
        return engine.resolveTap(ctx[k], prefix[k], touch[k], rect[k])
    }
}
