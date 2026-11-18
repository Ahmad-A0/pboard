package dev.pboard.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.View
import dev.pboard.ime.layout.Key
import dev.pboard.ime.layout.KeyboardLanguage
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import kotlin.math.ceil

/**
 * Transparent, non-touchable layer spanning the whole IME window. Draws everything that may extend above the
 * keyboard: key preview bubbles, the long-press alternates panel and the gesture trail.
 * Bubbles and the panel are RenderNodes with an outline and 4 dp elevation, so the platform renders real
 * Material shadows and animations only touch node properties (no re-recording).
 * All coordinates are in this view's space (the window).
 */
class OverlayView(context: Context, val d: Dimens) : View(context) {
    lateinit var painter: KeyPainter
    var colors: KeyboardColors? = null

    /** Small-damage previews (their own views); null draws them in this overlay. */
    var pressLayer: PressLayer? = null

    inner class Preview(val key: Key, val label: String, val cx: Float, val cy: Float, val hasMore: Boolean) {
        var dismissAt = 0L
        var bubble: PressLayer.BubbleView? = null
        private val renderNode = RenderNode("preview")

        /** The bubble's node, recorded again if the platform dropped its content (see KeyboardView.onDraw). */
        val node: RenderNode get() = renderNode.also { if (!it.hasDisplayList()) record(it) }

        private fun record(n: RenderNode) {
            val c = colors ?: return
            val r = d.previewDiameter / 2f
            val size = ceil(d.previewDiameter).toInt()
            n.setPosition((cx - r).toInt(), (cy - r).toInt(), (cx - r).toInt() + size, (cy - r).toInt() + size)
            n.setOutline(Outline().apply { setOval(0, 0, size, size) })
            n.setClipToOutline(false)
            n.elevation = d.popupElevation
            n.setAmbientShadowColor(c.shadow)
            n.setSpotShadowColor(c.shadow)
            val canvas = n.beginRecording()
            val ox = cx - r - (cx - r).toInt()
            val oy = cy - r - (cy - r).toInt()
            canvas.translate(ox, oy)
            fill.color = c.previewBackground
            canvas.drawCircle(r, r, r, fill)
            text.typeface = painter.typeface
            text.color = c.previewLabel
            text.textSize = d.previewTextSize
            val m = capOrX(label, text)
            canvas.drawText(label, r, r + m / 2f, text)
            if (hasMore) {
                // "..." under the letter: the key has long-press alternates.
                fill.color = c.previewLabel
                val dy = r + d.m(55f)
                val gap = d.m(9f)
                val dot = d.m(2.6f)
                for (i in -1..1) canvas.drawCircle(r + i * gap, dy, dot, fill)
            }
            n.endRecording()
        }
    }

    private val previews = ArrayList<Preview>(4)
    var panel: PopupPanel? = null
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showPreview(key: Key, label: String, cx: Float, cy: Float, hasMore: Boolean = false): Preview {
        val layer = pressLayer
        if (layer != null) {
            // The overlay itself is not touched: only the bubble's own bounds repaint.
            return Preview(key, label, cx, cy, hasMore).also { it.bubble = layer.showBubble(key, label, hasMore, cx, cy) }
        }
        previews.removeAll { it.key === key }
        val p = Preview(key, label, cx, cy, hasMore)
        previews += p
        invalidate()
        return p
    }

    fun dismissPreview(p: Preview?, delayMs: Long = PREVIEW_LINGER_MS) {
        if (p == null) return
        p.bubble?.let { b -> if (b.owner === p.key) pressLayer?.hideBubble(b); return }
        if (delayMs <= 0L) {
            previews.remove(p)
            invalidate()
            return
        }
        p.dismissAt = SystemClock.uptimeMillis() + delayMs
        postInvalidateOnAnimation()
    }

    fun clearPreviews() {
        pressLayer?.hideAllBubbles()
        if (previews.isEmpty()) return
        previews.clear()
        invalidate()
    }

    // --- long-press panel ---
    private val panelNode = RenderNode("panel")
    private var panelShownAt = 0L
    private var closingAt = 0L
    private var closing = false

