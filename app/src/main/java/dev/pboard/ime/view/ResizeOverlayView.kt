package dev.pboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme
import kotlin.math.abs
import kotlin.math.hypot

interface ResizeListener {
    /** The top edge was dragged by [dy] px (negative = taller); called on release. */
    fun onResizeHeight(dy: Float)
    /** The bottom edge was dragged by [dy] px (negative = lifted); called on release. */
    fun onResizeBottom(dy: Float)
    fun onResizeReset()
    fun onResizeDone()
}

/**
 * Gboard's resize overlay (toolbar "Resize"), laid over the keyboard: a light veil, a 2 dp primary outline with 32 dp
 * corners, grab bars at the top and bottom edges, and Reset / move / Done controls in the middle. Measured on
 * Gboard 15.1 at 420 dpi, unscaled: outline 5.25 px, grab bars 105x8 px 42 px inside the edges, 105 px controls.
 */
@SuppressLint("ViewConstructor")
class ResizeOverlayView(context: Context, private var colors: KeyboardColors) : View(context) {
    var listener: ResizeListener? = null

    private val px = context.resources.displayMetrics.density / Dimens.REF_DENSITY
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5.25f * px }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = 47.25f * px
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }

    /** Live drag offsets of the top and bottom edges (px). */
    private var topDy = 0f
    private var bottomDy = 0f
    private var dragging = 0
    private var downY = 0f
    private var pressed = 0

    private val outline = RectF()
    private val reset = RectF()
    private val done = RectF()

    private fun layoutControls() {
        val h = 105f * px
        val cy = (outline.top + outline.bottom) / 2f
        val cx = width / 2f
        reset.set(cx - 52.5f * px - 43f * px - 267f * px, cy - h / 2f, cx - 52.5f * px - 43f * px, cy + h / 2f)
        done.set(cx + 52.5f * px + 42f * px, cy - h / 2f, cx + 52.5f * px + 42f * px + 260f * px, cy + h / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors
        val inset = stroke.strokeWidth / 2f
        outline.set(inset, inset + topDy, width - inset, height - inset + bottomDy)
        layoutControls()
        val r = 84f * px
        fill.color = KeyboardTheme.withAlpha(c.key, 0.65f)
        canvas.drawRoundRect(outline, r, r, fill)
        stroke.color = KeyboardTheme.withAlpha(c.primary, 0.8f)
        canvas.drawRoundRect(outline, r, r, stroke)
        // Grab bars
        fill.color = stroke.color
        val bw = 52.5f * px
        val bh = 4f * px
        val cx = width / 2f
        canvas.drawRoundRect(cx - bw, outline.top + 42f * px - bh, cx + bw, outline.top + 42f * px + bh, bh, bh, fill)
        canvas.drawRoundRect(cx - bw, outline.bottom - 42f * px - bh, cx + bw, outline.bottom - 42f * px + bh, bh, bh, fill)
        // Reset, move, Done
        for ((rect, label, icon, id) in listOf(
            Quad(reset, "Reset", Symbols.UNDO, PRESS_RESET), Quad(done, "Done", Symbols.CHECK, PRESS_DONE),
        )) {
            fill.color = if (pressed == id) c.functionalKeyPressed else c.functionalKey
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
            text.color = c.functionalIcon
            val tw = text.measureText(label)
            val iconW = 50f * px
            val x0 = rect.centerX() - (iconW + 16f * px + tw) / 2f
            fill.color = c.functionalIcon
            Icons.draw(canvas, icon, iconW, x0 + iconW / 2f, rect.centerY(), fill)
            canvas.drawText(label, x0 + iconW + 16f * px, rect.centerY() + text.textSize * 0.355f, text)
        }
        fill.color = c.keyPressed
        val mcy = reset.centerY()
        canvas.drawCircle(cx, mcy, 52.5f * px, fill)
        fill.color = c.keyLabel
        Icons.draw(canvas, Symbols.ZOOM_OUT_MAP, 50f * px, cx, mcy, fill)
    }

    private data class Quad(val rect: RectF, val label: String, val icon: String, val id: Int)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                val grab = 70f * px
                dragging = when {
                    abs(e.y - (outline.top + 42f * px)) < grab -> 1
                    abs(e.y - (outline.bottom - 42f * px)) < grab -> 2
                    else -> 0
                }
                pressed = when {
                    reset.contains(e.x, e.y) -> PRESS_RESET
                    done.contains(e.x, e.y) -> PRESS_DONE
                    else -> 0
                }
                if (dragging != 0 || pressed != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                when (dragging) {
                    1 -> topDy = e.y - downY
                    2 -> bottomDy = e.y - downY
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val l = listener
                when {
                    dragging == 1 && abs(topDy) > 2f -> l?.onResizeHeight(topDy)
                    dragging == 2 && abs(bottomDy) > 2f -> l?.onResizeBottom(bottomDy)
                    pressed == PRESS_RESET && reset.contains(e.x, e.y) -> l?.onResizeReset()
                    pressed == PRESS_DONE && done.contains(e.x, e.y) -> l?.onResizeDone()
                }
                topDy = 0f; bottomDy = 0f; dragging = 0; pressed = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { topDy = 0f; bottomDy = 0f; dragging = 0; pressed = 0; invalidate() }
        }
        return true
    }

    companion object {
        private const val PRESS_RESET = 1
        private const val PRESS_DONE = 2
    }
}
