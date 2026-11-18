package dev.pboard.ime.hardware

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols
import kotlin.math.hypot

/**
 * The whole IME window while a physical keyboard is in use (Gboard 18.6, measured at 420 dpi on the emulator): it is
 * transparent except for
 *
 * * a 56 dp round button (surface colour, soft shadow, an 18 dp "menu" glyph) 8 dp from the left edge, centred at
 *   52 % of the window height; tapping it toggles
 * * a menu card above it (356 dp wide or the screen minus 16 dp, 52 dp rows: icon, label, shortcut key caps), and
 * * a candidate card above the word being typed: the auto-correction space would apply, 16 sp text in a white card with
 *   a 1 dp outline, 14 dp padding, its text starting about 3 dp left of the word, its bottom 5 dp above the line.
 *
 * The app gets every touch outside these.
 */
@SuppressLint("ViewConstructor")
class HardwareKeyboardView(context: Context, var colors: KeyboardColors) : View(context) {
    enum class Item(val label: String, val shortcut: String) {
        SETTINGS("Settings", "I"),
        CLIPBOARD("Show clipboard", "V"),
        EMOJI("Show emoji keyboard", "."),
        ON_SCREEN("Show on-screen keyboard", "K"),
        VOICE("Voice", "D"),
    }

    interface Listener {
        fun onMenuItem(item: Item)
        fun onLayoutChanged()
    }

    var listener: Listener? = null

    /** Settings › Physical keyboard › Show toolbar: off leaves only the candidate card (the Alt shortcuts still work). */
    var showButton = true
        set(value) { if (field != value) { field = value; if (!value) menuOpen = false; invalidate(); requestLayout() } }

