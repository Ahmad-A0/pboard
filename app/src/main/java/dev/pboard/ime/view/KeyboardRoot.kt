package dev.pboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.ime.theme.ThemeImages

/**
 * The IME's input view. It fills the IME window (which is full-height so popups can extend above the keyboard);
 * the keyboard itself ([panel]) sits at the bottom, and [overlay] covers everything for previews and trails.
 * Draws the keyboard background (including behind the navigation bar) and the soft top edge shadow.
 */
@SuppressLint("ViewConstructor")
class KeyboardRoot(context: Context, val d: Dimens) : FrameLayout(context) {
    val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val overlay = OverlayView(context, d)
    var colors: KeyboardColors? = null
        set(value) {
            field = value
            shadowPaint.shader = null
            value?.backgroundImage?.let { ThemeImages.prefetch(context, it) { postInvalidate() } }
            invalidate()
        }

    private val bgPaint = Paint()
    private data class GradientKey(val top: Float, val bottom: Float, val start: Int, val end: Int)
    private var gradientKey: GradientKey? = null
    private var gradientShader: Shader? = null
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageSrc = android.graphics.Rect()
    private val imageDst = android.graphics.RectF()
    private val shadowPaint = Paint()

    // Benchmark idle probe: count every touch the IME window handled (bench build type only).
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (dev.pboard.BuildConfig.BENCH_PROBE) dev.pboard.ime.bench.BenchProbe.onTouch(ev.actionMasked)
        return handled
    }

    /** Bottom inset (navigation bar) the keyboard keeps clear of keys. */
    var bottomInset = 0
        set(value) {
            if (field != value) { field = value; applyPadding() }
        }

    /** Free columns beside the keyboard (one-handed mode); the keyboard and its bar use the width left between. */
    var sideLeft = 0
        private set
    var sideRight = 0
        private set

    fun setSideMargins(left: Int, right: Int) {
        if (left == sideLeft && right == sideRight) return
        sideLeft = left; sideRight = right
        applyPadding()
    }

    /** Empty space kept under the keys (one-handed mode keeps the normal keyboard height). */
    var extraBottom = 0
        set(value) { if (field != value) { field = value; applyPadding() } }

    private fun applyPadding() {
        val f = floating
        if (f != null) panel.setPadding(f.left, f.topPad, f.rightMargin, f.bottomGap + f.handleHeight)
        else panel.setPadding(sideLeft, 0, sideRight, bottomInset + extraBottom)
    }

    /**
     * Floating keyboard: a card from [left] to [rightMargin] px before the right edge, whose bottom sits [bottomGap] px
     * above the window bottom, with a
     * [handleHeight] strip under the keys holding the drag handle and the hide button.
     */
    class Floating(val left: Int, val rightMargin: Int, val bottomGap: Int, val handleHeight: Int, val topPad: Int)

    var floating: Floating? = null
        set(value) { field = value; applyPadding(); requestLayout(); invalidate() }

    interface FloatingListener {
        /** The card was dragged by ([dx], [dy]) px since the last call. */
        fun onFloatDrag(dx: Float, dy: Float)
        fun onFloatDragEnd()
        fun onFloatHide()
    }

    var floatingListener: FloatingListener? = null

    /** The floating card's bounds in this view (null when docked). */
    fun floatingCard(out: android.graphics.Rect): android.graphics.Rect? {
        val f = floating ?: return null
        out.set(f.left, panel.top, width - f.rightMargin, height - f.bottomGap)
        return out
    }

    private val cardRect = android.graphics.RectF()
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawFloating(canvas: Canvas, c: KeyboardColors, f: Floating) {
        cardRect.set(f.left.toFloat(), panel.top.toFloat(), (width - f.rightMargin).toFloat(), (height - f.bottomGap).toFloat())
        val radius = d.m(63f) / d.density * resources.displayMetrics.density
        cardPaint.color = c.background
        cardPaint.setShadowLayer(d.m(21f), 0f, d.m(5f), c.shadow)
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint)
        cardPaint.clearShadowLayer()
        // Drag handle (pill) centred in the strip under the keys, hide chevron at its left.
        val hy = cardRect.bottom - f.handleHeight / 2f
        // Measured (Gboard 15.1, 420 dpi, unscaled): 190x10.5 px handle, the hide chevron 89 px from the card's left.
        val px = resources.displayMetrics.density / Dimens.REF_DENSITY
        cardPaint.color = KeyboardTheme.withAlpha(c.hintLabel, 0.45f)
        val hw = 95f * px
        val hh = 5.25f * px
        canvas.drawRoundRect(cardRect.centerX() - hw, hy - hh, cardRect.centerX() + hw, hy + hh, hh, hh, cardPaint)
        cardPaint.color = c.hintLabel
        Icons.draw(canvas, Symbols.ARROW_DOWN, 63f * px, cardRect.left + 89f * px, hy, cardPaint)
    }

    private var dragLastX = 0f
    private var dragLastY = 0f
    private var floatTouch = 0

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val f = floating ?: return false
        val bottom = height - f.bottomGap
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (e.y < bottom - f.handleHeight || e.y > bottom || e.x < f.left || e.x > width - f.rightMargin) return false
                floatTouch = if (e.x < f.left + d.m(130f)) 2 else 1
                dragLastX = e.rawX; dragLastY = e.rawY
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> if (floatTouch == 1) {
                floatingListener?.onFloatDrag(e.rawX - dragLastX, e.rawY - dragLastY)
                dragLastX = e.rawX; dragLastY = e.rawY
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (floatTouch == 1) floatingListener?.onFloatDragEnd()
                if (floatTouch == 2 && e.x < f.left + d.m(130f)) floatingListener?.onFloatHide()
                floatTouch = 0
            }
            android.view.MotionEvent.ACTION_CANCEL -> floatTouch = 0
        }
        return true
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Window-space top of the keyboard (for insets / touchable region). */
    val keyboardTop: Int get() = panel.top

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        floating?.let { drawFloating(canvas, c, it); return }
        val top = panel.top.toFloat()
        bgPaint.color = bandColor ?: c.background
        // Gradient themes: a vertical gradient over the whole keyboard (top bar to the navigation bar).
        val end = c.backgroundEnd.takeIf { bandColor == null }
        bgPaint.shader = if (end == null) null else {
            val key = GradientKey(top, height.toFloat(), c.background, end)
            if (key != gradientKey) {
                gradientKey = key
                gradientShader = LinearGradient(0f, key.top, 0f, key.bottom, key.start, key.end, Shader.TileMode.CLAMP)
            }
            gradientShader
        }
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), bgPaint)
        if (bandColor == null) c.backgroundImage?.let { drawImage(canvas, c, it, top) }
        val h = d.topShadow
        if (shadowPaint.shader == null || shadowTop != top) {
            shadowTop = top
            val edge = if (c.isDark) SHADOW_EDGE_DARK else SHADOW_EDGE
            shadowPaint.shader = LinearGradient(0f, top - h, 0f, top, 0x00000000, edge, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, top - h, width.toFloat(), top, shadowPaint)
    }

    private var shadowTop = -1f

    /** Image themes: the picture centre-cropped to the keyboard (as in the theme picker), under the theme's scrim. */
    private fun drawImage(canvas: Canvas, c: KeyboardColors, path: String, top: Float) {
        val bmp = ThemeImages.peek(context, path) ?: return
        val w = width.toFloat()
        val h = height - top
        if (w <= 0f || h <= 0f) return
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val sw = (w / scale).toInt().coerceAtMost(bmp.width)
        val sh = (h / scale).toInt().coerceAtMost(bmp.height)
        imageSrc.set((bmp.width - sw) / 2, (bmp.height - sh) / 2, (bmp.width + sw) / 2, (bmp.height + sh) / 2)
        imageDst.set(0f, top, w, height.toFloat())
        canvas.drawBitmap(bmp, imageSrc, imageDst, imagePaint)
        if (c.imageScrim > 0f) {
            bgPaint.shader = null
            bgPaint.color = KeyboardTheme.withAlpha(0xFF000000.toInt(), c.imageScrim)
            canvas.drawRect(imageDst, bgPaint)
        }
    }

    /**
     * Normally the root fills the (full-height) IME window. In fullscreen extract mode it wraps the keyboard: the
     * window-sized children (overlay, pooled press views) must not stretch it to the window height, or the extract
     * editor above gets no room.
     */
    /** Fullscreen extract mode: wrap the keyboard instead of filling the window. */
    var wrapKeyboard = false
        set(value) { if (field != value) { field = value; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!wrapKeyboard || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        measureChild(panel, widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = panel.measuredHeight
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** Called when the root's width changes (rotation, window insets): the keyboard must be rebuilt for it. */
    var onWidthChanged: ((Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) post { onWidthChanged?.invoke(w) }
    }

    /** View filling the free column beside a one-handed keyboard (bar top to keys bottom). */
    var sideView: View? = null
        set(value) {
            field?.let { removeView(it) }
            field = value
            value?.let { addView(it, 1, LayoutParams(0, 0)) }
        }

    /** Resize overlay laid over the keyboard (bar top to keys bottom). */
    var resizeView: View? = null
        set(value) {
            field?.let { removeView(it) }
            field = value
            value?.let { addView(it, childCount - 1, LayoutParams(0, 0)) }
        }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        resizeView?.let { v ->
            val b = panel.bottom - bottomInset
            v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - panel.top, MeasureSpec.EXACTLY))
            v.layout(0, panel.top, width, b)
        }
        sideView?.let { v ->
            val (x, w) = OneHandedLayout.sideColumn(width, sideLeft, sideRight)
            val b = panel.bottom - bottomInset
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - panel.top, MeasureSpec.EXACTLY))
            v.layout(x, panel.top, x + w, b)
        }
        // The background, top shadow and floating card depend only on the panel's bounds: repaint the window when
        // they move, not on every layout pass (one per key press used to repaint everything).
        val sig = intArrayOf(panel.top, panel.bottom, panel.left, panel.right, width, height)
        if (!sig.contentEquals(laidOut)) { laidOut = sig; invalidate() }
    }

    private var laidOut = IntArray(0)

    /** A panel with its own surface colour (Gboard 18.6's emoji panel) colours the navigation band too. */
    var bandColor: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    fun setPanelContent(vararg views: View) {
        bandColor = null
        panel.removeAllViews()
        for (v in views) panel.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    companion object {
        /** ~3.4% black at the keyboard edge, fading out over 8 px (measured on Gboard's light theme). */
        const val SHADOW_EDGE = 0x09000000
        /** Barely visible on the dark theme (one step darker over ~5 px, measured). */
        const val SHADOW_EDGE_DARK = 0x05000000
    }
}
