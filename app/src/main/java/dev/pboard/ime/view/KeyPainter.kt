package dev.pboard.ime.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import dev.pboard.ime.layout.Codes
import dev.pboard.ime.layout.EnterAction
import dev.pboard.ime.layout.Key
import dev.pboard.ime.layout.KeyIcon
import dev.pboard.ime.layout.KeyShape
import dev.pboard.ime.layout.KeyStyle
import dev.pboard.ime.layout.LabelSize
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors

/** Draws individual keys (background, label, hint, icon). Shared by the key surface and the popups. */
/** Key fills with key borders off, as Gboard 18.6 draws them (measured on Black, Cyan, Default, Dynamic Color). */
object BorderlessFill {
    /** The fill of a key at rest ([rest] = its bordered fill): 0 for no cap. */
    fun of(style: KeyStyle, code: Int, rest: Int, c: KeyboardColors): Int = when {
        style == KeyStyle.ACCENT -> rest
        code == Codes.SPACE -> c.spaceBar
        code == Codes.SYMBOLS || code == Codes.ALPHA -> c.borderlessSymbolKey
        else -> 0
    }
}

class KeyPainter(var colors: KeyboardColors, val d: Dimens) {
    var typeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private set
    private var fontVariation: String? = null

    /** Debug/tuning hook: swaps the label typeface (and optional font variation settings). */
    fun setTypeface(tf: Typeface, variation: String?) {
        typeface = tf
        fontVariation = variation
        labelPaint.typeface = tf
        labelPaint.fontVariationSettings = variation
        hintPaint.fontVariationSettings = variation
        metricCache.clear()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = this@KeyPainter.typeface
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = this@KeyPainter.typeface
        textAlign = Paint.Align.LEFT
        textSize = d.hintSize
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    /** Ascent metrics per text size: x-height and cap-height, measured from the font. */
    private val metricCache = HashMap<Float, FloatArray>()

    private fun metrics(size: Float): FloatArray = metricCache.getOrPut(size) {
        val p = Paint(labelPaint).apply { textSize = size }
        p.getTextBounds("x", 0, 1, bounds)
        val xh = -bounds.top.toFloat()
        p.getTextBounds("H", 0, 1, bounds)
        val ch = -bounds.top.toFloat()
        floatArrayOf(xh, ch)
    }

    fun clearMetrics() = metricCache.clear()

    var keyBorders = true
    var shiftState = 0
    var enterAction = EnterAction.RETURN
    /** Label shown on the space bar (language name), or null. */
    var spaceLabel: String? = null
    var spaceLabelAlpha = 0f

    fun textSize(key: Key): Float = when (key.spec.labelSize) {
        LabelSize.LETTER -> d.letterSize
        LabelSize.PUNCTUATION -> d.punctuationSize
        LabelSize.FUNCTION_TEXT -> d.functionTextSize
        LabelSize.NUMPAD -> d.numpadSize
        LabelSize.STACKED -> d.stackedSize
        LabelSize.NUMBER_ROW -> d.numberRowSize
    }

    fun background(key: Key, pressed: Boolean): Int {
        val c = colors
        return when (key.spec.style) {
            KeyStyle.NORMAL -> if (pressed) c.keyPressed else c.key
            KeyStyle.FUNCTIONAL -> if (pressed) c.functionalKeyPressed else c.functionalKey
            KeyStyle.ACCENT -> if (pressed) c.enterKeyPressed else c.enterKey
            KeyStyle.PLAIN -> if (pressed) c.keyPressed else 0
        }
    }

    fun drawBackground(canvas: Canvas, key: Key, color: Int, rect: RectF = key.rect) {
        // Key borders off: keys at rest lose their caps except the space bar, ?123 / ABC and enter (Gboard).
        val atRest = color == colors.key || color == colors.functionalKey
        val fill = if (!keyBorders && atRest) BorderlessFill.of(key.spec.style, key.spec.code, color, colors) else color
        drawFill(canvas, key, fill, rect)
    }

    private fun drawFill(canvas: Canvas, key: Key, color: Int, rect: RectF) {
        if (color == 0) return
        // A lifted key is erased with the background colour, which a gradient or picture has no single value of.
        if (color == colors.background && !colors.solidBackground) return
        bgPaint.color = color
        val r = if (key.spec.shape == KeyShape.PILL) rect.height() / 2f else d.keyRadius
        canvas.drawRoundRect(rect, r, r, bgPaint)
    }

    fun foreground(key: Key): Int = when (key.spec.style) {
        KeyStyle.FUNCTIONAL -> colors.functionalIcon
        KeyStyle.ACCENT -> colors.enterIcon
        else -> colors.keyLabel
    }

    /** Draws label/icon/hint of [key]; [alpha] scales the foreground (used for the "lifted" state). */
    fun drawForeground(canvas: Canvas, key: Key, alpha: Int = 255, rect: RectF = key.rect) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val fg = foreground(key)
        when (key.spec.icon) {
            KeyIcon.SHIFT -> {
                iconPaint.color = fg; iconPaint.alpha = alpha
                Icons.drawShift(canvas, d.iconShiftWidth, cx + d.iconShiftDx, cy - d.m(0.5f), shiftState, iconPaint)
                return
            }
            KeyIcon.DELETE -> { icon(canvas, Symbols.BACKSPACE, d.iconDeleteBox, cx + d.iconDeleteDx, cy, fg, alpha); return }
            KeyIcon.ENTER -> {
                val sym = when (enterAction) {
                    EnterAction.RETURN -> Symbols.RETURN
                    EnterAction.DONE -> Symbols.CHECK
                    EnterAction.SEARCH -> Symbols.ENTER_SEARCH
                    EnterAction.SEND -> Symbols.SEND
                    EnterAction.GO -> Symbols.ENTER_GO
                    EnterAction.NEXT -> Symbols.TAB
                    EnterAction.PREVIOUS -> Symbols.TAB_RTL
                }
                icon(canvas, sym, d.iconEnterBox, cx + d.iconEnterDx, cy, fg, alpha)
                return
            }
            KeyIcon.EMOJI -> { icon(canvas, Symbols.EMOJI, d.iconEmojiBox, cx, cy + d.iconEmojiDy, fg, alpha); return }
            KeyIcon.GLOBE -> { icon(canvas, Symbols.LANGUAGE, d.iconEmojiBox, cx, cy, fg, alpha); return }
            KeyIcon.SPACE -> {
                if (key.spec.style == KeyStyle.FUNCTIONAL || key.spec.shape == KeyShape.PILL) drawSpaceGlyph(canvas, cx, cy, fg, alpha)
                else spaceLabel?.let { label ->
                    if (spaceLabelAlpha > 0f) {
                        labelPaint.color = colors.hintLabel
                        labelPaint.alpha = (alpha * spaceLabelAlpha).toInt()
                        labelPaint.textSize = d.functionTextSize * 0.8f
                        val m = metrics(labelPaint.textSize)
                        canvas.drawText(label, cx, cy + m[1] / 2f, labelPaint)
                    }
                }
                return
            }
            KeyIcon.NONE -> {}
        }
        val label = key.label(shiftState != 0)
        if (label.isEmpty()) return
        val size = textSize(key)
        labelPaint.color = fg
        labelPaint.alpha = alpha
        labelPaint.textSize = size
        val fnText = key.spec.labelSize == LabelSize.FUNCTION_TEXT
        labelPaint.typeface = if (fnText) Typeface.create(typeface, d.functionTextWeight, false) else typeface
        labelPaint.letterSpacing = if (fnText) d.functionTextSpacing else 0f
        val m = metrics(size)
        if (key.spec.labelSize == LabelSize.STACKED) {
            val lines = label.split('\n')
            val lh = m[1] * 1.25f
            var y = cy - (lines.size - 1) * lh / 2f + m[1] / 2f
            labelPaint.letterSpacing = 0.12f
            for (l in lines) { canvas.drawText(l, cx, y, labelPaint); y += lh }
            labelPaint.letterSpacing = 0f
            return
        }
        val sub = key.spec.subLabel
        if (key.spec.labelDx != 0f) {
            // Phone pad: the digit sits left of centre with its letters to the right (Gboard).
            val dcx = cx + d.m(key.spec.labelDx)
            canvas.drawText(label, dcx, cy + m[1] / 2f, labelPaint)
            if (sub != null) {
                labelPaint.getTextBounds(label, 0, label.length, bounds)
                val right = dcx + bounds.width() / 2f
                hintPaint.textSize = d.numpadSubSize
                hintPaint.typeface = typeface
                hintPaint.color = colors.keyLabel
                hintPaint.alpha = alpha
                canvas.drawText(sub, right + d.m(38f), cy + metrics(d.numpadSubSize)[1] / 2f, hintPaint)
            }
            return
        }
        // Lower-case letters are centred on their x-height, everything else on the cap height (as Gboard does).
        val single = label.codePointCount(0, label.length) == 1
        val lower = single && Character.isLowerCase(label.codePointAt(0))
        val dy = when (key.spec.labelSize) {
            LabelSize.FUNCTION_TEXT -> d.functionTextDy
            LabelSize.PUNCTUATION -> d.punctuationDy
            LabelSize.LETTER -> d.letterDy
            else -> 0f
        }
        // Smaller size sets keep lower-case letters, punctuation and function labels at the reference set's baseline
        // offset; capitals and digits stay centred on their own cap height (measured on the 18.6 phone).
        val scale = when {
            key.spec.labelSize == LabelSize.FUNCTION_TEXT -> d.functionBaselineScale
            lower || key.spec.labelSize == LabelSize.PUNCTUATION -> d.letterBaselineScale
            else -> 1f
        }
        val baseline = cy + (if (lower) m[0] else m[1]) / 2f * scale + dy
        canvas.drawText(label, cx, baseline, labelPaint)
        labelPaint.letterSpacing = 0f
        val hint = key.spec.hint
        if (hint != null) {
            hintPaint.textSize = d.hintSize
            hintPaint.typeface = if (d.hintBold) Typeface.create(typeface, Typeface.BOLD) else typeface
            hintPaint.color = colors.hintLabel
            hintPaint.alpha = alpha
            hintPaint.getTextBounds(hint, 0, hint.length, bounds)
            val x = rect.right - d.hintRightInset - bounds.right
            val y = rect.top + d.hintTopInset - bounds.top
            canvas.drawText(hint, x, y, hintPaint)
        }
    }

