package dev.pboard.ime.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import dev.pboard.ime.config.ToolbarItems
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme

/** One suggestion slot's content. */
data class StripItem(
    val text: String,
    val emphasized: Boolean = false,
    val isEmoji: Boolean = false,
    val restore: Boolean = false,
    /** With [restore]: the "Redo" chip shown after Undo (Gboard 18.6), which deletes the restored text again. */
    val redo: Boolean = false,
    /** Recently copied clip chip: drawn with a clipboard icon. */
    val clip: Boolean = false,
    /** Grammar fix (dev.pboard.ime.textintel): a single tonal pill centred in the strip (Gboard 18.6). */
    val fix: Boolean = false,
)

interface TopBarListener {
    fun onToolbarItem(id: String)
    fun onSuggestion(slot: Int)
    fun onSuggestionLongPress(slot: Int) {}
}

/**
 * The row above the keys. Shows either the toolbar (expand button, configurable items, voice button) or the
 * suggestion strip (three slots, best candidate in the middle), cross-fading between the two like Gboard.
 * Drawn directly (no child views) for speed.
 */
@SuppressLint("ViewConstructor")
class TopBarView(context: Context, val d: Dimens) : View(context) {
    var listener: TopBarListener? = null
    var colors: KeyboardColors? = null
        set(value) { field = value; invalidate() }

    /** Toolbar item ids between the expand button and the mic, in order. */
    var toolbarItems: List<String> = emptyList()
        set(value) { field = value; invalidate() }
    /** Ids drawn disabled (e.g. stickers/GIF in fields without rich content support, voice in email fields). */
    var disabledItems: Set<String> = emptySet()
        set(value) { field = value; invalidate() }
    var showVoice = true
        set(value) { field = value; invalidate() }

    /**
     * Ids whose mode is on (Translate): drawn on a 152 x 84 px pill (reference px at 420 dpi) in [activePill] with the
     * icon in [activeIcon] (Gboard 18.6: secondary / onSecondary).
     */
    var activeItems: Set<String> = emptySet()
        set(value) { field = value; invalidate() }
    var activePill = 0xFF575E71.toInt()
    var activeIcon = 0xFFFFFFFF.toInt()

    /** Slots 0..2 (left, centre, right); slot 3 is set only when the right slot is split for a second emoji. */
    private var strip: Array<StripItem?> = arrayOfNulls(4)
    private var stripVisible = false
    private var fade = 0f // 0 = toolbar, 1 = suggestions
    private var animator: ValueAnimator? = null

