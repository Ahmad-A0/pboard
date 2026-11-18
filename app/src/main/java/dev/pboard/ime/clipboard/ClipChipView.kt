package dev.pboard.ime.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols
import kotlin.math.roundToInt

/**
 * One chip in the strip's chip row (recently copied text, an entity found in it, or "Undo" after a paste), drawn like
 * Gboard 18.6's (measured at 420 dpi, matching its inline autofill chips): as tall as the strip, a pill in the
 * letter-key colour as tall as a toolbar button with 4 dp transparent sides, 11 dp padding, a 24 dp icon, 8 dp gap,
 * 14 sp text in the suggestion colour and 16 dp end padding; the full-clip chip's text stops at 104 dp with an ellipsis,
 * entity chips show their whole text (the row scrolls).
 */
@SuppressLint("ViewConstructor")
class ClipChipView(
    context: Context,
    private val d: Dimens,
    private val c: KeyboardColors,
    private val icon: Icon,
    label: String,
    maxTextDp: Float?,
    description: String,
    private val onTap: () -> Unit,
) : View(context) {
    enum class Icon { CLIPBOARD, PHONE, LINK, MAIL, NUMBERS, LOCATION, UNDO }

    private val dp = context.resources.displayMetrics.density
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * context.resources.displayMetrics.scaledDensity
        color = c.suggestionText
    }
    private val label: String = if (maxTextDp != null) {
        TextUtils.ellipsize(label, text, maxTextDp * dp, TextUtils.TruncateAt.END).toString()
    } else label
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private var pressed = false

    private val side = 4f * dp
    private val padStart = 11f * dp
    private val iconBox = 24f * dp
    private val gap = 8f * dp
    private val padEnd = 16f * dp

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = side + padStart + iconBox + gap + text.measureText(label) + padEnd + side
        setMeasuredDimension(w.roundToInt(), d.barHeight.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = d.barButtonDiameter
        val top = ((height - h) / 2f).roundToInt().toFloat()
        pill.set(side, top, width - side, top + h)
        fill.color = if (pressed) c.keyPressed else c.key
        canvas.drawRoundRect(pill, h / 2f, h / 2f, fill)
        val cx = pill.left + padStart + iconBox / 2f
        val cy = pill.centerY()
        fill.color = c.suggestionText
        drawIcon(canvas, cx, cy)
        canvas.drawText(label, pill.left + padStart + iconBox + gap, cy + text.textSize * 0.36f, text)
    }

    /** Entity icons: the clipboard outline with the entity's glyph small in its lower half (pboard's own composite). */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float) {
        val glyph = when (icon) {
            Icon.CLIPBOARD -> { Icons.draw(canvas, Symbols.CLIPBOARD, iconBox, cx, cy, fill); return }
            Icon.UNDO -> { Icons.draw(canvas, Symbols.UNDO, iconBox, cx, cy, fill); return }
            Icon.PHONE -> Symbols.CALL
            Icon.LINK -> Symbols.LINK
            Icon.MAIL -> Symbols.MAIL
            Icon.NUMBERS -> Symbols.NUMBERS
            Icon.LOCATION -> Symbols.LOCATION
        }
        Icons.draw(canvas, Symbols.CLIPBOARD_EMPTY, iconBox, cx, cy, fill)
        // Knock out a disc behind the glyph so it reads on top of the outline.
        val gx = cx + iconBox * 0.12f
        val gy = cy + iconBox * 0.14f
        val r = iconBox * 0.33f
        val bg = fill.color
        fill.color = if (pressed) c.keyPressed else c.key
        canvas.drawCircle(gx, gy, r, fill)
        fill.color = bg
        Icons.draw(canvas, glyph, iconBox * 0.58f, gx, gy, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val hit = pressed && e.x >= 0 && e.x < width && e.y >= 0 && e.y < height
                pressed = false
                invalidate()
                if (hit) onTap()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
    }
}
