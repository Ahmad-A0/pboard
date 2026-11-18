package dev.pboard.ime.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import kotlin.math.roundToInt

/**
 * Inline autofill chips laid over the suggestion strip (Gboard 18.6, measured at 420 dpi): the features button stays
 * visible on the left; the chips scroll horizontally in a viewport inset 134 px (51 dp) from both edges of the strip,
 * centred when they fit; a 1 dp divider as tall as a chip marks content hidden past either edge; pinned chips (the
 * autofill service's own button) sit right of the viewport in the voice key's place, which they replace. Without a
 * pinned chip the voice key stays visible.
 *
 * Touches outside the chips fall through to the bar underneath (the features button).
 */
@SuppressLint("ViewConstructor")
class InlineStripView(context: Context, private val d: Dimens, private val c: KeyboardColors) : FrameLayout(context) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val scroll = object : HorizontalScrollView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            clipChips()
            this@InlineStripView.invalidate()
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val pinnedBox = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // Measured: the pinned icon is centred 69 px (420 dpi) from the right edge: the chip ends 6 dp before it.
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(0, 0, (6 * context.resources.displayMetrics.density).roundToInt(), 0)
    }
    private val paint = Paint()

    /** Left and right edge of the scrolling viewport (Gboard: 134 px at 420 dpi on each side). */
    private val viewportInset get() = d.m(134f).roundToInt()
    private val dividerWidth get() = d.dividerWidth.roundToInt().coerceAtLeast(1)

    init {
        setWillNotDraw(false)
        clipChildren = true
        addView(scroll)
        addView(pinnedBox)
    }

    val hasPinned get() = pinnedBox.childCount > 0

    fun setChips(chips: List<View>, pinned: List<View>, rtl: Boolean) {
        row.removeAllViews()
        pinnedBox.removeAllViews()
        row.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        for (v in chips) { detach(v); row.addView(v, params(v)) }
        for (v in pinned) { detach(v); pinnedBox.addView(v, params(v)) }
        scroll.scrollTo(if (rtl) Int.MAX_VALUE else 0, 0)
        requestLayout()
        invalidate()
    }

    private fun detach(v: View) { (v.parent as? android.view.ViewGroup)?.removeView(v) }

    /** The platform gives each inflated chip layout params with the size its content measured to; keep that size. */
    private fun params(v: View): LinearLayout.LayoutParams {
        val lp = v.layoutParams
        val w = lp?.width?.takeIf { it > 0 } ?: LinearLayout.LayoutParams.WRAP_CONTENT
        val h = lp?.height?.takeIf { it > 0 } ?: LinearLayout.LayoutParams.MATCH_PARENT
        return LinearLayout.LayoutParams(w, h)
    }

    /** Screen-space bounds of the first chip (for the tooltip's pointer), or null. */
    fun firstChipLeftInWindow(): Int? {
        val v = row.getChildAt(0) ?: return null
        val loc = IntArray(2); v.getLocationInWindow(loc)
        return loc[0]
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = d.barHeight.roundToInt()
        val vp = (w - 2 * viewportInset).coerceAtLeast(0)
        scroll.measure(MeasureSpec.makeMeasureSpec(vp, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val pw = (viewportInset - dividerWidth).coerceAtLeast(0)
        pinnedBox.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val vi = viewportInset
        scroll.layout(vi, 0, w - vi, scroll.measuredHeight)
        val pl = w - vi + dividerWidth
        pinnedBox.layout(pl, 0, pl + pinnedBox.measuredWidth, pinnedBox.measuredHeight)
        clipChips()
        invalidate() // the overflow dividers depend on the laid-out content width
    }

    private val clip = android.graphics.Rect()

    /**
     * The chips are surfaces drawn on top of the keyboard window, so the scroll view's clipping does not reach them:
     * crop each one's surface to the viewport (as Gboard's chips are cut at the viewport's edges).
     */
    private fun clipChips() {
        val left = scroll.scrollX
        val right = left + scroll.width
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i)
            val l = (left - chip.left).coerceIn(0, chip.width)
            val r = (right - chip.left).coerceIn(0, chip.width)
            clip.set(l, 0, r, chip.height)
            val surface = (chip as? android.view.ViewGroup)?.getChildAt(0) as? android.view.SurfaceView
            if (r <= l) chip.visibility = View.INVISIBLE else {
                chip.visibility = View.VISIBLE
                surface?.clipBounds = if (l == 0 && r == chip.width) null else android.graphics.Rect(clip)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Cover the toolbar icons under the chips (the chips' own margins are transparent); the voice key stays
        // visible unless a pinned chip takes its place.
        val vi = viewportInset
        val dw = dividerWidth
        paint.color = c.background
        val coverRight = if (hasPinned) width else width - vi + dw
        canvas.drawRect((vi - dw).toFloat(), 0f, coverRight.toFloat(), height.toFloat(), paint)
        val content = row.width
        val vp = scroll.width
        if (content <= vp) return
        // Measured divider colour: on_surface over the background at 14 % (light) / 34 % (dark), not outline_variant.
        paint.color = dev.pboard.ime.theme.KeyboardTheme.blend(c.background, c.keyLabel, if (c.isDark) 0.34f else 0.14f)
        val ch = d.barButtonDiameter
        val top = (d.barCenterY - ch / 2f).roundToInt().toFloat()
        val bottom = top + ch.roundToInt()
        if (scroll.scrollX > 0) canvas.drawRect((vi - dw).toFloat(), top, vi.toFloat(), bottom, paint)
        if (scroll.scrollX + vp < content) canvas.drawRect((width - vi).toFloat(), top, (width - vi + dw).toFloat(), bottom, paint)
    }

    /** Touches on empty parts (left of the viewport) go to the bar below. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val x = ev.x.roundToInt()
            val inScroll = x >= viewportInset && x < width - viewportInset
            val inPinned = hasPinned && x >= width - viewportInset
            if (!inScroll && !inPinned) return false
        }
        return super.dispatchTouchEvent(ev)
    }
}
