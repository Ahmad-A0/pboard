package dev.pboard.translate

import java.io.File

/**
 * Translates a text file line by line (each line on its own, like the FLORES evaluation) and reports latency.
 *
 *     ./gradlew :translate:evalMt -PevalArgs="<modelDir> <src.txt> <out.txt> [limit]"
 *
 * <modelDir> is a Firefox Translations model directory (model.*.bin, vocab.*.spm or srcvocab/trgvocab, lex.*.bin) or
 * a pack directory with manifest.json (then a 4th/5th arg "from-to" picks the model).
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "flores") return flores(args.drop(1))
    require(args.size >= 3) { "usage: <modelDir> <src.txt> <out.txt> [limit]" }
    val dir = File(args[0])
    val limit = args.getOrNull(3)?.toIntOrNull() ?: Int.MAX_VALUE
    val t0 = System.nanoTime()
    val tr = loadModelDir(dir)
    val loadMs = (System.nanoTime() - t0) / 1e6
    val lines = File(args[1]).readLines().take(limit)
    val out = ArrayList<String>()
    val times = ArrayList<Double>()
    for (l in lines) {
        val s = System.nanoTime()
        out.add((tr as MarianTranslator).translateSentence(l)!!.replace('\n', ' '))
        times.add((System.nanoTime() - s) / 1e6)
    }
    File(args[2]).writeText(out.joinToString("\n", postfix = "\n"))
    val sorted = times.sorted()
    System.err.println("load %.0f ms; %d lines; mean %.1f ms, median %.1f ms, p90 %.1f ms".format(
        loadMs, lines.size, times.average(), sorted[sorted.size / 2], sorted[(sorted.size * 9) / 10]))
}

val FLORES_CODES = mapOf("en" to "eng_Latn", "es" to "spa_Latn", "fr" to "fra_Latn", "de" to "deu_Latn", "it" to "ita_Latn",
    "pt" to "por_Latn", "nl" to "nld_Latn", "pl" to "pol_Latn")

/**
 * flores <modelsRoot> <floresDevtestDir> <outDir> <threads> <pair>... : translates FLORES devtest (<code>.txt files)
 * for every pair (model dir <modelsRoot>/<pair>) in parallel, writing <outDir>/<pair>.hyp and timing lines.
 */
private fun flores(args: List<String>) {
    val root = File(args[0]); val fl = File(args[1]); val outDir = File(args[2]).apply { mkdirs() }
    val threads = args[3].toInt()
    val pairs = args.drop(4)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
    val jobs = pairs.map { pair ->
        pool.submit<String> {
            val (s, t) = pair.split('-')
            val tr = loadModelDir(File(root, pair)) as MarianTranslator
            val lines = File(fl, FLORES_CODES.getValue(s) + ".txt").readLines()
            var ms = 0.0
            var tokens = 0L
            val out = lines.map { l ->
                val t0 = System.nanoTime()
                val r = tr.translateSentence(l)!!
                ms += (System.nanoTime() - t0) / 1e6
                tokens += l.split(' ').size
                r.replace('\n', ' ')
            }
            File(outDir, "$pair.hyp").writeText(out.joinToString("\n", postfix = "\n"))
            "%s %d lines %.1f ms/line (%d words)".format(pair, lines.size, ms / lines.size, tokens)
        }
    }
    for (j in jobs) System.err.println(j.get())
    pool.shutdown()
}

/** Loads a raw Firefox Translations model directory as a [Translator]. */
fun loadModelDir(dir: File): Translator {
    val files = dir.listFiles()!!.map { it.name }
    fun find(p: (String) -> Boolean) = files.firstOrNull { p(it) && !it.endsWith(".gz") }
    val model = find { it.startsWith("model.") && it.endsWith(".bin") } ?: error("no model in $dir")
    val vocab = find { it.startsWith("vocab.") && it.endsWith(".spm") }
    val srcVocab = vocab ?: find { it.startsWith("srcvocab.") }!!
    val trgVocab = vocab ?: find { it.startsWith("trgvocab.") }!!
    val lex = find { it.startsWith("lex.") && it.endsWith(".bin") }
    val pair = dir.name.split('-')
    val spec = ModelSpec(pair.getOrElse(0) { "xx" }, pair.getOrElse(1) { "yy" }, ModelSpec.TYPE_MARIAN, model, srcVocab, trgVocab, lex)
    return Translators.load(PackStorage.Directory(dir), spec)
}
