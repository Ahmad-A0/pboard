package dev.pboard.bench

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Replays touch traces on whatever IME is active, with the trace's own timing, and records the text
 * that the IME produces in dev.pboard's PlaygroundActivity.
 *
 * Injection: every MotionEvent is built with eventTime = anchor + trace t (ms) and is handed to
 * UiAutomation.injectInputEvent at that uptime instant, from a high-priority thread that sleeps then
 * spins to the deadline. The timestamps the IME sees are therefore exactly the trace's; real-time
 * arrival (which matters for IME timers such as long-press) follows the trace up to host scheduling
 * noise, which is measured per item ("stall") and retried when excessive.
 *
 * Arguments (am instrument -e key value):
 *   mode       replay | predict | timing                        (default replay)
 *   trace      device path of the JSONL trace (read via the shell, so /data/local/tmp works)
 *   out        output file name inside this app's files dir    (default results.jsonl / predict.jsonl)
 *   start, limit   item subset                                 (default all)
 *   chain      true: keep typing into the field when its text already equals the next item's
 *              context (continuous typing); false: reset the field before every item (default true)
 *   holdMs     tap contact duration when the trace has none    (default 45 = fitted human median)
 *   settleMs   minimum wait after the last event before reading (default 300 tap / 600 swipe),
 *              then until the text is unchanged for 250 ms
 *   resetMs    wait after resetting the field                   (default 700)
 *   stallTapMs, stallSwipeMs   retry threshold for real-time stalls (default 150 / 150)
 *   retries    max retries per item                             (default 3)
 *   variant    PlaygroundActivity field variant                 (default "default")
 *   slots      predict: "x:y,x:y,x:y" screen coords of suggestion slots, best slot first
 *   ready      predict: pixel probe "x:y,...;r,g,b;tol" that holds only while suggestions are shown
 *   hz, durMs, reps   timing: sample rates, duration and repetitions of the synthetic test swipes
 */
