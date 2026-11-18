package dev.pboard.ime.emoji

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
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

interface EmojiPanelListener {
    fun onEmoji(emoji: String)
    fun onEmojiDelete()
    fun onEmojiClose()
    fun onEmojiSearch() {}
    /** A key-press haptic (the IME applies the user's vibration settings). */
    fun onEmojiKeyFeedback() {}
}

/**
 * Gboard-style emoji panel (18.6 layout) drawn in one View: header (back button, search pill, category tabs), a
 * scrolling 8-column grid with sentence-case section headers, and the bottom bar (ABC, emoji / GIF / sticker /
 * emoticon tabs, delete). Long-press shows the skin-tone (and woman / man) variants. Network features Gboard has
 * here (sticker/GIF search) are omitted; their tabs are drawn disabled. Geometry measured on the 392.7 dp reference
 * phone, in dp.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context, private val d: Dimens, private var colors: KeyboardColors) : View(context) {
    var listener: EmojiPanelListener? = null
    var recordRecents = true

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // Geometry. The panel is the keyboard's height plus 124 dp (Gboard 15.1 and 18.6): header as tall as the
    // keyboard's, a 48 dp bottom bar, the grid in between.
    private val headerH = d.barHeight
    private val bottomH = d.dp(48f)
    private val contentH = d.barHeight + d.bodyHeight() + d.dp(124f) - headerH - bottomH
    // Gboard 18.6 "Emoji size" (85..200 %): bigger emoji in bigger cells, so fewer fit per row (8 at 100 %).
    private val emojiScale = dev.pboard.ime.config.Config.current.emojiScale
    private val gridLeft = d.dp(5.9f)
    private val gridRight = d.dp(4.4f)
    private val columns = (8f / emojiScale + 0.001f).toInt().coerceIn(4, 8)
    private var cellW = (context.resources.displayMetrics.widthPixels - gridLeft - gridRight) / columns
    private val cellH = d.dp(50.5f) * emojiScale
    private val sectionH = d.dp(23.8f)
    private val emojiSize = d.dp(30.3f) * emojiScale

    private inner class Section(val title: String, val icon: String, val items: List<Emoji>, var top: Float = 0f) {
        val rows get() = ceil(items.size / columns.toFloat()).toInt()
    }

    private val sections = ArrayList<Section>()
    private var contentHeight = 0f
    private var scrollY = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setData(categories: List<EmojiCategory>, recents: List<String>) {
        sections.clear()
        variantPopup = null
        val icons = listOf(Symbols.EMOJI_CATEGORY, Symbols.PEOPLE, Symbols.NATURE, Symbols.FOOD, Symbols.TRAVEL,
            Symbols.ACTIVITIES, Symbols.OBJECTS, Symbols.SYMBOLS, Symbols.FLAG)
        // The recents tab is always there (Gboard); with nothing recent its section takes no space.
        sections += Section("Recently used", Symbols.RECENT, recents.map { Emoji(it) })
        for (c in categories) sections += Section(c.title, icons[c.id], c.emoji)
        var y = 0f
        for (s in sections) { s.top = y; if (s.items.isNotEmpty()) y += sectionH + s.rows * cellH }
        contentHeight = y
        scrollY = 0f
        invalidate()
    }

    fun setColors(c: KeyboardColors) { colors = c; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (headerH + contentH + bottomH).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0) cellW = (w - gridLeft - gridRight) / columns
    }

    private val contentTop get() = headerH
    private val contentBottom get() = headerH + contentH
    private val maxScroll get() = max(0f, contentHeight - contentH)

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val c = colors
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
        // Gboard 18.6: the whole panel sits on surfaceContainerLow.
        fill.color = panelColor(c)
        canvas.drawRect(0f, contentTop, width.toFloat(), contentBottom, fill)
        canvas.save()
        canvas.clipRect(0f, contentTop, width.toFloat(), contentBottom)
        emojiPaint.textSize = emojiSize
        val fm = emojiPaint.fontMetrics
        val emojiBaseline = -(fm.ascent + fm.descent) / 2f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        textPaint.textSize = d.dp(12f)
        textPaint.letterSpacing = 0f
        textPaint.color = c.emojiSubtle ?: c.hintLabel
        textPaint.textAlign = Paint.Align.LEFT
        for (s in sections) {
            if (s.items.isEmpty()) continue
            val top = contentTop + s.top - scrollY
            val bottom = top + sectionH + s.rows * cellH
            if (bottom < contentTop || top > contentBottom) continue
            canvas.drawText(s.title, d.dp(12.4f), top + d.dp(17.5f), textPaint)
            for ((i, e) in s.items.withIndex()) {
                val r = i / columns
                val col = i % columns
                val cy = top + sectionH + r * cellH + cellH / 2f
                if (cy + cellH < contentTop || cy - cellH > contentBottom) continue
                val cx = gridLeft + col * cellW + cellW / 2f
                if (pressedSection === s && pressedIndex == i) {
                    fill.color = c.keyPressed
                    canvas.drawCircle(cx, cy, cellW / 2f - d.m(4f), fill)
                }
                canvas.drawText(e.text, cx, cy + emojiBaseline, emojiPaint)
                if (e.rows.isNotEmpty()) {
                    // Small corner mark: skin-tone / gender variants available on long-press.
                    fill.color = c.hintLabel
                    val mx = cx + cellW / 2f - d.m(14f)
                    val my = cy + cellH / 2f - d.m(14f)
                    val path = android.graphics.Path().apply {
                        moveTo(mx + d.m(6f), my - d.m(6f)); lineTo(mx + d.m(6f), my + d.m(6f)); lineTo(mx - d.m(6f), my + d.m(6f)); close()
                    }
                    canvas.drawPath(path, fill)
                }
            }
        }
        canvas.restore()
        drawHeader(canvas, c)
        drawBottomBar(canvas, c)
        variantPopup?.let { drawVariants(canvas, c, it) }
    }

    private fun currentSection(): Int {
        var idx = 0
        for ((i, s) in sections.withIndex()) if (s.top <= scrollY + 1f) idx = i
        return idx
    }

    private fun panelColor(c: KeyboardColors) = c.emojiBackground ?: c.surfaceLowest ?: c.key
    private fun chipColor(c: KeyboardColors) = c.emojiChip ?: c.key

    private val backCx get() = d.dp(25.3f)
    private val headerCy get() = d.barCenterY
    // Gboard 18.6: no title; a "Search" pill after the back button, then the category tabs, which scroll sideways.
    private val chipR get() = d.dp(17.1f)
    private val pill get() = RectF(d.dp(50.5f), headerCy - chipR, d.dp(160f), headerCy + chipR)
    private val tabsLeft get() = d.dp(168.4f)
    private val tabsClip get() = d.dp(164f)
    private val tabPitch get() = d.dp(34.2f)
    private var tabScroll = 0f
    private var lastCurrent = -1
    private val maxTabScroll get() = max(0f, tabsLeft + tabPitch * sections.size + d.m(8f) - width)

    private fun drawHeader(canvas: Canvas, c: KeyboardColors) {
        fill.color = panelColor(c)
        canvas.drawRect(0f, 0f, width.toFloat(), headerH, fill)
        val chip = chipColor(c)
        val chipPressed = KeyboardTheme.blend(chip, c.keyLabel, 0.1f)
        fill.color = if (pressedHeader == -2) chipPressed else chip
        canvas.drawCircle(backCx, headerCy, chipR, fill)
        fill.color = c.hintLabel
        Icons.draw(canvas, Symbols.ARROW_BACK, d.barIconBox, backCx, headerCy, fill)
        // Search pill
        val pr = pill
        fill.color = if (pressedHeader == -3) chipPressed else chip
        canvas.drawRoundRect(pr, pr.height() / 2f, pr.height() / 2f, fill)
        fill.color = c.hintLabel
        // Box and offset measured against Gboard 18.6 (tools/icons/score.py).
        Icons.draw(canvas, Symbols.SEARCH, d.m(47.7f), d.dp(67.1f) + d.m(0.4f), headerCy + d.m(1.1f), fill)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.letterSpacing = 0f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = d.dp(15f)
        textPaint.color = c.hintLabel
        canvas.drawText("Search", d.dp(80.7f), headerCy + d.dp(15f) * 0.716f / 2f, textPaint)
        val cur = currentSection()
        if (cur != lastCurrent) {
            // Keep the current category's tab in view as the grid scrolls.
            lastCurrent = cur
            val cx = tabsLeft + tabPitch * (cur + 0.5f) - tabScroll
            if (cx + tabPitch / 2f > width) tabScroll += cx + tabPitch / 2f - width
            if (cx - tabPitch / 2f < tabsClip) tabScroll -= tabsClip - (cx - tabPitch / 2f)
            tabScroll = tabScroll.coerceIn(0f, maxTabScroll)
        }
        canvas.save()
        canvas.clipRect(tabsClip, 0f, width.toFloat(), headerH)
        for ((i, s) in sections.withIndex()) {
            val cx = tabsLeft + tabPitch * (i + 0.5f) - tabScroll
            if (i == cur) {
                fill.color = c.emojiSelected ?: KeyboardTheme.blend(c.background, c.primary, 0.18f)
                canvas.drawCircle(cx, headerCy, d.dp(16.75f), fill)
            }
            fill.color = if (i == cur) c.emojiSelectedIcon ?: c.primary else c.emojiSubtle ?: c.hintLabel
            Icons.draw(canvas, s.icon, d.dp(18.3f), cx, headerCy, fill)
        }
        canvas.restore()
    }

    /** Bottom bar slots: 0 = ABC, 1..4 = the emoji / GIF / sticker / emoticon tabs, 5 = delete. */
    private fun slotX(i: Int) = when (i) {
        0 -> d.dp(30.4f)
        5 -> width - d.dp(34.7f)
        else -> d.dp(97.5f) + (i - 1) * d.dp(65.5f)
    }

    private fun drawBottomBar(canvas: Canvas, c: KeyboardColors) {
        val top = contentBottom
        fill.color = panelColor(c)
        canvas.drawRect(0f, top, width.toFloat(), top + bottomH, fill)
        val cy = top + bottomH / 2f
        val chip = chipColor(c)
        // ABC
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        textPaint.textSize = d.functionTextSize
        textPaint.letterSpacing = 0f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = c.hintLabel
        if (pressedBottom == 0) { fill.color = chip; canvas.drawCircle(slotX(0), cy, chipR * 1.2f, fill) }
        canvas.drawText("ABC", slotX(0), cy + d.functionTextSize * 0.711f / 2f, textPaint)
        // Tabs: pills; the emoji tab is selected.
        val pw = d.dp(30.2f)
        val ph = d.dp(19.1f)
        for (i in 1..4) {
            fill.color = if (i == 1) c.emojiSelected ?: c.primary else chip
            val x = slotX(i)
            canvas.drawRoundRect(x - pw, cy - ph, x + pw, cy + ph, ph, ph, fill)
        }
        fill.color = c.emojiSelectedIcon ?: c.popupSelectedLabel
        Icons.draw(canvas, Symbols.EMOJI, d.barIconBox, slotX(1), cy, fill)
        // Unavailable tabs (network GIF / sticker search, emoticons) are drawn disabled.
        val disabled = KeyboardTheme.blend(chip, c.hintLabel, 0.3f)
        fill.color = disabled
        // GIF, sticker and delete boxes/offsets measured against Gboard 18.6 (tools/icons/score.py).
        Icons.draw(canvas, Symbols.GIF_BAR, d.m(107f), slotX(2), cy + d.m(0.6f), fill)
        Icons.draw(canvas, Symbols.STICKER, d.m(72.6f), slotX(3) - d.m(0.5f), cy, fill)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        textPaint.textSize = d.dp(16f)
        textPaint.color = disabled
        canvas.drawText(":-)", slotX(4), cy + d.dp(16f) * 0.711f / 2f, textPaint)
        // Delete
        if (pressedBottom == 5) { fill.color = chip; canvas.drawCircle(slotX(5), cy, chipR * 1.2f, fill) }
        fill.color = c.hintLabel
        Icons.draw(canvas, Symbols.BACKSPACE, d.iconDeleteBox, slotX(5) - d.m(0.6f), cy + d.m(1f), fill)
    }

    // ------------------------------------------------------------------ variants popup

    /**
     * Long-press picker: [rows] top to bottom (skin tones; woman / man rows for people; a 5x5 tone grid under the
     * base for two-person emoji), each row centred, [cell] wide and [rowH] tall per emoji. As in Gboard it opens
     * with nothing selected and stays open when the long-press ends outside it; sliding onto an emoji and lifting,
     * or tapping one, picks it; a touch elsewhere closes it.
     */
    private class VariantPopup(val rows: List<List<String>>, val rect: RectF, val cell: Float, val rowH: Float) {
        var row = -1
        var col = -1
        /** False until the long-pressing finger moves: lifting it unmoved never picks (the picker may cover it). */
        var armed = false
        fun rowLeft(r: Int) = rect.centerX() - rows[r].size * cell / 2f
        /** Selects the emoji under (x, y); outside the picker the selection is kept. */
        fun select(x: Float, y: Float) {
            if (!armed || !rect.contains(x, y)) return
            val r = ((y - rect.top) / rowH).toInt().coerceIn(0, rows.size - 1)
            val c = floor((x - rowLeft(r)) / cell).toInt()
            if (c in rows[r].indices) { row = r; col = c }
        }
        val selected get() = rows.getOrNull(row)?.getOrNull(col)
    }
    private var variantPopup: VariantPopup? = null
    private var dismissingPopup = false

    private fun drawVariants(canvas: Canvas, c: KeyboardColors, p: VariantPopup) {
        fill.color = c.popupBackground
        fill.setShadowLayer(d.popupElevation, 0f, d.popupElevation / 3f, c.shadow)
        canvas.drawRoundRect(p.rect, d.popupRadius / 2f, d.popupRadius / 2f, fill)
        fill.clearShadowLayer()
        emojiPaint.textSize = emojiSize * p.rowH / cellH
        val fm = emojiPaint.fontMetrics
        val base = -(fm.ascent + fm.descent) / 2f
        for ((r, row) in p.rows.withIndex()) {
            val cy = p.rect.top + p.rowH * (r + 0.5f)
            for ((i, e) in row.withIndex()) {
                val cx = p.rowLeft(r) + p.cell * (i + 0.5f)
                if (r == p.row && i == p.col) {
                    fill.color = KeyboardTheme.blend(c.popupBackground, c.primary, 0.2f)
                    canvas.drawCircle(cx, cy, min(p.cell, p.rowH) / 2f - d.m(6f), fill)
                }
                canvas.drawText(e, cx, cy + base, emojiPaint)
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressedSection: Section? = null
    private var pressedIndex = -1
    private var pressedHeader = -1
    private var pressedBottom = -1
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable { openVariants() }
    private val deleteRepeat = object : Runnable {
        override fun run() {
            listener?.onEmojiDelete()
            handler.postDelayed(this, 50)
        }
    }

    private fun hitEmoji(x: Float, y: Float): Pair<Section, Int>? {
        if (y < contentTop || y > contentBottom) return null
        val cy = y - contentTop + scrollY
        for (s in sections) {
            if (s.items.isEmpty()) continue
            val gridTop = s.top + sectionH
            val gridBottom = gridTop + s.rows * cellH
            if (cy in gridTop..gridBottom) {
                val r = ((cy - gridTop) / cellH).toInt()
                val c = ((x - gridLeft) / cellW).toInt().coerceIn(0, columns - 1)
                val i = r * columns + c
                return if (i < s.items.size) s to i else null
            }
        }
        return null
    }

    private fun openVariants() {
        val s = pressedSection ?: return
        val e = s.items.getOrNull(pressedIndex) ?: return
        if (e.rows.isEmpty()) return
        val r = pressedIndex / columns
        val col = pressedIndex % columns
        val cx = gridLeft + col * cellW + cellW / 2f
        val cellTop = contentTop + s.top - scrollY + sectionH + r * cellH
        // A grid as tall as the panel allows: rows shrink (down to 70 %) before the picker would leave the view.
        val margin = d.m(8f)
        val cell = min(cellW, (width - 2 * margin) / e.rows.maxOf { it.size })
        val rowH = min(cellH, max(cellH * 0.7f, (height - 2 * margin) / e.rows.size))
        val w = e.rows.maxOf { it.size } * cell
        val h = e.rows.size * rowH
        val left = (cx - w / 2f).coerceIn(margin, max(margin, width - w - margin))
        // Above the cell (Gboard); where the panel has no room above, below it; else as close as it fits.
        val above = cellTop - margin - h
        val below = cellTop + cellH + margin
        val top = when {
            above >= margin -> above
            below + h <= height - margin -> below
            else -> above.coerceIn(margin, max(margin, height - h - margin))
        }
        variantPopup = VariantPopup(e.rows, RectF(left, top, left + w, top + h), cell, rowH)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        // An open picker takes the whole gesture: inside it selects, elsewhere it only closes the picker.
        val open = variantPopup
        if (open != null && (e.actionMasked == MotionEvent.ACTION_DOWN || dismissingPopup)) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (open.rect.contains(x, y)) { open.armed = true; open.select(x, y) } else {
                    variantPopup = null
                    dismissingPopup = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dismissingPopup = false
            }
            invalidate()
            return true
        }
        if (dismissingPopup) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) dismissingPopup = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x; downY = y; lastY = y
                dragging = false
                when {
                    y < headerH -> {
                        pressedHeader = when {
                            abs(x - backCx) < d.barButtonDiameter / 2f -> -2
                            pill.contains(x, y) -> -3
                            x >= tabsClip -> ((x - tabsLeft + tabScroll) / tabPitch).toInt().takeIf { it in sections.indices } ?: -1
                            else -> -1
                        }
                        lastX = x
                    }
                    y > contentBottom -> {
                        pressedBottom = (0..5).minByOrNull { abs(slotX(it) - x) } ?: -1
                        if (pressedBottom == 5) {
                            listener?.onEmojiDelete()
                            handler.postDelayed(deleteRepeat, 400)
                        }
                    }
                    else -> hitEmoji(x, y)?.let { (s, i) ->
                        pressedSection = s; pressedIndex = i
                        handler.postDelayed(longPress, 300)
                    }
                }
                // Buttons that cannot start a scroll give feedback on touch-down. Emoji cells and category tabs sit in
                // scrollable areas, so their feedback waits for the tap to complete: a touch that starts a scroll
                // must not vibrate (bug report: every scroll start buzzed like a key press).
                if (pressedHeader == -2 || pressedHeader == -3 || pressedBottom != -1) listener?.onEmojiKeyFeedback()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val vp = variantPopup
                if (vp != null) {
                    if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) vp.armed = true
                    vp.select(x, y)
                    invalidate()
                    return true
                }
                if (downY < headerH && downX >= tabsClip) {
                    // Sideways drag on the category tabs scrolls them.
                    if (!dragging && abs(x - downX) > touchSlop) { dragging = true; pressedHeader = -1 }
                    if (dragging) { tabScroll = (tabScroll + (lastX - x)).coerceIn(0f, maxTabScroll); lastX = x; invalidate() }
                    return true
                }
                if (!dragging && pressedBottom == -1 && pressedHeader == -1 && abs(y - downY) > touchSlop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    pressedSection = null
                    pressedIndex = -1
                }
                if (dragging) {
                    scrollY = (scrollY + (lastY - y)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(deleteRepeat)
                val vp = variantPopup
                when {
                    vp != null -> {
                        vp.select(x, y)
                        // Lifted on an emoji of the picker: pick it. Otherwise (still on the cell) the picker stays.
                        vp.selected?.let { commit(it); variantPopup = null }
                    }
                    dragging && downY < headerH -> {}
                    dragging -> {
                        velocity?.let {
                            it.computeCurrentVelocity(1000)
                            scroller.fling(0, scrollY.toInt(), 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll.toInt())
                        }
                    }
                    pressedHeader == -2 -> listener?.onEmojiClose()
                    pressedHeader == -3 -> listener?.onEmojiSearch()
                    pressedHeader >= 0 -> {
                        listener?.onEmojiKeyFeedback()
                        scroller.forceFinished(true)
                        scrollY = sections[pressedHeader].top.coerceIn(0f, maxScroll)
                    }
                    pressedBottom == 0 -> listener?.onEmojiClose()
                    pressedSection != null -> pressedSection?.items?.getOrNull(pressedIndex)?.let {
                        listener?.onEmojiKeyFeedback()
                        commit(it.text)
                    }
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(deleteRepeat)
                variantPopup = null
                reset()
            }
        }
        return true
    }

    private fun reset() {
        pressedSection = null
        pressedIndex = -1
        pressedHeader = -1
        pressedBottom = -1
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    private fun commit(emoji: String) {
        listener?.onEmoji(emoji)
        if (recordRecents) EmojiData.addRecent(context, emoji)
    }
}
