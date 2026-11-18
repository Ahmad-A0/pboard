package dev.pboard.bench

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App-compatibility runner (tools/appcompat): plays scenarios in OTHER apps' text fields on whatever IME is active
 * and records what the field holds after every step, read through accessibility (text, selection, class, input
 * type), plus whether the IME is shown, which app is in front and the IME/app process ids (a changed pid = crash).
 *
 *   am instrument -w -e scenarios /data/local/tmp/appcompat/s.jsonl [-e out appcompat] dev.pboard.bench/.AppCompat
 *
 * A scenario (one JSON object per line): {"id", "setup": [op...], "steps": [op...], "ime": "<package>",
 * "app": "<package>", "probe": selector?}. Setup ops run unobserved, then the field is observed (step -1), then every
 * step is run and observed. A failed setup (field not found / IME not shown) is recorded and the scenario skipped.
 *
 * Ops: shell {cmd} | sleep {ms} | wait {ms} (observed) | click {sel, optional, timeout} (tap a node's centre) |
 *      dismiss {texts: [...]} (click whichever is shown, repeatedly, up to 6 times) |
 *      focus {sel, timeout} (tap the node, wait for an input-focused node and the IME) |
 *      gesture {strokes} (touch strokes [[x, y, tMs], ...]: tap = 2 points) |
 *      setsel {start, end} / settext {text} / paste {text} (accessibility actions on the focused field: what the app
 *      or the user's own selection handles do) | clip {text} (clipboard only) | key {code} (`input keyevent`) |
 *      rotate {rot} (0 portrait, 1 landscape) | back (KEYCODE_BACK) | resume {pkg} (back to the app's task, as the
 *      launcher does) | refocus {ifHidden} (tap the scenario's field "sel" again, e.g. after an app switch).
 * A selector: {"res", "text", "textStartsWith", "desc", "cls", "pkg", "editable": bool, "index": n}.
 * Observation: after the step, field state is sampled every 100 ms until unchanged for >= 3 samples over >= 450 ms
 * (idle) or 5 s. `probe` names a node whose text is recorded too (the test web page's composition status line).
 * Results: files/<out>/obs.jsonl (pulled by the host with run-as).
 */
class AppCompat : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var ua: UiAutomation

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
            val info = ua.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            ua.serviceInfo = info
            result.putString("summary", run().toString())
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            result.putString("error", Log.getStackTraceString(t))
            code = Activity.RESULT_CANCELED
        }
        finish(code, result)
    }

    // ---------------------------------------------------------------- helpers

    private fun shell(cmd: String): String {
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun pidOf(pkg: String?): Int =
        if (pkg.isNullOrEmpty()) -1 else shell("pidof $pkg").trim().split(" ").firstOrNull()?.toIntOrNull() ?: -1

    private fun imeShown(): Boolean = runCatching { ua.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    private fun topPackage(): String? = runCatching {
        ua.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            .firstNotNullOfOrNull { it.root?.packageName?.toString() } ?: ua.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    private fun roots(): List<AccessibilityNodeInfo> = runCatching {
        val ws = ua.windows.filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ws.sortedByDescending { if (it.isActive) 1 else 0 }.mapNotNull { it.root }
    }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(ua.rootInActiveWindow) }

    private fun matches(n: AccessibilityNodeInfo, sel: JSONObject): Boolean {
        sel.optString("res").takeIf { it.isNotEmpty() }?.let { if (n.viewIdResourceName != it) return false }
        sel.optString("text").takeIf { it.isNotEmpty() }?.let { t ->
            val s = n.text?.toString(); val d = n.contentDescription?.toString()
            if (!(s.equals(t, true) || d.equals(t, true))) return false
        }
        sel.optString("textStartsWith").takeIf { it.isNotEmpty() }?.let { if (n.text?.toString()?.startsWith(it) != true) return false }
        sel.optString("textContains").takeIf { it.isNotEmpty() }?.let { t ->
            if (n.text?.toString()?.contains(t, true) != true && n.contentDescription?.toString()?.contains(t, true) != true) return false
        }
        sel.optString("desc").takeIf { it.isNotEmpty() }?.let { if (n.contentDescription?.toString()?.equals(it, true) != true) return false }
        sel.optString("cls").takeIf { it.isNotEmpty() }?.let { if (n.className?.toString() != it) return false }
        sel.optString("pkg").takeIf { it.isNotEmpty() }?.let { if (n.packageName?.toString() != it) return false }
        if (sel.has("editable") && n.isEditable != sel.getBoolean("editable")) return false
        if (!n.isVisibleToUser) return false
        return true
    }

    private fun find(sel: JSONObject): AccessibilityNodeInfo? {
        val want = sel.optInt("index", 0)
        var k = 0
        for (root in roots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            // breadth-first, in document order
            while (stack.isNotEmpty()) {
                val n = stack.removeFirst()
                if (matches(n, sel)) { if (k == want) return n; k++ }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun waitFind(sel: JSONObject, timeoutMs: Long): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            find(sel)?.let { return it }
            if (SystemClock.uptimeMillis() > end) return null
            SystemClock.sleep(250)
        }
    }

    private fun centre(n: AccessibilityNodeInfo): Pair<Float, Float> {
        val r = Rect(); n.getBoundsInScreen(r)
        return Pair(r.exactCenterX(), r.exactCenterY())
    }

    private fun focused(): AccessibilityNodeInfo? = runCatching {
        ua.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.also { it.refresh() }
    }.getOrNull()

    // ---------------------------------------------------------------- injection

    private val inject3: java.lang.reflect.Method? by lazy {
        runCatching {
            UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    private fun injectOne(ev: MotionEvent) {
        val m = inject3
        if (m != null) m.invoke(ua, ev, false, false) else ua.injectInputEvent(ev, false)
    }

    private class Ev(val t: Double, val stroke: Int, val kind: Int, val x: Float, val y: Float)

    /** Plays touch strokes with their own timing (overlapping strokes become multi-pointer); returns the last event's uptime. */
    private fun inject(strokes: JSONArray): Long {
        val evs = ArrayList<Ev>()
        for (s in 0 until strokes.length()) {
            val pts = strokes.getJSONArray(s)
            val n = pts.length()
            for (i in 0 until n) {
                val p = pts.getJSONArray(i)
                evs += Ev(p.getDouble(2), s, when (i) { 0 -> 0; n - 1 -> 2; else -> 1 }, p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
                if (n == 1) evs += Ev(p.getDouble(2) + 45.0, s, 2, p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
            }
        }
        evs.sortWith(compareBy<Ev>({ it.t }, { if (it.kind == 2) 0 else 1 }))
        val t0 = evs.first().t
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val anchor = SystemClock.uptimeMillis() + 30
        val anchorNs = System.nanoTime() + 30_000_000L
        val active = sortedMapOf<Int, FloatArray>()
        val pidOf = HashMap<Int, Int>()
        var downTime = anchor
        var last = anchor
        for (e in evs) {
            val offNs = ((e.t - t0) * 1e6).toLong()
            while (true) {
                val rem = anchorNs + offNs - System.nanoTime()
                if (rem <= 0) break
                if (rem > 3_000_000L) Thread.sleep((rem - 2_000_000L) / 1_000_000L) else Thread.onSpinWait()
            }
            val eventTime = anchor + offNs / 1_000_000L
            last = eventTime
            val action: Int = when (e.kind) {
                0 -> {
                    var pid = 0
                    while (active.containsKey(pid)) pid++
                    pidOf[e.stroke] = pid
                    active[pid] = floatArrayOf(e.x, e.y)
                    if (active.size == 1) { downTime = eventTime; MotionEvent.ACTION_DOWN }
                    else MotionEvent.ACTION_POINTER_DOWN or (active.keys.indexOf(pid) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                1 -> { val pid = pidOf[e.stroke] ?: continue; active[pid] = floatArrayOf(e.x, e.y); MotionEvent.ACTION_MOVE }
                else -> {
                    val pid = pidOf[e.stroke] ?: continue
                    active[pid] = floatArrayOf(e.x, e.y)
                    if (active.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or (active.keys.indexOf(pid) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }
            val ids = active.keys.toList()
            val props = Array(ids.size) { k -> MotionEvent.PointerProperties().apply { id = ids[k]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(ids.size) { k ->
                val xy = active.getValue(ids[k])
                MotionEvent.PointerCoords().apply { x = xy[0]; y = xy[1]; pressure = 1f; size = 0.05f; touchMajor = 40f; touchMinor = 40f }
            }
            val ev = MotionEvent.obtain(downTime, eventTime, action, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            injectOne(ev)
            ev.recycle()
            if (e.kind == 2) active.remove(pidOf.remove(e.stroke))
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        return last
    }

    private fun tap(x: Float, y: Float, hold: Int = 60) {
        inject(JSONArray().put(JSONArray().put(JSONArray().put(x).put(y).put(0)).put(JSONArray().put(x).put(y).put(hold))))
    }

    private fun setClip(text: String) {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("appcompat", text))
            }.onFailure { Log.w(TAG, "clipboard", it) }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- observation

    private fun fieldState(probe: JSONObject?): JSONObject {
        val o = JSONObject()
        val n = focused()
        if (n == null) {
            o.put("field", JSONObject.NULL)
        } else {
            val hint = runCatching { n.isShowingHintText }.getOrDefault(false)
            o.put("text", if (hint) "" else n.text?.toString() ?: "")
            o.put("selStart", n.textSelectionStart).put("selEnd", n.textSelectionEnd)
            o.put("cls", n.className?.toString()).put("res", n.viewIdResourceName).put("pkg", n.packageName?.toString())
            o.put("password", n.isPassword).put("inputType", n.inputType)
            if (hint) o.put("hint", true)
            // views that show their text only as a description (terminal emulators): the end of it
            if (n.text.isNullOrEmpty()) n.contentDescription?.toString()?.trimEnd()?.takeLast(240)?.let { o.put("desc", it) }
        }
        if (probe != null) find(probe)?.let { it.refresh(); o.put("probe", it.text?.toString() ?: it.contentDescription?.toString()) }
        return o
    }

    private fun key(o: JSONObject) = listOf(o.opt("text"), o.opt("selStart"), o.opt("selEnd"), o.opt("probe"), o.opt("desc")).joinToString("\u0001")

    private fun observe(rec: JSONObject, sc: JSONObject, lastEvent: Long) {
        val probe = sc.optJSONObject("probe")
        val minWait = lastEvent + argL("minIdleMs", 300) + rec.optLong("settle", 0) - SystemClock.uptimeMillis()
        if (minWait > 0) SystemClock.sleep(minWait)
        val start = SystemClock.uptimeMillis()
        var state = fieldState(probe)
        var since = SystemClock.uptimeMillis()
        var same = 1
        var idle = false
        while (SystemClock.uptimeMillis() - start < argL("idleTimeoutMs", 5000)) {
            SystemClock.sleep(100)
            val s = fieldState(probe)
            if (key(s) == key(state)) {
                same++
                if (same >= 3 && SystemClock.uptimeMillis() - since >= argL("stableSpanMs", 450)) { idle = true; break }
            } else { state = s; same = 1; since = SystemClock.uptimeMillis() }
        }
        for (k in state.keys()) rec.put(k, state.get(k))
        rec.put("idle", idle).put("idleMs", since - lastEvent)
        rec.put("ime", imeShown()).put("top", topPackage() ?: JSONObject.NULL)
        rec.put("imePid", pidOf(sc.optString("ime"))).put("appPid", pidOf(sc.optString("app")))
    }

    // ---------------------------------------------------------------- ops

    private class SetupFailed(msg: String) : RuntimeException(msg)

    /** Runs one op; returns the uptime of its last input event (or now). */
    private fun op(o: JSONObject, sc: JSONObject): Long {
        when (o.getString("op")) {
            "shell" -> { shell(o.getString("cmd")) }
            "sleep", "wait" -> SystemClock.sleep(o.optLong("ms", 500))
            "click" -> {
                val n = waitFind(o.getJSONObject("sel"), o.optLong("timeout", 4000))
                if (n == null) { if (!o.optBoolean("optional")) throw SetupFailed("not found: " + o.getJSONObject("sel")) }
                else { val (x, y) = centre(n); tap(x, y); SystemClock.sleep(o.optLong("after", 600)) }
            }
            "dismiss" -> {
                val texts = o.getJSONArray("texts")
                var rounds = 0
                while (rounds < o.optInt("max", 6)) {
                    var hit = false
                    for (i in 0 until texts.length()) {
                        val t = texts.getString(i)
                        val n = find(JSONObject().put("text", t)) ?: continue
                        val (x, y) = centre(n); tap(x, y); hit = true
                        SystemClock.sleep(o.optLong("after", 1200))
                        break
                    }
                    if (!hit) break
                    rounds++
                }
            }
            "resume" -> {
                // back to the app's task the way the launcher does it (an existing task comes to the front as it was)
                shell("monkey -p " + o.getString("pkg") + " -c android.intent.category.LAUNCHER 1")
                SystemClock.sleep(o.optLong("after", 1500))
            }
            "refocus" -> {
                if (!(o.optBoolean("ifHidden") && imeShown())) {
                    val sel = sc.getJSONObject("sel")
                    val n = waitFind(sel, 5000) ?: throw SetupFailed("field not found again: $sel")
                    val (x, y) = centre(n)
                    tap(x, y)
                    val end = SystemClock.uptimeMillis() + 5000
                    while (!imeShown() && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
                    SystemClock.sleep(500)
                }
            }
            "focus" -> {
                val sel = o.getJSONObject("sel")
                val n = waitFind(sel, o.optLong("timeout", 8000)) ?: throw SetupFailed("field not found: $sel")
                val (x, y) = centre(n)
                tap(x, y)
                val end = SystemClock.uptimeMillis() + o.optLong("imeTimeout", 6000)
                while (!(focused() != null && imeShown())) {
                    if (SystemClock.uptimeMillis() > end) {
                        if (o.optBoolean("noIme")) break
                        throw SetupFailed("IME not shown after focusing $sel (focused=${focused()?.className})")
                    }
                    SystemClock.sleep(200)
                }
                // a web page's field can take a moment to be reported as the input-focused node (not the WebView)
                val edEnd = SystemClock.uptimeMillis() + 2000
                while (focused()?.isEditable != true && SystemClock.uptimeMillis() < edEnd) SystemClock.sleep(200)
                SystemClock.sleep(o.optLong("after", 700))
            }
            "gesture" -> return inject(o.getJSONArray("strokes"))
            "setsel" -> {
                val n = focused() ?: throw SetupFailed("no focused field for setsel")
                val b = Bundle()
                b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, o.getInt("start"))
                b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, o.optInt("end", o.getInt("start")))
                n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, b)
            }
            "settext" -> {
                // The app sets the text. Retried while the field does not show it yet: right after focusing, a web
                // page's accessibility tree can still report the WebView itself as the input-focused node.
                val want = o.getString("text")
                for (attempt in 0 until 4) {
                    val n = focused()
                    if (n != null && n.isEditable) {
                        val b = Bundle()
                        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, want)
                        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                    }
                    SystemClock.sleep(250)
                    val now = focused()
                    val shown = if (now != null && runCatching { now.isShowingHintText }.getOrDefault(false)) "" else now?.text?.toString()
                    if (shown == want || (want.isEmpty() && shown.isNullOrEmpty())) break
                    SystemClock.sleep(400)
                }
            }
            "clip" -> setClip(o.getString("text"))
            "paste" -> {
                // The app's own paste (context menu). Retried once when nothing arrived: the first read of a freshly
                // set clip can be refused while the clipboard service still sees the app as unfocused.
                setClip(o.getString("text"))
                SystemClock.sleep(400)
                for (attempt in 0 until 2) {
                    val n = focused() ?: throw SetupFailed("no focused field for paste")
                    val before = n.text?.toString()
                    n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    SystemClock.sleep(800)
                    if (focused()?.text?.toString() != before) break
                }
            }
            "key" -> shell("input keyevent " + o.getString("code"))
            "back" -> shell("input keyevent KEYCODE_BACK")
            // hide the keyboard (Back) only while it is shown: Back would otherwise leave the screen
            "hide" -> if (imeShown()) { shell("input keyevent KEYCODE_BACK"); SystemClock.sleep(400) }
            "rotate" -> {
                shell("settings put system accelerometer_rotation 0")
                shell("settings put system user_rotation " + o.getInt("rot"))
                SystemClock.sleep(o.optLong("after", 1800))
            }
            else -> error("unknown op " + o.getString("op"))
        }
        return SystemClock.uptimeMillis()
    }

    private fun argL(k: String, d: Long) = args.getString(k)?.toLong() ?: d

    private fun run(): JSONObject {
        val path = args.getString("scenarios") ?: error("missing -e scenarios")
        val scenarios = shell("cat $path").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { JSONObject(it) }.toList()
        val outDir = File(context.filesDir, args.getString("out", "appcompat")).apply { mkdirs() }
        val obs = File(outDir, "obs.jsonl")
        if (args.getString("append") != "1") obs.delete()
        var steps = 0
        var failedSetups = 0
        obs.appendText("") // create
        java.io.FileWriter(obs, true).buffered().use { w ->
            fun emit(r: JSONObject) { w.write(r.toString()); w.write("\n"); w.flush() }
            for (sc in scenarios) {
                val id = sc.getString("id")
                Log.i(TAG, "scenario $id")
                val t0 = SystemClock.uptimeMillis()
                try {
                    val setup = sc.optJSONArray("setup") ?: JSONArray()
                    for (i in 0 until setup.length()) op(setup.getJSONObject(i), sc)
                    val r0 = JSONObject().put("sc", id).put("step", -1).put("op", "setup").put("setupMs", SystemClock.uptimeMillis() - t0)
                    observe(r0, sc, SystemClock.uptimeMillis())
                    emit(r0)
                } catch (e: SetupFailed) {
                    failedSetups++
                    val r = JSONObject().put("sc", id).put("step", -1).put("op", "setup").put("setupFailed", e.message)
                    observe(r, sc, SystemClock.uptimeMillis())
                    emit(r)
                    shell("settings put system user_rotation 0")
                    continue
                }
                val st = sc.getJSONArray("steps")
                for (i in 0 until st.length()) {
                    val s = st.getJSONObject(i)
                    val rec = JSONObject().put("sc", id).put("step", i).put("op", s.getString("op"))
                    s.optString("label").takeIf { it.isNotEmpty() }?.let { rec.put("label", it) }
                    if (s.has("settle")) rec.put("settle", s.getLong("settle"))
                    s.optLong("gap", 0).takeIf { it > 0 }?.let { SystemClock.sleep(it) }
                    val last = try { op(s, sc) } catch (e: SetupFailed) { rec.put("opFailed", e.message); SystemClock.uptimeMillis() }
                    observe(rec, sc, last)
                    emit(rec)
                    steps++
                }
                // leave the device in portrait for the next scenario
                shell("settings put system user_rotation 0")
            }
        }
        return JSONObject().put("scenarios", scenarios.size).put("steps", steps).put("failedSetups", failedSetups).put("out", outDir.path)
    }

    companion object {
        const val TAG = "PboardAppCompat"
    }
}
