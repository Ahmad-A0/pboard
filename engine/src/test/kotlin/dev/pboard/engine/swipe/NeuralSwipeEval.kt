package dev.pboard.engine.swipe

import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.devlm.DevData
import dev.pboard.engine.eval.ConfigOverrides
import dev.pboard.engine.eval.EvalRecord
import dev.pboard.engine.eval.HumanSets
import dev.pboard.engine.eval.contextOf
import dev.pboard.engine.eval.percentiles
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.UserNgramModel
import dev.pboard.engine.testutil.En
import java.io.File

/**
 * Swipe decoder comparison on the human splits (docs/swipe-neural.md), production LM:
 *
 *   ./gradlew :engine:swipeEval -PswipeArgs="--split tune --model app/.../qwerty.pbsw --variant swipe.neuralLmWeight=0.8"
 *
 *   --split tune|test     core splits tools/data/human/splits/v1/swipe_{futo,hws}.<split>.jsonl.gz
 *   --full                the full local sets tools/data/human/out/v1/swipe_*_full.<split>.jsonl.gz instead
 *   --sets futo,hws       which sources (or paths of trace sets, *.jsonl.gz)
 *   --limit N             first N records per set
 *   --model file.pbsw     neural model (default: the one bundled with the engine)
 *   --lm-tag en-GB        dialect of the production LM (default en-GB)
 *   --nnlm                add the neural LM rescorer (en.nnlm)
 *   --geometry file.json  a bench-format geometry the set's points are in (e.g. tools/conformance/geometry/pboard_phone440.json)
 *   --geometry phone      replay on the dev phone's Gboard size set (350–399 dp: row pitch 139 px) instead of Pixel 8
 *   --decoders shark,neural   configurations to run (shark = template matcher, neural = SwipeParams.neural)
 *   --set a.b=v           EngineConfig override for every configuration
 *   --variant a=v,b=w     an extra neural configuration (repeatable)
 *   --out file.json       per-configuration summary as JSON
 *   --hit-centres         give the engine hit-box centres, as the IME does (BuiltKeyboard.engineGeometry), instead of
 *                         the drawn key centres (they differ for edge keys such as a and l)
 *   --ime-sim             feed each path as the IME's touch handling would (KeyboardView TAP mode before the glide
 *                         starts: sliding onto a neighbouring key re-presses it and clears the path so far)
 *   --dump file.jsonl     per-item output of the last configuration (id, target, candidates, in vocabulary)
 *   --warm-context        before each timed decode, call predictNext(context) untimed, as the IME does after the previous
 *                         commit (the neural LM's context state is then cached, as on the device)
 *   --emissions file.bin  also decode precomputed emissions of another model (tools/swipe/futo_bench.py), one
 *                         configuration per variant in the file, with the neural decoder's settings
 */
