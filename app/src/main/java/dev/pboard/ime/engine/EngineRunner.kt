package dev.pboard.ime.engine

import android.os.Handler
import android.os.Looper
import dev.pboard.BuildConfig
import dev.pboard.engine.KeyboardEngine
import dev.pboard.ime.bench.BenchProbe
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.ime.SerialWorker
import dev.pboard.ime.view.LatencyLog
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Runs all engine work on one background thread so decoding never blocks touch handling or drawing.
 * Requests are sequenced: only the newest request's result is delivered (on the main thread); older ones are
 * dropped. [awaitLatest] lets a fast space wait briefly for the up-to-date result (Gboard waits up to 40 ms).
 */
class EngineRunner {
    private val main = Handler(Looper.getMainLooper())
    /** After [shutdown] (the IME being destroyed) requests and background work are dropped, never rejected. */
    private val exec = SerialWorker("pboard-engine")

    @Volatile var engine: KeyboardEngine? = null

    @Volatile private var seq = 0

    /** Superseded decodes stop early; `adb shell setprop log.tag.PboardNoCancel DEBUG` turns it off (latency A/B). */
    private val cancelSuperseded = !android.util.Log.isLoggable("PboardNoCancel", android.util.Log.DEBUG)

    /** Engine phase timings of the call in progress (engine thread only; filled when latency logging is on). */
    private val phases = LinkedHashMap<String, Long>()

    private fun gcCount(): Int = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toIntOrNull() ?: 0

    /** Runs [work] timing it (and, for slow calls, its phases and GCs) when latency logging is on. */
    private fun <T> timed(label: String, e: KeyboardEngine, work: (KeyboardEngine) -> T): T {
        if (!LatencyLog.enabled) return work(e)
        val pe = e as? PboardEngine
        phases.clear()
        if (pe != null && pe.phaseTimer == null) pe.phaseTimer = { name, ns -> phases[name] = (phases[name] ?: 0L) + ns }
        val gc0 = gcCount()
        val t0 = System.nanoTime()
        val r = work(e)
        val ms = (System.nanoTime() - t0) / 1_000_000f
        LatencyLog.engine(label, ms)
        if (ms >= LatencyLog.SLOW_MS) LatencyLog.slow(label, ms, phases, gcCount() - gc0)
        return r
    }
    private var latest: Pending<*>? = null

    private inner class Pending<T>(val id: Int, val deliver: (T) -> Unit) {
        @Volatile var result: Any? = NONE
        var delivered = false
        lateinit var future: Future<*>

        fun deliverIfCurrent() {
            if (delivered || id != seq) return
            val r = result
            if (r === NONE) return
            delivered = true
            @Suppress("UNCHECKED_CAST")
            deliver(r as T)
        }
    }

    /** Computes [work] off the main thread and hands the result to [deliver] unless a newer request was made. */
    fun <T> request(work: (KeyboardEngine) -> T, deliver: (T) -> Unit) = request("engine", work, deliver)

    /** As [request], timing the engine call under [label] for the latency log. */
    fun <T> request(label: String, work: (KeyboardEngine) -> T, deliver: (T) -> Unit) {
        val e = engine ?: return
        if (exec.isShutdown) return
        val p = Pending(++seq, deliver)
        latest = p
        val queued = System.nanoTime()
        // Benchmark idle probe (bench build type only): requests in flight until delivered or dropped.
        if (BuildConfig.BENCH_PROBE) BenchProbe.engineInFlight.incrementAndGet()
        p.future = exec.submit {
          var handedOff = false
          try {
            if (p.id != seq) return@submit
            // Time spent waiting for the engine thread (e.g. behind a glide's live preview).
            if (LatencyLog.enabled) LatencyLog.engine("$label-wait", (System.nanoTime() - queued) / 1_000_000f)
            // A newer request makes this one's result unwanted: let a long decode (a glide's live preview)
            // stop early instead of holding up the newer one.
            val pe = (e as? PboardEngine)?.takeIf { cancelSuperseded }
            pe?.cancelled = { p.id != seq }
            try {
                p.result = timed(label, e, work)
            } finally {
                pe?.cancelled = null
            }
            if (p.id != seq) return@submit
            handedOff = true
            main.post {
                try { p.deliverIfCurrent() } finally {
                    if (BuildConfig.BENCH_PROBE) BenchProbe.engineInFlight.decrementAndGet()
                }
            }
          } finally {
            if (BuildConfig.BENCH_PROBE && !handedOff) BenchProbe.engineInFlight.decrementAndGet()
          }
        } ?: return run {
            if (BuildConfig.BENCH_PROBE) BenchProbe.engineInFlight.decrementAndGet()
        }
    }

    /** Fire-and-forget engine work (geometry updates, learning) kept in order with requests. */
    fun run(work: (KeyboardEngine) -> Unit) = run("background", work)

    /** As [run], timed under [label] for the latency log (work queued here delays the next request). */
    fun run(label: String, work: (KeyboardEngine) -> Unit) {
        val e = engine ?: return
        if (!BuildConfig.BENCH_PROBE) { exec.execute { timed(label, e, work) }; return }
        BenchProbe.engineInFlight.incrementAndGet()
        val queued = exec.execute {
            try { timed(label, e, work) } finally { BenchProbe.engineInFlight.decrementAndGet() }
        }
        if (!queued) BenchProbe.engineInFlight.decrementAndGet()
    }

    /** Cancels delivery of any outstanding request. */
    fun invalidate() { seq++ ; latest = null }

    /** Blocks the caller up to [ms] for the newest request and delivers it synchronously if it finished. */
    fun awaitLatest(ms: Long) {
        val p = latest ?: return
        if (!p.delivered) {
            val t0 = System.nanoTime()
            runCatching { p.future.get(ms, TimeUnit.MILLISECONDS) }
            if (LatencyLog.enabled) LatencyLog.engine("commit-wait", (System.nanoTime() - t0) / 1_000_000f)
            p.deliverIfCurrent()
        }
    }

    /** Stops the engine thread once queued work (e.g. saving learned data) is done; later work is dropped. */
    fun shutdown() = exec.shutdown()

    private companion object { val NONE = Any() }
}
