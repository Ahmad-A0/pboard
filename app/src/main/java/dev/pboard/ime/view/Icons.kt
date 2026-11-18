package dev.pboard.ime.view

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/**
 * Vector icons for the key surface and bars: Material Symbols path data ([Symbols]) plus a few hand-drawn shapes
 * where Gboard's glyph differs from the Material Symbol (shift). Paths are parsed once and cached per size.
 */
object Icons {
    private val parsed = HashMap<String, Path>()
    private val scaled = HashMap<Long, Path>()
    private val tmpBounds = RectF()

    private fun base(data: String): Path = parsed.getOrPut(data) {
        PathParser.createPathFromPathData(data).apply {
            // Material Symbols use the viewport (0,-960)..(960,0).
            transform(Matrix().apply { setTranslate(0f, 960f) })
        }
    }

    /** The symbol scaled so its 960-unit viewport spans [box] pixels, with its origin at the box's top-left. */
    fun path(data: String, box: Float): Path {
        val key = (data.hashCode().toLong() shl 32) or java.lang.Float.floatToIntBits(box).toLong().and(0xffffffffL)
        return scaled.getOrPut(key) {
            Path(base(data)).apply { transform(Matrix().apply { setScale(box / 960f, box / 960f) }) }
        }
    }

    /** Draws a symbol whose viewport spans [box] px, centred on (cx, cy) by its viewport centre. */
    fun draw(canvas: Canvas, data: String, box: Float, cx: Float, cy: Float, paint: Paint) {
        val p = path(data, box)
        canvas.save()
        canvas.translate(cx - box / 2f, cy - box / 2f)
        canvas.drawPath(p, paint)
        canvas.restore()
    }

    /** Draws a symbol centred on (cx, cy) by its ink bounds (for glyphs whose ink isn't centred in the viewport). */
    fun drawInkCentered(canvas: Canvas, data: String, box: Float, cx: Float, cy: Float, paint: Paint) {
        val p = path(data, box)
        @Suppress("DEPRECATION") p.computeBounds(tmpBounds, true)
        canvas.save()
        canvas.translate(cx - tmpBounds.centerX(), cy - tmpBounds.centerY())
        canvas.drawPath(p, paint)
        canvas.restore()
    }

    private val shiftCache = HashMap<Float, Path>()

    /**
     * Gboard's shift arrow: a wide head on a short stem, stroked with rounded joins. Geometry measured relative to
     * the glyph width [w] (58.8 px at 420dpi): head spans the full width, stem 54% of it.
     */
    fun shiftPath(w: Float): Path = shiftCache.getOrPut(w) {
        val s = w / 60f
        Path().apply {
            moveTo(0f, -25.6f * s)
            lineTo(26.8f * s, 1.2f * s)
            lineTo(14.6f * s, 1.2f * s)
            lineTo(14.6f * s, 25.4f * s)
            lineTo(-14.6f * s, 25.4f * s)
            lineTo(-14.6f * s, 1.2f * s)
            lineTo(-26.8f * s, 1.2f * s)
            close()
        }
    }

    fun shiftStroke(w: Float) = 5.6f * w / 60f

    /**
     * Shift states: 0 outline, 1 filled (the filled arrow is drawn at 90% so its outer edge matches Gboard's), 2 locked
     * (a filled arrow lifted up with a bar underneath). Proportions fitted to Gboard 18.6 by tools/icons/fit_shift.py
     * (soft IoU 0.834 / 0.941 / 0.945; the best Material Symbols, shift / shift FILL 1 / shift_lock FILL 1, reach
     * 0.514 / 0.865 / 0.804, so the arrow stays hand-drawn).
     */
    fun drawShift(canvas: Canvas, w: Float, cx: Float, cy: Float, state: Int, paint: Paint) {
        val u = w / 60f
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        canvas.save()
        when (state) {
            2 -> {
                val sw = w * 0.89f
                canvas.translate(cx, cy - 4.5f * u)
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = shiftStroke(sw)
                canvas.drawPath(shiftPath(sw), paint)
                val y = 25.4f * sw / 60f + 13.5f * u
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5.9f * u
                canvas.drawLine(-14.6f * sw / 60f, y, 14.6f * sw / 60f, y, paint)
            }
            1 -> {
                val sw = w * 0.9f
                canvas.translate(cx, cy)
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = shiftStroke(sw)
                canvas.drawPath(shiftPath(sw), paint)
            }
            else -> {
                canvas.translate(cx, cy)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = shiftStroke(w)
                canvas.drawPath(shiftPath(w), paint)
            }
        }
        canvas.restore()
        paint.style = Paint.Style.FILL
    }
}