fun main(args: Array<String>) {
    val a = args.flatMap { it.split(' ') }.filter { it.isNotBlank() }
    var split = "tune"; var full = false; var sets = listOf("futo", "hws"); var limit = Int.MAX_VALUE
    var modelPath: String? = null; var tag = "en-GB"; var nnlm = false; var phone = false
    var decoders = listOf("shark", "neural"); val overrides = LinkedHashMap<String, String>(); val variants = ArrayList<Map<String, String>>()
    var outFile: String? = null; var examples = 0; var emissionsFile: String? = null; var warmContext = false
    var dumpFile: String? = null; var imeSim = false; var hitCentres = false; var geometryFile: String? = null
    var i = 0
    while (i < a.size) {
        when (a[i]) {
            "--split" -> split = a[++i]
            "--full" -> full = true
            "--sets" -> sets = a[++i].split(',')
            "--limit" -> limit = a[++i].toInt()
            "--model" -> modelPath = a[++i]
            "--lm-tag" -> tag = a[++i]
            "--nnlm" -> nnlm = true
            "--geometry" -> a[++i].let { if (it == "phone") phone = true else geometryFile = it }
            "--decoders" -> decoders = a[++i].split(',')
            "--set" -> a[++i].split('=', limit = 2).let { overrides[it[0]] = it[1] }
            "--variant" -> variants.add(a[++i].split(',').associate { kv -> kv.split('=', limit = 2).let { it[0] to it[1] } })
            "--out" -> outFile = a[++i]
            "--examples" -> examples = a[++i].toInt()
            "--emissions" -> emissionsFile = a[++i]
            "--warm-context" -> warmContext = true
            "--dump" -> dumpFile = a[++i]
            "--ime-sim" -> imeSim = true
            "--hit-centres" -> hitCentres = true
            else -> error("unknown option ${a[i]}")
        }
        i++
    }
    val root = DevData.projectRoot()
    val prodDir = File(root, "app/src/main/assets/lm")
    val basePack = EnglishPack.load(tag, prodDir, UserNgramModel(), useNeural = nnlm)
    val model = modelPath?.let { SwipeModel.read(File(root, it).takeIf { f -> f.exists() } ?: File(it)) } ?: SwipeModel.bundled()
    if (model != null) System.err.println("model: conv ${model.conv} k${model.kernel}/s${model.stride}, ${model.layers}×BiLSTM ${model.hid}; ${model.metadata.take(200)}")
    val geoFile = geometryFile?.let { File(root, it).takeIf { f -> f.exists() } ?: File(it) } ?: HumanSets.defaultGeometry(root)
    val pixel8 = HumanSets.geometry(HumanSets.defaultGeometry(root))
    val visual = if (phone) phoneGeometry(pixel8) else HumanSets.geometry(geoFile)
    val geometry = if (hitCentres) hitCentreGeometry(geoFile, phone) else visual
    val known: (String) -> Boolean = { w -> basePack.model.lexIdOf(w) >= 0 }

    val configs = ArrayList<Pair<String, Map<String, String>>>()
    if ("shark" in decoders) configs.add("shark" to mapOf("swipe.neural" to "false"))
    if ("neural" in decoders) configs.add("neural" to mapOf("swipe.neural" to "true"))
    for ((k, v) in variants.withIndex()) configs.add("variant${k + 1} $v" to (mapOf("swipe.neural" to "true") + v))
    val emissions = emissionsFile?.let { readEmissions(File(it)) } ?: emptyMap()
    for (name in emissions.keys) configs.add("ext:$name" to mapOf("swipe.neural" to "true"))

    val summary = LinkedHashMap<String, Any?>()
    for (src in sets) {
        val file = if (src.endsWith(".jsonl.gz")) File(root, src).takeIf { it.exists() } ?: File(src)
            else if (full) File(root, "tools/data/human/out/v1/swipe_${src}_full.$split.jsonl.gz") else File(root, "tools/data/human/splits/v1/swipe_$src.$split.jsonl.gz")
        var recs = HumanSets.traces(file, pixel8).records.take(limit)
        if (phone) recs = recs.map { r -> EvalRecord(r.id, r.context, r.target, r.kind, r.points.map { toPhone(it) }, participant = r.participant) }
        if (imeSim) recs = recs.map { r -> EvalRecord(r.id, r.context, r.target, r.kind, imePath(r.points, geometry), participant = r.participant) }
        println("## ${file.name} (${recs.size} swipes${if (phone) ", phone geometry" else ""})")
        for ((name, cfg) in configs) {
            val config = ConfigOverrides.apply(EngineConfig(), overrides + cfg)
            val pack = EnglishPack.load(tag, basePack.model.pack, File(prodDir, "${EnglishPack.dialectOf(tag)}.lmo").readBytes(), UserNgramModel(),
                if (nnlm) dev.pboard.engine.lm.NeuralLm.read(File(prodDir, "en.nnlm")) else null)
            val engine = PboardEngine.create(listOf(pack.spec()), config)
            if (model != null) engine.swipeModel = model
            engine.setGeometry(geometry)
            val ext = if (name.startsWith("ext:")) emissions.getValue(name.removePrefix("ext:")) else null
            val byPath = java.util.IdentityHashMap<List<TouchPoint>, FloatArray>()
            if (ext != null) for (r in recs) byPath[r.points] = ext[r.id] ?: error("no emissions for ${r.id}")
            NeuralSwipeEncoder.evalEmissions = if (ext != null) { p -> byPath[p] } else null
            val tw = System.nanoTime()
            engine.warmUp()
            val warm = (System.nanoTime() - tw) / 1e6
            val dump = dumpFile?.let { File(it).also { f -> if (name == configs.first().first && src == sets.first()) f.writeText("") } }
            val r = run(engine, recs, known, examples, warmContext, dump?.let { f -> { line: String -> f.appendText(line + "\n") } })
            println("  %-28s %s | warm-up %.0f ms".format(name, r.line(), warm))
            r.failures.forEach { println("    $it") }
            summary["${src}:$name"] = r.json()
        }
    }
    outFile?.let { File(it).writeText(dev.pboard.engine.eval.Json.write(summary) + "\n") }
}

