package dev.pboard.engine.eval

import dev.pboard.engine.Context
import dev.pboard.engine.KeyboardEngine
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.Suggestions
import dev.pboard.engine.TouchPoint
import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.devlm.DevData
import dev.pboard.engine.testutil.En
import dev.pboard.engine.testutil.Layouts
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Evaluation harness: `./gradlew :engine:eval` (args via -Peval.args="...").
 *
 * Format-driven: any JSONL trace set (see [EvalSets]) can be evaluated, synthetic or human; records are
 * evaluated according to their "kind" and each file's own geometry.
 *
 *   --sets a.jsonl,b.jsonl  sets to evaluate (default: the synthetic tools/bench/sets/{taps,swipes}-v3.jsonl)
 *   --regen                 regenerate the synthetic default sets from the held-out corpus
 *   --limit N               evaluate only the first N records of each set
 *   --set a.b=v             override an EngineConfig field, e.g. --set tap.lmWeight=1.2 --set swipe.keyWeight=3
 *   --examples N            print N failure examples per category
 *   --human tune|test       evaluate the human v1 splits (tools/data/human/splits/v1, see docs/eval-data.md)
 *   --geometry file         geometry for gzip human sets (default tools/data/human/geometry/gboard_pixel8.json)
 *   --lm prod               use the shipped language packs (app/src/main/assets/lm) instead of the dev stand-in
 *   --lm-tag en-GB|en-US    dialect for records without one (production LM)
 *   --learn target|committed|none  what the simulated user teaches the engine after each word (default: the
 *                           intended word, i.e. mistakes are fixed before moving on)
 *   --diagnose              swipe: report where targets are lost (pruning stage) vs. ranked below others
 *   --variant a=v,b=w       evaluate an extra configuration (repeatable) in the same run, for sensitivity checks;
 *                           the base configuration is always evaluated first
 */
fun main(args: Array<String>) {
    val opts = EvalOptions.parse(args)
    val root = DevData.projectRoot()
    val setsDir = File(root, "tools/bench/sets")
    val tapFile = File(setsDir, "taps-v3.jsonl")
    val swipeFile = File(setsDir, "swipes-v3.jsonl")
    val dialectFile = File(setsDir, "dialect-v2.jsonl")
    val data by lazy { DevData.load() }
    // Production language packs (app/src/main/assets/lm), one per dialect tag, when --lm prod.
    val prodDir = opts.lmDir?.let { File(it) } ?: File(root, "app/src/main/assets/lm")
    val packs = HashMap<String, dev.pboard.engine.lm.LanguagePack>()
    fun pack(tag: String) = packs.getOrPut(tag) { dev.pboard.engine.lm.EnglishPack.load(tag, prodDir, dev.pboard.engine.lm.UserNgramModel()) }
    // --neural: the LSTM rescorer (en.nnlm) over the n-gram model, shared between dialects.
    val neural by lazy { dev.pboard.engine.lm.NeuralLm.read(File(prodDir, "en.nnlm")) }
    val defaults = listOf(tapFile, swipeFile, dialectFile)
    if (opts.regen || (opts.sets.isEmpty() && opts.human == null && defaults.any { !it.exists() })) {
        EvalGen.generate(data.heldOut, Layouts.pixel8Qwerty(), tapFile, swipeFile)
        EvalGen.generateDialect(data, Layouts.pixel8Qwerty(), dialectFile)
    }
    val humanDir = File(root, "tools/data/human/splits/v1")
    val humanFiles = opts.human?.let { split ->
        listOf("tap_tsi", "tap_vtap", "swipe_futo", "swipe_hws", "text_typos", "text_wikipedia").map { File(humanDir, "$it.$split.jsonl.gz") }
    } ?: emptyList()
    val files = (opts.sets.map { File(it).let { f -> if (f.isAbsolute) f else File(root, it) } } + humanFiles).ifEmpty { defaults }
    val humanGeometry by lazy { HumanSets.geometry(opts.geometry?.let { File(root, it) } ?: HumanSets.defaultGeometry(root)) }
    val report = StringBuilder()
    fun out(s: String) { println(s); report.appendLine(s) }
    val summary = LinkedHashMap<String, Any?>()
    for ((vi, variant) in (listOf(emptyMap<String, String>()) + opts.variants).withIndex()) {
    val overrides = opts.overrides + variant
    out("config overrides: $overrides" + if (opts.prod) "  [production LM${if (opts.neural) " + neural rescorer" else ""}, ${opts.lmTag} unless the record says otherwise]" else "  [dev stand-in LM]")
    // One engine per (dialect, geometry). Dev LM: records without a dialect use the neutral stand-in model.
    // Production LM: the record's dialect, else --lm-tag; a fresh user model per engine.
    fun engineFor(dialect: String?, g: KeyboardGeometry, text: Boolean = false): PboardEngine {
        // Text-level sets decode words from their text alone, which the IME only does for re-opened words (no
        // autocorrection there): switch it on to measure what a text-only decode would correct.
        val config = ConfigOverrides.apply(EngineConfig(), (if (text) mapOf("autocorrect.reopenedWords" to "true") else emptyMap()) + overrides)
        val e = if (opts.prod) {
            val tag = dialect ?: opts.lmTag
            val overlay = File(prodDir, "${dev.pboard.engine.lm.EnglishPack.dialectOf(tag)}.lmo").readBytes()
            PboardEngine.create(listOf(dev.pboard.engine.lm.EnglishPack.load(tag, pack(tag).model.pack, overlay, dev.pboard.engine.lm.UserNgramModel(),
                if (opts.neural) neural else null).spec()), config)
        } else if (dialect == null) PboardEngine(data.lm, data.lexicon, config)
        else data.dialect(dialect).let { PboardEngine(it.lm, it.lexicon, config) }
        return e.also { it.setGeometry(g) }
    }
    val tag = if (vi == 0) "" else "variant$vi:"
    for (f in files) {
        if (HumanSets.isHuman(f) && HumanSets.isTextSet(f)) {
            out("## ${f.name}")
            val engine = engineFor(null, humanGeometry, text = true)
            summary["$tag${f.name}:text"] = TextEval(engine).run(HumanSets.text(f).take(opts.limit), opts.examples, ::out)
            continue
        }
        val set = if (HumanSets.isHuman(f)) HumanSets.traces(f, humanGeometry) else EvalSets.read(f)
        val recs = set.records.take(opts.limit)
        out("## ${f.name}")
        for ((dialect, group) in recs.groupBy { it.dialect }) {
            val label = "$tag${f.name}${dialect?.let { ":$it" } ?: ""}"
            if (dialect != null) out("# dialect $dialect")
            val taps = group.filter { it.kind == "tap" }
            val swipes = group.filter { it.kind == "swipe" }
            val wrongDialect: ((String, String) -> Boolean)? = dialect?.let { d ->
                if (opts.prod) {
                    // Same VarCon spelling class in the pack, different spelling.
                    val m = pack(d).model
                    return@let { committed: String, target: String ->
                        val c = m.lexIdOf(committed); val t = m.lexIdOf(target)
                        fun rep(id: Int) = m.pack.variantOf(id).let { v -> if (v >= 0) v else id }
                        c >= 0 && t >= 0 && !committed.equals(target, true) && rep(c) == rep(t)
                    }
                }
                val fl = data.dialect(d)
                return@let { committed: String, target: String ->
                    val c = fl.rules.lowercase(committed); val t = fl.rules.lowercase(target)
                    c != t && fl.groups[t]?.contains(c) == true
                }
            }
            // PBOARD_PARTIAL=1: the partial-word set instead (prefixes of the real words, completion on space).
            if (taps.isNotEmpty() && System.getenv("PBOARD_PARTIAL") == "1") summary["$label:partial"] = PartialEval(engineFor(dialect, set.geometry), set.geometry, opts.resolveTaps).run(taps, opts.examples, ::out)
            else if (taps.isNotEmpty()) summary["$label:tap"] = TapEval(engineFor(dialect, set.geometry), set.geometry, wrongDialect, opts.learn, opts.resolveTaps, opts.keysOnly).run(taps, opts.examples, ::out)
            val known: (String) -> Boolean = if (opts.prod) { w -> pack(dialect ?: opts.lmTag).model.lexIdOf(w) >= 0 }
                else { w -> data.lexicon.contains(w) || data.lexicon.contains(w.lowercase()) || data.lexicon.contains(w.replaceFirstChar { it.uppercase() }) }
            if (swipes.isNotEmpty()) summary["$label:swipe"] = SwipeEval(engineFor(dialect, set.geometry), wrongDialect, known, opts.diagnose).run(swipes, opts.examples, ::out)
        }
    }
    }
    val outDir = File(root, "engine/build/eval").also { it.mkdirs() }
    File(outDir, "summary.json").writeText(Json.write(summary) + "\n")
    File(outDir, "report.txt").writeText(report.toString())
}