    var rtl = false

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = d.suggestionTextSize
    }
    private var boldTypeface: Typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false)
    private var regularTypeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var mediumTypeface: Typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 500, false)

    fun setTypeface(tf: Typeface) {
        regularTypeface = tf
        boldTypeface = Typeface.create(tf, 700, false)
        mediumTypeface = Typeface.create(tf, 500, false)
        invalidate()
    }

    private var pressedSlot = -1
    private var pressedItem: String? = null

    fun setSuggestions(items: List<StripItem?>, animate: Boolean = true) {
        for (i in 0 until 4) strip[i] = items.getOrNull(i)
        // Gboard: two emoji candidates share the right slot, half its width each, with a divider between them.
        if (strip[3] != null && !(strip[2]?.isEmoji == true && strip[3]?.isEmoji == true)) strip[3] = null
        val show = items.any { it != null }
        if (show != stripVisible) {
            stripVisible = show
            animateTo(if (show) 1f else 0f, animate)
        }
        invalidate()
        accessibilityChanged()
    }

    private val a11y = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)

    /** Lets TalkBack re-read the bar's virtual views after the strip or toolbar content changed. */
    private fun accessibilityChanged() {
        if (a11y?.isEnabled == true) sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun showToolbar(animate: Boolean = true) {
        strip.fill(null)
        if (stripVisible) {
            stripVisible = false
            animateTo(0f, animate)
        }
        invalidate()
        accessibilityChanged()
    }

    val isShowingSuggestions get() = stripVisible

    /**
     * Gboard: toolbar -> suggestions swaps instantly; when the suggestions clear, the strip disappears at once and
     * the toolbar icons fade back in over 200 ms after a 100 ms delay.
     */
    private fun animateTo(target: Float, animate: Boolean) {
        animator?.cancel()
        if (!animate || target == 1f) { fade = target; toolbarAlpha = 1f; invalidate(); return }
        fade = 1f
        toolbarAlpha = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = TOOLBAR_FADE_DELAY_MS
            duration = TOOLBAR_FADE_MS
            interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)
            addUpdateListener {
                fade = 0f
                toolbarAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Toolbar opacity while it fades back in (1 when settled). */
    private var toolbarAlpha = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), d.barHeight.toInt())
    }

    // --- geometry ---
    private val cy get() = d.barCenterY
    private val buttonR get() = d.barButtonDiameter / 2f
    /** Gboard 18.6: the features button mirrors the mic (15.1 had it 2 px further left). */
    private val firstCx get() = d.barSideInset + buttonR
    private val lastCx get() = width - d.barSideInset - buttonR

    private fun toolbarCenters(): FloatArray {
        val n = toolbarItems.size + 2
        val step = (lastCx - firstCx) / (n - 1)
        return FloatArray(n) { firstCx + it * step }
    }

    /** Suggestion slot bounds between the expand button and the mic; the middle slot is a little wider. */
    private fun slotRect(i: Int, out: RectF): RectF {
        val left = d.stripLeft
        val right = width - d.stripRightInset
        val side = sideSlotWidth(left, right, d.stripMiddleFraction)
        val mid = right - left - 2 * side
        val split = strip[3] != null
        // Visual order left to right; RTL mirrors it.
        val (a, b) = when (i) {
            0 -> left to left + side
            1 -> left + side to left + side + mid
            2 -> if (split) left + side + mid to right - side / 2f else left + side + mid to right
            else -> right - side / 2f to right
        }
        if (rtl) out.set(left + right - b, 0f, left + right - a, height.toFloat())
        else out.set(a, 0f, b, height.toFloat())
        return out
    }

    private val slotCount get() = if (strip[3] != null) 4 else 3

    private val tmpRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        // Expand button and mic are shared by both modes.
        drawExpand(canvas, c, firstCx)
        if (expanded && !stripVisible && powerKey != null) {
            // Gboard: a soft halo around the power key shows it accepts a drop ("Drag here to replace").
            circlePaint.color = KeyboardTheme.blend(c.background, c.tooltip, if (dragOverPower) 0.9f else 0.28f)
            canvas.drawCircle(lastCx, cy, buttonR + d.m(13f), circlePaint)
        }
        if (showVoice) drawMic(canvas, c, lastCx)
        if (stripVisible) drawStrip(canvas, c, 255) else if (toolbarAlpha > 0f) drawToolbar(canvas, c, (toolbarAlpha * 255).toInt())
    }

    /** The "more" panel is open: the four-squares button becomes a back arrow in a tonal circle. */
    var expanded = false
        set(value) { field = value; invalidate() }

    private fun drawExpand(canvas: Canvas, c: KeyboardColors, cx: Float) {
        if (expanded) {
            circlePaint.color = if (pressedItem == ToolbarItems.EXPAND) c.functionalKeyPressed else c.functionalKey
            canvas.drawCircle(cx, cy, buttonR, circlePaint)
            iconPaint.color = c.functionalIcon
            Icons.draw(canvas, Symbols.ARROW_BACK, d.barIconBox, cx, cy, iconPaint)
            return
        }
        if (pressedItem == ToolbarItems.EXPAND) {
            circlePaint.color = c.barButtonPressed
            canvas.drawCircle(cx, cy, buttonR, circlePaint)
        }
        iconPaint.color = c.barIcon
        Icons.draw(canvas, Symbols.GRID, d.m(65.6f), cx, cy - d.m(0.5f), iconPaint)
    }

    /** The "power key" at the right end of the bar (voice by default), drawn in a filled circle. */
    var powerKey: String? = ToolbarItems.VOICE
        set(value) { field = value; invalidate() }

    private fun drawMic(canvas: Canvas, c: KeyboardColors, cx: Float) {
        val id = powerKey ?: return
        val enabled = id !in disabledItems
        circlePaint.color = if (pressedItem == id) c.barButtonPressed else c.barButton
        if (enabled) canvas.drawCircle(cx, cy, buttonR, circlePaint)
        iconPaint.color = if (enabled) c.barIcon else c.barIconDisabled
        val (sym, box) = iconFor(id)
        // Gboard 18.6: unavailable voice typing shows a crossed-out mic (15.1: the mic greyed out).
        Icons.draw(canvas, if (!enabled && id == ToolbarItems.VOICE) Symbols.MIC_OFF else sym, box, cx, cy, iconPaint)
    }

    // --- customisation (the "more" panel is open) ---

    var dragHost: ToolbarDragHost? = null
    /** Item being dragged (not drawn on the bar), the bar slot it would drop into, and whether it is over the power key. */
    private var dragHidden: String? = null
    private var dragInsert = -1
    private var dragOverPower = false

    fun setDragPreview(hidden: String?, insert: Int, overPower: Boolean) {
        dragHidden = hidden; dragInsert = insert; dragOverPower = overPower
        invalidate()
    }

    /** Bar items as laid out while customising: the dragged one removed, a gap at the insertion slot. */
    private fun barLayout(): List<String?> {
        val items: MutableList<String?> = toolbarItems.filter { it != dragHidden }.toMutableList()
        if (dragInsert >= 0) {
            items.add(dragInsert.coerceIn(0, items.size), null)
            // A full bar shows its last item spilling into the panel while a drop is pending (Gboard).
            while (items.size > barCapacity) items.removeAt(items.indexOfLast { it != null })
        }
        return items
    }

    /** Most items the bar holds (ToolbarItem.BAR_CAPACITY). */
    var barCapacity = 5

    private fun centersFor(n: Int): FloatArray {
        val count = n + 2
        val step = (lastCx - firstCx) / (count - 1)
        return FloatArray(count) { firstCx + it * step }
    }

    /** Insertion index on the bar for a drop at local [x] (gaps between the visible items). */
    fun barInsertIndex(x: Float): Int {
        val visible = toolbarItems.filter { it != dragHidden }
        val slots = minOf(visible.size + 1, barCapacity)
        val c = centersFor(slots)
        var i = 0
        while (i < slots - 1 && c[i + 1] + (c[1] - c[0]) / 2f < x) i++
        return i.coerceIn(0, visible.size)
    }

    fun isOverPowerKey(x: Float, y: Float) = powerKey != null && kotlin.math.hypot(x - lastCx, y - cy) < buttonR * 1.4f

    fun isInBarZone(x: Float) = x > firstCx + buttonR && x < lastCx - buttonR

    /** Centre x of the power key (for the "Drag here to replace" tooltip). */
    val powerKeyCenterX get() = lastCx

    /** Expanded (customising) bar: pinned items as 55x32 dp tiles; the power key highlighted while a drop would replace it. */
    private fun drawCustomizeBar(canvas: Canvas, c: KeyboardColors) {
        val items = barLayout()
        val centers = centersFor(items.size)
        val tw = d.m(144f) / 2f
        val th = d.m(84f) / 2f
        val rr = d.m(31.5f)
        for ((i, id) in items.withIndex()) {
            val cx = centers[i + 1]
            if (id == null) continue // drop slot: the lifted tile hovers over it
            val enabled = id !in disabledItems
            circlePaint.color = when {
                pressedItem == id -> c.keyPressed
                enabled -> c.key
                else -> KeyboardTheme.blend(c.background, c.key, 0.5f)
            }
            canvas.drawRoundRect(cx - tw, cy - th, cx + tw, cy + th, rr, rr, circlePaint)
            iconPaint.color = if (enabled) c.barIcon else c.barIconDisabled
            val (sym, box) = iconFor(id)
            Icons.draw(canvas, sym, box, cx, cy, iconPaint)
        }
    }

    private fun drawToolbar(canvas: Canvas, c: KeyboardColors, alpha: Int) {
        if (expanded) { drawCustomizeBar(canvas, c); return }
        val centers = toolbarCenters()
        for ((i, id) in toolbarItems.withIndex()) {
            val cx = centers[i + 1]
            val active = id in activeItems
            if (active) {
                circlePaint.color = activePill
                circlePaint.alpha = alpha
                canvas.drawRoundRect(cx - d.m(76f), cy - d.m(42f), cx + d.m(76f), cy + d.m(42f), d.m(42f), d.m(42f), circlePaint)
            } else if (pressedItem == id) {
                circlePaint.color = c.barButtonPressed
                circlePaint.alpha = alpha
                canvas.drawCircle(cx, cy, buttonR, circlePaint)
            }
            iconPaint.color = if (active) activeIcon else if (id in disabledItems) c.barIconDisabled else c.barIcon
            iconPaint.alpha = iconPaint.alpha * alpha / 255
            val (sym, box) = iconFor(id)
            Icons.draw(canvas, sym, box, cx, cy, iconPaint)
        }
    }

    private fun iconFor(id: String): Pair<String, Float> = when (id) {
        // Sticker, GIF and clipboard boxes fitted to Gboard 18.6 for the Symbols.kt variants (tools/icons/score.py).
        ToolbarItems.STICKER -> Symbols.STICKER to d.m(58.2f)
        ToolbarItems.GIF -> Symbols.GIF to d.m(96.4f)
        ToolbarItems.CLIPBOARD -> Symbols.CLIPBOARD to d.m(57.2f)
        ToolbarItems.SETTINGS -> Symbols.SETTINGS to d.m(62.7f)
        ToolbarItems.THEME -> Symbols.PALETTE to d.m(62.7f)
        ToolbarItems.EMOJI -> Symbols.EMOJI to d.m(62.7f)
        ToolbarItems.TRANSLATE -> Symbols.TRANSLATE to d.m(62.7f)
        ToolbarItems.VOICE -> Symbols.MIC to d.barIconBox
        ToolbarItems.ONE_HANDED -> Symbols.BACK_HAND to d.m(62.7f)
        ToolbarItems.TEXT_EDITING -> Symbols.TEXT_EDITING to d.m(62.7f)
        ToolbarItems.UNDO -> Symbols.UNDO to d.m(62.7f)
        ToolbarItems.RESIZE -> Symbols.RESIZE to d.m(62.7f)
        ToolbarItems.FLOATING -> Symbols.KEYBOARD to d.m(62.7f)
        "split" -> Symbols.SPLIT to d.m(62.7f)
        "scan_text" -> Symbols.SCAN to d.m(62.7f)
        else -> Symbols.MORE to d.m(62.7f)
    }

    private fun drawStrip(canvas: Canvas, c: KeyboardColors, alpha: Int) {
        val bh = d.dividerHeight
        // The divider is translucent in colour themes (white / black at 10-20 %): scale its own alpha, don't replace it.
        dividerPaint.color = c.divider
        dividerPaint.alpha = (c.divider ushr 24) * alpha / 255
        for (i in 0 until slotCount) {
            val r = slotRect(i, tmpRect)
            // Measured: a divider only separates two filled slots (none around a lone live-glide preview, none
            // next to an empty right slot).
            if (i > 0 && strip[i] != null && strip[i - 1] != null) {
                val x = if (rtl) r.right else r.left
                // Gboard 18.6's dividers are crisp: whole pixels (1 dp -> 3 px, 24 dp tall: 63 px at 420 dpi, 66 px at
                // 440), square ends, on the header's centre line.
                val w = Math.round(d.dividerWidth).toFloat()
                val left = dividerLeft(x, w)
                val top = Math.round(cy - bh / 2f).toFloat()
                canvas.drawRect(left, top, left + w, top + Math.round(bh), dividerPaint)
            }
            val item = strip[i] ?: continue
            if (pressedSlot == i) {
                circlePaint.color = c.barButtonPressed
                circlePaint.alpha = alpha
                val rr = d.barButtonDiameter / 2f
                canvas.drawRoundRect(r.left + d.m(8f), cy - rr, r.right - d.m(8f), cy + rr, rr, rr, circlePaint)
            }
            if (removeArmed == i) {
                iconPaint.color = c.barIcon
                Icons.draw(canvas, Symbols.DELETE, d.barIconBox, r.centerX(), cy, iconPaint)
                continue
            }
            if (item.fix) { drawFixChip(canvas, c, item.text, alpha); continue }
            if (item.restore) {
                drawUndoChip(canvas, c, pressedSlot == i, alpha, item.redo)
                continue
            }
            if (item.clip) {
                // The recently-copied chip (clipboard icon).
                iconPaint.color = c.barIcon
                textPaint.typeface = regularTypeface
                textPaint.textSize = d.suggestionTextSize
                val label = TextUtils.ellipsize(item.text, textPaint, r.width() - d.m(110f), TextUtils.TruncateAt.END).toString()
                val w = textPaint.measureText(label) + d.m(60f)
                val x0 = r.centerX() - w / 2f
                Icons.draw(canvas, Symbols.CLIPBOARD, d.m(50f), x0 + d.m(22f), cy, iconPaint)
                textPaint.color = c.suggestionText
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, x0 + d.m(60f), cy + textPaint.textSize * CAP_HEIGHT / 2f, textPaint)
                textPaint.textAlign = Paint.Align.CENTER
                continue
            }
            textPaint.color = c.suggestionText
            textPaint.alpha = alpha
            textPaint.typeface = if (item.emphasized) boldTypeface else regularTypeface
            textPaint.textSize = if (item.isEmoji) d.suggestionTextSize * 1.15f else d.suggestionTextSize
            val avail = r.width() - d.m(24f)
            val text = TextUtils.ellipsize(item.text, textPaint, avail, TextUtils.TruncateAt.MIDDLE).toString()
            val capH = textPaint.textSize * CAP_HEIGHT
            canvas.drawText(text, r.centerX(), cy + capH / 2f, textPaint)
        }
    }

    /**
     * Gboard 18.6 grammar chip (measured at 420 dpi): a fully rounded pill 89 px (34 dp) tall on the header's centre
     * line, centred, 33.5 px (12.75 dp) side padding, filled with the functional-key colour; label 0.8x the candidate
     * size, medium weight, on-surface-variant colour.
     */
    private fun drawFixChip(canvas: Canvas, c: KeyboardColors, text: String, alpha: Int) {
        textPaint.typeface = mediumTypeface
        textPaint.textSize = d.suggestionTextSize * 0.8f
        val pad = d.m(33.5f)
        val label = TextUtils.ellipsize(text, textPaint, width - 2 * (d.stripLeft + pad), TextUtils.TruncateAt.END).toString()
        val w = textPaint.measureText(label) + 2 * pad
        val h = d.m(89f)
        val cx = width / 2f
        circlePaint.color = if (pressedSlot >= 0) c.functionalKeyPressed else c.functionalKey
        circlePaint.alpha = alpha
        canvas.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, h / 2f, h / 2f, circlePaint)
        textPaint.color = c.hintLabel
        textPaint.alpha = alpha
        canvas.drawText(label, cx, cy + textPaint.textSize * CAP_HEIGHT / 2f, textPaint)
        textPaint.textSize = d.suggestionTextSize
    }

    private val undoLabel: String get() = context.getString(dev.pboard.R.string.undo_chip)
    private val redoLabel: String get() = context.getString(dev.pboard.R.string.redo_chip)

    /**
     * Gboard 18.6's Undo chip (measured at 392.7 dp): a pill as tall as the mic button on the key colour, centred in
     * the bar, with the undo arrow 22 dp from its left end and "Undo" (16 dp, regular) from 40 dp; 12 dp after the text.
     */
    private fun drawUndoChip(canvas: Canvas, c: KeyboardColors, pressed: Boolean, alpha: Int, redo: Boolean = false) {
        textPaint.typeface = regularTypeface
        textPaint.textSize = d.dp(16f)
        textPaint.textAlign = Paint.Align.LEFT
        val label = if (redo) redoLabel else undoLabel
        val h = d.barButtonDiameter
        val w = d.dp(40f) + textPaint.measureText(label) + d.dp(12f)
        val left = width / 2f - w / 2f
        circlePaint.color = if (pressed) c.barButtonPressed else c.barButton
        circlePaint.alpha = alpha
        canvas.drawRoundRect(left, cy - h / 2f, left + w, cy + h / 2f, h / 2f, h / 2f, circlePaint)
        iconPaint.color = c.barIcon
        iconPaint.alpha = alpha
        val ix = left + d.dp(22f)
        if (redo) { canvas.save(); canvas.scale(-1f, 1f, ix, cy) } // Material's redo arrow is undo mirrored
        Icons.draw(canvas, Symbols.UNDO, d.m(50f), ix, cy, iconPaint)
        if (redo) canvas.restore()
        textPaint.color = c.suggestionText
        textPaint.alpha = alpha
        canvas.drawText(label, left + d.dp(40f), cy + textPaint.textSize * CAP_HEIGHT / 2f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    // --- touch ---

    private var downTime = 0L
    /** Slot armed for removal by a long press: it shows a delete icon; lifting on it removes the suggestion. */
    private var removeArmed = -1

    private val longPress = Runnable {
        val s = pressedSlot
        if (s >= 0) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            removeArmed = s
            invalidate()
        }
    }

    private fun hitTest(x: Float): Pair<String?, Int> {
        if (x < firstCx + buttonR + d.m(8f)) return ToolbarItems.EXPAND to -1
        if (x > lastCx - buttonR - d.m(8f)) return powerKey to -1
        if (stripVisible) {
            for (i in 0 until slotCount) if (slotRect(i, tmpRect).let { x >= it.left && x < it.right }) return null to i
            return null to -1
        }
        val centers = toolbarCenters()
        val half = if (centers.size > 1) (centers[1] - centers[0]) / 2f else width / 2f
        for ((i, id) in toolbarItems.withIndex()) if (kotlin.math.abs(x - centers[i + 1]) < half) return id to -1
        return null to -1
    }

    /**
     * Gives a touch to the keys (in this bar's coordinates); returns false when there are no keys to take it (a panel
     * replaces them). See [TouchRouting.barTouchToKeys].
     */
    var keysHandoff: ((MotionEvent) -> Boolean)? = null
    private var handingOff = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            handingOff = TouchRouting.barTouchToKeys(e.y, cy + buttonR) && keysHandoff?.invoke(e) == true
            if (handingOff) return true
        } else if (handingOff) {
            keysHandoff?.invoke(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) handingOff = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (item, slot) = hitTest(e.x)
                pressedItem = item
                pressedSlot = if (slot >= 0 && strip[slot] != null) slot else -1
                downTime = e.eventTime
                draggingItem = false
                if (pressedSlot >= 0) postDelayed(longPress, 500)
                if (expanded && item != null && item in toolbarItems) {
                    dragRaw[0] = e.rawX; dragRaw[1] = e.rawY
                    postDelayed(startDrag, TOOLBAR_DRAG_DELAY_MS)
                }
                if (item != null || pressedSlot >= 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingItem) { dragHost?.dragMove(e.rawX, e.rawY); return true }
                dragRaw[0] = e.rawX; dragRaw[1] = e.rawY
                if (e.y < -d.m(40f) || e.y > height + d.m(40f)) cancelPress()
                // Sliding off an armed slot cancels the removal.
                if (removeArmed >= 0 && !slotRect(removeArmed, tmpRect).contains(e.x, height / 2f)) cancelPress()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                removeCallbacks(startDrag)
                if (draggingItem) {
                    draggingItem = false
                    cancelPress()
                    dragHost?.dragEnd(e.rawX, e.rawY)
                    return true
                }
                val item = pressedItem
                val slot = pressedSlot
                val armed = removeArmed
                cancelPress()
                if (armed >= 0) listener?.onSuggestionLongPress(armed)
                else if (slot >= 0) listener?.onSuggestion(slot)
                else if (item != null && item !in disabledItems) listener?.onToolbarItem(item)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (draggingItem) { draggingItem = false; dragHost?.dragEnd(Float.NaN, Float.NaN) }
                cancelPress()
            }
        }
        return true
    }

    private var draggingItem = false
    private val dragRaw = FloatArray(2)
    private val startDrag = Runnable {
        val id = pressedItem ?: return@Runnable
        draggingItem = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dragHost?.dragStart(id, fromBar = true, rawX = dragRaw[0], rawY = dragRaw[1])
    }

    private fun cancelPress() {
        removeArmed = -1
        removeCallbacks(longPress)
        removeCallbacks(startDrag)
        pressedItem = null
        pressedSlot = -1
        invalidate()
    }

    // --- accessibility: the buttons and suggestions as virtual views (TalkBack) ---

    private inner class BarNodes : android.view.accessibility.AccessibilityNodeProvider() {
        /** (description, bounds, action) for every target currently shown. */
        fun targets(): List<Triple<String, android.graphics.Rect, () -> Unit>> {
            val out = ArrayList<Triple<String, android.graphics.Rect, () -> Unit>>()
            fun circle(cx: Float) = android.graphics.Rect((cx - buttonR).toInt(), (cy - buttonR).toInt(), (cx + buttonR).toInt(), (cy + buttonR).toInt())
            out += Triple(if (expanded) "Back" else "Open features menu", circle(firstCx)) { listener?.onToolbarItem(ToolbarItems.EXPAND) }
            if (stripVisible) {
                for (i in 0 until slotCount) {
                    val item = strip[i] ?: continue
                    val r = android.graphics.Rect(); slotRect(i, tmpRect).roundOut(r)
                    out += Triple(if (item.restore) (if (item.redo) redoLabel else undoLabel) else item.text, r) { listener?.onSuggestion(i) }
                }
            } else if (!expanded) {
                val centers = toolbarCenters()
                for ((i, id) in toolbarItems.withIndex()) out += Triple(labelFor(id), circle(centers[i + 1])) { listener?.onToolbarItem(id) }
            }
            powerKey?.let { id -> if (showVoice) out += Triple(labelFor(id), circle(lastCx)) { listener?.onToolbarItem(id) } }
            return out
        }

        override fun createAccessibilityNodeInfo(id: Int): android.view.accessibility.AccessibilityNodeInfo? {
            val t = targets()
            if (id == HOST_VIEW_ID) {
                val info = android.view.accessibility.AccessibilityNodeInfo.obtain(this@TopBarView)
                onInitializeAccessibilityNodeInfo(info)
                for (i in t.indices) info.addChild(this@TopBarView, i)
                return info
            }
            val (desc, r, _) = t.getOrNull(id) ?: return null
            val info = android.view.accessibility.AccessibilityNodeInfo.obtain(this@TopBarView, id)
            info.packageName = context.packageName
            info.className = android.widget.Button::class.java.name
            info.contentDescription = desc
            info.setParent(this@TopBarView)
            info.setBoundsInParent(r)
            val loc = IntArray(2); getLocationOnScreen(loc)
            info.setBoundsInScreen(android.graphics.Rect(r).apply { offset(loc[0], loc[1]) })
            info.isEnabled = true; info.isVisibleToUser = true; info.isClickable = true; info.isFocusable = true
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            return info
        }

        override fun performAction(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (id == HOST_VIEW_ID) return performAccessibilityAction(action, arguments)
            val t = targets().getOrNull(id) ?: return false
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) { t.third(); return true }
            return false
        }
    }

    private val barNodes = BarNodes()

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = barNodes

    private fun labelFor(id: String): String = when (id) {
        ToolbarItems.STICKER -> "Stickers"; ToolbarItems.GIF -> "GIF"; ToolbarItems.CLIPBOARD -> "Clipboard"
        ToolbarItems.SETTINGS -> "Settings"; ToolbarItems.THEME -> "Theme"; ToolbarItems.EMOJI -> "Emoji"
        ToolbarItems.TRANSLATE -> "Translate"; ToolbarItems.VOICE -> "Voice typing"
        ToolbarItems.ONE_HANDED -> "One-handed mode"; ToolbarItems.TEXT_EDITING -> "Text editing"
        ToolbarItems.UNDO -> "Undo"; ToolbarItems.RESIZE -> "Resize"; ToolbarItems.FLOATING -> "Floating keyboard"
        else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    companion object {
        /** Toolbar <-> suggestions swap instantly in Gboard 15. */
        const val TOOLBAR_FADE_DELAY_MS = 100L
        const val TOOLBAR_FADE_MS = 200L
        const val CAP_HEIGHT = 0.711f

        /** Width of each side slot of the strip [left, right] whose middle slot takes [midFraction] of it. */
        fun sideSlotWidth(left: Float, right: Float, midFraction: Float): Float = (right - left) * (1f - midFraction) / 2f

        /** Left pixel of a [w] px divider centred on the slot boundary [x]. */
        fun dividerLeft(x: Float, w: Float): Float = Math.round(x - w / 2f).toFloat()
    }
}
