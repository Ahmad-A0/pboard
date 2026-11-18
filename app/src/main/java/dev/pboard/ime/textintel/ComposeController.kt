package dev.pboard.ime.textintel

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import dev.pboard.engine.textintel.Completion
import dev.pboard.ime.SerialWorker
import java.util.Locale

/**
 * Smart Compose: after every edit, completes the phrase being typed from the on-device language model
 * ([dev.pboard.engine.textintel.InlineCompleter]) and shows it as grey text after the cursor ([GhostTextView]),
 * only when the model is confident (see CompleterConfig). Accepting inserts it with a trailing space.
 *
 * Shown only with the cursor at the end of the field's text, in fields that allow suggestions and personalised
 * learning (not passwords, not incognito), for English, and only when the app reports where its cursor is
 * (CursorAnchorInfo). Nothing leaves the device.
 */
class ComposeController(private val context: Context, private val host: Host) {
    interface Host {
        val inputConnection: InputConnection?
        val models: TextIntelModels?
        val ghost: GhostTextView?
        /** Inserts the accepted completion at the cursor (the keyboard finishes its composing word first). */
        fun insertCompletion(text: String)
        /** The window's touchable area changed (the ghost text appeared or went). */
        fun touchableChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = SerialWorker("pboard-compose")
    @Volatile private var generation = 0

    /** Smart Compose is on and this field qualifies (set at each start of input). */
    var active = false
        set(value) { field = value; if (!value) { generation++; hide() } }
    var locale: Locale = Locale.US

    private var shown: Completion? = null
    private var lastAccept = 0L
    private var shownAt = -1
    private var anchor: CursorAnchorInfo? = null

    /**
     * The app moved its cursor or changed text: drop what is shown, then complete what is now before the cursor.
     * Nothing here runs on the input path: the UI thread only bumps a counter and queues the work; reading the field
     * and running the model happen on a background-priority worker, and a request a newer edit has superseded is
     * dropped before it reads or computes anything.
     */
    fun onSelectionChanged(selStart: Int, selEnd: Int) {
        val gen = ++generation
        hide()
        if (!active || selStart != selEnd || selStart < 0) return
        val ic = host.inputConnection ?: return
        val models = host.models ?: return
        val at = selStart
        worker.execute {
            if (gen != generation) return@execute
            lowPriority()
            if (!ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return@execute // only at the end of the text
            val before = ic.getTextBeforeCursor(BEFORE, 0)?.toString() ?: return@execute
            val (ctx, prefix) = split(before) ?: return@execute
            if (gen != generation) return@execute
            var c = runCatching { models.completer.complete(ctx, prefix) }.getOrNull()
            // a sentence starts with a capital ("Thank you"); mid-word, the typed letters keep their case
            if (c != null && prefix.isEmpty() && ctx.isEmpty()) c = c.copy(text = c.text.replaceFirstChar { it.titlecase(locale) })
            if (c != null) main.post { if (gen == generation) show(c, at) }
        }
    }

    private fun lowPriority() = TextIntel.backgroundPriority()

    /** The sentence's words before the word being typed, and that word's letters so far (empty after a space). */
    private fun split(before: String): Pair<List<String>, String>? {
        if (before.isEmpty()) return emptyList<String>() to ""
        val last = before.last()
        // after punctuation other than a sentence end + space nothing is completed ("Hi," "(")
        if (!last.isLetter() && last != ' ' && last != '\'') return null
        if (last == ' ' && before.length >= 2 && before[before.length - 2] == ' ') return null
        val words = TOKEN.findAll(before).map { it.value }.toList()
        val prefix = if (last == ' ') "" else words.lastOrNull().orEmpty()
        val ctx = (if (prefix.isEmpty()) words else words.dropLast(1)).takeLast(12)
        // at a sentence start the LM's context is empty
        val sentence = ctx.takeLastWhile { it.none { ch -> ch in ".!?" } }
        return sentence to prefix
    }

    fun onCursorAnchor(info: CursorAnchorInfo) {
        anchor = info
        shown?.let { draw(it) }
    }

    private fun show(c: Completion, at: Int) {
        // debug only, and never the text: `adb shell setprop log.tag.PboardTextIntel DEBUG` (measurement of how often)
        if (TextIntel.debug) android.util.Log.d(TextIntel.TAG, "compose shown words=${c.words.size}")
        shown = c
        shownAt = at
        draw(c)
    }

    private fun draw(c: Completion) {
        val ghost = host.ghost ?: return
        val a = anchor ?: return
        val x0 = a.insertionMarkerHorizontal
        if (x0.isNaN() || a.selectionStart != shownAt) { ghost.hide(); return }
        val m = Matrix(a.matrix)
        val pts = floatArrayOf(x0, a.insertionMarkerBaseline, x0, a.insertionMarkerTop, x0, a.insertionMarkerBottom)
        m.mapPoints(pts)
        val loc = IntArray(2).also { ghost.getLocationOnScreen(it) }
        var size = DEFAULT_TEXT_SP * context.resources.displayMetrics.scaledDensity
        var color = DEFAULT_COLOR
        var face: Typeface? = null
        var right = ghost.width * RIGHT_EDGE
        if (Build.VERSION.SDK_INT >= 34) {
            a.textAppearanceInfo?.let { t ->
                if (t.textSize > 0) size = t.textSize * scaleY(m)
                color = fade(t.textColor)
                face = Typeface.create(t.systemFontFamilyName?.let { Typeface.create(it, Typeface.NORMAL) }, t.textFontWeight.takeIf { it > 0 } ?: 400, false)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            a.editorBoundsInfo?.editorBounds?.let { r ->
                val e = floatArrayOf(r.right, r.top)
                m.mapPoints(e)
                right = minOf(right, e[0] - loc[0])
            }
        }
        val text = c.text
        ghost.show(text, pts[0] - loc[0], pts[1] - loc[1], pts[3] - loc[1], pts[5] - loc[1], right, size, color, face)
        host.touchableChanged()
    }

    private fun scaleY(m: Matrix): Float {
        val v = FloatArray(9).also { m.getValues(it) }
        return v[Matrix.MSCALE_Y].takeIf { it > 0f } ?: 1f
    }

    private fun fade(c: Int): Int = Color.argb((Color.alpha(c) * GHOST_ALPHA).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** What is shown right now, if anything. */
    val visible: Boolean get() = shown != null && host.ghost?.isShowing == true

    /** Accepts the completion (tap/swipe on it, or a rightward space-bar swipe). Returns false if nothing was shown. */
    fun accept(): Boolean {
        val c = shown ?: return false
        // one gesture accepts one completion (the space-bar drag goes on reporting moves after the first)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAccept < ACCEPT_GAP_MS) return true
        lastAccept = now
        hide()
        host.insertCompletion(c.text)
        return true
    }

    fun hide() {
        val had = shown != null
        shown = null
        host.ghost?.hide()
        if (had) host.touchableChanged()
    }

    fun shutdown() {
        hide()
        worker.shutdown()
    }

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{M}'’]+|[.!?…]+")
        private const val BEFORE = 200
        /** Gboard's default text size for apps it knows nothing about. */
        private const val DEFAULT_TEXT_SP = 16f
        private const val DEFAULT_COLOR = 0x80757575.toInt()
        private const val GHOST_ALPHA = 0.45f
        /** Gboard clips its overlay at 0.85-0.98 of the screen width depending on the app; 0.9 for others. */
        private const val RIGHT_EDGE = 0.9f
        private const val ACCEPT_GAP_MS = 600L
    }
}