class EvalOptions(
    val regen: Boolean, val limit: Int, val sets: List<String>,
    val overrides: Map<String, String>, val examples: Int, val variants: List<Map<String, String>> = emptyList(),
    val human: String? = null, val geometry: String? = null, val diagnose: Boolean = false,
    val prod: Boolean = false, val lmTag: String = "en-US", val learn: String = "target", val resolveTaps: Boolean = false, val keysOnly: Boolean = false, val neural: Boolean = false, val lmDir: String? = null,
) {
    companion object {
        fun parse(args: Array<String>): EvalOptions {
            var regen = false; var limit = Int.MAX_VALUE; var examples = 12
            val sets = ArrayList<String>()
            val ov = LinkedHashMap<String, String>()
            val variants = ArrayList<Map<String, String>>()
            var human: String? = null; var geometry: String? = null; var diagnose = false; var prod = false; var lmTag = "en-US"; var learn = "target"; var resolveTaps = false; var keysOnly = false; var neural = false; var lmDir: String? = null
            val a = args.flatMap { it.split(' ') }.filter { it.isNotBlank() }
            var i = 0
            while (i < a.size) {
                when (a[i]) {
                    "--regen" -> regen = true
                    "--limit" -> limit = a[++i].toInt()
                    "--sets" -> sets.addAll(a[++i].split(',').filter { it.isNotBlank() })
                    "--examples" -> examples = a[++i].toInt()
                    "--set" -> { val (k, v) = a[++i].split('=', limit = 2); ov[k] = v }
                    "--human" -> human = a[++i]
                    "--diagnose" -> diagnose = true
                    "--lm" -> prod = a[++i] == "prod"
                    "--lm-tag" -> lmTag = a[++i]
                    "--learn" -> learn = a[++i]
                    "--resolve-taps" -> resolveTaps = true
                    "--keys-only" -> keysOnly = true
                    "--neural" -> neural = true
                    "--lm-dir" -> lmDir = a[++i]
                    "--geometry" -> geometry = a[++i]
                    "--variant" -> variants.add(a[++i].split(',').associate { kv -> kv.split('=', limit = 2).let { it[0] to it[1] } })
                    else -> error("unknown option ${a[i]}")
                }
                i++
            }
            return EvalOptions(regen, limit, sets, ov, examples, variants, human, geometry, diagnose, prod, lmTag, learn, resolveTaps, keysOnly, neural, lmDir)
        }
    }
}

/** Skeletons of words whose apostrophe form is a different word ("its"/"it's", "were"/"we're", "ill"/"I'll"…). */
val CONTRACTION_AMBIGUOUS = setOf("its", "were", "ill", "well", "id", "hell", "shell", "wed", "cant", "wont", "lets", "shed", "hed", "shes", "whos", "youre", "theyre", "im", "ive", "dont")
fun isContractionAmbiguous(word: String) = En.rules.skeleton(word) in CONTRACTION_AMBIGUOUS

private val CONTEXT_TOKEN = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[.!?]+")

/** Context as the engine sees it from the text before the word. */
fun contextOf(text: String): Context {
    val t = text.trimEnd()
    val start = t.isEmpty() || t.last() in ".!?"
    // Words plus sentence-ending punctuation as tokens, as the IME passes them (the LM needs the boundaries).
    return Context(CONTEXT_TOKEN.findAll(t.replace('’', '\'')).map { it.value }.toList(), start)
}

fun percentiles(nanos: LongArray): Map<String, Double> {
    if (nanos.isEmpty()) return emptyMap()
    val s = nanos.sortedArray()
    fun p(q: Double) = s[((s.size - 1) * q).toInt()] / 1e6
    return linkedMapOf("p50" to p(0.5), "p90" to p(0.9), "p95" to p(0.95), "p99" to p(0.99), "max" to s.last() / 1e6, "mean" to s.average() / 1e6)
}

private fun fmtMs(m: Map<String, Double>) = m.entries.joinToString("  ") { "%s=%.2fms".format(it.key, it.value) }
private fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.2f%%".format(100.0 * a / b)

/**
 * What a keyboard registers for a touch, from the set's geometry alone: among letter keys whose
 * vertical extent contains the touch, the one with the nearest centre in x (keys tile rows); outside
 * every row, the nearest letter key.
 */
class HitTester(private val g: KeyboardGeometry) {
    private val letters = g.keys.filter { it.label.length == 1 && Character.isLetter(it.label[0]) }
    fun hit(x: Float, y: Float): String {
        var best = letters.first(); var bestD = Float.MAX_VALUE
        for (k in letters) {
            if (y < k.cy - k.height / 2 || y >= k.cy + k.height / 2) continue
            val d = abs(k.cx - x)
            if (d < bestD) { bestD = d; best = k }
        }
        if (bestD == Float.MAX_VALUE) for (k in letters) {
            val d = (k.cx - x) * (k.cx - x) + (k.cy - y) * (k.cy - y)
            if (d < bestD) { bestD = d; best = k }
        }
        return best.label
    }
}

/**
 * Replays tap traces keystroke by keystroke. [wrongDialect] (committed, target) flags a commit that is
 * the target's other-dialect spelling; it is reported separately and must stay at zero.
 */