    fun showPanel(p: PopupPanel, @Suppress("UNUSED_PARAMETER") key: Key) {
        panel = p
        panelShownAt = SystemClock.uptimeMillis()
        closing = false
        recordPanel(p)
        invalidate()
    }

    fun hidePanel() {
        if (panel != null) {
            closing = true
            closingAt = SystemClock.uptimeMillis()
        }
        panel = null
        invalidate()
    }

    fun panelChanged() {
        panel?.let { recordPanel(it) }
        invalidate()
    }

    private fun recordPanel(p: PopupPanel) {
        val c = colors ?: return
        val single = p.items.size == 1
        // A single alternate (q's 1) is the one-cell panel drawn as a circle: Gboard 18.6 at 440 dpi, 166 px (40 dp
        // cell + 10 dp padding each side), bottom 6 dp above the key, slid flush with the screen edge on q / p.
        val bounds = RectF(p.rect)
        val l = bounds.left.toInt()
        val t = bounds.top.toInt()
        val w = ceil(bounds.right).toInt() - l
        val h = ceil(bounds.bottom).toInt() - t
        panelNode.setPosition(l, t, l + w, t + h)
        val outline = Outline()
        if (single) outline.setOval(0, 0, w, h) else outline.setRoundRect(0, 0, w, h, d.popupRadius)
        panelNode.setOutline(outline)
        panelNode.elevation = d.popupElevation
        panelNode.setAmbientShadowColor(c.shadow)
        panelNode.setSpotShadowColor(c.shadow)
        // Scale/fade about the default item (the one above the key).
        val anchor = p.cells.getOrNull(0)
        panelNode.pivotX = (anchor?.centerX() ?: bounds.centerX()) - l
        panelNode.pivotY = bounds.bottom - t
        val canvas = panelNode.beginRecording()
        canvas.translate(-l.toFloat(), -t.toFloat())
        fill.color = c.popupBackground
        if (single) canvas.drawOval(bounds, fill) else canvas.drawRoundRect(bounds, d.popupRadius, d.popupRadius, fill)
        for (i in p.items.indices) drawItem(canvas, p, i, c)
        panelNode.endRecording()
    }

    // --- gesture trail ---
    private val trailX = FloatArray(MAX_TRAIL)
    private val trailY = FloatArray(MAX_TRAIL)
    private val trailT = LongArray(MAX_TRAIL)
    private var trailStart = 0
    private var trailCount = 0
    private var trailActive = false

    fun trailBegin() { trailStart = 0; trailCount = 0; trailActive = true }

    fun trailAdd(x: Float, y: Float, t: Long = SystemClock.uptimeMillis()) {
        val i = (trailStart + trailCount) % MAX_TRAIL
        trailX[i] = x; trailY[i] = y; trailT[i] = t
        if (trailCount < MAX_TRAIL) trailCount++ else trailStart = (trailStart + 1) % MAX_TRAIL
        postInvalidateOnAnimation()
    }

    fun trailEnd() { trailActive = false; postInvalidateOnAnimation() }

    // --- toolbar customisation: the tile lifted under the finger ---
    private var ghostSymbol: String? = null
    private var ghostX = 0f
    private var ghostY = 0f
    private val ghostRect = RectF()

    /** Shows (or moves) the dragged toolbar tile centred at overlay (x, y); null [symbol] hides it. */
    fun setDragGhost(symbol: String?, x: Float, y: Float) {
        ghostSymbol = symbol; ghostX = x; ghostY = y
        invalidate()
    }

    private fun drawGhost(canvas: Canvas, c: KeyboardColors) {
        val sym = ghostSymbol ?: return
        // Measured: a 270x163 px tile (1.3x a grid tile), no shadow, the icon at its normal size.
        val w = d.m(208f) * GHOST_SCALE / 2f
        val h = d.m(126f) * GHOST_SCALE / 2f
        ghostRect.set(ghostX - w, ghostY - h, ghostX + w, ghostY + h)
        fill.color = c.key
        canvas.drawRoundRect(ghostRect, d.m(31.5f) * GHOST_SCALE, d.m(31.5f) * GHOST_SCALE, fill)
        fill.color = c.barIcon
        Icons.draw(canvas, sym, d.barIconBox, ghostX, ghostY, fill)
    }

