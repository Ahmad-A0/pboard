package dev.pboard.ime.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols

interface TranslateBarListener {
    fun onTranslateClose()
    fun onPickSource()
    fun onPickTarget()
    fun onSwap()
    /** The status pill (e.g. "no language pack") was tapped. */
    fun onStatusTap()
}

/**
 * The translate band that sits above the toolbar/suggestion strip while Translate is open (Gboard 18.6 layout,
 * measured at 420 dpi; all reference px below are at that density, see docs/translate.md "UI"):
 *
 *     portrait (270 px):  ( ← )      [ Source ] ⇄ [ Target ]        chips 95 px tall, 32 px from the top
 *                         ( Type here to translate               )  box 95 px tall, 15 px side margins
 *     landscape (137 px): ( ← ) ( box ................ ) [ Source ] ⇄ [ Target ]
 *
 * The box shows the source text the keyboard types into [TranslateInputConnection] (with a blinking caret), or a
 * status pill in its place when no model can be used.
 */
@SuppressLint("ViewConstructor")
class TranslateBarView(context: Context, private val d: Dimens, private var colors: TranslateColors) : View(context) {
    var listener: TranslateBarListener? = null

    /** Chip labels (language names). */
    var sourceLabel = ""; set(v) { field = v; layoutChips(); invalidate() }
    var targetLabel = ""; set(v) { field = v; layoutChips(); invalidate() }

    /** Text in the box and the caret position in it. */
    private var text = ""
    private var caret = 0

    /** Shown instead of the box when set (no pack, loading failed). */
    var status: String? = null; set(v) { field = v; invalidate() }

    fun setColors(c: TranslateColors) { colors = c; invalidate() }

    fun setText(t: String, caretPos: Int) {
        text = t
        caret = caretPos.coerceIn(0, t.length)
        caretShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private val landscape = d.landscape
    private val viewH = d.m(if (landscape) 137f else 270f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d.m(3f) }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = d.m(36.75f) // 14 sp
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 500, false)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = d.m(36.75f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // Geometry (px at the reference density, scaled by d.m)
    private val chipH = d.m(95f)
    private val chipPad = d.m(42f)
    private val gap = d.m(11f)
    private val swapW = d.m(105f)
    private val backD = d.m(89f)
    private val boxH = d.m(95f)
    private val boxInset = d.m(15f)
    private val textPad = d.m(42f)

    private val back = RectF()
    private val source = RectF()
    private val swap = RectF()
    private val target = RectF()
    private val box = RectF()
    private var sourceText = ""
    private var targetText = ""

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), viewH.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutChips()

    private fun layoutChips() {
        val w = width.toFloat()
        if (w <= 0f) return
        val rowCy = if (landscape) viewH / 2f else d.m(79.5f)
        // back button: in the 115 px (portrait) / 142 px (landscape) slot at the left
        val backCx = if (landscape) d.m(71f) else d.m(59.5f)
        back.set(backCx - backD / 2f, rowCy - backD / 2f, backCx + backD / 2f, rowCy + backD / 2f)
        val maxChip = (w - d.m(if (landscape) 1300f else 340f)).coerceAtLeast(d.m(160f)) / 2f
        sourceText = ellipsize(sourceLabel, maxChip - 2 * chipPad)
        targetText = ellipsize(targetLabel, maxChip - 2 * chipPad)
        val sw = chipPaint.measureText(sourceText) + 2 * chipPad
        val tw = chipPaint.measureText(targetText) + 2 * chipPad
        val group = sw + gap + swapW + gap + tw
        val left = if (landscape) {
            w - d.m(11f) - group
        } else {
            // centred ~5 px right of the middle, kept 55 px inside the 115..(w-15) row container
            val lo = d.m(170f)
            val hi = w - d.m(70f)
            (w / 2f + d.m(5f) - group / 2f).coerceIn(lo, (hi - group).coerceAtLeast(lo))
        }
        val top = rowCy - chipH / 2f
        source.set(left, top, left + sw, top + chipH)
        swap.set(source.right + gap, top, source.right + gap + swapW, top + chipH)
        target.set(swap.right + gap, top, swap.right + gap + tw, top + chipH)
        if (landscape) box.set(d.m(142f), rowCy - boxH / 2f, source.left - d.m(42f), rowCy + boxH / 2f)
        else box.set(boxInset, d.m(159f), w - boxInset, d.m(159f) + boxH)
    }