class TapEval(private val engine: KeyboardEngine, g: KeyboardGeometry, private val wrongDialect: ((String, String) -> Boolean)? = null,
              private val learn: String = "target", private val resolveTaps: Boolean = false, private val keysOnly: Boolean = false,
              /** Decode each word once with all its taps (same result; no keystroke savings or per-keystroke latency). */
              private val finalOnly: Boolean = false) {
    private val hit = HitTester(g)

    private val deleteRect: FloatArray? = g.keys.firstOrNull { it.label == "delete" }
        ?.let { floatArrayOf(it.cx - it.width / 2, it.cy - it.height / 2, it.cx + it.width / 2, it.cy + it.height / 2) }
    private val spaceRect: FloatArray? = g.keys.firstOrNull { it.label == "space" || it.label == " " }
        ?.let { floatArrayOf(it.cx - it.width / 2, it.cy - it.height / 2, it.cx + it.width / 2, it.cy + it.height / 2) }

    private fun inRect(r: FloatArray?, p: TouchPoint) = r != null && p.x >= r[0] && p.x < r[2] && p.y >= r[1] && p.y < r[3]

    /**
     * PBOARD_IME_TOUCHES=1: every touch of a word does what it does in the IME. Touches on the space bar, the delete key
     * and the other keys go to [KeyboardEngine.resolveTap] with that key (inside a word); one that stays the key breaks
     * the word (".", ",", enter, the emoji/symbols keys: the word comes out wrong), deletes the letter before (delete),
     * types nothing (shift, a space at the word's start) or splits the word (space: replayed with split-word repair).
     * Touches above the letter rows (on the suggestion strip) break the word. Default: every touch is a letter tap on
     * the nearest letter (delete touches delete), as the decoder sees ideal key handling.
     */
    private val imeTouches = System.getenv("PBOARD_IME_TOUCHES") == "1"
    private val fnRects: List<Pair<String, FloatArray>> = g.keys.filter { !(it.label.length == 1 && Character.isLetter(it.label[0])) }
        .map { it.label to floatArrayOf(it.cx - it.width / 2, it.cy - it.height / 2, it.cx + it.width / 2, it.cy + it.height / 2) }
    private val dotRect: FloatArray? = fnRects.firstOrNull { it.first == "." }?.second
    private val lettersTop = g.keys.filter { it.label.length == 1 && Character.isLetter(it.label[0]) }.minOf { it.cy - it.height / 2 }

    /** The non-letter key a touch lands on ("off" above the letter rows), or null on a letter key. */
    private fun nonLetterAt(p: TouchPoint): String? {
        for ((l, r) in fnRects) if (inRect(r, p)) return if (l == " ") "space" else l
        return if (p.y < lettersTop) "off" else null
    }

    private val WORD_START_KEYS = setOf("space", ".", "shift")

    private class ImeWord(val taps: List<TouchPoint>, val rect: String, val resolved: String, val broken: Boolean, val splitAt: Int)

    private fun imeWord(r: EvalRecord, ctx: Context): ImeWord {
        val pts = if (r.terminator != null) r.points.dropLast(1) else r.points
        val taps = ArrayList<TouchPoint>(); val rect = StringBuilder(); val res = StringBuilder()
        var broken = false; var splitAt = -1
        for (p in pts) {
            val first = res.isEmpty() && ctx.sentenceStart
            val fk = nonLetterAt(p)
            val near = hit.hit(p.x, p.y).let { if (first) it.uppercase() else it }
            val out = when {
                fk == "off" -> fk
                fk != null && res.isEmpty() && fk !in WORD_START_KEYS -> fk
                fk != null && res.isEmpty() -> engine.resolveTap(ctx, "", p, fk)
                else -> engine.resolveTap(ctx, res.toString(), p, fk ?: near)
            }
            when {
                out.length == 1 && Character.isLetter(out[0]) -> { taps.add(p); rect.append(near); res.append(if (first) out.uppercase() else out.lowercase()) }
                out == "delete" -> if (taps.isNotEmpty()) { taps.removeAt(taps.size - 1); rect.setLength(rect.length - 1); res.setLength(res.length - 1) } else broken = true
                // Shift types nothing; a space at the word's start only doubles the space before it (scored alike).
                out == "shift" || (out == "space" && res.isEmpty()) -> {}
                out == "space" -> { if (splitAt < 0) splitAt = taps.size; taps.add(p); rect.append(near); res.append(near) }
                else -> broken = true
            }
        }
        return ImeWord(taps, rect.toString(), res.toString(), broken, splitAt)
    }

    private var lastSplitJoined = false

    /**
     * The word as the IME leaves it when a tap at [splitAt] became a space: first part committed by the space, the
     * second typed after it and committed by the word's terminator; split-word repair may join them back. Without
     * learning, so the fragments are not learnt as words.
     */
    private fun splitReplay(ctx: Context, taps: List<TouchPoint>, typed: String, rectTyped: String, splitAt: Int): String {
        val nl = Context(ctx.words, ctx.sentenceStart, learningAllowed = false, autoCorrectAllowed = ctx.autoCorrectAllowed)
        val y1 = typed.substring(0, splitAt); val y2 = typed.substring(splitAt + 1)
        val s1 = engine.suggestForTaps(nl, taps.subList(0, splitAt), y1)
        val c1 = if (s1.autoCorrectIndex >= 0) s1.candidates[s1.autoCorrectIndex].text else y1
        engine.resolveTap(nl, y1, taps[splitAt], if (inRect(spaceRect, taps[splitAt])) "space" else rectTyped.substring(splitAt, splitAt + 1))
        engine.onWordCommitted(nl, c1, s1.autoCorrectIndex < 0)
        val nl2 = Context(ctx.words + c1, false, learningAllowed = false, autoCorrectAllowed = ctx.autoCorrectAllowed)
        val s2 = engine.suggestForTaps(nl2, taps.subList(splitAt + 1, taps.size), y2)
        val a2 = s2.candidates.getOrNull(s2.autoCorrectIndex)
        lastSplitJoined = a2 != null && a2.replacesBefore.isNotEmpty()
        return if (lastSplitJoined) a2!!.text else c1 + " " + (a2?.text ?: y2)
    }

    /**
     * Letter taps of a record: human tap traces end with a terminator touch, which is not part of the word.
     * Touches on the delete key (the VelociTap traces keep them) remove the letter before, as in the IME.
     */
    fun letterTaps(r: EvalRecord): List<TouchPoint> {
        val pts = if (r.terminator != null) r.points.dropLast(1) else r.points
        val d = deleteRect ?: return pts
        if (pts.none { it.x >= d[0] && it.x < d[2] && it.y >= d[1] && it.y < d[3] }) return pts
        val out = ArrayList<TouchPoint>(pts.size)
        for (p in pts) {
            if (p.x >= d[0] && p.x < d[2] && p.y >= d[1] && p.y < d[3]) { if (out.isNotEmpty()) out.removeAt(out.size - 1) } else out.add(p)
        }
        return out
    }

    fun typedFor(points: List<TouchPoint>, sentenceStart: Boolean): String {
        val sb = StringBuilder()
        for (p in points) {
            val l = hit.hit(p.x, p.y)
            sb.append(if (sb.isEmpty() && sentenceStart) l.uppercase() else l)
        }
        return sb.toString()
    }

    fun run(records: List<EvalRecord>, examples: Int, out: (String) -> Unit): Map<String, Any?> {
        // JIT warm-up on a slice (the engine keeps learning afterwards, like a real user's would).
        for (r in records.take(300)) letterTaps(r).let { engine.suggestForTaps(contextOf(r.context), it, typedFor(it, contextOf(r.context).sentenceStart)) }
        var n = 0; var errors = 0; var errorsCi = 0; var baseErrors = 0
        var clean = 0; var falseCorr = 0; var keptVariant = 0; var noisy = 0; var fixed = 0
        var missp = 0; var misspFixed = 0; var wrongD = 0; var keptOther = 0
        var top1 = 0; var top3 = 0; var acFired = 0
        var ksUsed = 0L; var ksBase = 0L
        val lat = ArrayList<Long>(); val latFinal = ArrayList<Long>()
        val fcEx = ArrayList<String>(); val missEx = ArrayList<String>(); val spEx = ArrayList<String>(); val wdEx = ArrayList<String>()
        val perPerson = java.util.TreeMap<String, IntArray>() // participant -> [words, errors (case-insensitive)]
        // Per participant (the bootstrap unit of the tuner's constraints): words, errors, clean words, false corrections.
        val units = java.util.TreeMap<String, IntArray>()
        val byExtra = java.util.TreeMap<String, IntArray>() // taps - letters -> [words, errors]
        val extraEx = ArrayList<String>()
        var amN = 0; var amErr = 0
        var msN = 0; var msOk = 0
        var keyTaps = 0; var keyRectOk = 0; var keyResolvedOk = 0; var keyHarmful = 0; var keyHelpful = 0
        // Space bar / delete key resolution: in-word taps on the bar (meant letters) and how many get their letter
        // back; in-word letter taps turned into space/delete (break the word); terminators meant as space that
        // hit the bar but became letters, or hit a letter (missed space) and became a space; words the IME would
        // break that way.
        var fnInWordSpace = 0; var fnInWordSpaceFixed = 0; var fnInWordToFn = 0
        var fnTermSpace = 0; var fnTermSpaceLost = 0; var fnTermLetter = 0; var fnTermLetterFixed = 0; var fnBrokenWords = 0
        var fnDeleteTouches = 0; var fnDeleteToLetter = 0
        // Deliberate spaces after a word: each word's own space-bar terminator, asked again as the first tap of the next
        // word (a second space after the word). Any letter is a deliberate space lost (word-start resolution).
        var fnStartSpace = 0; var fnStartSpaceToLetter = 0
        // Terminators on the period key: spaces that missed the bar (turned into space?) and deliberate periods (lost?).
        val dotEx = ArrayList<String>()
        var dotTermSpace = 0; var dotTermSpaceFixed = 0; var dotTermPeriod = 0; var dotTermPeriodLost = 0
        // Words a tap-time space split in two, joined back by split-word repair, and right in the end.
        var fnSplitWords = 0; var fnSplitJoined = 0; var fnSplitRight = 0
        // Each participant is a separate user: what the engine learnt from one must not help the next (the
        // human sets reuse phrase lists across participants).
        var participant: String? = null
        for (r in records) {
            if (r.participant != null && r.participant != participant) { engine.clearUserData(); participant = r.participant }
            val ctx = contextOf(r.context)
            val ime = if (imeTouches) imeWord(r, ctx) else null
            val taps = ime?.taps ?: letterTaps(r)
            if (taps.isEmpty()) continue
            val targetKeys = En.keys(r.target) ?: continue
            n++
            var used = -1
            val pred = engine.predictNext(ctx)
            // TF-01: each tap resolved at touch-up given the letters before it (the IME's behaviour); the word
            // decode still gets the raw touch points.
            val rectTyped = ime?.rect ?: typedFor(taps, ctx.sentenceStart)
            val resolved = StringBuilder()
            // In-word taps go to resolveTap with the key whose rectangle holds them (the space bar included: the
            // letter HitTester maps such taps to the nearest letter). A tap resolved to space/delete would break
            // the word in the IME: counted, and the rectangle's letter is kept for the decode.
            var broken = false
            var splitAt = -1
            if (ime != null) { resolved.append(ime.resolved); broken = ime.broken; splitAt = ime.splitAt }
            else for ((i, p) in taps.withIndex()) {
                val onSpace = inRect(spaceRect, p)
                val res = engine.resolveTap(ctx, resolved.toString(), p, if (onSpace) "space" else rectTyped.substring(i, i + 1))
                val aligned = taps.size == targetKeys.length
                if (onSpace) { fnInWordSpace++; if (res.length == 1 && aligned && res[0].lowercaseChar() == targetKeys[i]) fnInWordSpaceFixed++ }
                else if (res.length != 1) fnInWordToFn++
                if (res.length != 1) broken = true
                if (splitAt < 0 && res == "space") splitAt = i
                resolved.append(if (res.length == 1) res else rectTyped.substring(i, i + 1))
            }
            // Touches on the delete key inside the word (VTAP keeps them; the typist's keyboard deleted): turned into
            // a letter they would not delete.
            run {
                val d = deleteRect ?: return@run
                val sofar = StringBuilder()
                for (p in if (r.terminator != null) r.points.dropLast(1) else r.points) {
                    if (inRect(d, p)) {
                        if (sofar.isNotEmpty()) {
                            fnDeleteTouches++
                            if (engine.resolveTap(ctx, sofar.toString(), p, "delete").length == 1) fnDeleteToLetter++
                            sofar.setLength(sofar.length - 1)
                        }
                    } else sofar.append(hit.hit(p.x, p.y))
                }
            }
            // The terminator touch: meant as space when the record says so.
            val term = if (r.terminator != null) r.points.last() else null
            var termOk = true
            // Terminators on the period key: meant as space (resolved to space?) or as a period (kept?).
            if (term != null && inRect(dotRect, term) && (r.terminator == "space" || r.terminator == "period")) {
                val res = engine.resolveTap(ctx, resolved.toString(), term, ".")
                if (r.terminator == "space") { dotTermSpace++; if (res == "space") dotTermSpaceFixed++ else if (dotEx.size < examples) dotEx += "    space kept as $res: ${r.context.takeLast(30)}|$resolved at ${"%.2f".format((term.x - dotRect!![0]) / (dotRect[2] - dotRect[0]))} of the key, ${"%.2f".format((term.y - dotRect[1]) / (dotRect[2] - dotRect[0]))} key widths below its top" }
                else { dotTermPeriod++; if (res != ".") { dotTermPeriodLost++; if (dotEx.size < examples) dotEx += "    period lost: ${r.context.takeLast(40)}|${r.target}. -> $res at ${"%.2f".format((term.x - dotRect!![0]) / (dotRect[2] - dotRect[0]))} of the key" } }
            }
            if (term != null && r.terminator == "space" && !inRect(dotRect, term)) {
                val onSpace = inRect(spaceRect, term)
                val rect = if (onSpace) "space" else if (inRect(deleteRect, term)) "delete" else hit.hit(term.x, term.y)
                val res = engine.resolveTap(ctx, resolved.toString(), term, rect)
                if (onSpace) { fnTermSpace++; if (res != "space") { fnTermSpaceLost++; termOk = false } }
                if (onSpace) {
                    fnStartSpace++
                    val after = Context(ctx.words + r.target, false, ctx.learningAllowed, ctx.autoCorrectAllowed)
                    if (engine.resolveTap(after, "", term, "space").let { it.length == 1 && Character.isLetter(it[0]) }) fnStartSpaceToLetter++
                }
                else if (rect.length == 1) { fnTermLetter++; if (res == "space") fnTermLetterFixed++ }
            }
            if (taps.size == targetKeys.length) for (i in taps.indices) {
                val want = targetKeys[i]
                val rOk = rectTyped[i].lowercaseChar() == want
                val vOk = resolved[i].lowercaseChar() == want
                keyTaps++
                if (rOk) keyRectOk++
                if (vOk) keyResolvedOk++
                if (rOk && !vOk) keyHarmful++
                if (!rOk && vOk) keyHelpful++
            }
            if (broken || !termOk) fnBrokenWords++
            if (keysOnly) { engine.onWordCommitted(ctx, r.target, true); continue }
            val typed = if (resolveTaps) resolved.toString() else rectTyped
            if (pred.candidates.take(3).any { it.text == r.target }) used = 1
            var final: Suggestions? = null
            for (k in (if (finalOnly) taps.size else 1)..taps.size) {
                val t0 = System.nanoTime()
                val s = engine.suggestForTaps(ctx, taps.subList(0, k), typed.substring(0, k))
                val dt = System.nanoTime() - t0
                lat.add(dt)
                if (k == taps.size) { final = s; latFinal.add(dt) }
                if (used < 0 && k < taps.size && s.candidates.take(3).any { it.text == r.target }) used = k + 1
            }
            val s = final!!
            val splitOut = if (splitAt > 0 && splitAt < taps.size - 1) splitReplay(ctx, taps, typed, rectTyped, splitAt) else null
            val committed = when {
                ime != null && ime.broken -> ""
                ime != null && splitOut != null -> splitOut
                s.autoCorrectIndex >= 0 -> s.candidates[s.autoCorrectIndex].text
                else -> typed
            }
            if (s.autoCorrectIndex >= 0) acFired++
            if (used < 0) used = taps.size + 1
            ksUsed += used; ksBase += targetKeys.length + 1
            // Human sets keep the prompt's case and have no shift taps: compare case-insensitively.
            val ci = r.participant != null
            fun same(a: String, b: String) = if (ci) a.equals(b, ignoreCase = true) else a == b
            val ok = same(committed, r.target)
            if (!ok) errors++
            if (!committed.equals(r.target, ignoreCase = true)) errorsCi++
            if (typed != r.target) baseErrors++
            if (s.candidates.isNotEmpty() && same(s.candidates[0].text, r.target)) top1++
            if (s.candidates.take(3).any { same(it.text, r.target) }) top3++
            val line = "  [${r.context}] typed=$typed target=${r.target} -> $committed  ${s.candidates.take(4).map { it.text }}"
            val isClean = typed.lowercase() == targetKeys
            // A cleanly typed word that comes out wrong is a false correction when the engine changed it; when the
            // engine kept a valid literal whose intended form differs only in apostrophes/case ("neighbours" for
            // "neighbour's", "were" for "we're"), that is the conservative choice, counted separately.
            val unit = units.getOrPut(r.participant ?: "all") { IntArray(4) }
            unit[0]++; if (!committed.equals(r.target, ignoreCase = true)) unit[1]++
            if (isClean) { clean++; unit[2]++; if (!ok) { if (committed.equals(typed, ignoreCase = true)) keptVariant++ else { falseCorr++; unit[3]++; if (fcEx.size < examples) fcEx.add(line) } } }
            else { noisy++; if (ok) fixed++ else if (missEx.size < examples) missEx.add(line) }
            if (r.misspelt != null) { missp++; if (ok) misspFixed++ else if (spEx.size < examples) spEx.add(line) }
            // Wrong dialect = the engine changed the input into the other spelling. Keeping a literal that the typist
            // happened to produce in the other dialect is required behaviour (never correct a valid spelling).
            if (wrongDialect?.invoke(committed, r.target) == true) {
                if (!committed.equals(typed, ignoreCase = true)) { wrongD++; if (wdEx.size < examples) wdEx.add(line) } else keptOther++
            }
            val extra = taps.size - targetKeys.length
            byExtra.getOrPut(when { extra < 0 -> "fewer"; extra == 0 -> "0"; extra == 1 -> "+1"; else -> "+2.." }) { IntArray(2) }.let { it[0]++; if (!ok) it[1]++ }
            if (isContractionAmbiguous(r.target)) { amN++; if (!ok) amErr++ }
            // PBOARD_WORD_DUMP=file: every word (id, ok, typed, target, committed, candidates), to diff two runs.
            System.getenv("PBOARD_WORD_DUMP")?.let { f ->
                java.io.File(f).appendText("${r.id}\t$ok\t$typed\t${r.target}\t$committed\t${s.candidates.take(4).joinToString(",") { it.text }}\n")
            }
            // PBOARD_TAP_DUMP=file: every word typed with extra taps, for offline inspection.
            System.getenv("PBOARD_TAP_DUMP")?.let { f ->
                if (extra > 0) java.io.File(f).appendText("$extra\t$ok\t${r.context.takeLast(40)}\t$typed\t${r.target}\t$committed\t${s.candidates.take(4).joinToString(",") { it.text }}\n")
            }
            if (!ok && extra != 0 && extraEx.size < examples) extraEx.add("  (${if (extra > 0) "+" else ""}$extra) [${r.context.takeLast(30)}] typed=$typed target=${r.target} -> $committed  ${s.candidates.take(4).map { it.text }}")
            r.participant?.let { p -> perPerson.getOrPut(p) { IntArray(2) }.let { it[0]++; if (!committed.equals(r.target, ignoreCase = true)) it[1]++ } }
            // Missed space: the terminator landed on a letter key, so a real keyboard typed that letter
            // into the word. The decoder should still end the word at the intended point.
            val tk = r.terminatorKey
            if (tk != null && tk.length == 1 && tk[0].isLetter()) {
                msN++
                val all = r.points
                val s2 = engine.suggestForTaps(ctx, all, typed + tk)
                val c2 = if (s2.autoCorrectIndex >= 0) s2.candidates[s2.autoCorrectIndex].text else typed + tk
                if (c2.trim().equals(r.target, ignoreCase = true)) msOk++
            }
            // A tap that became a space split the word in the IME: replay that (first part committed by the space, the
            // second typed after it and committed by the word's terminator) and see whether split-word repair joins it
            // back. Without learning, so the fragments are not learnt as words.
            if (splitOut != null) {
                fnSplitWords++
                if (lastSplitJoined) fnSplitJoined++
                if (splitOut.equals(r.target, ignoreCase = true)) fnSplitRight++
            }
            // What the simulated user's learning sees: by default the text they end up with (they fix mistakes
            // before moving on), so errors do not feed back into the user model.
            when (learn) {
                "committed" -> engine.onWordCommitted(ctx, committed, s.autoCorrectIndex < 0)
                "target" -> engine.onWordCommitted(ctx, r.target, s.autoCorrectIndex < 0 && committed.equals(r.target, ignoreCase = true))
                else -> {}
            }
        }
        val latMs = percentiles(lat.toLongArray())
        val res = linkedMapOf<String, Any?>(
            "words" to n,
            "wer" to errors.toDouble() / n,
            "werCaseInsensitive" to errorsCi.toDouble() / n,
            "werNoCorrection" to baseErrors.toDouble() / n,
            "cleanWords" to clean,
            "falseCorrectionRate" to falseCorr.toDouble() / clean,
            "cleanKeptVariantRate" to keptVariant.toDouble() / clean,
            "noisyWords" to noisy,
            "noisyRecovered" to fixed.toDouble() / noisy,
            "misspeltWords" to missp,
            "misspeltRecovered" to if (missp > 0) misspFixed.toDouble() / missp else null,
            "wrongDialectRate" to if (wrongDialect != null) wrongD.toDouble() / n else null,
            "top1" to top1.toDouble() / n,
            "top3" to top3.toDouble() / n,
            "autocorrectFired" to acFired.toDouble() / n,
            "keystrokeSavings" to 1.0 - ksUsed.toDouble() / ksBase,
            "missedSpaceWords" to msN,
            "missedSpaceRecovered" to if (msN > 0) msOk.toDouble() / msN else null,
            "perParticipantWerCi" to perPerson.mapValues { it.value[1].toDouble() / it.value[0] },
            "units" to units,
            "latencyPerKeystroke" to latMs,
            "latencyFinal" to percentiles(latFinal.toLongArray()),
        )
        out("== TAP  words=$n")
        out("  WER after autocorrect   ${pct(errors, n)}  (case-insensitive ${pct(errorsCi, n)}; no correction ${pct(baseErrors, n)})")
        out("  false corrections       ${pct(falseCorr, clean)} of $clean cleanly typed words (+ ${pct(keptVariant, clean)} kept as typed where an apostrophe/case variant was meant)")
        out("  noisy recovered         ${pct(fixed, noisy)} of $noisy mistyped words (misspellings: ${pct(misspFixed, missp)} of $missp)")
        out("  top-1 / top-3           ${pct(top1, n)} / ${pct(top3, n)}   autocorrect fired ${pct(acFired, n)}")
        out("  keystroke savings       %.2f%%".format(100 * (1.0 - ksUsed.toDouble() / ksBase)))
        if (amN > 0) out("  contraction-ambiguous   WER ${pct(amErr, amN)} of $amN words (its/it's, were/we're, ill/I'll, …)")
        out("  WER by extra taps       " + byExtra.entries.joinToString("  ") { "${it.key}: ${pct(it.value[1], it.value[0])} (n=${it.value[0]})" })
        if (msN > 0) out("  missed-space terminators ${pct(msOk, msN)} of $msN words still come out right")
        if (keyTaps > 0) out("  per-tap keys            rectangle ${pct(keyRectOk, keyTaps)}  resolved ${pct(keyResolvedOk, keyTaps)}  " +
            "fixed ${pct(keyHelpful, keyTaps)}  overridden (rectangle was right) ${pct(keyHarmful, keyTaps)} of $keyTaps aligned taps" +
            if (resolveTaps) "  [words typed with resolved keys]" else "")
        out("  space bar / delete      in-word taps on the bar $fnInWordSpace (given their letter ${pct(fnInWordSpaceFixed, fnInWordSpace)}); " +
            "letter taps turned into space/delete $fnInWordToFn; terminators on the bar $fnTermSpace (turned into letters $fnTermSpaceLost); " +
            "missed-space terminators on letters $fnTermLetter (turned into space ${pct(fnTermLetterFixed, fnTermLetter)}); words broken ${pct(fnBrokenWords, n)}; " +
            "in-word delete touches $fnDeleteTouches (turned into letters $fnDeleteToLetter); deliberate spaces asked again at the next " +
            "word's start $fnStartSpace (turned into letters $fnStartSpaceToLetter); words split by a space $fnSplitWords " +
            "(joined back $fnSplitJoined, right in the end $fnSplitRight)")
        out("  period key              terminators meant as space $dotTermSpace (turned into space $dotTermSpaceFixed); " +
            "deliberate periods $dotTermPeriod (turned into something else $dotTermPeriodLost)")
        if (perPerson.isNotEmpty() && perPerson.size <= 40) out("  WER (ci) per participant " + perPerson.entries.joinToString("  ") { "${it.key.substringAfterLast('/')}=${pct(it.value[1], it.value[0])}" })
        if (wrongDialect != null) out("  wrong-dialect corrections ${pct(wrongD, n)} ($wrongD of $n); other-dialect literals typed by mistake and kept: $keptOther")
        out("  latency/keystroke       ${fmtMs(latMs)}")
        if (fcEx.isNotEmpty()) { out("  false-correction examples:"); fcEx.forEach(out) }
        if (missEx.isNotEmpty()) { out("  missed-correction examples:"); missEx.forEach(out) }
        if (spEx.isNotEmpty()) { out("  missed misspelling examples:"); spEx.forEach(out) }
        if (extraEx.isNotEmpty()) { out("  extra/missing-tap failures:"); extraEx.forEach(out) }
        if (wdEx.isNotEmpty()) { out("  WRONG-DIALECT examples:"); wdEx.forEach(out) }
        if (dotEx.isNotEmpty()) { out("  period-key terminators wrong:"); dotEx.forEach(out) }
        return res
    }
}

