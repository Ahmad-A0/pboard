package dev.pboard.ime.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The "Translate from" / "Translate to" picker: a centred dialog over a scrim, drawn inside the IME's full-height
 * window (Gboard 18.6 uses a system dialog of the same size: 342 × 680 dp, 28 dp corners, 32 % black scrim, fixed
 * title, 72 dp rows, the current choice and recent ones on top, a short divider, then every other language). pboard
 * lists only languages it has a model for, and ends with "Get more languages" (opens Settings › Translate).
 */
@SuppressLint("ViewConstructor")
class LanguagePickerView(
    context: Context,
    private val d: Dimens,
    private val colors: TranslateColors,
    private val title: String,
    /** Current choice first, then recents (most recent first). */
    top: List<LanguageRow>,
    /** Every other language, alphabetical. */
    rest: List<LanguageRow>,
    private val onPick: (LanguageRow?) -> Unit,
) : View(context) {
    private val items: List<LanguageRow?> = buildList {
        addAll(top)
        if (top.isNotEmpty() && rest.isNotEmpty()) add(null) // divider
        addAll(rest)
        add(LanguageRow(null, context.getString(dev.pboard.R.string.translate_more_languages)))
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = d.m(63f); typeface = Typeface.create("sans-serif", Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = d.m(52.5f); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val dialog = RectF()
    private val rowH = d.m(189f)
    private val dividerH = d.m(111f)
    private var listTop = 0f
    private var listBottom = 0f
    private var scrollY = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = title
    }

    private fun itemH(i: Int) = if (items[i] == null) dividerH else rowH
    private val contentH get() = items.indices.sumOf { itemH(it).toDouble() }.toFloat()
    private val maxScroll get() = max(0f, contentH - (listBottom - listTop))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val dw = min(d.m(898f), w - d.m(42f))
        // Gboard's list always fills 680 dp (~130 languages); pboard's is short, so the dialog wraps it
        val dh = min(min(d.m(1785f), h - d.m(84f)), d.m(184f + 63f) + contentH)
        dialog.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        listTop = dialog.top + d.m(184f)
        listBottom = dialog.bottom - d.m(63f)
        // start with the selected row visible
        val sel = items.indexOfFirst { it?.selected == true }
        if (sel > 0) scrollY = min(maxScroll, (0 until sel).sumOf { itemH(it).toDouble() }.toFloat() - rowH)
    }

    override fun onDraw(canvas: Canvas) {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
        canvas.drawColor(SCRIM)
        val c = colors
        fill.color = c.dialog
        fill.setShadowLayer(d.m(16f), 0f, d.m(4f), 0x30000000)
        canvas.drawRoundRect(dialog, d.m(73.5f), d.m(73.5f), fill)
        fill.clearShadowLayer()
        titlePaint.color = c.text
        val tfm = titlePaint.fontMetrics
        canvas.drawText(title, dialog.centerX(), dialog.top + d.m(63f + 47.5f) - (tfm.ascent + tfm.descent) / 2f, titlePaint)

        val left = dialog.left + d.m(63f)
        val right = dialog.right - d.m(63f)
        canvas.save()
        canvas.clipRect(dialog.left, listTop, dialog.right, listBottom)
        var y = listTop - scrollY
        val fm = rowPaint.fontMetrics
        for ((i, row) in items.withIndex()) {
            val h = itemH(i)
            if (y + h >= listTop && y <= listBottom) {
                if (row == null) {
                    fill.color = c.divider
                    val cy = y + h / 2f
                    canvas.drawRoundRect(dialog.centerX() - d.m(168f), cy - d.m(2.5f), dialog.centerX() + d.m(168f), cy + d.m(2.5f), d.m(2.5f), d.m(2.5f), fill)
                } else {
                    val r = RectF(left, y, right, y + h)
                    if (row.selected || i == pressedIndex) {
                        fill.color = if (row.selected) c.chip else c.pressed
                        canvas.drawRoundRect(r, h / 2f, h / 2f, fill)
                    }
                    val fg = if (row.selected) c.chipText else c.text
                    fill.color = fg
                    if (row.selected) Icons.draw(canvas, Symbols.CHECK, d.m(63f), r.left + d.m(52.5f + 31.5f), r.centerY(), fill)
                    if (row.code == null) Icons.draw(canvas, TranslateSymbols.DOWNLOAD, d.m(63f), r.left + d.m(52.5f + 31.5f), r.centerY(), fill)
                    rowPaint.color = fg
                    val tx = r.left + d.m(148f)
                    val label = TextUtils.ellipsize(row.label, rowPaint, r.right - tx - d.m(42f), TextUtils.TruncateAt.END)
                    canvas.drawText(label.toString(), tx, r.centerY() - (fm.ascent + fm.descent) / 2f, rowPaint)
                }
            }
            y += h
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ touch

    private var pressedIndex = -1
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var downOutside = false

    private fun indexAt(y: Float): Int {
        if (y < listTop || y > listBottom) return -1
        var top = listTop - scrollY
        for (i in items.indices) {
            val h = itemH(i)
            if (y < top + h) return if (items[i] == null) -1 else i
            top += h
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downY = y; lastY = y; dragging = false
                downOutside = !dialog.contains(x, y)
                pressedIndex = if (downOutside) -1 else indexAt(y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!dragging && !downOutside && abs(y - downY) > touchSlop) { dragging = true; pressedIndex = -1 }
                if (dragging) { scrollY = (scrollY + (lastY - y)).coerceIn(0f, maxScroll); invalidate() }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> velocity?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(0, scrollY.toInt(), 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll.toInt())
                        postInvalidateOnAnimation()
                    }
                    downOutside && !dialog.contains(x, y) -> onPick(null)
                    pressedIndex >= 0 && indexAt(y) == pressedIndex -> {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val row = items[pressedIndex]
                        pressedIndex = -1
                        onPick(row)
                    }
                }
                pressedIndex = -1
                dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedIndex = -1; dragging = false; invalidate() }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { onPick(null); return true }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /** 32 % black (measured over the keyboard background). */
        const val SCRIM = 0x52000000
    }
}
