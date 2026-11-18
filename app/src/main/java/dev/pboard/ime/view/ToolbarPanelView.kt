package dev.pboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.toolbar.ToolbarItem

/**
 * The "more" panel opened from the bar's four-squares button: the toolbar items that are not pinned, as a 4-column
 * grid of tiles under the hint line (Gboard 15.1 at 420 dpi: 208x126 px tiles with 12 dp corners, 79 px left margin,
 * 26 px gaps, 192.5 px row pitch, 14 sp labels 42 px below the tile).
 * Tapping a tile runs the item; holding a tile lifts it for dragging onto the bar, the power key or another cell
 * ([ToolbarDragHost] applies the rules).
 * When the grid is taller than the panel (three or more rows on a short keyboard) it scrolls vertically, so the last
 * row's labels are never cut off (bug report: "Scan text" clipped and unreachable).
 */
@SuppressLint("ViewConstructor")
class ToolbarPanelView(
    context: Context,
    private val d: Dimens,
    private val colors: KeyboardColors,
    items: List<ToolbarItem>,
    private val panelHeight: Int,
    private val onItem: (String) -> Unit,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tiles = ArrayList<RectF>()
    private var pressed = -1
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var scrolling = false
    private var downY = 0f
    private var lastY = 0f

    var items: List<ToolbarItem> = items
        set(value) { field = value; tiles.clear(); scroll = scroll.coerceIn(0f, maxScroll); invalidate() }

    /** Content height: hint line, the rows of tiles and their labels, and a bottom margin. */
    private val contentHeight: Float
        get() {
            val rows = (items.size + 1 + 3) / 4 // +1: room for a drop gap while dragging
            return d.m(101f) + (rows - 1).coerceAtLeast(0) * d.m(192.5f) + tileH + d.m(42f) + d.m(36f)
        }
    private val maxScroll: Float get() = (contentHeight - panelHeight).coerceAtLeast(0f)

    var dragHost: ToolbarDragHost? = null
    private var dragHidden: String? = null
    private var dragInsert = -1

    /** Shows the dragged item lifted out of the grid and a gap where it would drop (-1: none). */
    fun setDragPreview(hidden: String?, insert: Int) {
        dragHidden = hidden; dragInsert = insert
        invalidate()
    }

    /** Grid order while dragging: the dragged item removed, a gap (null) at the drop cell. */
    private fun gridLayout(): List<ToolbarItem?> {
        val list: MutableList<ToolbarItem?> = items.filter { it.id != dragHidden }.toMutableList()
        if (dragInsert >= 0) list.add(dragInsert.coerceIn(0, list.size), null)
        return list
    }

    /** Drop index in the panel for local (x, y): the nearest cell among the items left after removing the dragged one. */
    fun insertIndexAt(x: Float, y: Float): Int {
        val n = items.count { it.id != dragHidden }
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0..n) {
            val r = cellRect(i)
            val dd = (r.centerX() - x) * (r.centerX() - x) + (r.centerY() - y) * (r.centerY() - y)
            if (dd < bestD) { bestD = dd; best = i }
        }
        return best
    }

    private val tileW get() = d.m(208f)
    private val tileH get() = d.m(126f)

    private fun cellRect(i: Int): RectF {
        val l = d.m(79f) + (i % 4) * (tileW + d.m(26f))
        val t = d.m(101f) + (i / 4) * d.m(192.5f) - scroll
        return RectF(l, t, l + tileW, t + tileH)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight)
    }

    private fun layoutTiles() {
        tiles.clear()
        for (i in items.indices) tiles += cellRect(i)
    }

    override fun onDraw(canvas: Canvas) {
        if (tiles.size != items.size) layoutTiles()
        fill.color = colors.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        text.textSize = d.m(36.75f)
        text.color = colors.keyLabel
        canvas.drawText("Hold and drag to customize", width / 2f, d.m(59f) - scroll, text)
        text.typeface = Typeface.DEFAULT
        val shown = if (dragHidden != null) gridLayout() else items
        for ((i, item) in shown.withIndex()) {
            val r = cellRect(i)
            if (item == null) continue // the lifted tile hovers over its drop cell
            fill.color = if (dragHidden == null && i == pressed) colors.keyPressed else colors.key
            canvas.drawRoundRect(r, d.m(31.5f), d.m(31.5f), fill)
            fill.color = colors.barIcon
            Icons.draw(canvas, symbolFor(item.symbol), d.barIconBox, r.centerX(), r.centerY(), fill)
            text.color = colors.keyLabel
            canvas.drawText(context.getString(item.label), r.centerX(), r.bottom + d.m(42f), text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                scrolling = false
                downY = e.y; lastY = e.y
                layoutTiles()
                pressed = tiles.indexOfFirst { it.contains(e.x, e.y) }
                dragging = false
                downRaw[0] = e.rawX; downRaw[1] = e.rawY
                if (pressed >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    postDelayed(startDrag, TOOLBAR_DRAG_DELAY_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (dragging) dragHost?.dragMove(e.rawX, e.rawY)
                else {
                    if (!scrolling && maxScroll > 0f && kotlin.math.abs(e.y - downY) > touchSlop) {
                        scrolling = true
                        removeCallbacks(startDrag); pressed = -1
                    }
                    if (scrolling) {
                        scroll = (scroll + (lastY - e.y)).coerceIn(0f, maxScroll)
                        layoutTiles()
                        invalidate()
                    } else if (pressed >= 0 && !tiles[pressed].contains(e.x, e.y)) {
                        removeCallbacks(startDrag); pressed = -1; invalidate()
                    }
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(startDrag)
                val p = pressed
                pressed = -1
                invalidate()
                if (dragging) { dragging = false; dragHost?.dragEnd(e.rawX, e.rawY); return true }
                if (scrolling) {
                    scrolling = false
                    velocity?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(0, scroll.toInt(), 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll.toInt())
                        postInvalidateOnAnimation()
                    }
                    velocity?.recycle(); velocity = null
                    return true
                }
                velocity?.recycle(); velocity = null
                if (p >= 0 && p < tiles.size && tiles[p].contains(e.x, e.y)) onItem(items[p].id)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDrag)
                scrolling = false
                velocity?.recycle(); velocity = null
                if (dragging) { dragging = false; dragHost?.dragEnd(Float.NaN, Float.NaN) }
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            layoutTiles()
            postInvalidateOnAnimation()
        }
    }

    private var dragging = false
    private val downRaw = FloatArray(2)
    private val startDrag = Runnable {
        val p = pressed
        val item = items.getOrNull(p) ?: return@Runnable
        dragging = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dragHost?.dragStart(item.id, fromBar = false, rawX = downRaw[0], rawY = downRaw[1])
    }

    companion object {
        /** Material Symbols name (as used by the toolbar model) to our path data. */
        fun symbolFor(name: String): String = when (name) {
            "assignment" -> Symbols.CLIPBOARD
            "settings" -> Symbols.SETTINGS
            "palette" -> Symbols.PALETTE
            "back_hand" -> Symbols.BACK_HAND
            "settings_ethernet" -> Symbols.TEXT_EDITING
            "crop_free" -> Symbols.RESIZE
            "keyboard" -> Symbols.KEYBOARD
            "sentiment_satisfied" -> Symbols.EMOJI_SMILE
            "undo" -> Symbols.UNDO
            "vertical_split" -> Symbols.SPLIT
            "sticky_note_2" -> Symbols.STICKY_NOTE
            "translate" -> Symbols.TRANSLATE
            "document_scanner" -> Symbols.SCAN
            "mic" -> Symbols.MIC
            else -> Symbols.MORE
        }
    }
}