/**
 * The partial-word set: for every human word of 3+ letters typed with one touch per letter, the prefixes of 2 to 5
 * letters (shorter than the word) with their real touches and context, as if space came next. Measures completion
 * on space: how often space would change the prefix (fired) and how often into the word the person was typing
 * (right); precision = right / fired.
 */
class PartialEval(private val engine: KeyboardEngine, g: KeyboardGeometry, private val resolveTaps: Boolean) {
    private val hit = HitTester(g)

    fun run(records: List<EvalRecord>, examples: Int, out: (String) -> Unit): Map<String, Any?> {
        val byLen = java.util.TreeMap<Int, IntArray>() // prefix letters -> [prefixes, fired, right]
        val wrongEx = ArrayList<String>()
        var participant: String? = null
        for (r in records) {
            if (r.participant != null && r.participant != participant) { engine.clearUserData(); participant = r.participant }
            val ctx = contextOf(r.context)
            val pts = if (r.terminator != null) r.points.dropLast(1) else r.points
            val keys = En.keys(r.target)
            if (keys != null && pts.size == keys.length && keys.length >= 3) {
                engine.predictNext(ctx)
                val rect = StringBuilder()
                for ((i, p) in pts.withIndex()) rect.append(hit.hit(p.x, p.y).let { if (i == 0 && ctx.sentenceStart) it.uppercase() else it })
                val typed = if (!resolveTaps) rect.toString() else {
                    val sb = StringBuilder()
                    for ((i, p) in pts.withIndex()) sb.append(engine.resolveTap(ctx, sb.toString(), p, rect.substring(i, i + 1)).takeIf { it.length == 1 } ?: rect.substring(i, i + 1))
                    sb.toString()
                }
                for (k in 2..minOf(5, keys.length - 1)) {
                    val t = byLen.getOrPut(k) { IntArray(3) }
                    t[0]++
                    val s = engine.suggestForTaps(ctx, pts.subList(0, k), typed.substring(0, k))
                    if (s.autoCorrectIndex < 0) continue
                    val c = s.candidates[s.autoCorrectIndex].text
                    t[1]++
                    if (c.equals(r.target, ignoreCase = true)) t[2]++
                    else if (wrongEx.size < examples) wrongEx.add("  [${r.context.takeLast(30)}] ${typed.substring(0, k)} -> $c (typing ${r.target})")
                }
            }
            engine.onWordCommitted(ctx, r.target, true)
        }
        val n = byLen.values.sumOf { it[0] }; val fired = byLen.values.sumOf { it[1] }; val right = byLen.values.sumOf { it[2] }
        out("== PARTIAL  prefixes=$n")
        out("  space would change the prefix ${pct(fired, n)}; into the word being typed ${pct(right, n)}; precision ${pct(right, fired)}")
        out("  by prefix letters        " + byLen.entries.joinToString("  ") { (k, v) -> "$k: fired ${pct(v[1], v[0])} precision ${pct(v[2], v[1])} (n=${v[0]})" })
        if (wrongEx.isNotEmpty()) { out("  wrong completions:"); wrongEx.forEach(out) }
        return linkedMapOf("prefixes" to n, "fired" to fired, "right" to right)
    }
}

