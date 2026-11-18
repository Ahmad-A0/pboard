package dev.pboard.ime.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols
import kotlin.math.abs
import kotlin.math.max

interface EmojiSearchListener {
    fun onSearchEmoji(emoji: String)
    fun onSearchClose()
    fun onSearchClear()
}

/**
 * Emoji search, shown above the toolbar and letters while searching (Gboard 15.1 layout, measured at 420 dpi):
 * a header with a back button and "Search emoji", then a card holding two rows of results that scroll sideways
 * (recently used emoji while the query is empty) above a divider and the search line (magnifier, query with caret,
 * clear button). The query itself is typed with the normal keyboard into [EmojiSearchConnection].
 */
@SuppressLint("ViewConstructor")
class EmojiSearchView(context: Context, private val d: Dimens, private var colors: KeyboardColors) : View(context) {
    var listener: EmojiSearchListener? = null

    private var query = ""
    private var results: List<String> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    // Geometry (reference px at 420 dpi, relative to this view's top = the keyboard's top edge).
    private val viewH = d.m(528f)
    private val headerH = d.m(141f)
    private val backCx = d.m(65.5f)
    private val backCy = d.m(70.5f)
    private val backD = d.m(89f)
    private val cardInset = d.m(21f)
    private val cardTop = headerH
    private val cardBottom = d.m(517f)
    private val cardRadius = d.m(42f)
    private val dividerY = d.m(419f)
    private val rowY = floatArrayOf(d.m(203f), d.m(329f))
    private val colFirst = d.m(68f) // first column centre from the card's left edge
    private val colPitch = d.m(138f)
    private val lineCy = d.m(469.5f)

    private var scrollX = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun setColors(c: KeyboardColors) { colors = c; invalidate() }

    /** New query text and its results (or recents for an empty query). */
    fun show(query: String, results: List<String>) {
        if (query != this.query) { scrollX = 0f; scroller.forceFinished(true) }
        this.query = query
        this.results = results
        caretShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), viewH.toInt())
    }

    private val columns get() = (results.size + 1) / 2
    private val maxScroll get() = max(0f, colFirst * 2 + (columns - 1) * colPitch - (width - 2 * cardInset))

    private var caretShownAt = 0L

    override fun onDraw(canvas: Canvas) {
        val c = colors
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
        // Header
        fill.color = if (pressed == PRESS_BACK) c.barButtonPressed else c.barButton
        canvas.drawCircle(backCx, backCy, backD / 2f, fill)
        fill.color = c.barIcon
        Icons.draw(canvas, Symbols.ARROW_BACK, d.barIconBox, backCx, backCy, fill)
        text.typeface = Typeface.DEFAULT
        text.textSize = d.m(52.5f)
        text.color = c.keyLabel
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("Search emoji", d.m(147f), d.m(85f), text)

        // Card
        val card = RectF(cardInset, cardTop, width - cardInset, cardBottom)
        fill.color = c.key
        canvas.drawRoundRect(card, cardRadius, cardRadius, fill)
        fill.color = c.divider
        canvas.drawRect(card.left, dividerY, card.right, dividerY + d.m(2.6f), fill)

        // Results: column-major, two rows, scrolling sideways.
        canvas.save()
        canvas.clipRect(card.left, card.top, card.right, dividerY)
        emojiPaint.textSize = d.m(73f)
        val fm = emojiPaint.fontMetrics
        val base = -(fm.ascent + fm.descent) / 2f
        for ((i, e) in results.withIndex()) {
            val cx = card.left + colFirst + (i / 2) * colPitch - scrollX
            if (cx < card.left - colPitch || cx > card.right + colPitch) continue
            val cy = rowY[i % 2]
            if (i == pressedIndex) {
                fill.color = c.keyPressed
                canvas.drawCircle(cx, cy, colPitch / 2f - d.m(10f), fill)
            }
            canvas.drawText(e, cx, cy + base, emojiPaint)
        }
        canvas.restore()

        // Search line: magnifier, query (or the hint) with a caret, clear button.
        fill.color = c.hintLabel
        Icons.draw(canvas, Symbols.SEARCH, d.m(63f), d.m(75.5f), lineCy, fill)
        text.textSize = d.m(47.25f)
        val capH = text.textSize * 0.716f
        val tx = d.m(124f)
        val baseY = lineCy + capH / 2f
        if (query.isEmpty()) {
            text.color = c.hintLabel
            canvas.drawText("Search", tx, baseY, text)
        } else {
            text.color = c.keyLabel
            canvas.drawText(query, tx, baseY, text)
        }
        // Caret (blinks at 500 ms like a text field)
        val now = SystemClock.uptimeMillis()
        if (((now - caretShownAt) / 500) % 2 == 0L) {
            val cx = tx + (if (query.isEmpty()) 0f else text.measureText(query)) + d.m(2f)
            fill.color = c.primary
            canvas.drawRect(cx, lineCy - d.m(30f), cx + d.m(5f), lineCy + d.m(30f), fill)
        }
        postInvalidateDelayed(500 - (now - caretShownAt) % 500)
        if (query.isNotEmpty()) {
            val ccx = width - cardInset - d.m(51.5f)
            fill.color = c.hintLabel
            canvas.drawCircle(ccx, lineCy, d.m(19.5f), fill)
            fill.color = c.key
            Icons.draw(canvas, Symbols.CLOSE, d.m(30f), ccx, lineCy, fill)
        }
    }

    // ------------------------------------------------------------------ touch

    private var pressed = 0
    private var pressedIndex = -1
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false

    private fun hitResult(x: Float, y: Float): Int {
        if (y < cardTop || y > dividerY) return -1
        val col = ((x - cardInset - colFirst + scrollX + colPitch / 2f) / colPitch).toInt()
        val row = if (y < (rowY[0] + rowY[1]) / 2f) 0 else 1
        val i = col * 2 + row
        return if (col >= 0 && i < results.size) i else -1
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
                downX = x; lastX = x; dragging = false
                pressed = when {
                    abs(x - backCx) < backD / 2f && abs(y - backCy) < backD / 2f -> PRESS_BACK
                    query.isNotEmpty() && y > dividerY && x > width - cardInset - d.m(110f) -> PRESS_CLEAR
                    else -> 0
                }
                pressedIndex = if (pressed == 0) hitResult(x, y) else -1
                // Results scroll sideways: their feedback waits for a completed tap, so a scroll start never vibrates.
                if (pressed != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!dragging && pressed == 0 && abs(x - downX) > touchSlop && y in cardTop..dividerY) {
                    dragging = true
                    pressedIndex = -1
                }
                if (dragging) {
                    scrollX = (scrollX + (lastX - x)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastX = x
            }
            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> velocity?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(scrollX.toInt(), 0, -it.xVelocity.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                    pressed == PRESS_BACK -> listener?.onSearchClose()
                    pressed == PRESS_CLEAR -> listener?.onSearchClear()
                    pressedIndex >= 0 -> results.getOrNull(pressedIndex)?.let {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onSearchEmoji(it)
                    }
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun reset() {
        pressed = 0
        pressedIndex = -1
        dragging = false
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    companion object {
        private const val PRESS_BACK = 1
        private const val PRESS_CLEAR = 2
    }
}
