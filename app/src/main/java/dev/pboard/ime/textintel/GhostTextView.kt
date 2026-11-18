package dev.pboard.ime.textintel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Smart Compose's inline completion, drawn the way Gboard 18.6 does it: as an overlay in the keyboard's (full-height)
 * window, right after the app's cursor, in the field's own text size and a faded text colour. The app's text is never
 * touched until the completion is accepted (tap or swipe on it, or swipe right on the space bar).
 * Coordinates are this view's (the IME window's).
 */
@SuppressLint("ViewConstructor")
class GhostTextView(context: Context, private val onAccept: () -> Unit) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var text: String? = null
    private var x = 0f
    private var baseline = 0f
    private var top = 0f
    private var bottom = 0f
    private var right = 0f

    /** The drawn area (for the window's touchable region), or null when nothing is shown. */
    val bounds: Rect? get() {
        val t = text ?: return null
        val w = minOf(paint.measureText(t), right - x)
        return Rect(x.toInt(), top.toInt(), (x + w).toInt() + 1, bottom.toInt() + 1)
    }

    /**
     * Shows [completion] at ([x], [baseline]) (this view's coordinates; [top]/[bottom]: the line), clipped at [right],
     * with the field's [textSize] (px), [color] and [typeface].
     */
    fun show(completion: String, x: Float, baseline: Float, top: Float, bottom: Float, right: Float, textSize: Float, color: Int, typeface: Typeface?) {
        text = completion
        this.x = x; this.baseline = baseline; this.top = top; this.bottom = bottom; this.right = right
        paint.textSize = textSize
        paint.color = color
        paint.typeface = typeface ?: Typeface.DEFAULT
        invalidate()
    }

    fun hide() {
        if (text == null) return
        text = null
        invalidate()
    }

    val isShowing get() = text != null

    override fun onDraw(canvas: Canvas) {
        val t = text ?: return
        canvas.save()
        canvas.clipRect(x, top, right, bottom)
        canvas.drawText(t, x, baseline, paint)
        canvas.restore()
    }

    private var downX = 0f
    private var downIn = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bounds ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downIn = b.contains(e.x.toInt(), e.y.toInt())
                downX = e.x
                return downIn
            }
            MotionEvent.ACTION_UP -> {
                // a tap, or a swipe along the suggestion (Gboard: "Tap or swipe on the inline suggestion to accept")
                if (downIn && (b.contains(e.x.toInt(), e.y.toInt()) || abs(e.x - downX) > b.height())) onAccept()
                downIn = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> downIn = false
        }
        return downIn
    }
}
