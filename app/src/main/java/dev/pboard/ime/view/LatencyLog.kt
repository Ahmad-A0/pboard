package dev.pboard.ime.view

import android.util.Log

/**
 * Touch-down to draw latency (input event time until the key surface's onDraw issues the frame).
 * Off unless enabled with `adb shell setprop log.tag.PboardLatency DEBUG` (then restart the keyboard);
 * every 50 samples it logs min/median/p95/max. Works in release builds too; costs nothing when off.
 */
object LatencyLog {
    private const val TAG = "PboardLatency"
    val enabled: Boolean = Log.isLoggable(TAG, Log.DEBUG)
    private val samples = LongArray(50)
    private var n = 0

    private val engineSamples = HashMap<String, FloatArray>()
    private val engineCounts = HashMap<String, Int>()

    /** Engine calls at least this slow are logged individually with their phases. */
    const val SLOW_MS = 8f

    /** One slow engine call: its phases (ms) and the number of GCs that ran during it. */
    fun slow(label: String, ms: Float, phases: Map<String, Long>, gcs: Int) {
        Log.d(TAG, "slow $label ${"%.1f".format(ms)} ms gc+$gcs " +
            phases.entries.joinToString(" ") { "${it.key}=${"%.1f".format(it.value / 1_000_000f)}" })
    }

    /** Engine call durations by label (taps, swipe, predict, commit-wait), logged every 50 calls. */
    @Synchronized
    fun engine(label: String, ms: Float) {
        val a = engineSamples.getOrPut(label) { FloatArray(50) }
        val n = engineCounts[label] ?: 0
        a[n] = ms
        if (n + 1 == a.size) {
            val s = a.sorted()
            Log.d(TAG, "engine $label ms: p50=${"%.1f".format(s[25])} p95=${"%.1f".format(s[47])} p99/max=${"%.1f".format(s.last())}")
            engineCounts[label] = 0
        } else engineCounts[label] = n + 1
    }

    fun touchToDraw(ms: Long) {
        samples[n++] = ms
        if (n == samples.size) {
            val s = samples.sorted()
            Log.d(TAG, "touch->draw ms: min=${s.first()} p50=${s[s.size / 2]} p95=${s[(s.size * 95) / 100]} max=${s.last()}")
            n = 0
        }
    }
}
