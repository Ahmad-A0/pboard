package dev.pboard.ime.rich

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.ime.view.Icons
import dev.pboard.ime.view.Symbols
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

interface StickerPanelListener {
    fun onSticker(s: StickerStore.Sticker)
    fun onStickerRemove(s: StickerStore.Sticker) {}
    fun onStickerAdd()
    fun onStickerClose()
    fun onStickerEmoji()
    fun onStickerDelete()
}

/**
 * The sticker panel (Gboard 18.6 layout, measured on the emulator, minus everything that needs Google's servers): the
 * emoji panel's frame (keyboard height + 124 dp) on surface_container_low; header with the back chip, an "add" button
 * (pboard: import from the photo picker; Gboard: browse more packs), the recently-sent tab and one tab per pack (its
 * first sticker), the current one on a filled circle; a 4-column grid of square white cards (94 dp at 411 dp width,
 * 13 px gaps, 8 dp corners) that scrolls; the bottom bar ABC · emoji · GIF (disabled) · sticker (selected) ·
 * :-) (disabled) · delete. Without stickers the grid shows a card that explains the feature and offers "Add stickers".
 * Long-press removes a sticker. All images are the user's own, app-private.
 */
@SuppressLint("ViewConstructor")
class StickerPanelView(context: Context, private val d: Dimens, private val colors: KeyboardColors) : View(context) {
    var listener: StickerPanelListener? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val headerH = d.barHeight
    private val bottomH = d.dp(48f)
    private val contentH = d.barHeight + d.bodyHeight() + d.dp(124f) - headerH - bottomH
    private val columns = 4
    private val gap = d.m(13f)
    private val side = d.m(28f)
    private var cell = 0f
    private val radius = d.dp(8f)

    /** Tab 0: recently sent; then one per pack. */
    private var tabs: List<List<StickerStore.Sticker>> = emptyList()
    private var tabIcons: List<StickerStore.Sticker?> = emptyList()
    private var current = 0
    private var scrollY = 0f
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashSet<String>()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun setData(recents: List<StickerStore.Sticker>, packs: List<StickerStore.Pack>) {
        tabs = listOf(recents) + packs.map { it.stickers }
        tabIcons = listOf<StickerStore.Sticker?>(null) + packs.map { it.stickers.firstOrNull() }
        // Gboard opens on the recent tab when something was sent before, else on the first pack.
        current = if (recents.isNotEmpty() || packs.isEmpty()) 0 else 1
        scrollY = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (headerH + contentH + bottomH).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cell = (w - 2 * side - (columns - 1) * gap) / columns
    }