    /** The menu's rows (Voice is left out while voice typing is off). */
    var items: List<Item> = Item.entries
        set(value) { field = value; invalidate() }
    private val dp = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }

    private val buttonR = 28f * dp
    private val buttonCx get() = 8f * dp + buttonR
    /** Measured: centred at 51.75 % of the screen height (1242 of 2400 px). */
    private val buttonCy: Float get() {
        getLocationOnScreen(windowOffset)
        val screenH = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.height()
        return screenH * 0.5175f - windowOffset[1]
    }
    private var menuOpen = false
    private val menuRect = RectF()
    private val rowH = 52f * dp
    private var pressedButton = false
    private var pressedRow = -1

    private var candidate: String? = null
    private var anchor: RectF? = null
    private val cardRect = RectF()
    private var windowOffset = IntArray(2)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadows on the transparent window
        isClickable = true
        contentDescription = "Physical keyboard tools"
    }

    fun dismissMenu() { if (menuOpen) { menuOpen = false; invalidate(); requestLayout() } }

    /** The correction to preview ([text] null hides the card) above [wordOnScreen]. */
    fun setCandidate(text: String?, wordOnScreen: RectF?) {
        candidate = text
        if (wordOnScreen != null) anchor = RectF(wordOnScreen)
        invalidate()
    }

    fun moveCandidate(wordOnScreen: RectF) { anchor = RectF(wordOnScreen); if (candidate != null) invalidate() }

    fun touchableRegion(out: Region) {
        out.setEmpty()
        if (!showButton) return
        out.op(Rect((buttonCx - buttonR).toInt(), (buttonCy - buttonR).toInt(), (buttonCx + buttonR).toInt(), (buttonCy + buttonR).toInt()), Region.Op.UNION)
        if (menuOpen) out.op(Rect(menuRect.left.toInt(), menuRect.top.toInt(), menuRect.right.toInt(), menuRect.bottom.toInt()), Region.Op.UNION)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(windowOffset)
        val c = colors
        val surface = c.extractBackground ?: c.key
        drawCard(canvas, c)
        if (!showButton) return
        // Button
        fill.color = surface
        fill.setShadowLayer(6f * dp, 0f, 2f * dp, c.shadow or 0x30000000)
        canvas.drawCircle(buttonCx, buttonCy, buttonR, fill)
        fill.clearShadowLayer()
        if (pressedButton || menuOpen) {
            fill.color = KeyboardTheme.withAlpha(c.keyLabel, 0.08f)
            canvas.drawCircle(buttonCx, buttonCy, buttonR, fill)
        }
        drawMenuGlyph(canvas, c.keyLabel)
        if (menuOpen) drawMenu(canvas, c, surface)
    }

    /** Hand-drawn "menu" glyph: three 2 dp bars, 18 dp wide, 5 dp apart. */
    private fun drawMenuGlyph(canvas: Canvas, color: Int) {
        fill.color = color
        val w = 9f * dp
        val h = 1f * dp
        for (k in -1..1) {
            val y = buttonCy + k * 6f * dp
            canvas.drawRoundRect(buttonCx - w, y - h, buttonCx + w, y + h, h, h, fill)
        }
    }

    private fun drawMenu(canvas: Canvas, c: KeyboardColors, surface: Int) {
        val w = minOf(356f * dp, width - 16f * dp)
        val h = items.size * rowH + 16f * dp
        val bottom = buttonCy - buttonR - 10f * dp
        menuRect.set(8f * dp, bottom - h, 8f * dp + w, bottom)
        fill.color = surface
        fill.setShadowLayer(8f * dp, 0f, 3f * dp, c.shadow or 0x30000000)
        canvas.drawRoundRect(menuRect, 24f * dp, 24f * dp, fill)
        fill.clearShadowLayer()
        val capFill = KeyboardTheme.blend(surface, c.keyLabel, 0.08f)
        for ((i, item) in items.withIndex()) {
            val top = menuRect.top + 8f * dp + i * rowH
            val cy = top + rowH / 2f
            if (pressedRow == i) {
                fill.color = KeyboardTheme.withAlpha(c.keyLabel, 0.08f)
                canvas.drawRect(menuRect.left, top, menuRect.right, top + rowH, fill)
            }
            fill.color = c.keyLabel
            val sym = when (item) {
                Item.SETTINGS -> Symbols.SETTINGS
                Item.CLIPBOARD -> Symbols.CLIPBOARD
                Item.EMOJI -> Symbols.EMOJI
                Item.ON_SCREEN -> Symbols.KEYBOARD
                Item.VOICE -> Symbols.MIC
            }
            Icons.draw(canvas, sym, 20f * dp, menuRect.left + 26f * dp, cy, fill)
            text.textSize = 14f * sp
            text.color = c.keyLabel
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(item.label, menuRect.left + 52f * dp, cy + text.textSize * 0.35f, text)
            // Key caps: "Alt" and the letter, right-aligned.
            text.textSize = 12f * sp
            text.textAlign = Paint.Align.CENTER
            var right = menuRect.right - 12f * dp
            for (cap in listOf(item.shortcut, "Alt")) {
                val cw = maxOf(text.measureText(cap) + 12f * dp, 22f * dp)
                fill.color = capFill
                canvas.drawRoundRect(right - cw, cy - 11f * dp, right, cy + 11f * dp, 6f * dp, 6f * dp, fill)
                canvas.drawText(cap, right - cw / 2f, cy + text.textSize * 0.35f, text)
                right -= cw + 4f * dp
            }
        }
    }

    private fun drawCard(canvas: Canvas, c: KeyboardColors) {
        val word = candidate ?: return
        val a = anchor ?: return
        text.textSize = 16f * sp
        text.textAlign = Paint.Align.LEFT
        val pad = 14f * dp
        val h = 38f * dp
        val w = text.measureText(word) + 2 * pad
        val left = (a.left - windowOffset[0] - pad - 4f * dp).coerceIn(4f * dp, (width - w - 4f * dp).coerceAtLeast(4f * dp))
        var bottom = a.top - windowOffset[1] - 5f * dp
        if (bottom - h < 0) bottom = a.bottom - windowOffset[1] + 8f * dp + h
        cardRect.set(left, bottom - h, left + w, bottom)
        fill.color = c.key
        fill.setShadowLayer(3f * dp, 0f, 1f * dp, c.shadow or 0x20000000)
        val r = 8f * dp
        canvas.drawRoundRect(cardRect, r, r, fill)
        fill.clearShadowLayer()
        stroke.color = c.divider
        stroke.strokeWidth = dp
        canvas.drawRoundRect(cardRect, r, r, stroke)
        text.color = c.keyLabel
        canvas.drawText(word, cardRect.left + pad, cardRect.centerY() + text.textSize * 0.35f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val onButton = showButton && hypot(e.x - buttonCx, e.y - buttonCy) <= buttonR
        val row = if (menuOpen && menuRect.contains(e.x, e.y)) ((e.y - menuRect.top - 8f * dp) / rowH).toInt() else -1
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!onButton && row !in items.indices) { dismissMenu(); return false }
                pressedButton = onButton
                pressedRow = row
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val wasButton = pressedButton
                val wasRow = pressedRow
                pressedButton = false; pressedRow = -1
                // The touchable region changes with the menu: a layout pass makes the IME recompute its insets.
                if (wasButton && onButton) { menuOpen = !menuOpen; requestLayout() }
                else if (wasRow >= 0 && wasRow == row) items.getOrNull(row)?.let { listener?.onMenuItem(it) }
                invalidate()
                listener?.onLayoutChanged()
            }
            MotionEvent.ACTION_CANCEL -> { pressedButton = false; pressedRow = -1; invalidate() }
        }
        return true
    }
}