/**
 * Text-level autocorrect (text_typos / text_wikipedia): the word as the fingers spelled it is decoded from
 * its text alone (no touches), as when a word is re-opened, then committed. Measures errors fixed and
 * correct words broken. Comparison is case-insensitive (sources keep prompt case, no shift taps).
 */
class TextEval(private val engine: KeyboardEngine) {
    fun run(records: List<HumanSets.TextRecord>, examples: Int, out: (String) -> Unit): Map<String, Any?> {
        var errN = 0; var errFixed = 0; var errTop3 = 0; var okN = 0; var okBroken = 0; var amN = 0; var amErr = 0
        val brokenEx = ArrayList<String>(); val missEx = ArrayList<String>()
        // Per participant (or per block of 50 records without one): misspelt, left wrong, correct, broken.
        val units = java.util.TreeMap<String, IntArray>()
        for ((ri, r) in records.withIndex()) {
            val unit = units.getOrPut(r.participant ?: "block${ri / 50}") { IntArray(4) }
            val ctx = contextOf(r.context)
            val typed = if (ctx.sentenceStart && r.typed.isNotEmpty()) r.typed[0].uppercaseChar() + r.typed.substring(1) else r.typed
            val s = engine.suggestForTaps(ctx, emptyList(), typed)
            val committed = if (s.autoCorrectIndex >= 0) s.candidates[s.autoCorrectIndex].text else typed
            val ok = r.targets.any { it.equals(committed, ignoreCase = true) }
            val line = "  [${r.context.takeLast(40)}] typed=$typed target=${r.targets.first()} -> $committed  ${s.candidates.take(4).map { it.text }}"
            if (r.targets.any { isContractionAmbiguous(it) }) { amN++; if (!ok) amErr++ }
            if (r.error) {
                errN++; unit[0]++
                if (!ok) unit[1]++
                if (ok) errFixed++ else if (missEx.size < examples) missEx.add(line)
                if (s.candidates.take(3).any { c -> r.targets.any { it.equals(c.text, ignoreCase = true) } }) errTop3++
            } else {
                okN++; unit[2]++
                if (!ok) { okBroken++; unit[3]++; if (brokenEx.size < examples) brokenEx.add(line) }
            }
        }
        out("== TEXT  words=${records.size}")
        out("  misspellings fixed      ${pct(errFixed, errN)} of $errN (target in top-3 ${pct(errTop3, errN)})")
        if (okN > 0) out("  correct words broken    ${pct(okBroken, okN)} of $okN")
        if (amN > 0) out("  contraction-ambiguous   wrong ${pct(amErr, amN)} of $amN words")
        if (missEx.isNotEmpty()) { out("  unfixed examples:"); missEx.forEach(out) }
        if (brokenEx.isNotEmpty()) { out("  broken examples:"); brokenEx.forEach(out) }
        return linkedMapOf("misspelt" to errN, "fixed" to if (errN > 0) errFixed.toDouble() / errN else null,
            "fixedTop3" to if (errN > 0) errTop3.toDouble() / errN else null,
            "correct" to okN, "broken" to if (okN > 0) okBroken.toDouble() / okN else null, "units" to units)
    }
}

