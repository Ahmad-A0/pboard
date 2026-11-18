package dev.pboard.translate

import java.io.File

/**
 * Headless latency/memory benchmark, runnable on a phone with app_process (see tools/translate/phone_bench.sh):
 *
 *     PhoneBench <modelDir> <sentences.txt> [warmupLines]
 *
 * Reports model load time, per-sentence latency (each sentence translated cold, i.e. not cached), a live-typing
 * simulation (the first sentence retranslated after every word, as the translate bar does while typing), the Java heap
 * in use after loading, and the process RSS.
 */
object PhoneBench {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0].substringBefore(".zip:"))
        val lines = File(args[1]).readLines().filter { it.isNotBlank() }
        val warm = args.getOrNull(2)?.toInt() ?: 5
        val rt = Runtime.getRuntime()
        gc()
        val heap0 = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        // a pack zip (deflated, like a pack APK's assets) "pack.zip:en-es", or a plain model directory
        val tr = if (args[0].contains(".zip:")) {
            val (zipPath, pair) = args[0].split(":")
            val zip = java.util.zip.ZipFile(zipPath)
            val storage = object : PackStorage {
                override fun open(path: String) = zip.getInputStream(zip.getEntry(path) ?: error("no $path"))
            }
            val manifest = PackManifest.parse(storage.open(PackManifest.FILE).use { it.readBytes().decodeToString() })
            val (f, t) = pair.split("-")
            Translators.load(storage, manifest.model(f, t)!!) as MarianTranslator
        } else loadModelDir(dir) as MarianTranslator
        val loadMs = (System.nanoTime() - t0) / 1e6
        gc()
        val heap1 = rt.totalMemory() - rt.freeMemory()
        println("model %s: load %.0f ms, heap +%.1f MB, rss %s".format(dir.name, loadMs, (heap1 - heap0) / 1e6, rss()))

        // the very first translation after loading (interpreter/JIT warm-up; an installed release build is AOT-compiled
        // from the baseline profile instead)
        val c0 = System.nanoTime()
        tr.translateSentence(lines.maxBy { it.length } + " .")
        println("first translation (cold code): %.0f ms".format((System.nanoTime() - c0) / 1e6))
        // warm-up (JIT), not measured
        for (l in lines.take(warm)) tr.translateSentence(l + " ")
        val times = DoubleArray(lines.size)
        var words = 0
        val tStart = System.nanoTime()
        for ((i, l) in lines.withIndex()) {
            val s = System.nanoTime()
            tr.translateSentence(l)
            times[i] = (System.nanoTime() - s) / 1e6
            words += l.split(' ').size
        }
        val total = (System.nanoTime() - tStart) / 1e6
        val sorted = times.sorted()
        println("sentences: %d (%.1f words avg): mean %.1f ms, median %.1f ms, p90 %.1f ms, max %.1f ms, %.0f words/s".format(
            lines.size, words.toDouble() / lines.size, times.average(), sorted[sorted.size / 2], sorted[sorted.size * 9 / 10],
            sorted.last(), words / (total / 1000)))

        // live typing: retranslate a growing prefix after each word (the whole text each time; earlier sentences cached)
        val text = lines.sortedByDescending { it.length }.take(3).joinToString(" ")
        val wordsOf = text.split(' ')
        val live = ArrayList<Double>()
        for (k in 1..wordsOf.size) {
            val prefix = wordsOf.take(k).joinToString(" ")
            val s = System.nanoTime()
            tr.translate(prefix)
            live.add((System.nanoTime() - s) / 1e6)
        }
        val ls = live.sorted()
        println("live typing (%d updates over %d words): mean %.1f ms, median %.1f ms, p90 %.1f ms, max %.1f ms".format(
            live.size, wordsOf.size, live.average(), ls[ls.size / 2], ls[ls.size * 9 / 10], ls.last()))
        gc()
        println("after: heap %.1f MB, rss %s".format((rt.totalMemory() - rt.freeMemory()) / 1e6, rss()))
    }

    private fun gc() {
        repeat(3) { System.gc(); Thread.sleep(50) }
    }

    private fun rss(): String = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS") }?.substringAfter(':')?.trim() ?: "?"
    } catch (e: Exception) { "?" }
}
