package dev.pboard.engine.swipe

import java.io.File

/**
 * Encoder latency of `.pbsw` models (JVM or ART via app_process; see tools/swipe/bench_device.sh):
 *   SwipeBenchKt <model.pbsw>... [--frames 46,110,150] [--reps 30]
 * Prints mean / p50 / p99 per model and input length, with the multiply-add count.
 */
fun main(args: Array<String>) {
    val files = ArrayList<String>()
    var frames = listOf(46, 110, 150)
    var reps = 30
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--frames" -> frames = args[++i].split(',').map { it.toInt() }
            "--reps" -> reps = args[++i].toInt()
            else -> files.add(args[i])
        }
        i++
    }
    val rnd = java.util.Random(1)
    for (f in files) {
        val m = SwipeModel.read(File(f))
        val enc = m.newEncoder()
        if (m.version == 2) enc.setKeys(FloatArray(26) { rnd.nextFloat() }, FloatArray(26) { rnd.nextFloat() })
        for (n in frames) {
            val x = FloatArray(n * m.nFeat) { rnd.nextFloat() }
            repeat(5) { enc.encode(x, n) }
            val ts = LongArray(reps)
            for (r in 0 until reps) { val t0 = System.nanoTime(); enc.encode(x, n); ts[r] = System.nanoTime() - t0 }
            ts.sort()
            val macs = m.macs(n)
            val mean = ts.average() / 1e6
            println("%-28s frames %3d  MACs %5.1fM  mean %6.2f ms  p50 %6.2f  p99 %6.2f  (%.2f GMAC/s)".format(
                File(f).name, n, macs / 1e6, mean, ts[reps / 2] / 1e6, ts[(reps - 1) * 99 / 100] / 1e6, macs / (mean * 1e6)))
        }
    }
}
