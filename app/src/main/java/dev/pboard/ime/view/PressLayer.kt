package dev.pboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import dev.pboard.ime.layout.Key
import dev.pboard.ime.theme.Dimens
import kotlin.math.ceil

/**
 * Touch feedback drawn in small views of their own so a key press only repaints the pressed key and its preview
 * bubble. A pressed key drawn into the keyboard's own display list (or a bubble into the window-sized overlay) made
 * every press re-render the whole key area or the whole window: on software GPUs (the emulator) that alone cost
 * 10-40 ms per frame. Views are pooled and shown/hidden with alpha and translation, which are render-node property
 * changes: the damage is just the view's bounds and nothing else is re-recorded.
 */
class PressLayer(private val root: FrameLayout, private val d: Dimens) {
    lateinit var painter: KeyPainter

    // ------------------------------------------------------------------ pressed key caps

    @SuppressLint("ViewConstructor")
    inner class CapView(context: Context) : View(context) {
        var key: Key? = null
        var lifted = false
        private val pad = 2
        private var capW = 1
        private var capH = 1

        init { alpha = 0f }

        // Sized by its key, not by layout params: resizing never requests a layout of the window (which would
        // repaint everything).
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(capW, capH)

        fun bind(k: Key, lifted: Boolean, originX: Float, originY: Float) {
            val changed = key !== k || this.lifted != lifted
            key = k
            this.lifted = lifted
            val w = ceil(k.rect.width()).toInt() + 2 * pad
            val h = ceil(k.rect.height()).toInt() + 2 * pad
            if (capW != w || capH != h) {
                capW = w; capH = h
                measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                layout(0, 0, w, h)
            }
            translationX = originX + k.rect.left - pad
            translationY = originY + k.rect.top - pad
            if (changed) invalidate()
            alpha = 1f
        }

        override fun onDraw(canvas: Canvas) {
            val k = key ?: return
            canvas.translate(pad - k.rect.left, pad - k.rect.top)
            if (lifted) {
                // The key "lifts" into its preview: the cap disappears and the label fades.
                painter.drawBackground(canvas, k, painter.colors.background)
                painter.drawForeground(canvas, k, alpha = KeyboardView.LIFTED_LABEL_ALPHA)
            } else {
                painter.drawKey(canvas, k, pressed = true)
            }
        }
    }

    private val caps = ArrayList<CapView>()

    /** Shows [key] pressed (or lifted into its preview) over the keyboard view whose top-left is at ([ox], [oy]). */
    fun showCap(key: Key, lifted: Boolean, ox: Float, oy: Float) {
        val v = caps.firstOrNull { it.key === key } ?: caps.firstOrNull { it.alpha == 0f } ?: newCap()
        v.bind(key, lifted, ox, oy)
    }

    fun hideCap(key: Key) { caps.firstOrNull { it.key === key }?.alpha = 0f }

    fun hideAllCaps() { for (c in caps) c.alpha = 0f }

    private fun newCap(): CapView = CapView(root.context).also {
        caps += it
        // Above the keyboard panel, below the bubbles and the overlay.
        root.addView(it, 1, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.START,
        ))
    }

    // ------------------------------------------------------------------ preview bubbles

    @SuppressLint("ViewConstructor")
    inner class BubbleView(context: Context) : View(context) {
        var label = ""
        var hasMore = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val tmp = Rect()
        var owner: Any? = null

        init {
            alpha = 0f
            elevation = d.popupElevation
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
        }

        fun bind(label: String, hasMore: Boolean, cx: Float, cy: Float) {
            val size = ceil(d.previewDiameter).toInt()
            if (width != size) {
                measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
                layout(0, 0, size, size)
            }
            val c = painter.colors
            outlineAmbientShadowColor = c.shadow
            outlineSpotShadowColor = c.shadow
            if (label != this.label || hasMore != this.hasMore) {
                this.label = label
                this.hasMore = hasMore
                invalidate()
            }
            translationX = cx - size / 2f
            translationY = cy - size / 2f
            alpha = 1f
        }

        override fun onDraw(canvas: Canvas) {
            val c = painter.colors
            val r = width / 2f
            fill.color = c.previewBackground
            canvas.drawCircle(r, r, r, fill)
            text.typeface = painter.typeface
            text.color = c.previewLabel
            text.textSize = d.previewTextSize
            val lower = label.codePointCount(0, label.length) == 1 && Character.isLowerCase(label.codePointAt(0))
            text.getTextBounds(if (lower) "x" else "H", 0, 1, tmp)
            canvas.drawText(label, r, r - tmp.top / 2f, text)
            if (hasMore) {
                // "..." under the letter: the key has long-press alternates.
                fill.color = c.previewLabel
                val dy = r + d.m(55f)
                val gap = d.m(9f)
                val dot = d.m(2.6f)
                for (i in -1..1) canvas.drawCircle(r + i * gap, dy, dot, fill)
            }
        }
    }

    private val bubbles = ArrayList<BubbleView>()

    /** Shows a preview bubble centred at root coordinates ([cx], [cy]); returns it as the handle to hide it with. */
    fun showBubble(owner: Any, label: String, hasMore: Boolean, cx: Float, cy: Float): BubbleView {
        val v = bubbles.firstOrNull { it.owner === owner } ?: bubbles.firstOrNull { it.alpha == 0f } ?: newBubble()
        v.owner = owner
        v.bind(label, hasMore, cx, cy)
        return v
    }

    fun hideBubble(v: BubbleView?) { v?.alpha = 0f; v?.owner = null }

    fun hideAllBubbles() { for (b in bubbles) { b.alpha = 0f; b.owner = null } }

    private fun newBubble(): BubbleView = BubbleView(root.context).also {
        bubbles += it
        // Above the caps; the overlay (trail, long-press panel) stays last.
        val size = ceil(d.previewDiameter).toInt()
        root.addView(it, root.childCount - 1, FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.START))
    }
}