    private fun panelColor(c: KeyboardColors) = c.emojiBackground ?: c.surfaceLowest ?: c.key
    private fun chipColor(c: KeyboardColors) = c.emojiChip ?: c.key
    private val items get() = tabs.getOrNull(current).orEmpty()
    private val contentTop get() = headerH
    private val contentBottom get() = headerH + contentH
    private val rows get() = ceil(items.size / columns.toFloat()).toInt()
    private val maxScroll get() = max(0f, rows * (cell + gap) + gap - contentH)

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val c = colors
        if (scroller.computeScrollOffset()) { scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll); postInvalidateOnAnimation() }
        fill.color = panelColor(c)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        drawHeader(canvas, c)
        canvas.save()
        canvas.clipRect(0f, contentTop, width.toFloat(), contentBottom)
        if (items.isEmpty()) drawEmpty(canvas, c) else drawGrid(canvas, c)
        canvas.restore()
        drawBottomBar(canvas, c)
    }

    private val chipR get() = d.dp(17.1f)
    private val headerCy get() = d.barCenterY
    private fun headerX(i: Int) = d.dp(25.3f) + i * d.dp(40.4f)

    /** Header slots: 0 back, 1 add, 2.. tabs (recent, packs). */
    private fun drawHeader(canvas: Canvas, c: KeyboardColors) {
        val chip = chipColor(c)
        fill.color = if (pressedHeader == 0) KeyboardTheme.blend(chip, c.keyLabel, 0.1f) else chip
        canvas.drawCircle(headerX(0), headerCy, chipR, fill)
        fill.color = c.hintLabel
        Icons.draw(canvas, Symbols.ARROW_BACK, d.barIconBox, headerX(0), headerCy, fill)
        fill.color = c.hintLabel
        if (pressedHeader == 1) { fill.color = chip; canvas.drawCircle(headerX(1), headerCy, chipR, fill); fill.color = c.hintLabel }
        Icons.draw(canvas, Symbols.ADD, d.dp(20f), headerX(1), headerCy, fill)
        for (t in tabs.indices) {
            val cx = headerX(2 + t)
            if (cx - chipR > width) break
            if (t == current) {
                fill.color = c.emojiSelected ?: KeyboardTheme.blend(c.background, c.primary, 0.18f)
                canvas.drawCircle(cx, headerCy, d.dp(16.75f), fill)
            }
            val icon = tabIcons.getOrNull(t)
            val bmp = icon?.let { bitmap(it) }
            if (bmp != null) {
                val s = d.dp(24f)
                canvas.drawBitmap(bmp, null, RectF(cx - s / 2, headerCy - s / 2, cx + s / 2, headerCy + s / 2), bmpPaint)
            } else {
                fill.color = if (t == current) c.emojiSelectedIcon ?: c.primary else c.emojiSubtle ?: c.hintLabel
                Icons.draw(canvas, if (t == 0) Symbols.RECENT else Symbols.IMAGE, d.dp(18.3f), cx, headerCy, fill)
            }
        }
    }

    private fun cellRect(i: Int): RectF {
        val col = i % columns
        val row = i / columns
        val l = side + col * (cell + gap)
        val t = contentTop + gap + row * (cell + gap) - scrollY
        return RectF(l, t, l + cell, t + cell)
    }

    private fun drawGrid(canvas: Canvas, c: KeyboardColors) {
        for ((i, s) in items.withIndex()) {
            val r = cellRect(i)
            if (r.bottom < contentTop || r.top > contentBottom) continue
            fill.color = if (i == pressedIndex) c.keyPressed else c.key
            canvas.drawRoundRect(r, radius, radius, fill)
            val bmp = bitmap(s) ?: continue
            val pad = d.dp(4f)
            val box = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
            val sc = minOf(box.width() / bmp.width, box.height() / bmp.height)
            val w = bmp.width * sc
            val h = bmp.height * sc
            canvas.drawBitmap(bmp, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), bmpPaint)
        }
    }

    private val addRect = RectF()

    private fun drawEmpty(canvas: Canvas, c: KeyboardColors) {
        val r = RectF(side, contentTop + gap, width - side, contentTop + gap + d.dp(170f))
        fill.color = c.key
        canvas.drawRoundRect(r, d.dp(16f), d.dp(16f), fill)
        text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        text.textSize = d.dp(16f)
        text.color = c.keyLabel
        text.textAlign = Paint.Align.LEFT
        val pad = d.dp(18f)
        val title = if (current == 0 && tabs.size > 1) "No recent stickers" else "Make your own stickers"
        canvas.drawText(title, r.left + pad, r.top + pad + text.textSize, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = d.dp(14f)
        text.color = c.hintLabel
        val body = "Add images from your photos. They stay on this device and are sent only to the app you choose."
        val l = StaticLayout.Builder.obtain(body, 0, body.length, text, (r.width() - 2 * pad).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        canvas.save(); canvas.translate(r.left + pad, r.top + pad + d.dp(28f)); l.draw(canvas); canvas.restore()
        text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        text.textSize = d.dp(14f)
        val label = "Add stickers"
        val bw = text.measureText(label) + d.dp(48f)
        addRect.set(r.right - pad - bw, r.bottom - pad - d.dp(40f), r.right - pad, r.bottom - pad)
        fill.color = if (pressedAdd) c.functionalKeyPressed else c.functionalKey
        canvas.drawRoundRect(addRect, addRect.height() / 2, addRect.height() / 2, fill)
        text.color = c.functionalIcon
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(label, addRect.centerX(), addRect.centerY() + text.textSize * 0.35f, text)
    }

    private fun slotX(i: Int) = when (i) {
        0 -> d.dp(30.4f)
        5 -> width - d.dp(34.7f)
        else -> d.dp(97.5f) + (i - 1) * d.dp(65.5f)
    }

    private fun drawBottomBar(canvas: Canvas, c: KeyboardColors) {
        val top = contentBottom
        val cy = top + bottomH / 2f
        val chip = chipColor(c)
        text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        text.textSize = d.functionTextSize
        text.textAlign = Paint.Align.CENTER
        text.color = c.hintLabel
        if (pressedBottom == 0) { fill.color = chip; canvas.drawCircle(slotX(0), cy, chipR * 1.2f, fill) }
        canvas.drawText("ABC", slotX(0), cy + d.functionTextSize * 0.711f / 2f, text)
        val pw = d.dp(30.2f)
        val ph = d.dp(19.1f)
        for (i in 1..4) {
            fill.color = if (i == 3) c.emojiSelected ?: c.primary else if (pressedBottom == i) KeyboardTheme.blend(chip, c.keyLabel, 0.1f) else chip
            val x = slotX(i)
            canvas.drawRoundRect(x - pw, cy - ph, x + pw, cy + ph, ph, ph, fill)
        }
        fill.color = c.hintLabel
        Icons.draw(canvas, Symbols.EMOJI, d.barIconBox, slotX(1), cy, fill)
        val disabled = KeyboardTheme.blend(chip, c.hintLabel, 0.3f)
        fill.color = disabled
        Icons.draw(canvas, Symbols.GIF_BAR, d.m(107f), slotX(2), cy + d.m(0.6f), fill)
        fill.color = c.emojiSelectedIcon ?: c.popupSelectedLabel
        Icons.draw(canvas, Symbols.STICKER, d.m(72.6f), slotX(3) - d.m(0.5f), cy, fill)
        text.textSize = d.dp(16f)
        text.color = disabled
        canvas.drawText(":-)", slotX(4), cy + d.dp(16f) * 0.711f / 2f, text)
        if (pressedBottom == 5) { fill.color = chip; canvas.drawCircle(slotX(5), cy, chipR * 1.2f, fill) }
        fill.color = c.hintLabel
        Icons.draw(canvas, Symbols.BACKSPACE, d.iconDeleteBox, slotX(5) - d.m(0.6f), cy + d.m(1f), fill)
    }

    /** Thumbnail from the cache, or null while it loads in the background (the view redraws when it arrives). */
    private fun bitmap(s: StickerStore.Sticker): Bitmap? {
        cache.get(s.id)?.let { return it }
        if (loading.add(s.id)) {
            val target = (cell.takeIf { it > 0 } ?: d.dp(94f)).toInt()
            loader.execute {
                val bmp = runCatching {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(s.file.path, o)
                    var sample = 1
                    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
                    BitmapFactory.decodeFile(s.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }.onFailure { android.util.Log.w("PboardStickers", "thumbnail ${s.id}", it) }.getOrNull()
                if (bmp == null) android.util.Log.w("PboardStickers", "no thumbnail for ${s.file} (${s.file.length()} bytes)")
                main.post { if (bmp != null) cache.put(s.id, bmp); loading.remove(s.id); invalidate() }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ touch

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedIndex = -1
    private var pressedHeader = -1
    private var pressedBottom = -1
    private var pressedAdd = false
    private var longFired = false
    private val longPress = Runnable {
        val i = pressedIndex
        if (i >= 0) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            items.getOrNull(i)?.let { listener?.onStickerRemove(it) }
            pressedIndex = -1
            invalidate()
        }
    }
    private val deleteRepeat = object : Runnable {
        override fun run() { listener?.onStickerDelete(); main.postDelayed(this, 50) }
    }

    private fun hitIndex(x: Float, y: Float): Int {
        if (y < contentTop || y > contentBottom) return -1
        for (i in items.indices) if (cellRect(i).contains(x, y)) return i
        return -1
    }

    private fun hitHeader(x: Float, y: Float): Int {
        if (y > headerH) return -1
        for (i in 0 until 2 + tabs.size) if (abs(x - headerX(i)) < d.dp(20f)) return i
        return -1
    }

    private fun hitBottom(x: Float, y: Float): Int {
        if (y < contentBottom) return -1
        return (0..5).minByOrNull { abs(x - slotX(it)) }?.takeIf { abs(x - slotX(it)) < d.dp(32f) } ?: -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y; dragging = false; longFired = false
                pressedIndex = hitIndex(e.x, e.y)
                pressedHeader = hitHeader(e.x, e.y)
                pressedBottom = hitBottom(e.x, e.y)
                pressedAdd = items.isEmpty() && addRect.contains(e.x, e.y)
                if (pressedIndex >= 0) main.postDelayed(longPress, 500)
                if (pressedBottom == 5) { listener?.onStickerDelete(); main.postDelayed(deleteRepeat, 400) }
                if (pressedIndex >= 0 || pressedHeader >= 0 || pressedBottom >= 0 || pressedAdd) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > touchSlop && downY in contentTop..contentBottom) {
                    dragging = true; pressedIndex = -1; main.removeCallbacks(longPress)
                }
                if (dragging) { scrollY = (scrollY - (e.y - lastY)).coerceIn(0f, maxScroll); invalidate() }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress); main.removeCallbacks(deleteRepeat)
                if (!dragging && !longFired) {
                    val i = pressedIndex
                    when {
                        i >= 0 && hitIndex(e.x, e.y) == i -> items.getOrNull(i)?.let { listener?.onSticker(it) }
                        pressedAdd -> listener?.onStickerAdd()
                        pressedHeader == 0 -> listener?.onStickerClose()
                        pressedHeader == 1 -> listener?.onStickerAdd()
                        pressedHeader >= 2 -> { current = pressedHeader - 2; scrollY = 0f }
                        pressedBottom == 0 -> listener?.onStickerClose()
                        pressedBottom == 1 -> listener?.onStickerEmoji()
                    }
                }
                pressedIndex = -1; pressedHeader = -1; pressedBottom = -1; pressedAdd = false; dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress); main.removeCallbacks(deleteRepeat)
                pressedIndex = -1; pressedHeader = -1; pressedBottom = -1; pressedAdd = false; dragging = false
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacksAndMessages(null)
        loader.shutdown()
    }

    @Suppress("unused")
    private val tmp = Rect()
}
