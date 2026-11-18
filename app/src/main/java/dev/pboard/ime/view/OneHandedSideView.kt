package dev.pboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import kotlin.math.hypot

interface OneHandedListener {
    /** Back to the full-width keyboard. */
    fun onOneHandedExit()
    /** Move the keyboard to the other edge. */
    fun onOneHandedSwap()
    fun onOneHandedResize()
}

/** Geometry of one-handed mode, kept free of Android types so it is unit-tested. */
object OneHandedLayout {
    /**
     * The column for the side buttons, as (x, width), given the keyboard's [sideLeft] and [sideRight] margins in a
     * root [width] px wide: the wider margin. The keyboard also keeps a few dp at its own edge, so the narrower margin
     * is never empty and "the non-zero one" picks the wrong side when the keyboard hugs the left edge.
     */
    fun sideColumn(width: Int, sideLeft: Int, sideRight: Int): Pair<Int, Int> =
        if (sideLeft >= sideRight) 0 to sideLeft else (width - sideRight) to sideRight
}

/**
 * The free column beside the one-handed keyboard (Gboard): three 30 dp round buttons — expand back to full width,
 * move the keyboard to the other side (chevron pointing there), and resize.
 */
@SuppressLint("ViewConstructor")
class OneHandedSideView(context: Context, private val d: Dimens, private var colors: KeyboardColors) : View(context) {
    var listener: OneHandedListener? = null
    /** The keyboard hugs the right edge (this column is on the left). */
    var keyboardOnRight = true
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = -1

    // Measured on Gboard 15.1 (420 dpi) at full scale (the keyboard beside it is scaled down, these are not):
    // 95 px buttons, centres 189.5 / 361.5 / 533.5 px below the keyboard's top edge, 24 dp icons.
    private val px = context.resources.displayMetrics.density / Dimens.REF_DENSITY
    private val radius get() = 47.5f * px

    /** Button centres, top to bottom: expand, swap, resize. */
    private fun centerY(i: Int): Float = (189.5f + 172f * i) * px

    override fun onDraw(canvas: Canvas) {
        val c = colors
        val cx = width / 2f
        val symbols = listOf(
            Symbols.ZOOM_OUT_MAP,
            if (keyboardOnRight) Symbols.CHEVRON_LEFT else Symbols.CHEVRON_RIGHT,
            Symbols.RESIZE,
        )
        for (i in 0 until 3) {
            val cy = centerY(i)
            fill.color = if (pressed == i) c.barButtonPressed else c.barButton
            canvas.drawCircle(cx, cy, radius, fill)
            fill.color = c.hintLabel
            Icons.draw(canvas, symbols[i], 63f * px, cx, cy, fill)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = (0 until 3).firstOrNull { hypot(e.x - cx, e.y - centerY(it)) < radius * 1.3f } ?: -1
                if (pressed >= 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1
                invalidate()
                if (p >= 0 && hypot(e.x - cx, e.y - centerY(p)) < radius * 1.5f) when (p) {
                    0 -> listener?.onOneHandedExit()
                    1 -> listener?.onOneHandedSwap()
                    2 -> listener?.onOneHandedResize()
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
