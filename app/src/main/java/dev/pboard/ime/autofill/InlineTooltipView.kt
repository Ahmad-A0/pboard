package dev.pboard.ime.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols

/**
 * Gboard's one-time "Autofill suggestions are now above your keyboard" bubble (measured on 18.6, 420 dpi): a pill
 * 300 dp wide and two 16 sp lines tall (~80 dp) in the tooltip colour, left-aligned with the chip viewport, with a
 * pointer whose tip is ~6.5 dp above the strip, 36 dp in from the bubble's left edge, a close (X) button on the right
 * and a soft shadow. The view is sized to the bubble; [tipX] is the pointer's x inside it.
 */
@SuppressLint("ViewConstructor")
class InlineTooltipView(context: Context, private val d: Dimens, private val c: KeyboardColors) : View(context) {
    var onClose: (() -> Unit)? = null
    private val dp = context.resources.displayMetrics.density
    private val text = "Autofill suggestions are now above your keyboard"
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * context.resources.displayMetrics.scaledDensity
        color = c.tooltipText
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val padLeft = 30f * dp
    private val closeArea = 64f * dp
    val pointerHeight = 10f * dp
    val tipX = 36f * dp
    private val shadow = 4f * dp
    private var layout: StaticLayout? = null

    /** y of the pointer's tip inside the view (0 until measured). */
    val tipY: Float get() = if (measuredHeight > 0) measuredHeight - shadow else 0f

    /** Bubble width for the given room. */
    fun bubbleWidth(maxWidth: Int) = minOf(300f * dp, maxWidth.toFloat())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = bubbleWidth(MeasureSpec.getSize(widthMeasureSpec))
        val l = StaticLayout.Builder.obtain(text, 0, text.length, tp, (w - padLeft - closeArea).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(22f * dp - tp.fontSpacing, 1f).build()
        layout = l
        val body = maxOf(l.height + 2 * 18f * dp, 56f * dp)
        setMeasuredDimension(w.toInt(), (body + pointerHeight + shadow).toInt())
    }

    private val body = RectF()

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        body.set(0f, 0f, width.toFloat(), height - pointerHeight - shadow)
        fill.color = c.tooltip
        fill.setShadowLayer(shadow, 0f, dp, c.shadow)
        path.reset()
        val r = body.height() / 2f
        path.addRoundRect(body, r, r, Path.Direction.CW)
        path.moveTo(tipX - pointerHeight, body.bottom - 1f)
        path.lineTo(tipX, body.bottom + pointerHeight)
        path.lineTo(tipX + pointerHeight, body.bottom - 1f)
        path.close()
        canvas.drawPath(path, fill)
        fill.clearShadowLayer()
        canvas.save()
        canvas.translate(padLeft, (body.height() - l.height) / 2f)
        l.draw(canvas)
        canvas.restore()
        fill.color = c.tooltipText
        Icons.draw(canvas, Symbols.CLOSE, 24f * dp, width - closeArea / 2f, body.centerY(), fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && e.x > width - closeArea) onClose?.invoke()
        return true
    }
}