    // --- tooltip bubble above the keyboard ("Drag here to replace" over the power key) ---
    private var tipText: String? = null
    private var tipAnchorX = 0f
    private var tipBottom = 0f
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val tipPath = android.graphics.Path()

    /** Shows [text] in a tertiary-container pill whose pointer touches ([anchorX], [bottom]); null hides it. */
    fun setTooltip(text: String?, anchorX: Float = 0f, bottom: Float = 0f) {
        tipText = text; tipAnchorX = anchorX; tipBottom = bottom
        invalidate()
    }

    private fun drawTooltip(canvas: Canvas, c: KeyboardColors) {
        val text = tipText ?: return
        // Measured on Gboard 15.1 (420 dpi): 100 px pill, 45 px side padding, 16 sp text, 19 px pointer.
        tipPaint.textSize = d.m(42f)
        tipPaint.typeface = painter.typeface
        val w = tipPaint.measureText(text) + 2 * d.m(45f)
        val h = d.m(100f)
        val pointer = d.m(19f)
        val bottom = tipBottom - pointer
        val right = minOf(width - d.m(6f), tipAnchorX + w / 2f).coerceAtLeast(w + d.m(6f))
        val left = right - w
        fill.color = c.tooltip
        canvas.drawRoundRect(left, bottom - h, right, bottom, h / 2f, h / 2f, fill)
        tipPath.reset()
        tipPath.moveTo(tipAnchorX - pointer * 0.8f, bottom - 1f)
        tipPath.lineTo(tipAnchorX, tipBottom)
        tipPath.lineTo(tipAnchorX + pointer * 0.8f, bottom - 1f)
        tipPath.close()
        canvas.drawPath(tipPath, fill)
        tipPaint.color = c.tooltipText
        tipPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, left + d.m(45f), bottom - h / 2f + tipPaint.textSize * 0.36f, tipPaint)
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        val now = SystemClock.uptimeMillis()
        var animating = drawTrail(canvas, c, now)
        drawTooltip(canvas, c)
        drawGhost(canvas, c)
        if (!canvas.isHardwareAccelerated) return
        canvas.enableZ()
        val it = previews.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.dismissAt != 0L && now >= p.dismissAt) { it.remove(); continue }
            if (p.dismissAt != 0L) animating = true
            canvas.drawRenderNode(p.node)
        }
        if (panel != null) {
            // Show: scale + fade in from 0 over 40 ms, decelerating.
            val f = ((now - panelShownAt) / PANEL_IN_MS.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - f) * (1f - f)
            if (f < 1f) animating = true
            setPanelProgress(e)
            if (!panelNode.hasDisplayList()) panel?.let { recordPanel(it) }
            canvas.drawRenderNode(panelNode)
        } else if (closing) {
            // Hide: 20 ms, decelerating.
            val f = ((now - closingAt) / PANEL_OUT_MS.toFloat()).coerceIn(0f, 1f)
            if (f >= 1f) closing = false else {
                animating = true
                setPanelProgress((1f - f) * (1f - f))
                if (panelNode.hasDisplayList()) canvas.drawRenderNode(panelNode)
            }
        }
        canvas.disableZ()
        if (animating) postInvalidateOnAnimation()
    }

    private fun setPanelProgress(t: Float) {
        panelNode.scaleX = t
        panelNode.scaleY = t
        panelNode.alpha = t
    }

    private val tmp = android.graphics.Rect()

    private fun capOrX(label: String, paint: Paint): Float {
        val lower = label.codePointCount(0, label.length) == 1 && Character.isLowerCase(label.codePointAt(0))
        paint.getTextBounds(if (lower) "x" else "H", 0, 1, tmp)
        return -tmp.top.toFloat()
    }

    private fun drawItem(canvas: Canvas, p: PopupPanel, i: Int, c: KeyboardColors) {
        val cell = p.cells[i]
        val cx = cell.centerX()
        val cy = cell.centerY()
        val selected = i == p.selected
        if (selected) {
            fill.color = c.popupSelected
            canvas.drawCircle(cx, cy, d.popupSelectedDiameter / 2f, fill)
        }
        val item = p.items[i]
        val color = if (selected) c.popupSelectedLabel else c.keyLabel
        when (item) {
            KeyboardLanguage.POPUP_SETTINGS -> {
                fill.color = color
                Icons.drawInkCentered(canvas, Symbols.SETTINGS, d.iconEmojiBox * 0.8f, cx, cy, fill)
            }
            KeyboardLanguage.POPUP_ONE_HANDED -> {
                fill.color = color
                Icons.drawInkCentered(canvas, Symbols.KEYBOARD, d.iconEmojiBox * 0.8f, cx, cy, fill)
            }
            else -> {
                text.typeface = painter.typeface
                text.color = color
                // Gboard 18.6 draws the selected label a third larger (à 61 px tall vs á 46 px at 440 dpi: 28 vs 21 dp).
                val size = if (selected) d.popupTextSize * SELECTED_LABEL_SCALE else d.popupTextSize
                text.textSize = if (item.length > 2) size * 0.6f else size
                val m = capOrX(item, text)
                canvas.drawText(item, cx, cy + m / 2f, text)
            }
        }
    }

    /** Draws the trail; returns true while it still needs animation frames (fading). */
    private fun drawTrail(canvas: Canvas, c: KeyboardColors, now: Long): Boolean {
        while (trailCount > 0 && now - trailT[trailStart] > TRAIL_FADE_MS) {
            trailStart = (trailStart + 1) % MAX_TRAIL
            trailCount--
        }
        if (trailCount < 2) return trailActive || trailCount > 0
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (n in 0 until trailCount) {
            val i = (trailStart + n) % MAX_TRAIL
            l = minOf(l, trailX[i]); r = maxOf(r, trailX[i]); t = minOf(t, trailY[i]); b = maxOf(b, trailY[i])
        }
        // The trail's opacity is drawn as grey levels on an opaque black layer: every segment is opaque, so a newer
        // segment simply replaces what is under it (no darker spots where segments, caps or crossings overlap), and
        // edges keep normal antialiasing. On compositing, a colour matrix turns the grey level into the trail colour's
        // alpha, so black becomes transparent.
        if (trailFilterColor != c.gestureTrail) {
            trailFilterColor = c.gestureTrail
            val cm = android.graphics.ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, android.graphics.Color.red(c.gestureTrail).toFloat(),
                0f, 0f, 0f, 0f, android.graphics.Color.green(c.gestureTrail).toFloat(),
                0f, 0f, 0f, 0f, android.graphics.Color.blue(c.gestureTrail).toFloat(),
                1f, 0f, 0f, 0f, 0f,
            ))
            trailLayerPaint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        val pad = d.trailWidth
        val layer = canvas.saveLayer(l - pad, t - pad, r + pad, b + pad, trailLayerPaint)
        canvas.drawColor(android.graphics.Color.BLACK)
        for (n in 1 until trailCount) {
            val i0 = (trailStart + n - 1) % MAX_TRAIL
            val i1 = (trailStart + n) % MAX_TRAIL
            val f = 1f - (now - trailT[i1]).coerceAtLeast(0L) / TRAIL_FADE_MS.toFloat()
            if (f <= 0f) continue
            val a = (TRAIL_ALPHA * f).toInt().coerceIn(0, 255)
            trailPaint.color = android.graphics.Color.rgb(a, a, a)
            trailPaint.strokeWidth = d.trailWidth * f
            canvas.drawLine(trailX[i0], trailY[i0], trailX[i1], trailY[i1], trailPaint)
        }
        canvas.restoreToCount(layer)
        return true
    }

    private val trailLayerPaint = Paint()
    private var trailFilterColor = 0

    companion object {
        const val PREVIEW_LINGER_MS = 0L
        const val MAX_TRAIL = 1024
        const val TRAIL_FADE_MS = 1000L
        /** Gboard 18.6 draws the trail at 80 % opacity (15.1: 250/255). */
        const val TRAIL_ALPHA = 204
        const val SELECTED_LABEL_SCALE = 4f / 3f
        const val PANEL_IN_MS = 40L
        const val PANEL_OUT_MS = 20L
        /** The lifted toolbar tile is drawn a little larger than in the grid. */
        const val GHOST_SCALE = 1.3f
    }
}