class SwipeEval(private val engine: KeyboardEngine, private val wrongDialect: ((String, String) -> Boolean)? = null,
                private val known: ((String) -> Boolean)? = null, private val diagnose: Boolean = false) {
    fun run(records: List<EvalRecord>, examples: Int, out: (String) -> Unit): Map<String, Any?> {
        for (r in records.take(200)) engine.suggestForSwipe(contextOf(r.context), r.points)
        var n = 0; var top1 = 0; var top3 = 0; var top1ci = 0; var wrongD = 0; var inVocab = 0; var inVocabTop1 = 0; var inVocabTop3 = 0
        val lat = ArrayList<Long>()
        val ex = ArrayList<String>()
        val byLen = HashMap<String, IntArray>()
        // Diagnostics: per length bucket, where the target was lost (pruning) vs. ranked below (ranking).
        val stages = java.util.TreeMap<String, java.util.TreeMap<String, Int>>()
        val keptTop1 = HashMap<String, IntArray>()
        // Per participant (or per block of 50 traces without one): traces, top-1 misses (the tuner's constraint units).
        val units = java.util.TreeMap<String, IntArray>()
        for ((ri, r) in records.withIndex()) {
            val ctx = contextOf(r.context)
            val t0 = System.nanoTime()
            val s = engine.suggestForSwipe(ctx, r.points)
            lat.add(System.nanoTime() - t0)
            n++
            val ci = r.participant != null
            val texts = s.candidates.map { if (ci) it.text.lowercase() else it.text }
            val target = if (ci) r.target.lowercase() else r.target
            val ok1 = texts.firstOrNull() == target
            if (texts.isNotEmpty() && wrongDialect?.invoke(texts[0], r.target) == true) wrongD++
            if (ok1) top1++
            // PBOARD_WORD_DUMP=file: every gesture (id, ok, target, top candidates), to diff two runs.
            System.getenv("PBOARD_WORD_DUMP")?.let { f -> java.io.File(f).appendText("${r.id}\t$ok1\t-\t${r.target}\t${texts.firstOrNull()}\t${texts.take(4).joinToString(",")}\n") }
            if (texts.firstOrNull()?.equals(r.target, true) == true) top1ci++
            if (target in texts.take(3)) top3++
            units.getOrPut(r.participant ?: "block${ri / 50}") { IntArray(2) }.let { it[0]++; if (!ok1) it[1]++ }
            if (diagnose && engine is PboardEngine) {
                val len0 = (En.keys(r.target) ?: "").length
                val bucket0 = when { len0 <= 3 -> "1-3"; len0 <= 6 -> "4-6"; else -> "7+" }
                val tr = engine.traceSwipe(ctx, r.points, r.target)
                val st = tr?.stage ?: "tap-like"
                stages.getOrPut(bucket0) { java.util.TreeMap() }.merge(st, 1, Int::plus)
                if (st == "kept") keptTop1.getOrPut(bucket0) { IntArray(3) }.let { it[0]++; if (ok1) it[1]++; if (target in texts.take(3)) it[2]++ }
                // PBOARD_SWIPE_DUMP=file: long-word failures with their stage and path, for offline inspection.
                System.getenv("PBOARD_SWIPE_DUMP")?.let { f ->
                    if (!ok1 && len0 >= 7) java.io.File(f).appendText(
                        "{\"id\":\"${r.id}\",\"target\":\"${r.target}\",\"stage\":\"$st\",\"top\":[${texts.take(4).joinToString(",") { "\"$it\"" }}]," +
                            "\"points\":[${r.points.joinToString(",") { "[${it.x},${it.y},${it.t}]" }}]}\n")
                }
            }
            if (known?.invoke(r.target) == true) { inVocab++; if (ok1) inVocabTop1++; if (target in texts.take(3)) inVocabTop3++ }
            val len = (En.keys(r.target) ?: "").length
            val bucket = when { len <= 3 -> "1-3"; len <= 6 -> "4-6"; else -> "7+" }
            val b = byLen.getOrPut(bucket) { IntArray(3) }; b[0]++; if (ok1) b[1]++; if (target in texts.take(3)) b[2]++
            if (!ok1 && ex.size < examples) ex.add("  ${r.id} [${r.context}] target=${r.target} -> ${texts.take(4)}")
        }
        val latMs = percentiles(lat.toLongArray())
        out("== SWIPE  words=$n")
        out("  top-1 / top-3           ${pct(top1, n)} / ${pct(top3, n)}  (top-1 case-insensitive ${pct(top1ci, n)})")
        out("  by length (top1/top3)   " + byLen.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}: ${pct(it.value[1], it.value[0])}/${pct(it.value[2], it.value[0])} (n=${it.value[0]})" })
        for ((b, m) in stages) {
            val tot = m.values.sum()
            val k = keptTop1[b]
            out("  candidate recall $b    " + m.entries.joinToString("  ") { "${it.key}=${pct(it.value, tot)}" } +
                (k?.let { "   | given kept: top-1 ${pct(it[1], it[0])} top-3 ${pct(it[2], it[0])}" } ?: ""))
        }
        if (known != null) out("  target in vocabulary     ${pct(inVocab, n)}; top-1 / top-3 on those ${pct(inVocabTop1, inVocab)} / ${pct(inVocabTop3, inVocab)}")
        if (wrongDialect != null) out("  wrong-dialect top-1     ${pct(wrongD, n)} ($wrongD of $n)")
        out("  latency                 ${fmtMs(latMs)}")
        if (ex.isNotEmpty()) { out("  failure examples:"); ex.forEach(out) }
        return linkedMapOf("words" to n, "top1" to top1.toDouble() / n, "top3" to top3.toDouble() / n,
            "wrongDialectRate" to if (wrongDialect != null) wrongD.toDouble() / n else null, "latency" to latMs, "units" to units)
    }
}