    private fun drawSpaceGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int, alpha: Int) {
        // "␣": an open bracket, 41x15 px at 420dpi with ~4px strokes.
        iconPaint.color = color; iconPaint.alpha = alpha
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = d.m(4.2f)
        iconPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.strokeJoin = Paint.Join.ROUND
        val w = d.m(37f) / 2f
        val h = d.m(11f)
        val path = android.graphics.Path().apply {
            moveTo(cx - w, cy - h / 2f); lineTo(cx - w, cy + h / 2f); lineTo(cx + w, cy + h / 2f); lineTo(cx + w, cy - h / 2f)
        }
        canvas.drawPath(path, iconPaint)
        iconPaint.style = Paint.Style.FILL
    }

    private fun icon(canvas: Canvas, sym: String, box: Float, cx: Float, cy: Float, color: Int, alpha: Int) {
        iconPaint.color = color
        iconPaint.alpha = alpha
        iconPaint.style = Paint.Style.FILL
        Icons.draw(canvas, sym, box, cx, cy, iconPaint)
    }

    fun drawKey(canvas: Canvas, key: Key, pressed: Boolean) {
        drawBackground(canvas, key, background(key, pressed))
        drawForeground(canvas, key)
    }

    /** True if the key's code is one of the letter-page character keys that show a preview bubble. */
    fun hasPreview(key: Key) = key.code > 0 && key.code != Codes.SPACE && key.spec.style != KeyStyle.FUNCTIONAL &&
        key.spec.labelSize != LabelSize.NUMPAD
}