    private fun ellipsize(s: String, avail: Float): String =
        TextUtils.ellipsize(s, android.text.TextPaint(chipPaint), avail.coerceAtLeast(d.m(60f)), TextUtils.TruncateAt.END).toString()

    private var caretShownAt = 0L

    override fun onDraw(canvas: Canvas) {
        val c = colors
        // back button
        fill.color = if (pressed == BACK) c.backButtonPressed else c.backButton
        canvas.drawCircle(back.centerX(), back.centerY(), backD / 2f, fill)
        fill.color = c.icon
        Icons.draw(canvas, Symbols.ARROW_BACK, d.m(63f), back.centerX(), back.centerY(), fill)
        // chips and swap
        drawChip(canvas, source, sourceText, pressed == SOURCE)
        drawChip(canvas, target, targetText, pressed == TARGET)
        if (pressed == SWAP) {
            fill.color = c.pressed
            canvas.drawRoundRect(swap, chipH / 2f, chipH / 2f, fill)
        }
        fill.color = c.swapIcon
        Icons.draw(canvas, TranslateSymbols.SWAP, d.m(53f), swap.centerX(), swap.centerY(), fill)
        // input box, or the status pill in its place
        val r = box.height() / 2f
        val st = status
        if (st != null) {
            fill.color = if (pressed == BOX) c.pressed else c.statusFill
            canvas.drawRoundRect(box, r, r, fill)
            textPaint.color = c.hint
            textPaint.textAlign = Paint.Align.CENTER
            val label = TextUtils.ellipsize(st, android.text.TextPaint(textPaint), box.width() - 2 * textPad, TextUtils.TruncateAt.END)
            canvas.drawText(label.toString(), box.centerX(), baseline(box), textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }
        fill.color = c.boxFill
        canvas.drawRoundRect(box, r, r, fill)
        stroke.color = c.boxOutline
        val h = stroke.strokeWidth / 2f
        canvas.drawRoundRect(box.left + h, box.top + h, box.right - h, box.bottom - h, r - h, r - h, stroke)
        val tx = box.left + textPad
        val avail = box.width() - 2 * textPad
        val y = baseline(box)
        canvas.save()
        canvas.clipRect(box.left + d.m(20f), box.top, box.right - d.m(20f), box.bottom)
        if (text.isEmpty()) {
            textPaint.color = c.hint
            canvas.drawText(context.getString(dev.pboard.R.string.translate_hint), tx, y, textPaint)
            drawCaret(canvas, tx, c)
        } else {
            // single line: keep the caret visible by scrolling the text left when it is too long
            val caretX = textPaint.measureText(text, 0, caret)
            val shift = (caretX - avail).coerceAtLeast(0f)
            textPaint.color = c.text
            canvas.drawText(text, tx - shift, y, textPaint)
            drawCaret(canvas, tx - shift + caretX, c)
        }
        canvas.restore()
    }

    private fun baseline(r: RectF): Float {
        val fm = textPaint.fontMetrics
        return r.centerY() - (fm.ascent + fm.descent) / 2f
    }

    private fun drawCaret(canvas: Canvas, x: Float, c: TranslateColors) {
        val now = SystemClock.uptimeMillis()
        if (((now - caretShownAt) / 500) % 2 == 0L) {
            fill.color = c.boxOutline
            canvas.drawRect(x, box.centerY() - d.m(27.5f), x + d.m(5.25f), box.centerY() + d.m(27.5f), fill)
        }
        postInvalidateDelayed(500 - (now - caretShownAt) % 500)
    }

    private fun drawChip(canvas: Canvas, r: RectF, label: String, isPressed: Boolean) {
        fill.color = if (isPressed) colors.chipPressed else colors.chip
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        chipPaint.color = colors.chipText
        chipPaint.textAlign = Paint.Align.CENTER
        val fm = chipPaint.fontMetrics
        canvas.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, chipPaint)
    }