/** Emissions file of tools/swipe/futo_bench.py: variant -> record id -> [T × 27] log-scores (blank first). */
private fun readEmissions(f: File): Map<String, Map<String, FloatArray>> {
    val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    require(b.get().toInt().toChar() == 'P' && b.get().toInt().toChar() == 'B' && b.get().toInt().toChar() == 'E' && b.get().toInt().toChar() == 'M')
    val nv = b.int; val nr = b.int
    fun str(): String { val a = ByteArray(b.short.toInt() and 0xffff); b.get(a); return String(a, Charsets.UTF_8) }
    val names = List(nv) { str() }
    val out = names.associateWith { HashMap<String, FloatArray>() }
    repeat(nr) {
        val id = str()
        val t = b.short.toInt() and 0xffff
        for (n in names) out.getValue(n)[id] = FloatArray(t * SwipeFeatures.N_CLASSES) { b.float }
    }
    return out
}

/**
 * What KeyboardView hands the engine for a touch sequence (app/.../KeyboardView.kt, TAP mode until the glide starts):
 * the path starts at touch-down, but sliding onto another letter key after 0.3 key widths of L1 travel (before the
 * 23 dp glide threshold) re-presses that key and clears the path. Pixel 8 at 420 dpi: glide 60.4 px, key 106.7 px.
 */
internal fun imePath(points: List<TouchPoint>, g: KeyboardGeometry, glide: Float = 23f * 2.625f, kw: Float = 154.6f * 0.69f): List<TouchPoint> {
    fun keyAt(x: Float, y: Float) = g.keys.firstOrNull { k -> kotlin.math.abs(x - k.cx) <= k.width / 2 && kotlin.math.abs(y - k.cy) <= k.height / 2 }
    val out = ArrayList<TouchPoint>()
    if (points.isEmpty()) return out
    val d = points[0]
    var key = keyAt(d.x, d.y)
    out.add(d)
    for (i in 1 until points.size) {
        val p = points[i]
        out.add(p)
        val dx = p.x - d.x; val dy = p.y - d.y
        if (kotlin.math.hypot(dx, dy) > glide) { for (j in i + 1 until points.size) out.add(points[j]); return out }
        val now = keyAt(p.x, p.y)
        if (now != null && key != null && now !== key && kotlin.math.abs(dx) + kotlin.math.abs(dy) > kw * 0.3f) {
            key = now; out.clear()
        }
    }
    return out
}

/** The geometry as the IME hands it to the engine: hit-box centres and sizes (BuiltKeyboard.engineGeometry). */
@Suppress("UNCHECKED_CAST")
internal fun hitCentreGeometry(file: File, phone: Boolean): KeyboardGeometry {
    val o = dev.pboard.engine.eval.Json.parse(file.readText()) as Map<String, Any?>
    val keys = (o["keys"] as List<Map<String, Any?>>).map { k ->
        val name = k["name"] as String; val label = k["label"] as String
        val hit = (k["hit"] as List<Number>).map { it.toFloat() }
        val lab = if (label.length == 1 && (label[0].isLetter() || label[0] in ",.'")) label else name
        KeyGeom(lab, (hit[0] + hit[2]) / 2, (hit[1] + hit[3]) / 2, hit[2] - hit[0], hit[3] - hit[1])
    }
    val screen = o["screen"] as Map<String, Any?>
    val g = KeyboardGeometry(keys, (screen["width"] as Number).toFloat(), (screen["height"] as Number).toFloat())
    return if (phone) phoneGeometry(g) else g
}

/** Dev phone (Gboard 350–399 dp set at 1080 px wide): same key pitch, row pitch 139 px instead of 154.75. */
private const val PHONE_ROW = 139f / 154.75f
private const val ROW_ORIGIN = 1715.5f