/** Builds the synthetic sets from held-out sentences (deterministic). */
object EvalGen {
    private val ALLOWED = Regex("^[A-Za-z',.?! ]+$")
    private val WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")

    fun eligible(s: String): Boolean {
        if (!ALLOWED.matches(s)) return false
        val toks = DevData.tokens(s)
        return toks.size in 3..14 && toks.all { En.keys(it) != null }
    }

    fun generate(heldOut: List<String>, g: KeyboardGeometry, tapFile: File, swipeFile: File) {
        val rng = Random(20261108)
        val pool = heldOut.filter(::eligible).sorted().shuffled(rng)
        val cal = Calibration.load(DevData.projectRoot())
        val synth = Synth(g, cal)
        val tapRecs = ArrayList<EvalRecord>(); val swipeRecs = ArrayList<EvalRecord>()
        val tapSentences = pool.take(700)
        val swipeSentences = pool.drop(700).take(450)
        for ((si, s) in tapSentences.withIndex()) {
            val prof = TypistProfile.random(rng, cal)
            val offsets = synth.keyOffsets(prof, rng)
            for ((wi, m) in WORD.findAll(s).withIndex()) {
                val keys = En.keys(m.value) ?: continue
                val typedKeys = if (keys.length >= 5 && rng.nextDouble() < prof.pMisspell) synth.misspell(keys, rng) else keys
                val misspelt = typedKeys.takeIf { it != keys }
                tapRecs.add(EvalRecord("t%04d.%02d".format(si, wi), s.substring(0, m.range.first).trimEnd(), m.value, "tap",
                    synth.taps(typedKeys, prof, rng, offsets), misspelt))
            }
        }
        for ((si, s) in swipeSentences.withIndex()) {
            val prof = SwiperProfile.random(rng, cal)
            for ((wi, m) in WORD.findAll(s).withIndex()) {
                val keys = En.keys(m.value) ?: continue
                if (keys.toSet().size < 2) continue // single-key words are tapped, not swiped
                swipeRecs.add(EvalRecord("s%04d.%02d".format(si, wi), s.substring(0, m.range.first).trimEnd(), m.value, "swipe", synth.swipe(keys, prof, rng)))
            }
        }
        val common = linkedMapOf<String, Any?>(
            "version" to 2,
            "source" to "Tatoeba English sentences (CC-BY 2.0 FR, https://tatoeba.org), held-out split",
            "seed" to 20261108,
            "synthetic" to true,
        )
        EvalSets.write(tapFile, common + mapOf(
            "kind" to "tap",
            "typist" to "per-sentence profile: Gaussian touch noise sigma 0.20-0.40 key widths, downward bias 0-0.18 kw, " +
                "thumb pull to centre 0-0.1 kw, per-key systematic offsets N(0, 0-0.1 kw); omission 0.4-1.5% (doubled letters 2-8%), " +
                "double tap 0.2-1%, transposition 0.2-1%, stray neighbour 0.1-0.6%; misspellings (vowel swap, ie/ei, doubled consonant " +
                "dropped/added) on 2-10% of 5+ letter words, recorded in 'misspelt'; touches landing on non-letter keys are redrawn; " +
                "contractions typed without apostrophe",
        ), g, tapRecs)
        EvalSets.write(swipeFile, common + mapOf(
            "kind" to "swipe",
            "swiper" to "per-sentence profile: via points at key centres + N(0, 0.12-0.30 kw), corner cutting 5-40%, " +
                "minimum-jerk quintic segments with Fitts durations and corner slow-down, speed x0.75-1.4, sloppy start up to 0.2 kw, " +
                "overshoot/undershoot up to 0.3 kw, sensor jitter, ~120 Hz sampling; doubled letters visited once; single-key words excluded",
        ), g, swipeRecs)
        System.err.println("generated ${tapRecs.size} tap and ${swipeRecs.size} swipe records")
    }
}