class Replay : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var ua: UiAutomation
    private val cbThread = HandlerThread("bench-cb").apply { start() }
    private val cbHandler = Handler(cbThread.looper)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_OK
        try {
            ua = uiAutomation
            val summary = when (args.getString("mode", "replay")) {
                "replay" -> replay()
                "predict" -> predict()
                "timing" -> timing()
                else -> error("unknown mode")
            }
            result.putString("summary", summary.toString())
            Log.i(TAG, "done: $summary")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            result.putString("error", Log.getStackTraceString(t))
            code = Activity.RESULT_CANCELED
        }
        finish(code, result)
    }

    // ---------------------------------------------------------------- playground control

    private fun playground(cmd: String, extras: Intent.() -> Unit = {}): JSONObject {
        val latch = CountDownLatch(1)
        var data: String? = null
        val intent = Intent(PLAYGROUND_ACTION).setPackage("dev.pboard").putExtra("cmd", cmd).apply(extras)
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { data = resultData; latch.countDown() }
        }, cbHandler, Activity.RESULT_OK, null, null)
        check(latch.await(5, TimeUnit.SECONDS)) { "playground did not answer $cmd" }
        return JSONObject(data ?: error("PlaygroundActivity is not running (no reply to $cmd)"))
    }

    private fun reset(text: String): JSONObject = playground("reset") {
        putExtra("text", text)
        putExtra("variant", args.getString("variant") ?: "default")
    }

    private fun shell(cmd: String): String {
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun readItems(): List<JSONObject> {
        val path = args.getString("trace") ?: error("missing -e trace")
        val all = shell("cat $path").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { JSONObject(it) }.toList()
        check(all.isNotEmpty()) { "empty trace $path" }
        val start = args.getString("start")?.toInt() ?: 0
        val limit = args.getString("limit")?.toInt() ?: all.size
        return all.drop(start).take(limit)
    }

    private fun outFile(default: String) = File(context.filesDir, args.getString("out", default))

    /** Waits (up to [timeoutMs]) until the input method window reports itself shown. */
    private fun waitForIme(timeoutMs: Long = 10_000): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (shell("dumpsys input_method").contains("mInputShown=true")) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun startup() {
        shell("am start -n dev.pboard/.PlaygroundActivity")
        if (settleMode == "slow") SystemClock.sleep(1500)
        else {  // state wait: until the playground answers
            val end = SystemClock.uptimeMillis() + 15_000
            while (runCatching { playground("get") }.isFailure && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        }
        // Right after `pm clear` an IME can take a while to come up; nudge it a few times.
        var shown = false
        for (i in 0 until 6) {
            reset("")
            if (waitForIme(10_000)) { shown = true; break }
            Log.i(TAG, "IME not shown yet, tapping the field")
            shell("input tap 540 200")
        }
        check(shown) { "IME not shown" }
        if (settleMode == "slow") SystemClock.sleep(1500)
    }

    // ---------------------------------------------------------------- injection

    private class Ev(val t: Double, val action: Int, val x: Float, val y: Float)

    /** Converts one trace item into a time-ordered single-pointer event list (t in trace ms). */
    private fun events(item: JSONObject): List<Ev> {
        val pts = item.getJSONArray("points")
        val n = pts.length()
        fun p(i: Int) = pts.getJSONArray(i)
        val out = ArrayList<Ev>()
        if (item.getString("kind") == "swipe") {
            for (i in 0 until n) {
                val a = when (i) { 0 -> MotionEvent.ACTION_DOWN; n - 1 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                out += Ev(p(i).getDouble(2), a, p(i).getDouble(0).toFloat(), p(i).getDouble(1).toFloat())
            }
        } else {
            val holds = item.optJSONArray("holdMs")
            val defHold = args.getString("holdMs")?.toDouble() ?: 45.0
            for (i in 0 until n) {
                val t = p(i).getDouble(2)
                var hold = holds?.optDouble(i, defHold) ?: defHold
                if (i + 1 < n) hold = min(hold, p(i + 1).getDouble(2) - t - 8.0)
                hold = max(hold, 15.0)
                val x = p(i).getDouble(0).toFloat(); val y = p(i).getDouble(1).toFloat()
                out += Ev(t, MotionEvent.ACTION_DOWN, x, y)
                out += Ev(t + hold, MotionEvent.ACTION_UP, x, y)
            }
        }
        return out
    }

    /** Injects [evs] in real time. Returns per-event lateness (ms) of the injection call vs schedule. */
    private fun inject(evs: List<Ev>): DoubleArray {
        val t0 = evs.first().t
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        // uptimeMillis and nanoTime are both CLOCK_MONOTONIC based; anchor once, spin on nanoTime.
        val anchorUp = SystemClock.uptimeMillis() + 60
        val anchorNs = System.nanoTime() + 60_000_000L
        val late = DoubleArray(evs.size)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        var downTime = anchorUp
        for ((k, e) in evs.withIndex()) {
            val offNs = ((e.t - t0) * 1e6).toLong()
            val deadline = anchorNs + offNs
            while (true) {
                val rem = deadline - System.nanoTime()
                if (rem <= 0) break
                if (rem > 3_000_000L) Thread.sleep((rem - 2_000_000L) / 1_000_000L) else Thread.onSpinWait()
            }
            val eventTime = anchorUp + ((offNs + 500_000L) / 1_000_000L)
            if (e.action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = e.x; y = e.y; pressure = 1f; size = 0.05f; touchMajor = 40f; touchMinor = 40f
            })
            val ev = MotionEvent.obtain(downTime, eventTime, e.action, 1, props, coords, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            late[k] = (System.nanoTime() - deadline) / 1e6
            injectFast(ev)
            ev.recycle()
        }
        lastInjectNs = System.nanoTime()
        return late
    }

    /** nanoTime right after the last injected event (quiescence counts from here). */
    private var lastInjectNs = 0L

    /** Largest real-time stretch of a gap between consecutive injections beyond the trace's gap (ms). */
    private fun stall(late: DoubleArray): Double {
        var m = 0.0
        for (k in 1 until late.size) m = max(m, late[k] - late[k - 1])
        return m
    }

    /**
     * For taps only the real-time hold matters (a stretched DOWN->UP can turn a tap into a
     * long-press); a delayed DOWN is harmless because the IME sees the exact timestamps.
     * Events of a tap item alternate DOWN, UP.
     */
    private fun holdStretch(late: DoubleArray): Double {
        var m = 0.0
        var k = 1
        while (k < late.size) { m = max(m, late[k] - late[k - 1]); k += 2 }
        return m
    }

    /**
     * UiAutomation.injectInputEvent(event, sync) waits for window animations after each event on
     * API 34+ (waitForAnimations defaults to true), stalling a DOWN while the key-press popup
     * animates. The 3-arg overload (TestApi, reached by reflection) skips that; fall back if absent.
     */
    private val inject3: java.lang.reflect.Method? by lazy {
        runCatching {
            UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }.getOrNull().also { Log.i(TAG, "3-arg injectInputEvent available: ${it != null}") }
    }

    private fun injectFast(ev: MotionEvent) {
        val m = inject3
        if (m != null) m.invoke(ua, ev, false, false) else ua.injectInputEvent(ev, false)
    }

    /** Waits at least [minMs], then until the field text is unchanged for [stableMs] (cap [maxMs]). */
    private fun settle(minMs: Long, stableMs: Long = 250, maxMs: Long = 4000): JSONObject {
        val start = SystemClock.uptimeMillis()
        SystemClock.sleep(minMs)
        var last = playground("get")
        var since = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < maxMs) {
            SystemClock.sleep(100)
            val cur = playground("get")
            if (cur.getString("text") != last.getString("text")) { last = cur; since = SystemClock.uptimeMillis() }
            else if (SystemClock.uptimeMillis() - since >= stableMs) break
        }
        return last
    }

    // ---------------------------------------------------------------- "done" detection
    //
    // settle=slow (default): fixed minimum wait, then field text unchanged for 250 ms (above).
    // settle=fast: explicit or evidence-based quiescence, no fixed slack:
    //   - pboard bench builds expose content://dev.pboard.benchprobe/state (BenchProbe): done when the
    //     IME has handled every injected UP, no engine request is in flight, and its main thread
    //     (which also runs the playground field) is idle after two drawn frames;
    //   - any other IME (Gboard): done when neither the field (playground change history) nor the
    //     IME window (its content frame stats) changed for QUIET ms after the last injected event.
    // settle=compare: fast, then slow on top; the slow result is used, both are recorded, so one
    //   run measures the false-early rate (fast text != slow text) and the time saved.

    private val settleMode by lazy { args.getString("settle", "slow")!! }
    private val quietMs by lazy { args.getString("quietMs")?.toLong() ?: 100L }
    private val probeUri = android.net.Uri.parse("content://dev.pboard.benchprobe/state")
    private var useProbe = false
    private var imeWindowId: Int? = null

    private class Probe(val ups: Long, val inFlight: Int, val mainIdle: Boolean)

    private fun probe(): Probe? = runCatching {
        context.contentResolver.query(probeUri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) Probe(c.getLong(c.getColumnIndexOrThrow("ups")),
                c.getInt(c.getColumnIndexOrThrow("engineInFlight")), c.getInt(c.getColumnIndexOrThrow("mainIdle")) == 1) else null
        }
    }.getOrNull()

    /** Chooses the done-detection source for the active IME (call after the IME is shown). */
    private fun setupQuiescence() {
        if (settleMode == "slow") return
        val ime = shell("settings get secure default_input_method").trim()
        useProbe = ime.startsWith("dev.pboard/") && probe() != null
        if (!useProbe) {
            runCatching {
                ua.serviceInfo = ua.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
            }
            imeWindowId = findImeWindow()
        }
        Log.i(TAG, "settle=$settleMode ime=$ime probe=$useProbe imeWindow=$imeWindowId")
    }

    private fun findImeWindow(): Int? = runCatching {
        ua.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.id
    }.getOrNull()

    private fun lastImeFrameNs(): Long {
        val id = imeWindowId ?: findImeWindow().also { imeWindowId = it } ?: return 0L
        val fs = runCatching { ua.getWindowContentFrameStats(id) }.getOrNull() ?: return 0L
        val n = fs.frameCount
        if (n == 0) return 0L
        val p = fs.getFramePresentedTimeNano(n - 1)
        return if (p > 0) p else fs.getFramePostedTimeNano(n - 1)
    }

    /** Fast quiescence; [targetUps] = UPs the IME must have handled (probe), else ignored. */
    private fun quiesce(targetUps: Long, capMs: Long = 4000): JSONObject {
        val start = System.nanoTime()
        val capNs = capMs * 1_000_000L
        if (useProbe) {
            while (System.nanoTime() - start < capNs) {
                val p = probe() ?: break
                if (p.ups >= targetUps && p.inFlight == 0 && p.mainIdle) break
            }
            return playground("get")
        }
        imeWindowId?.let { id -> runCatching { ua.clearWindowContentFrameStats(id) } }
        val quietNs = quietMs * 1_000_000L
        var cur: JSONObject
        while (true) {
            cur = playground("get")
            val nowNs = System.nanoTime()
            val changeUp = cur.optLong("lastChangeUptime", 0L)
            val lastChangeNs = if (changeUp > 0) nowNs - (SystemClock.uptimeMillis() - changeUp) * 1_000_000L else 0L
            val last = maxOf(lastInjectNs, lastChangeNs, lastImeFrameNs())
            if (nowNs - last >= quietNs || nowNs - start >= capNs) break
            SystemClock.sleep(10)
        }
        return cur
    }

    private fun upsNow(): Long = if (useProbe) probe()?.ups ?: 0L else 0L

    /** Result of waiting after an item: the field state used, plus compare-mode extras. */
    private class Settled(val state: JSONObject, val fastText: String?, val fastMs: Double, val slowMs: Double)

    private fun settleAfter(slowMinMs: Long, targetUps: Long): Settled {
        val t0 = System.nanoTime()
        return when (settleMode) {
            "fast" -> { val s = quiesce(targetUps); Settled(s, null, (System.nanoTime() - t0) / 1e6, 0.0) }
            "compare" -> {
                val f = quiesce(targetUps)
                val fastMs = (System.nanoTime() - t0) / 1e6
                val s = settle(slowMinMs)
                Settled(s, f.getString("text"), fastMs, (System.nanoTime() - t0) / 1e6)
            }
            else -> { val s = settle(slowMinMs); Settled(s, null, 0.0, (System.nanoTime() - t0) / 1e6) }
        }
    }

    /** After a field reset: fast modes wait for quiescence instead of the fixed reset wait. */
    private fun settleReset(resetMs: Long): JSONObject = when (settleMode) {
        "fast" -> { lastInjectNs = System.nanoTime(); quiesce(upsNow()) }
        else -> settle(resetMs, stableMs = 200, maxMs = resetMs + 1500)
    }

    // ---------------------------------------------------------------- modes

    private fun replay(): JSONObject {
        val items = readItems()
        startup()
        setupQuiescence()
        if (settleMode != "slow") { lastInjectNs = System.nanoTime(); quiesce(upsNow(), capMs = 10_000) }
        val chain = args.getString("chain", "true") != "false"
        val resetMs = args.getString("resetMs")?.toLong() ?: 700
        val maxRetries = args.getString("retries")?.toInt() ?: 3
        val out = outFile("results.jsonl")
        var maxStall = 0.0
        var resets = 0
        var retries = 0
        var degraded = 0
        focusLost = 0
        out.bufferedWriter().use { w ->
            for ((idx, item) in items.withIndex()) {
                val ctx = item.optString("context", "")
                val kind = item.getString("kind")
                var before = playground("get")
                val chained = chain && idx > 0 &&
                    before.getString("text").trimEnd().equals(ctx.trimEnd(), ignoreCase = true)
                val startText = if (chained) before.getString("text") else ctx
                val evs = events(item)
                val metric: (DoubleArray) -> Double = if (kind == "swipe") ::stall else ::holdStretch
                // Host load can stall an injection call (typically a DOWN). Event timestamps stay
                // exact regardless, but real-time arrival matters to IME timers (long-press), so an
                // attempt whose injection stretched a swipe gap / a tap hold by more than the limit is redone from a
                // reset to the same starting text. Same if the IME produced nothing at all.
                val stallLimit = args.getString(if (kind == "swipe") "stallSwipeMs" else "stallTapMs")?.toDouble()
                    ?: 150.0
                val settleMs = args.getString("settleMs")?.toLong() ?: if (kind == "swipe") 600 else 300
                var attempt = 0
                var late: DoubleArray
                var after: JSONObject
                var settled: Settled
                val nUps = evs.count { it.action == MotionEvent.ACTION_UP }
                while (true) {
                    if (!chained || attempt > 0) {
                        reset(startText); resets++
                        before = settleReset(resetMs)
                    }
                    val ups0 = upsNow()
                    late = inject(evs)
                    settled = settleAfter(settleMs, ups0 + nUps)
                    after = settled.state
                    val unchanged = after.getString("text") == before.getString("text")
                    // A stray touch may have opened something over the field (e.g. an IME toolbar
                    // button launching its settings): bring the playground back and redo the item.
                    val lostFocus = !after.optBoolean("windowFocus", true)
                    if (lostFocus) { focusLost++; recoverFocus() }
                    if ((metric(late) <= stallLimit && !unchanged && !lostFocus) || attempt >= maxRetries) break
                    attempt++
                    Log.i(TAG, "item $idx: stall ${metric(late)} ms, unchanged=$unchanged, lostFocus=$lostFocus, retry $attempt")
                    if (unchanged) waitForIme(3000)
                    SystemClock.sleep(300)
                }
                retries += attempt
                val st = metric(late)
                maxStall = max(maxStall, st)
                if (st > stallLimit) degraded++
                val rec = JSONObject().put("i", idx).put("kind", kind).put("context", ctx)
                    .put("target", item.getString("target")).put("chained", chained)
                    .put("before", before.getString("text")).put("after", after.getString("text"))
                    .put("composingStart", after.getInt("composingStart")).put("composingEnd", after.getInt("composingEnd"))
                    .put("stallMs", (st * 10).roundToInt() / 10.0).put("gapStallMs", (stall(late) * 10).roundToInt() / 10.0)
                    .put("startLateMs", (late[0] * 10).roundToInt() / 10.0)
                    .put("attempts", attempt + 1).put("timingDegraded", st > stallLimit)
                if (settleMode != "slow") rec.put("settle", settleMode).put("fastMs", (settled.fastMs * 10).roundToInt() / 10.0)
                if (settleMode == "compare") rec.put("fastAfter", settled.fastText).put("slowMs", (settled.slowMs * 10).roundToInt() / 10.0)
                // Carry any extra annotation fields of the trace item through to the result.
                for (k in item.keys()) if (k !in setOf("points", "holdMs") && !rec.has(k)) rec.put(k, item.get(k))
                w.write(rec.toString()); w.write("\n"); w.flush()
                Log.i(TAG, "item $idx/${items.size} target=${item.getString("target")} after=${JSONObject.quote(after.getString("text"))}")
            }
        }
        return JSONObject().put("items", items.size).put("resets", resets).put("retries", retries)
            .put("timingDegraded", degraded).put("focusLost", focusLost).put("maxStallMs", maxStall).put("out", out.path)
    }

    private var focusLost = 0

    private fun recoverFocus() {
        Log.w(TAG, "playground lost window focus; bringing it back")
        shell("input keyevent KEYCODE_BACK")
        SystemClock.sleep(500)
        shell("am start -n dev.pboard/.PlaygroundActivity")
        SystemClock.sleep(1500)
        reset("")
        waitForIme(5000)
        SystemClock.sleep(500)
    }

    /** spec: "x:y,x:y,...;r,g,b;tol" - every listed pixel must be within tol of the colour. */
    private fun pixelsMatch(bmp: Bitmap, spec: String): Boolean {
        val parts = spec.split(";")
        val rgb = parts[1].split(",").map { it.trim().toInt() }
        val tol = parts[2].trim().toInt()
        return parts[0].split(",").all { p ->
            val xy = p.split(":").map { it.trim().toFloat().roundToInt() }
            val c = bmp.getPixel(xy[0], xy[1])
            abs(((c shr 16) and 255) - rgb[0]) <= tol && abs(((c shr 8) and 255) - rgb[1]) <= tol &&
                abs((c and 255) - rgb[2]) <= tol
        }
    }

    /**
     * Next-word prediction without OCR. Item kind "predict": the field is pre-filled with `prefix`,
     * the item's taps type the last context word cleanly plus space (so the IME is in its natural
     * after-a-word state), then one suggestion slot is tapped; whatever word the IME inserts is that
     * slot's prediction. This is repeated from scratch for every slot. Before tapping, the IME-specific
     * pixel probe `ready` (e.g. Gboard's strip dividers) must hold, so a toolbar button is never hit.
     */
    private fun predict(): JSONObject {
        val items = readItems()
        startup()
        val slots = (args.getString("slots") ?: error("missing -e slots")).split(",").map { s ->
            s.split(":").let { Pair(it[0].toFloat(), it[1].toFloat()) }
        }
        val ready = args.getString("ready")
        val resetMs = args.getString("resetMs")?.toLong() ?: 700
        val out = outFile("predict.jsonl")
        var noStrip = 0
        out.bufferedWriter().use { w ->
            for ((idx, item) in items.withIndex()) {
                val prefix = item.optString("prefix", "")
                val evs = if (item.getJSONArray("points").length() > 0) events(item) else emptyList()
                val preds = JSONArray()
                var shown = true
                for (s in slots) {
                    var before: JSONObject
                    var attempt = 0
                    while (true) {
                        reset(prefix)
                        settle(resetMs, stableMs = 200)
                        val late = if (evs.isNotEmpty()) inject(evs) else DoubleArray(1)
                        before = settle(450)
                        if (holdStretch(late) <= 150.0 || attempt >= 2) break
                        attempt++
                    }
                    if (ready != null) {
                        // Screenshots may be hardware bitmaps (no getPixel); copy to software.
                        val bmp = ua.takeScreenshot()?.copy(Bitmap.Config.ARGB_8888, false)
                        if (bmp == null || !pixelsMatch(bmp, ready)) { shown = false; break }
                    }
                    inject(listOf(Ev(0.0, MotionEvent.ACTION_DOWN, s.first, s.second), Ev(60.0, MotionEvent.ACTION_UP, s.first, s.second)))
                    val after = settle(400)
                    preds.put(JSONObject().put("before", before.getString("text")).put("after", after.getString("text")))
                }
                if (!shown) noStrip++
                val rec = JSONObject().put("i", idx).put("context", item.optString("context")).put("prefix", prefix)
                    .put("target", item.getString("target")).put("stripShown", shown).put("slots", preds)
                for (k in listOf("id", "sentence", "word")) if (item.has(k)) rec.put(k, item.get(k))
                w.write(rec.toString()); w.write("\n"); w.flush()
                Log.i(TAG, "predict $idx/${items.size} target=${item.getString("target")} shown=$shown")
            }
        }
        reset("")
        return JSONObject().put("items", items.size).put("noStrip", noStrip).put("out", out.path)
    }

    /**
     * Timing self-test: injects synthetic swipes onto the PlaygroundActivity window (above the
     * keyboard) and records what the app actually received next to what was scheduled.
     */
    private fun timing(): JSONObject {
        shell("am start -n dev.pboard/.PlaygroundActivity")
        SystemClock.sleep(1000)
        val hzList = (args.getString("hz") ?: "60,90,120").split(",").map { it.toDouble() }
        val durMs = args.getString("durMs")?.toDouble() ?: 600.0
        val reps = args.getString("reps")?.toInt() ?: 5
        val res = JSONObject()
        for (rep in 0 until reps) for (hz in hzList) {
            val n = (durMs * hz / 1000).roundToInt() + 1
            val evs = (0 until n).map { i ->
                val f = i.toDouble() / (n - 1)
                val a = when (i) { 0 -> MotionEvent.ACTION_DOWN; n - 1 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                Ev(i * 1000.0 / hz, a, (150 + 750 * f).toFloat(), (500 + 250 * kotlin.math.sin(f * 6.28)).toFloat())
            }
            playground("probe_start")
            SystemClock.sleep(150)
            val late = inject(evs)
            SystemClock.sleep(400)
            val got = playground("probe_get").getJSONArray("events")
            val res1 = JSONObject().put("hz", hz).put("rep", rep)
                .put("scheduledRel", JSONArray(evs.map { (it.t - evs.first().t).roundToInt() }))
                .put("injectLateMs", JSONArray(late.map { (it * 100).roundToInt() / 100.0 }))
                .put("stallMs", stall(late)).put("received", got)
            res.put("${hz.roundToInt()}Hz#$rep", res1)
        }
        val f = outFile("timing.json")
        f.writeText(res.toString())
        return JSONObject().put("out", f.path)
    }

    companion object {
        const val TAG = "PboardBench"
        const val PLAYGROUND_ACTION = "dev.pboard.bench.PLAYGROUND"
    }
}