private fun toPhone(p: TouchPoint) = TouchPoint(p.x, ROW_ORIGIN + (p.y - ROW_ORIGIN) * PHONE_ROW, p.t)

private fun phoneGeometry(g: KeyboardGeometry) = KeyboardGeometry(
    g.keys.map { k -> KeyGeom(k.label, k.cx, ROW_ORIGIN + (k.cy - ROW_ORIGIN) * PHONE_ROW, k.width, k.height * PHONE_ROW, k.alternates) },
    g.width, g.height,
)

class SwipeResult {
    var n = 0; var top1 = 0; var top3 = 0; var inList = 0; var inVocab = 0; var inVocabTop1 = 0; var inVocabTop3 = 0
    val byLen = LinkedHashMap<String, IntArray>().apply { for (b in BUCKETS) put(b, IntArray(3)) }
    val lat = ArrayList<Long>()
    val failures = ArrayList<String>()

    fun line(): String {
        val p = percentiles(lat.toLongArray())
        return "top1 %s top3 %s all %s | ".format(pct(top1, n), pct(top3, n), pct(inList, n)) +
            byLen.entries.joinToString(" ") { (k, v) -> "$k: ${pct(v[1], v[0])}/${pct(v[2], v[0])}" } +
            " | OOV %s, in-vocab top1 %s top3 %s | ms p50 %.2f p95 %.2f p99 %.2f".format(
                pct(n - inVocab, n), pct(inVocabTop1, inVocab), pct(inVocabTop3, inVocab), p["p50"], p["p95"], p["p99"])
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "n" to n, "top1" to top1.toDouble() / n, "top3" to top3.toDouble() / n,
        "byLength" to byLen.mapValues { (_, v) -> mapOf("n" to v[0], "top1" to v[1].toDouble() / maxOf(1, v[0]), "top3" to v[2].toDouble() / maxOf(1, v[0])) },
        "oovRate" to (n - inVocab).toDouble() / n, "inVocabTop1" to inVocabTop1.toDouble() / maxOf(1, inVocab),
        "inVocabTop3" to inVocabTop3.toDouble() / maxOf(1, inVocab), "latencyMs" to percentiles(lat.toLongArray()),
    )

    companion object {
        val BUCKETS = listOf("2", "3-4", "5-6", "7+")
        fun bucket(len: Int) = when { len <= 2 -> "2"; len <= 4 -> "3-4"; len <= 6 -> "5-6"; else -> "7+" }
        private fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.2f".format(100.0 * a / b)
    }
}

/** Same scoring as EvalMain's SwipeEval: case-insensitive for human sets, top-1/top-3 of the strip. */
fun run(
    engine: PboardEngine, recs: List<EvalRecord>, known: (String) -> Boolean, examples: Int = 0, warmContext: Boolean = false,
    dump: ((String) -> Unit)? = null,
): SwipeResult {
    for (r in recs.take(200)) engine.suggestForSwipe(contextOf(r.context), r.points)
    val res = SwipeResult()
    for (r in recs) {
        val ctx = contextOf(r.context)
        if (warmContext) engine.predictNext(ctx)
        val t0 = System.nanoTime()
        val s = engine.suggestForSwipe(ctx, r.points)
        res.lat.add(System.nanoTime() - t0)
        val texts = s.candidates.map { it.text.lowercase() }
        val target = r.target.lowercase()
        val ok1 = texts.firstOrNull() == target
        val ok3 = target in texts.take(3)
        res.n++
        if (ok1) res.top1++
        if (ok3) res.top3++
        if (target in texts) res.inList++
        dump?.invoke(dev.pboard.engine.eval.Json.write(linkedMapOf("id" to r.id, "target" to r.target, "top" to texts,
            "known" to known(r.target), "ms" to (System.nanoTime() - t0) / 1e6)))
        if (!ok1 && res.failures.size < examples) res.failures.add("${r.id} [${r.context.takeLast(30)}] ${r.target} -> ${texts.take(4)}")
        if (known(r.target)) { res.inVocab++; if (ok1) res.inVocabTop1++; if (ok3) res.inVocabTop3++ }
        val b = res.byLen.getValue(SwipeResult.bucket((En.keys(r.target) ?: "").length))
        b[0]++; if (ok1) b[1]++; if (ok3) b[2]++
    }
    return res
}