/** Dialect set generation lives with the dev data it depends on. */
fun EvalGen.generateDialect(data: DevData, g: KeyboardGeometry, file: File) {
    val rng = Random(20261109)
    val gb = data.dialect("en-GB"); val us = data.dialect("en-US")
    val word = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
    fun variantWord(v: String) = gb.groups.containsKey(v.lowercase()) && v == v.lowercase()
    val pool = data.heldOut.filter(::eligible).sorted().shuffled(rng)
        .filter { s -> word.findAll(s).any { variantWord(it.value) } }
        .take(300)
    val cal = Calibration.load(DevData.projectRoot())
    val synth = Synth(g, cal)
    val recs = ArrayList<EvalRecord>()
    fun convert(text: String, fl: dev.pboard.engine.devlm.DialectFlavour): String = word.replace(text) { m ->
        val l = m.value.lowercase()
        val p = fl.preferred[l]
        if (p == null || m.value != l) m.value else p
    }
    for ((si, s) in pool.withIndex()) {
        val m = word.findAll(s).first { variantWord(it.value) }
        val l = m.value
        for ((tag, fl, other) in listOf(Triple("en-GB", gb, us), Triple("en-US", us, gb))) {
            val ctx = convert(s.substring(0, m.range.first), fl).trimEnd()
            val own = fl.preferred[l]!!
            val prof = TypistProfile.random(rng, cal)
            val keys = En.keys(own) ?: continue
            recs.add(EvalRecord("d%04d.$tag.own".format(si), ctx, own, "tap", synth.taps(keys, prof, rng, synth.keyOffsets(prof, rng)), dialect = tag))
            // The user deliberately types the other dialect's spelling, carefully: it must be kept.
            val foreign = other.preferred[l]!!
            if (foreign != own) {
                val careful = prof.copy(sigma = 0.12, pWide = 0.0, pOmit = 0.0, pOmitDouble = 0.0, pDouble = 0.0, pTranspose = 0.0, pStray = 0.0)
                val fk = En.keys(foreign) ?: continue
                recs.add(EvalRecord("d%04d.$tag.other".format(si), ctx, foreign, "tap", synth.taps(fk, careful, rng), dialect = tag))
            }
        }
    }
    EvalSets.write(file, linkedMapOf(
        "version" to 1, "kind" to "tap", "synthetic" to true, "seed" to 20261109,
        "source" to "Tatoeba English sentences (CC-BY 2.0 FR), held-out split, respelt per dialect",
        "description" to "Words with British/American spellings. 'dialect' is the user's dialect; '.own' records type that " +
            "dialect's spelling with typist noise (must come out in that spelling), '.other' records carefully type the other " +
            "dialect's spelling (must be kept). Context is respelt in the user's dialect.",
    ), g, recs)
    System.err.println("generated ${recs.size} dialect records")
}

/** Reflection-based overrides of nested config fields, e.g. "tap.lmWeight" = "1.2" (dev tool only; mutates a fresh config). */
object ConfigOverrides {
    fun apply(base: EngineConfig, overrides: Map<String, String>): EngineConfig {
        for ((path, value) in overrides) set(base, path.split('.'), value)
        return base
    }

    private fun set(obj: Any, path: List<String>, value: String) {
        val f = obj.javaClass.getDeclaredField(path[0]).also { it.isAccessible = true }
        if (path.size > 1) return set(f.get(obj), path.drop(1), value)
        val v: Any = when (f.type) {
            java.lang.Float.TYPE -> value.toFloat(); java.lang.Integer.TYPE -> value.toInt()
            java.lang.Boolean.TYPE -> value.toBoolean(); java.lang.Double.TYPE -> value.toDouble()
            else -> error("unsupported field type ${f.type} for ${path[0]}")
        }
        f.set(obj, v)
    }
}