    // ------------------------------------------------------------------ touch

    private var pressed = 0

    private fun hit(x: Float, y: Float): Int {
        val slop = d.m(12f)
        fun RectF.near() = x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
        return when {
            back.near() -> BACK
            source.near() -> SOURCE
            swap.contains(x, y) -> SWAP
            target.near() -> TARGET
            status != null && box.contains(x, y) -> BOX
            else -> 0
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(e.x, e.y)
                if (pressed != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressed != 0 && hit(e.x, e.y) != pressed) { pressed = 0; invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = 0
                invalidate()
                when (p) {
                    BACK -> listener?.onTranslateClose()
                    SOURCE -> listener?.onPickSource()
                    TARGET -> listener?.onPickTarget()
                    SWAP -> listener?.onSwap()
                    BOX -> listener?.onStatusTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = 0; invalidate() }
        }
        return true
    }

    /** Announces a translation result for TalkBack users (the app field changes without focus moving). */
    fun announce(textForA11y: String) {
        if (textForA11y.isNotEmpty()) announceForAccessibility(textForA11y)
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = javaClass.name
    }

    override fun getContentDescription(): CharSequence =
        context.getString(dev.pboard.R.string.translate_bar_description, sourceLabel, targetLabel)

    /** TalkBack: the back button, both chips and swap as separate buttons (Gboard's descriptions). */
    private val nodes = object : android.view.accessibility.AccessibilityNodeProvider() {
        fun targets(): List<Triple<String, RectF, () -> Unit>> = buildList {
            val l = listener
            add(Triple(context.getString(dev.pboard.R.string.translate_hide), back) { l?.onTranslateClose() })
            add(Triple(context.getString(dev.pboard.R.string.translate_from_desc, sourceLabel), source) { l?.onPickSource() })
            add(Triple(context.getString(dev.pboard.R.string.translate_swap), swap) { l?.onSwap() })
            add(Triple(context.getString(dev.pboard.R.string.translate_to_desc, targetLabel), target) { l?.onPickTarget() })
            status?.let { add(Triple(it, box) { l?.onStatusTap() }) }
        }

        override fun createAccessibilityNodeInfo(id: Int): android.view.accessibility.AccessibilityNodeInfo? {
            val t = targets()
            if (id == HOST_VIEW_ID) {
                val info = android.view.accessibility.AccessibilityNodeInfo.obtain(this@TranslateBarView)
                onInitializeAccessibilityNodeInfo(info)
                for (i in t.indices) info.addChild(this@TranslateBarView, i)
                return info
            }
            val (desc, rf, _) = t.getOrNull(id) ?: return null
            val r = android.graphics.Rect().also { rf.roundOut(it) }
            val info = android.view.accessibility.AccessibilityNodeInfo.obtain(this@TranslateBarView, id)
            info.packageName = context.packageName
            info.className = android.widget.Button::class.java.name
            info.contentDescription = desc
            info.setParent(this@TranslateBarView)
            info.setBoundsInParent(r)
            val loc = IntArray(2).also { getLocationOnScreen(it) }
            info.setBoundsInScreen(android.graphics.Rect(r).apply { offset(loc[0], loc[1]) })
            info.isEnabled = true; info.isVisibleToUser = true; info.isClickable = true; info.isFocusable = true
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            return info
        }

        override fun performAction(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (id == HOST_VIEW_ID) return performAccessibilityAction(action, arguments)
            val t = targets().getOrNull(id) ?: return false
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) { t.third(); return true }
            return false
        }
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = nodes

    companion object {
        private const val BACK = 1
        private const val SOURCE = 2
        private const val SWAP = 3
        private const val TARGET = 4
        private const val BOX = 5
    }
}
