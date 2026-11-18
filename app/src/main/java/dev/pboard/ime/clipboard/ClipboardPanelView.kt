package dev.pboard.ime.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols

interface ClipboardPanelListener {
    fun onClipPaste(text: String)
    fun onClipboardClose()
    fun onClipboardToggle(enabled: Boolean)
}

/**
 * Clipboard panel in place of the keys: header (back, title, history switch), then either the "turn on" card or a
 * two-column grid of recent clips (tap to paste, long-press to pin/unpin, swipe-free delete via the × corner).
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(
    context: Context,
    private val d: Dimens,
    private var colors: KeyboardColors,
    private val history: ClipboardHistory,
    private val panelHeight: Int,
) : View(context) {
    var listener: ClipboardPanelListener? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val headerH = d.barHeight
    private val pad = d.m(31f)
    private val cardH = d.m(190f)
    private val gap = d.m(21f)

    private class Hit(val rect: RectF, val action: () -> Unit, val long: (() -> Unit)? = null)
    private val hits = ArrayList<Hit>()
    private var pressed: Hit? = null
    private val handler = Handler(Looper.getMainLooper())
    private var longFired = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors
        hits.clear()
        fill.color = c.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val cy = d.barCenterY
        // Back button
        val backCx = d.m(70.5f)
        val back = RectF(backCx - d.barButtonDiameter / 2f, cy - d.barButtonDiameter / 2f, backCx + d.barButtonDiameter / 2f, cy + d.barButtonDiameter / 2f)
        fill.color = if (pressed?.rect == back) c.barButtonPressed else c.barButton
        canvas.drawCircle(backCx, cy, d.barButtonDiameter / 2f, fill)
        fill.color = c.barIcon
        Icons.draw(canvas, Symbols.ARROW_BACK, d.barIconBox, backCx, cy, fill)
        hits += Hit(back, { listener?.onClipboardClose() })
        // Title
        text.typeface = Typeface.DEFAULT
        text.textSize = d.m(57f)
        text.color = c.barIcon
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("Clipboard", d.m(146f), cy + d.m(20f), text)
        // History switch (M3 style)
        val on = history.enabled
        val sw = RectF(width - d.m(170f), cy - d.m(42f), width - d.m(34f), cy + d.m(42f))
        fill.color = if (on) c.primary else c.functionalKey
        canvas.drawRoundRect(sw, sw.height() / 2f, sw.height() / 2f, fill)
        fill.color = if (on) c.popupSelectedLabel else c.hintLabel
        val knobR = if (on) d.m(31f) else d.m(21f)
        canvas.drawCircle(if (on) sw.right - sw.height() / 2f else sw.left + sw.height() / 2f, cy, knobR, fill)
        hits += Hit(RectF(sw.left - d.m(20f), 0f, width.toFloat(), headerH), { listener?.onClipboardToggle(!on) })

        val top = headerH + d.m(16f)
        if (!on) {
            drawCard(canvas, c, top, "Copy more for faster pasting",
                "Turn on clipboard history to keep copied text for an hour. It stays on this device and is never saved to storage.",
                "Turn on Clipboard") { listener?.onClipboardToggle(true) }
            return
        }
        val clips = history.clips()
        if (clips.isEmpty()) {
            text.textSize = d.m(42f)
            text.color = c.hintLabel
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("Copied text will appear here", width / 2f, top + d.m(150f), text)
            return
        }
        val colW = (width - 2 * pad - gap) / 2f
        for ((i, clip) in clips.withIndex()) {
            val col = i % 2
            val row = i / 2
            val l = pad + col * (colW + gap)
            val t = top + row * (cardH + gap)
            if (t > height) break
            val r = RectF(l, t, l + colW, t + cardH)
            fill.color = if (pressed?.rect == r) c.keyPressed else c.key
            canvas.drawRoundRect(r, d.keyRadius, d.keyRadius, fill)
            if (clip.pinned) {
                fill.color = c.hintLabel
                Icons.draw(canvas, Symbols.PIN_FILLED, d.m(42f), r.right - d.m(30f), r.top + d.m(30f), fill)
            }
            text.textSize = d.m(39f)
            text.color = c.keyLabel
            text.textAlign = Paint.Align.LEFT
            val layout = StaticLayout.Builder.obtain(clip.text, 0, clip.text.length, text, (colW - d.m(70f)).toInt())
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save()
            canvas.translate(r.left + d.m(26f), r.top + d.m(24f))
            canvas.clipRect(0f, 0f, colW - d.m(52f), cardH - d.m(40f))
            layout.draw(canvas)
            canvas.restore()
            hits += Hit(r, { listener?.onClipPaste(clip.text) }, { history.togglePin(clip); invalidate() })
        }
    }

    private fun drawCard(canvas: Canvas, c: KeyboardColors, top: Float, title: String, body: String, button: String, action: () -> Unit) {
        val r = RectF(pad, top, width - pad, top + d.m(420f))
        fill.color = c.key
        canvas.drawRoundRect(r, d.m(42f), d.m(42f), fill)
        text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        text.textSize = d.m(47f)
        text.color = c.keyLabel
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(title, r.left + d.m(48f), r.top + d.m(90f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = d.m(37f)
        text.color = c.hintLabel
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, text, (r.width() - d.m(96f)).toInt()).build()
        canvas.save()
        canvas.translate(r.left + d.m(48f), r.top + d.m(120f))
        layout.draw(canvas)
        canvas.restore()
        text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        text.textSize = d.m(37f)
        val bw = text.measureText(button) + d.m(96f)
        val b = RectF(r.right - d.m(48f) - bw, r.bottom - d.m(140f), r.right - d.m(48f), r.bottom - d.m(40f))
        fill.color = if (pressed?.rect == b) c.functionalKeyPressed else c.functionalKey
        canvas.drawRoundRect(b, b.height() / 2f, b.height() / 2f, fill)
        text.color = c.functionalIcon
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(button, b.centerX(), b.centerY() + d.m(13f), text)
        hits += Hit(b, action)
    }

    fun setColors(c: KeyboardColors) { colors = c; invalidate() }

    private val longPress = Runnable {
        pressed?.long?.let {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            it()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.lastOrNull { it.rect.contains(e.x, e.y) }
                longFired = false
                if (pressed != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handler.postDelayed(longPress, 500)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val p = pressed
                pressed = null
                if (p != null && !longFired && p.rect.contains(e.x, e.y)) p.action()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); pressed = null; invalidate() }
        }
        return true
    }
}
