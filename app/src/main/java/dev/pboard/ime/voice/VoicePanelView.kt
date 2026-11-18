package dev.pboard.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols
import kotlin.math.hypot

/**
 * Voice typing panel in place of the keys, after Gboard's: a large round microphone button in the middle (filled with
 * the accent colour while listening, a soft ring around it following the input level), the state underneath ("Speak
 * now", "Listening…", "Tap to speak"), a keyboard button bottom-left back to the keys and a repeating delete key
 * bottom-right. The recognised words appear in the text field itself as you speak.
 */
@SuppressLint("ViewConstructor")
class VoicePanelView(
    context: Context,
    private val d: Dimens,
    private val colors: KeyboardColors,
    private val panelHeight: Int,
) : View(context) {
    interface Listener {
        /** The microphone button: stop when listening, listen again when stopped. */
        fun onVoiceToggle()
        fun onVoiceDelete()
        /** Keyboard button: back to the keys. */
        fun onVoiceClose()
    }

    enum class State { LOADING, READY, LISTENING, STOPPED }

    var listener: Listener? = null
    var state = State.LOADING
        set(value) { field = value; if (value != State.LISTENING) level = 0f; invalidate() }
    /** Shown instead of the state line (e.g. an error). */
    var message: String? = null
        set(value) { field = value; invalidate() }
    private var level = 0f
    private var shownLevel = 0f

    fun setLevel(l: Float) {
        level = l
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private var pressed = NONE
    private val handler = Handler(Looper.getMainLooper())
    private val repeater = object : Runnable {
        override fun run() {
            listener?.onVoiceDelete()
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight)
    }

    private val micR get() = d.m(105f)
    private val micCx get() = width / 2f
    private val micCy get() = height * 0.36f
    private val sideR get() = d.barButtonDiameter / 2f * 1.15f
    private val sideCy get() = height - d.m(110f)
    private val kbCx get() = d.m(110f)
    private val delCx get() = width - d.m(110f)

    override fun onDraw(canvas: Canvas) {
        fill.color = colors.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val listening = state == State.LISTENING
        // level ring, eased towards the input level
        shownLevel += (level - shownLevel) * 0.5f
        if (listening) {
            fill.color = colors.primary
            fill.alpha = 56
            canvas.drawCircle(micCx, micCy, micR * (1.08f + 0.45f * shownLevel), fill)
            fill.alpha = 255
            if (kotlin.math.abs(level - shownLevel) > 0.01f) postInvalidateOnAnimation()
        }
        fill.color = when {
            listening -> colors.primary
            pressed == MIC -> colors.keyPressed
            else -> colors.key
        }
        canvas.drawCircle(micCx, micCy, micR, fill)
        if (state == State.LOADING) {
            // "Getting ready": an indeterminate arc circling the button while the model loads (the mic already records)
            val sweep = (android.os.SystemClock.uptimeMillis() % 1200L) / 1200f * 360f
            ring.color = colors.primary
            ring.strokeWidth = d.m(9f)
            val r = micR + d.m(14f)
            canvas.drawArc(micCx - r, micCy - r, micCx + r, micCy + r, sweep - 90f, 100f, false, ring)
            postInvalidateOnAnimation()
        }
        fill.color = if (listening) colors.popupSelectedLabel else colors.primary
        Icons.draw(canvas, Symbols.MIC, d.m(110f), micCx, micCy, fill)
        // state line
        text.textSize = d.m(46f)
        text.color = colors.keyLabel
        val line = message ?: when (state) {
            State.LOADING -> "Getting ready…"
            State.READY -> "Speak now"
            State.LISTENING -> "Listening…"
            State.STOPPED -> "Tap to speak"
        }
        canvas.drawText(line, micCx, micCy + micR + d.m(80f), text)
        // keyboard (left) and delete (right)
        fill.color = if (pressed == KEYBOARD) colors.barButtonPressed else colors.barButton
        canvas.drawCircle(kbCx, sideCy, sideR, fill)
        fill.color = if (pressed == DELETE) colors.functionalKeyPressed else colors.functionalKey
        canvas.drawCircle(delCx, sideCy, sideR, fill)
        fill.color = colors.barIcon
        Icons.draw(canvas, Symbols.KEYBOARD, d.barIconBox, kbCx, sideCy, fill)
        fill.color = colors.functionalIcon
        Icons.draw(canvas, Symbols.BACKSPACE, d.barIconBox, delCx, sideCy, fill)
    }

    private fun hit(x: Float, y: Float): Int = when {
        hypot(x - micCx, y - micCy) <= micR * 1.1f -> MIC
        hypot(x - kbCx, y - sideCy) <= sideR * 1.3f -> KEYBOARD
        hypot(x - delCx, y - sideCy) <= sideR * 1.3f -> DELETE
        else -> NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(e.x, e.y)
                if (pressed != NONE) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (pressed == DELETE) { listener?.onVoiceDelete(); handler.postDelayed(repeater, REPEAT_START_MS) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeater)
                val p = pressed
                pressed = NONE
                invalidate()
                if (p != NONE && hit(e.x, e.y) == p) when (p) {
                    MIC -> listener?.onVoiceToggle()
                    KEYBOARD -> listener?.onVoiceClose()
                }
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeater); pressed = NONE; invalidate() }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeater)
    }

    private companion object {
        const val NONE = 0
        const val MIC = 1
        const val KEYBOARD = 2
        const val DELETE = 3
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
    }
}
