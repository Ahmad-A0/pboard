package dev.pboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors

interface TextEditingListener {
    /** Cursor or selection-end move; [select] extends the selection (the Select toggle is on). */
    fun onEditMove(direction: Int, select: Boolean)
    fun onEditSelectAll()
    fun onEditCopy()
    fun onEditCut()
    fun onEditPaste()
    fun onEditDelete()
    /** The Select toggle was switched off: collapse the selection. */
    fun onEditSelectEnd()
    /** Back arrow in the header. */
    fun onEditClose()
}

/**
 * Gboard's text-editing panel (toolbar item "Text editing"), replacing the toolbar and the keys. Measured on Gboard
 * 15.1 at 420 dpi: a header (back button, "Text editing"), then a 4x4 grid of 247.5x140 px keys with 20 px gaps
 * from 126 px down: tall left/right arrows around up / Select / down, start and end of text below them, and a
 * functional-colour column with Select all, Copy, Paste and delete. Arrows and delete repeat while held; keys that
 * cannot act (empty field, nothing selected, empty clipboard) are greyed.
 */
@SuppressLint("ViewConstructor")
class TextEditingPanelView(
    context: Context,
    private val d: Dimens,
    private val colors: KeyboardColors,
    private val panelHeight: Int,
) : View(context) {
    var listener: TextEditingListener? = null
    /** Selection mode: arrows extend the selection. */
    var selecting = false
        private set
    /** The editor has a selection (Copy/Cut enabled). */
    var hasSelection = false
        set(value) { field = value; invalidate() }
    /** The field holds text (moves and Select all enabled). */
    var hasText = true
        set(value) { field = value; invalidate() }
    /** The clipboard holds something to paste. */
    var canPaste = true
        set(value) { field = value; invalidate() }

    private class Button(val id: Int, val col: Float, val row: Float, val cols: Float, val rows: Float,
                         val icon: String? = null, val label: String? = null, val repeat: Boolean = false)

    private val buttons = listOf(
        Button(LEFT, 0f, 0f, 1f, 3f, icon = Symbols.CHEVRON_LEFT, repeat = true),
        Button(UP, 1f, 0f, 1f, 1f, icon = Symbols.ARROW_UP, repeat = true),
        Button(SELECT, 1f, 1f, 1f, 1f, label = "Select"),
        Button(DOWN, 1f, 2f, 1f, 1f, icon = Symbols.ARROW_DOWN_KEY, repeat = true),
        Button(RIGHT, 2f, 0f, 1f, 3f, icon = Symbols.CHEVRON_RIGHT, repeat = true),
        Button(HOME, 0f, 3f, 1.5f, 1f, icon = Symbols.FIRST_PAGE),
        Button(END, 1.5f, 3f, 1.5f, 1f, icon = Symbols.LAST_PAGE),
        Button(SELECT_ALL, 3f, 0f, 1f, 1f, label = "Select all"),
        Button(COPY, 3f, 1f, 1f, 1f, label = "Copy"),
        Button(PASTE, 3f, 2f, 1f, 1f, label = "Paste"),
        Button(DELETE, 3f, 3f, 1f, 1f, icon = Symbols.BACKSPACE, repeat = true),
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private var pressed: Button? = null
    private var pressedBack = false
    private val handler = Handler(Looper.getMainLooper())
    private val repeater = object : Runnable {
        override fun run() {
            pressed?.let { act(it) }
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight)
    }

    private fun rect(b: Button): RectF {
        val gap = d.m(20f)
        val side = d.m(15f)
        val cellW = (width - 2 * side - 3 * gap) / 4f
        val cellH = d.m(140f)
        val top = d.m(126f)
        val x0 = side + b.col * (cellW + gap)
        val x1 = side + (b.col + b.cols) * (cellW + gap) - gap
        val y0 = top + b.row * (cellH + gap)
        val y1 = top + (b.row + b.rows) * (cellH + gap) - gap
        // The two half-width keys of the last row split the first three columns.
        return if (b.cols == 1.5f) {
            val span = 3 * cellW + 2 * gap
            val w = (span - gap) / 2f
            val l = side + if (b.col == 0f) 0f else w + gap
            RectF(l, y0, l + w, y1)
        } else RectF(x0, y0, x1, y1)
    }

    private val backCx get() = d.m(59f)
    private val backCy get() = d.barCenterY

    override fun onDraw(canvas: Canvas) {
        fill.color = colors.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        // Header
        fill.color = if (pressedBack) colors.barButtonPressed else colors.barButton
        canvas.drawCircle(backCx, backCy, d.barButtonDiameter / 2f, fill)
        fill.color = colors.barIcon
        Icons.draw(canvas, Symbols.ARROW_BACK, d.barIconBox, backCx, backCy, fill)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = d.m(57f)
        text.color = colors.keyLabel
        canvas.drawText("Text editing", d.m(137f), backCy + d.m(57f) * 0.358f, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = d.functionTextSize
        for (b in buttons) {
            val r = rect(b)
            val active = b.id == SELECT && selecting
            val functional = b.col >= 3f
            fill.color = when {
                active -> colors.primary
                pressed === b -> if (functional) colors.functionalKeyPressed else colors.keyPressed
                functional -> colors.functionalKey
                else -> colors.key
            }
            canvas.drawRoundRect(r, d.keyRadius, d.keyRadius, fill)
            val enabled = when (b.id) {
                COPY -> hasSelection
                PASTE -> canPaste
                DELETE -> true
                else -> hasText
            }
            val ink = when {
                active -> colors.popupSelectedLabel
                !enabled -> colors.barIconDisabled
                functional -> colors.functionalIcon
                else -> colors.keyLabel
            }
            if (b.icon != null) {
                fill.color = ink
                Icons.draw(canvas, b.icon, d.barIconBox, r.centerX(), r.centerY(), fill)
            } else if (b.label != null) {
                text.color = ink
                canvas.drawText(b.label, r.centerX(), r.centerY() + text.textSize * 0.355f, text)
            }
        }
    }

    private fun act(b: Button) {
        val l = listener ?: return
        when (b.id) {
            UP, DOWN, LEFT, RIGHT, HOME, END -> l.onEditMove(b.id, selecting)
            SELECT -> {
                selecting = !selecting
                if (!selecting) l.onEditSelectEnd()
                invalidate()
            }
            SELECT_ALL -> { l.onEditSelectAll(); selecting = true; invalidate() }
            COPY -> if (hasSelection) l.onEditCopy()
            PASTE -> l.onEditPaste()
            DELETE -> l.onEditDelete()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedBack = kotlin.math.hypot(e.x - backCx, e.y - backCy) < d.barButtonDiameter / 2f
                pressed = buttons.firstOrNull { rect(it).contains(e.x, e.y) }
                pressed?.let { b ->
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (b.repeat) { act(b); handler.postDelayed(repeater, REPEAT_START_MS) }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeater)
                if (pressedBack) {
                    pressedBack = false
                    invalidate()
                    if (kotlin.math.hypot(e.x - backCx, e.y - backCy) < d.barButtonDiameter) listener?.onEditClose()
                    return true
                }
                val b = pressed
                pressed = null
                if (b != null && !b.repeat && rect(b).contains(e.x, e.y)) act(b)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeater); pressed = null; invalidate() }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeater)
    }

    companion object {
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 3
        const val RIGHT = 4
        const val HOME = 5
        const val END = 6
        const val SELECT = 7
        const val SELECT_ALL = 8
        const val COPY = 9
        const val PASTE = 10
        const val DELETE = 11
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_MS = 50L
    }
}
