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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Conformance runner (tools/conformance): plays scenarios step by step on whatever IME is active and
 * records everything observable after every step, once the IME is idle.
 *
 *   am instrument -w -e scenarios /data/local/tmp/conform/s.jsonl [-e out conform] dev.pboard.bench/.Conform
 *
 * A scenario (one JSON object per line):
 *   {"id": "...", "field": {"variant": "default", "text": "", "inputType": 1, "imeOptions": 6,
 *    "selStart": 0, "selEnd": 0, "privateImeOptions": "..."}, "steps": [step, ...]}
 * Steps (`op`):
 *   gesture  {"strokes": [[[x, y, t], ...], ...]}  one or more touch strokes with real timing (t ms from the
 *            step's first event). A stroke is DOWN at its first point, MOVEs, UP at its last point, so a tap
 *            is [[x, y, 0], [x, y, hold]]. Overlapping strokes become a multi-pointer gesture (rollover).
 *   fieldtap {"offset": n}   tap the text field at the caret position before character n (first waits, up to arg
 *            spellWaitMs = 20000, until the system spell checker has settled on the field: see awaitSpellCheck)
 *   app      {"cmd": "settext"|"setsel"|"reset", ...extras}   the app changes the field (no IME input)
 *   wait     {"ms": n}       do nothing for n ms, then observe
 * Every step may carry "gap" (ms from the previous step's last event to this step's first; default
 * arg gapMs = 1200, longer than double-space / double-tap windows so that idle waits never change
 * behaviour) and "settle" (extra minimum wait before observing).
 *
 * Observation (after the step's last event): wait minIdleMs, then sample screenshot + field state every
 * frameGapMs until `stableFrames` consecutive samples spanning >= stableSpanMs are identical (idle) or
 * idleTimeoutMs passes. Records the field (text, selection, composing span), every change the field saw
 * during the step (playground history, with uptimes), hashes of the IME area / suggestion strip / keys,
 * and saves each distinct IME-area image once as img/<hash>.png. Results: files/<out>/obs.jsonl.
 */
class Conform : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var ua: UiAutomation
    private val cbThread = HandlerThread("conform-cb").apply { start() }
    private val cbHandler = Handler(cbThread.looper)

    private fun argL(k: String, d: Long) = args.getString(k)?.toLong() ?: d
    private fun argI(k: String, d: Int) = args.getString(k)?.toInt() ?: d

    private val gapMs by lazy { argL("gapMs", 1200) }
    private val minIdleMs by lazy { argL("minIdleMs", 150) }
    private val frameGapMs by lazy { argL("frameGapMs", 40) }
    private val stableFrames by lazy { argI("stableFrames", 3) }
    private val stableSpanMs by lazy { argL("stableSpanMs", 250) }
    private val idleTimeoutMs by lazy { argL("idleTimeoutMs", 4000) }
    private val regionTop by lazy { argI("regionTop", 1300) }
    private val stripTop by lazy { argI("stripTop", 1517) }
    private val stripBottom by lazy { argI("stripBottom", 1639) }
    private val traceFrames by lazy { args.getString("traceFrames") == "1" }
    /** Shared device (dev phone): never press keys outside our app; stop the run when anything else is in front. */
    private val strict by lazy { args.getString("strict") == "1" }
    private val textX by lazy { (args.getString("stripTextX") ?: "105,975").split(",").map { it.trim().toInt() } }
    private val density by lazy { argI("density", 420) }

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
            val summary = run()
            result.putString("summary", summary.toString())
            Log.i(TAG, "done: $summary")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            result.putString("error", Log.getStackTraceString(t))
            code = Activity.RESULT_CANCELED
        }
        finish(code, result)
    }

    // ---------------------------------------------------------------- playground

    private fun playground(cmd: String, extras: Intent.() -> Unit = {}): JSONObject? {
        val latch = CountDownLatch(1)
        var data: String? = null
        val intent = Intent(PLAYGROUND_ACTION).setPackage("dev.pboard").putExtra("cmd", cmd).apply(extras)
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { data = resultData; latch.countDown() }
        }, cbHandler, Activity.RESULT_OK, null, null)
        if (!latch.await(5, TimeUnit.SECONDS)) return null
        return data?.let { JSONObject(it) }
    }

    private fun shell(cmd: String): String {
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun imeShown() = shell("dumpsys input_method").contains("mInputShown=true")

    private fun focusedWindow(): String =
        shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus") }?.trim() ?: "?"

    /** True when pboard's playground is the top resumed activity (tools/phone/guard.sh's foreground rule). */
    private fun playgroundInFront(): Boolean {
        val line = shell("dumpsys activity activities").lineSequence()
            .firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") } ?: return false
        return line.contains("dev.pboard/.PlaygroundActivity") || line.contains("dev.pboard/dev.pboard.PlaygroundActivity")
    }

    /** Fast foreground check around every screen capture: the playground itself reports window focus. */
    private fun playgroundFocused(): Boolean = playground("get")?.optBoolean("windowFocus", false) ?: false

    private val spellWaitMs by lazy { argL("spellWaitMs", 20000) }

    /**
     * Before a tap into the text: wait until the system spell checker has finished with the field (no word still
     * being checked, the set of marked words unchanged for 400 ms), at most spellWaitMs. Tapping a marked word opens
     * the TextView's suggestion popup with either keyboard, but the checker is Gboard's service: right after an IME
     * restart Gboard's process can take > 10 s to answer, so without the wait the popup depended on timing. Records
     * spellWaitMs / spellMarked (and spellTimeout) on the step. A playground without the `spell` command: no wait.
     */
    private fun awaitSpellCheck(rec: JSONObject) {
        if (spellWaitMs <= 0) return
        val t0 = SystemClock.uptimeMillis()
        var marked = -1
        var stableSince = t0
        while (true) {
            val q = playground("spell") ?: break
            if (!q.has("spellPending")) break
            val now = SystemClock.uptimeMillis()
            val m = q.optInt("spellMarked")
            if (m != marked) { marked = m; stableSince = now }
            if (q.optInt("spellPending") == 0 && now - stableSince >= 400) break
            if (now - t0 >= spellWaitMs) { rec.put("spellTimeout", true); break }
            SystemClock.sleep(100)
        }
        rec.put("spellWaitMs", SystemClock.uptimeMillis() - t0).put("spellMarked", marked)
    }

    /**
     * Our test screen is what is on screen: the playground has window focus, or (when a popup of its own text
     * field holds the focus) the playground is still the top resumed activity - the guard's rule.
     */
    private fun ourScreen(): Boolean = playgroundFocused() || playgroundInFront()

    private class Stop(msg: String) : RuntimeException(msg)

    /** Strict mode: the guard's check before every injected step; stops the run when it fails. */
    private fun guardStep(what: String) {
        if (strict && !playgroundInFront()) throw Stop("foreground is not the playground before $what: ${focusedWindow()}")
    }

    /**
     * Whether the suggestion strip shows suggestions (at least one divider: a thin, isolated, grey vertical
     * line in the strip's text area). Taps on the strip are only made then, so a tap can never land on a
     * toolbar button (which could open settings or another app).
     */
    private fun stripHasDivider(): Boolean {
        if (strict && !ourScreen()) throw Stop("playground not in front before a strip read")
        val shot = ua.takeScreenshot() ?: return false
        if (strict && !ourScreen()) { shot.recycle(); throw Stop("playground not in front after a strip read (discarded)") }
        val bmp = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        try {
            val y0 = stripTop + 6; val y1 = stripBottom - 6
            val x0 = textX[0]; val x1 = textX[1]
            val counts = HashMap<Int, Int>()
            for (y in y0 until y1 step 3) for (x in x0 until x1 step 7) { val c = bmp.getPixel(x, y) and 0xffffff; counts[c] = (counts[c] ?: 0) + 1 }
            val bg = counts.maxByOrNull { it.value }?.key ?: return false
            fun d(c: Int): Int = maxOf(abs(((c shr 16) and 255) - ((bg shr 16) and 255)), abs(((c shr 8) and 255) - ((bg shr 8) and 255)), abs((c and 255) - (bg and 255)))
            fun sat(c: Int): Int { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return maxOf(r, g, b) - minOf(r, g, b) }
            val minRun = 35 * density / 420
            for (x in x0 + 8 until x1 - 8) {
                var run = 0; var best = 0; var bestEnd = 0
                for (y in y0 until y1) {
                    val c = bmp.getPixel(x, y)
                    if (d(c) in 9..119 && sat(c) < 30) { run++; if (run > best) { best = run; bestEnd = y } } else run = 0
                }
                if (best < minRun) continue
                // A divider is a thin line: background 3 px and 7 px away on both sides. (The 7 px test alone took
                // the anti-aliased outer edges of Gboard 18.6's clipboard toolbar icon at 420 dpi for dividers, so a
                // strip pick tapped the toolbar and opened the clipboard / translate panel.)
                var side = 0
                for (y in bestEnd - best + 1..bestEnd) {
                    if (d(bmp.getPixel(x - 3, y)) > 8 || d(bmp.getPixel(x + 3, y)) > 8 ||
                        d(bmp.getPixel(x - 7, y)) > 8 || d(bmp.getPixel(x + 7, y)) > 8) side++
                }
                if (side < best / 10) return true
            }
            return false
        } finally { bmp.recycle() }
    }

    /**
     * Geometry check against the key surface's accessibility nodes (pboard exposes every key as a virtual view of
     * class Keyboard$Key described by its label: "q", "Capital Q", "Space", ...). [expected] is "name:x,y;..."
     * (screen px). Returns null when the keys match within [tol] px, the mismatch otherwise, or "" when the IME
     * exposes no key nodes (Gboard): then only the typed-key calibration checks the geometry.
     */
    private fun keyNodeMismatch(expected: String, tol: Float): String? {
        val info = ua.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ua.serviceInfo = info
        SystemClock.sleep(300)
        val found = HashMap<String, Pair<Float, Float>>()
        for (w in ua.windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            w.root?.let { stack.add(it) }
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n.className?.toString() == "android.inputmethodservice.Keyboard\$Key") {
                    val d = n.contentDescription?.toString() ?: continue
                    val name = when {
                        d.startsWith("Capital ") -> d.removePrefix("Capital ").lowercase()
                        d == "Space" -> "space"
                        d.length == 1 -> d.lowercase()
                        else -> d.lowercase()
                    }
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    found[name] = Pair(r.exactCenterX(), r.exactCenterY())
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        if (found.size < 10) return ""
        val bad = ArrayList<String>()
        for (e in expected.split(";").filter { it.isNotBlank() }) {
            val (name, xy) = e.split(":")
            val (x, y) = xy.split(",").map { it.toFloat() }
            val got = found[name] ?: continue
            if (abs(got.first - x) > tol || abs(got.second - y) > tol) bad += "%s expected %.0f,%.0f found %.0f,%.0f".format(name, x, y, got.first, got.second)
        }
        return if (bad.isEmpty()) null else bad.joinToString("; ")
    }

    /** Starts (or restarts) the playground and waits for the IME to show. */
    private fun startup(): Boolean {
        shell("am start -n dev.pboard/.PlaygroundActivity")
        SystemClock.sleep(1500)
        for (i in 0 until 6) {
            playground("reset") { putExtra("text", ""); putExtra("variant", "default") }
            val end = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < end) {
                if (imeShown()) { SystemClock.sleep(1000); return true }
                SystemClock.sleep(250)
            }
            if (!strict || playgroundInFront()) shell("input tap 540 200")
        }
        return false
    }

    private fun resetField(field: JSONObject?): JSONObject? = playground("reset") {
        val f = field ?: JSONObject()
        putExtra("text", f.optString("text", ""))
        putExtra("variant", f.optString("variant", "default"))
        if (f.has("inputType")) putExtra("inputType", f.getInt("inputType"))
        if (f.has("imeOptions")) putExtra("imeOptions", f.getInt("imeOptions"))
        if (f.has("privateImeOptions")) putExtra("privateImeOptions", f.getString("privateImeOptions"))
    }

    // ---------------------------------------------------------------- injection

    private class Ev(val t: Double, val stroke: Int, val kind: Int, val x: Float, val y: Float)

    private val inject3: java.lang.reflect.Method? by lazy {
        runCatching {
            UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    private fun injectFast(ev: MotionEvent) {
        val m = inject3
        if (m != null) m.invoke(ua, ev, false, false) else ua.injectInputEvent(ev, false)
    }

    /**
     * Injects the strokes with their own timing; the first event fires at uptime [startAt] (or now + 60 ms
     * if later). Returns the uptimes of the first and last events.
     */
    private fun inject(strokes: JSONArray, startAt: Long): LongArray {
        val evs = ArrayList<Ev>()
        for (s in 0 until strokes.length()) {
            val pts = strokes.getJSONArray(s)
            val n = pts.length()
            for (i in 0 until n) {
                val p = pts.getJSONArray(i)
                val kind = when (i) { 0 -> 0; n - 1 -> 2; else -> 1 }
                evs += Ev(p.getDouble(2), s, kind, p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
                if (n == 1) evs += Ev(p.getDouble(2) + 45.0, s, 2, p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
            }
        }
        // Stable by time; at equal times an UP goes before a DOWN.
        evs.sortWith(compareBy<Ev>({ it.t }, { if (it.kind == 2) 0 else 1 }))
        val t0 = evs.first().t
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val now = SystemClock.uptimeMillis()
        val anchorUp = maxOf(now + 60, startAt)
        val anchorNs = System.nanoTime() + (anchorUp - now) * 1_000_000L
        // Active pointers: stroke -> (pointer id, x, y), kept ordered by pointer id.
        val active = sortedMapOf<Int, FloatArray>() // pid -> [x, y]
        val pidOf = HashMap<Int, Int>()
        var downTime = anchorUp
        var last = anchorUp
        for (e in evs) {
            val offNs = ((e.t - t0) * 1e6).toLong()
            val deadline = anchorNs + offNs
            while (true) {
                val rem = deadline - System.nanoTime()
                if (rem <= 0) break
                if (rem > 3_000_000L) Thread.sleep((rem - 2_000_000L) / 1_000_000L) else Thread.onSpinWait()
            }
            val eventTime = anchorUp + ((offNs + 500_000L) / 1_000_000L)
            last = eventTime
            val action: Int
            when (e.kind) {
                0 -> {
                    var pid = 0
                    while (active.containsKey(pid)) pid++
                    pidOf[e.stroke] = pid
                    active[pid] = floatArrayOf(e.x, e.y)
                    if (active.size == 1) { downTime = eventTime; action = MotionEvent.ACTION_DOWN }
                    else action = MotionEvent.ACTION_POINTER_DOWN or (active.keys.indexOf(pid) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                1 -> {
                    val pid = pidOf[e.stroke] ?: continue
                    active[pid] = floatArrayOf(e.x, e.y)
                    action = MotionEvent.ACTION_MOVE
                }
                else -> {
                    val pid = pidOf[e.stroke] ?: continue
                    active[pid] = floatArrayOf(e.x, e.y)
                    action = if (active.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or (active.keys.indexOf(pid) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }
            val ids = active.keys.toList()
            val props = Array(ids.size) { k -> MotionEvent.PointerProperties().apply { id = ids[k]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(ids.size) { k ->
                val xy = active.getValue(ids[k])
                MotionEvent.PointerCoords().apply { x = xy[0]; y = xy[1]; pressure = 1f; size = 0.05f; touchMajor = 40f; touchMinor = 40f }
            }
            val ev = MotionEvent.obtain(downTime, eventTime, action, ids.size, props, coords, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectFast(ev)
            ev.recycle()
            if (e.kind == 2) active.remove(pidOf.remove(e.stroke))
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        return longArrayOf(anchorUp, last)
    }

    // ---------------------------------------------------------------- observation

    private lateinit var outDir: File
    private lateinit var imgDir: File
    private val savedImages = HashSet<String>()
    private var screenshotMs = 0L
    private var screenshots = 0

    private class Frame(val ime: String, val strip: String, val keys: String, val bmp: Bitmap?)

    private fun hashRows(px: IntArray, w: Int, from: Int, to: Int): String {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64
        for (i in from * w until to * w) {
            h = (h xor (px[i].toLong() and 0xffffffL)) * 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }

    private fun frame(keep: Boolean): Frame? {
        val t = SystemClock.uptimeMillis()
        if (strict && !ourScreen()) throw Stop("playground not in front before a screen capture")
        val shot = ua.takeScreenshot() ?: return null
        if (strict && !ourScreen()) { shot.recycle(); throw Stop("playground not in front after a screen capture (frame discarded)") }
        val bmp = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        screenshotMs += SystemClock.uptimeMillis() - t; screenshots++
        val w = bmp.width
        val h = bmp.height - regionTop
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, regionTop, w, h)
        val f = Frame(hashRows(px, w, 0, h), hashRows(px, w, stripTop - regionTop, stripBottom - regionTop),
            hashRows(px, w, stripBottom - regionTop, h), if (keep) bmp else null)
        if (!keep) bmp.recycle()
        return f
    }

    private fun stateKey(s: JSONObject?): String =
        if (s == null) "null" else listOf(s.optString("text"), s.optInt("selStart"), s.optInt("selEnd"),
            s.optInt("composingStart"), s.optInt("composingEnd")).joinToString("\u0001")

    /** Waits for idle after the step that ended at [lastEvent] and records one observation. */
    private fun observe(lastEvent: Long, histSince: Long, rec: JSONObject) {
        val minIdle = minIdleMs + rec.optLong("settle", 0)
        val wait = lastEvent + minIdle - SystemClock.uptimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
        val samples = ArrayList<Pair<Long, String>>()
        val frames = JSONArray()
        var lastFrame: Frame? = null
        var lastState: JSONObject? = null
        var idle = false
        val start = SystemClock.uptimeMillis()
        while (true) {
            val f = frame(keep = true) ?: break
            val st = playground("get")
            val now = SystemClock.uptimeMillis()
            lastFrame?.bmp?.recycle()
            lastFrame = f; lastState = st
            samples += Pair(now, f.ime + "|" + stateKey(st))
            if (traceFrames) frames.put(JSONArray().put(now - lastEvent).put(f.ime).put(f.strip).put(f.keys).put(stateKey(st).hashCode()))
            if (st == null) break
            // Idle: the newest sample has been unchanged for >= stableFrames samples spanning >= stableSpanMs.
            var run = 1
            while (run < samples.size && samples[samples.size - 1 - run].second == samples.last().second) run++
            if (run >= stableFrames && now - samples[samples.size - run].first >= stableSpanMs) { idle = true; break }
            if (now - start > idleTimeoutMs) break
            SystemClock.sleep(frameGapMs)
        }
        val endT = SystemClock.uptimeMillis()
        val f = lastFrame
        if (f?.bmp != null) {
            if (savedImages.add(f.ime)) {
                val crop = Bitmap.createBitmap(f.bmp, 0, regionTop, f.bmp.width, f.bmp.height - regionTop)
                File(imgDir, f.ime + ".png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle()
            }
            f.bmp.recycle()
        }
        // Idle = the time the final state was first seen (relative to the step's last event).
        var firstSeen = samples.lastOrNull()?.first ?: endT
        for (k in samples.indices.reversed()) { if (samples[k].second == samples.last().second) firstSeen = samples[k].first else break }
        if (traceFrames) rec.put("frames", frames)
        rec.put("idle", idle).put("idleMs", firstSeen - lastEvent).put("samples", samples.size)
            .put("observedMs", endT - lastEvent)
        if (f != null) rec.put("ime", f.ime).put("strip", f.strip).put("keys", f.keys)
        val st = lastState
        if (st == null) {
            rec.put("playgroundDead", true)
            return
        }
        rec.put("text", st.getString("text")).put("selStart", st.getInt("selStart")).put("selEnd", st.getInt("selEnd"))
            .put("compStart", st.getInt("composingStart")).put("compEnd", st.getInt("composingEnd"))
            .put("windowFocus", st.optBoolean("windowFocus", true)).put("focused", st.optBoolean("focused", true))
        playground("hist") { putExtra("since", histSince) }?.optJSONArray("hist")?.let { h ->
            // Times relative to the step's first event (negative: late changes after the previous observation).
            val t0 = rec.optLong("t0", histSince)
            val list = JSONArray()
            for (i in 0 until h.length()) {
                val e = h.getJSONObject(i)
                list.put(JSONArray().put(e.getLong("t") - t0).put(e.getString("ev")).put(e.getString("text"))
                    .put(e.getInt("s")).put(e.getInt("e")).put(e.getInt("cs")).put(e.getInt("ce")))
            }
            rec.put("hist", list)
        }
        if (!st.optBoolean("windowFocus", true)) rec.put("focusWindow", focusedWindow())
    }

    // ---------------------------------------------------------------- run

    private fun readScenarios(): List<JSONObject> {
        val path = args.getString("scenarios") ?: error("missing -e scenarios")
        val all = shell("cat $path").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { JSONObject(it) }.toList()
        val start = argI("start", 0)
        return all.drop(start).take(argI("limit", all.size))
    }

    private fun run(): JSONObject {
        val scenarios = readScenarios()
        outDir = File(context.filesDir, args.getString("out", "conform")).apply { deleteRecursively(); mkdirs() }
        imgDir = File(outDir, "img").apply { mkdirs() }
        check(startup()) { "IME not shown" }
        args.getString("keyCheck")?.let { exp ->
            val mm = keyNodeMismatch(exp, argI("keyCheckTol", 25).toFloat())
            Log.i(TAG, "key-node geometry check: " + (mm ?: "ok"))
            if (!mm.isNullOrEmpty()) {
                return JSONObject().put("scenarios", 0).put("steps", 0).put("stopped", "geometry mismatch (key nodes): $mm")
            }
        }
        var steps = 0
        var aborted = 0
        var stopped: String? = null
        var lastEvent = SystemClock.uptimeMillis()
        File(outDir, "obs.jsonl").bufferedWriter().use { w ->
            fun emit(r: JSONObject) { w.write(r.toString()); w.write("\n"); w.flush() }
            for ((si, sc) in scenarios.withIndex()) try {
                val id = sc.getString("id")
                Log.i(TAG, "scenario $si/${scenarios.size} $id")
                if (strict && !playgroundInFront()) {
                    stopped = "foreground is not the playground before " + id + ": " + focusedWindow()
                    break
                }
                if (playground("get") == null || !(playground("get")?.optBoolean("windowFocus", true) ?: false)) {
                    if (strict && !playgroundInFront()) { stopped = "playground not focused before " + id + ": " + focusedWindow(); break }
                    shell("input keyevent KEYCODE_BACK"); SystemClock.sleep(300)
                    startup()
                }
                // Initial state: reset the field, wait for the IME, observe (step -1).
                val resetAt = SystemClock.uptimeMillis()
                resetField(sc.optJSONObject("field"))
                SystemClock.sleep(argL("resetMs", 1500))
                val r0 = JSONObject().put("sc", id).put("step", -1).put("t0", resetAt)
                observe(SystemClock.uptimeMillis(), resetAt, r0)
                emit(r0)
                lastEvent = SystemClock.uptimeMillis()
                var histSince = SystemClock.uptimeMillis()
                val st = sc.getJSONArray("steps")
                for (i in 0 until st.length()) {
                    val step = st.getJSONObject(i)
                    val rec = JSONObject().put("sc", id).put("step", i).put("op", step.getString("op"))
                    if (step.has("settle")) rec.put("settle", step.getLong("settle"))
                    val startAt = lastEvent + step.optLong("gap", gapMs)
                    guardStep(id + " step " + i)
                    if (step.optBoolean("requireSuggestions") && !stripHasDivider()) {
                        // No suggestions showing: skip the strip tap (it could hit a toolbar button).
                        rec.put("skipped", "no-suggestions")
                    }
                    val times: LongArray = if (rec.has("skipped")) longArrayOf(SystemClock.uptimeMillis(), SystemClock.uptimeMillis())
                    else when (step.getString("op")) {
                        "gesture" -> inject(step.getJSONArray("strokes"), startAt)
                        "fieldtap" -> {
                            awaitSpellCheck(rec)
                            val p = playground("pos") { putExtra("offset", step.getInt("offset")) }
                            if (p == null || !p.has("x")) longArrayOf(SystemClock.uptimeMillis(), SystemClock.uptimeMillis()) else {
                                rec.put("x", p.getDouble("x")).put("y", p.getDouble("y"))
                                val tap = JSONArray().put(JSONArray().put(JSONArray().put(p.getDouble("x")).put(p.getDouble("y")).put(0))
                                    .put(JSONArray().put(p.getDouble("x")).put(p.getDouble("y")).put(step.optInt("hold", 50))))
                                inject(tap, startAt)
                            }
                        }
                        "app" -> {
                            val wait = startAt - SystemClock.uptimeMillis()
                            if (wait > 0) SystemClock.sleep(wait)
                            val t = SystemClock.uptimeMillis()
                            playground(step.getString("cmd")) {
                                for (k in step.keys()) when (val v = step.get(k)) {
                                    is Int -> putExtra(k, v)
                                    is Long -> putExtra(k, v)
                                    is String -> if (k != "op" && k != "cmd") putExtra(k, v)
                                    is Boolean -> putExtra(k, v)
                                }
                            }
                            longArrayOf(t, t)
                        }
                        "wait" -> {
                            val t = SystemClock.uptimeMillis()
                            SystemClock.sleep(step.optLong("ms", 0))
                            longArrayOf(t, SystemClock.uptimeMillis())
                        }
                        else -> error("unknown op ${step.getString("op")}")
                    }
                    rec.put("t0", times[0]).put("tEnd", times[1] - times[0]).put("gapActual", times[0] - lastEvent)
                    observe(times[1], histSince, rec)
                    histSince = SystemClock.uptimeMillis()
                    lastEvent = times[1]
                    emit(rec)
                    steps++
                    if (rec.optBoolean("playgroundDead") || !rec.optBoolean("windowFocus", true)) {
                        // The app died (an IME crash in dev.pboard takes the playground with it) or something
                        // else took the focus: the rest of the scenario is meaningless.
                        aborted++
                        emit(JSONObject().put("sc", id).put("step", i).put("aborted", true)
                            .put("focusWindow", if (rec.optBoolean("playgroundDead")) "dead" else rec.optString("focusWindow")))
                        if (strict && !playgroundInFront()) { stopped = "focus lost in " + id + " step " + i + ": " + rec.optString("focusWindow"); break }
                        shell("input keyevent KEYCODE_BACK"); SystemClock.sleep(500)
                        startup()
                        lastEvent = SystemClock.uptimeMillis()
                        break
                    }
                }
                if (sc.has("expectText") && stopped == null) {
                    val txt = playground("get")?.optString("text")
                    if (txt != sc.getString("expectText")) {
                        stopped = "geometry check failed: " + id + " typed '" + txt + "', expected '" + sc.getString("expectText") + "'"
                    }
                }
                if (stopped != null) break
            } catch (e: Stop) {
                stopped = e.message
                Log.w(TAG, "stopped: " + e.message)
                break
            }
        }
        return JSONObject().put("scenarios", scenarios.size).put("steps", steps).put("aborted", aborted)
            .put("stopped", stopped ?: JSONObject.NULL)
            .put("screenshots", screenshots).put("meanScreenshotMs", if (screenshots > 0) screenshotMs / screenshots else 0)
            .put("out", outDir.path)
    }

    companion object {
        const val TAG = "PboardConform"
        const val PLAYGROUND_ACTION = "dev.pboard.bench.PLAYGROUND"
    }
}
