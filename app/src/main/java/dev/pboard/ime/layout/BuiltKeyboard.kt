package dev.pboard.ime.layout

import android.graphics.RectF
import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.TouchPoint
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.SizeSet

/**
 * A layout positioned for a given width: keys with pixel rectangles, in key-surface-local coordinates. A split layout
 * ([Split]) carries [virtual], the same layout unsplit: the engine sees that one (a split row repeats its middle key,
 * and a tap or glide on either copy must reach the decoder as the same key), and [toEngine] maps touches into it.
 */
class BuiltKeyboard(
    val spec: LayoutSpec, val keys: List<Key>, val width: Int, val height: Int, val virtual: BuiltKeyboard? = null,
) {
    val page get() = spec.page
    val isSplit get() = virtual != null

    /** The keys in engine coordinates ([virtual]'s when split). */
    val engineKeys: List<Key> get() = (virtual ?: this).keys

    /**
     * A touch on this surface in the engine's coordinates: in a split layout, the same relative position in the
     * corresponding key of the unsplit layout (the touch's key is found as for [keyAt]); otherwise unchanged.
     */
    fun toEngine(p: TouchPoint): TouchPoint {
        val v = virtual ?: return p
        val k = keyAt(p.x, p.y) ?: return p
        val vk = v.keys.firstOrNull { it.row == k.row && it.index == k.index } ?: return p
        val f = if (k.hit.width() > 0f) ((p.x - k.hit.left) / k.hit.width()).coerceIn(0f, 1f) else 0.5f
        return TouchPoint(vk.hit.left + f * vk.hit.width(), p.y, p.t)
    }

    fun toEngine(path: List<TouchPoint>): List<TouchPoint> = if (virtual == null) path else path.map { toEngine(it) }

    /** Hit-tests by touch area; falls back to the nearest key so every point inside the surface maps to a key. */
    fun keyAt(x: Float, y: Float): Key? {
        for (k in keys) if (k.hit.contains(x, y)) return k
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dx = (x - x.coerceIn(k.rect.left, k.rect.right))
            val dy = (y - y.coerceIn(k.rect.top, k.rect.bottom))
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** The key whose touch area contains the point, or null (no nearest-key fallback). */
    fun keyUnder(x: Float, y: Float): Key? = keys.firstOrNull { it.hit.contains(x, y) }

    fun find(code: Int): Key? = keys.firstOrNull { it.code == code }

    /** Geometry for the engine: every key that produces text, with its hit-box centre and size. */
    fun engineGeometry(): KeyboardGeometry = virtual?.engineGeometry() ?: KeyboardGeometry(
        keys = keys.filter { it.code > 0 || it.code == Codes.SHIFT || it.code == Codes.DELETE }.map { k ->
            KeyGeom(
                label = when (k.code) {
                    Codes.SPACE -> "space"
                    Codes.SHIFT -> "shift"
                    Codes.DELETE -> "delete"
                    else -> k.geometryLabel.lowercase()
                },
                cx = k.hit.centerX(), cy = k.hit.centerY(), width = k.hit.width(), height = k.hit.height(),
                // Long-press alternates (accents) count as typed on this key; digits and action items do not.
                alternates = k.spec.popup.filter { a -> a.isNotEmpty() && a[0] != '\u0000' && a.any { it.isLetter() } }
                    .map { it.lowercase() },
            )
        },
        width = width.toFloat(), height = height.toFloat(),
    )

    /**
     * Split layout (CX-11), measured on Gboard 18.6 on a 10" tablet (800 dp, portrait and landscape): the rows break
     * into a left and a right half [half] px wide, the left one starting at [leftStart], the right one ending at
     * [rightEnd]. A row with an odd number of keys repeats its middle key in both halves (QWERTY: G and V). In a half,
     * wide keys and indents give up a third of their extra width (shift 1.5 -> 1.33 slots, the A row's 0.5 indent ->
     * 0.33), and the keys share the half like LinearLayout weights (top row 5 x 1, A row 0.33 + 5, Z row 1.33 + 4).
     * The bottom row keeps the halves' unit (half / 5.33) for its keys either side of the space bar, which spans the
     * gap between the halves.
     */
    class Split(val leftStart: Int, val half: Int, val rightEnd: Int) {
        val leftEnd get() = leftStart + half
        val rightStart get() = rightEnd - half

        companion object {
            /** Share of a wide key's (or an indent's) extra width it keeps in a half. */
            const val SHRINK = 2f / 3f
            /** Slots per half for the bottom row's unit: 5 keys and a third. */
            const val HALF_UNITS = 16f / 3f
            fun weight(w: Float) = if (w > 1f) 1f + (w - 1f) * SHRINK else w

            /** Width of each half (cells, including the keys' side insets). */
            const val HALF_DP = 273f
            /** Space between the keyboard's edges and the halves: [MARGIN_RATIO] of the width, at least [MARGIN_MIN_DP]. */
            const val MARGIN_RATIO = 0.0355f
            const val MARGIN_MIN_DP = 30f

            /** Split is offered on large screens only (tablet size set). */
            const val MIN_SMALLEST_WIDTH_DP = 600
            /** Gboard 18.6 opens split by default from this smallest width (measured at 800 dp); below it, unsplit. */
            const val DEFAULT_SMALLEST_WIDTH_DP = 800

            /**
             * Whether the keyboard is split on a screen of [smallestWidthDp]: the user's [choice] (toolbar Split) where
             * split is offered, otherwise (null) Gboard's default.
             */
            fun wanted(choice: Boolean?, smallestWidthDp: Int): Boolean =
                smallestWidthDp >= MIN_SMALLEST_WIDTH_DP && (choice ?: (smallestWidthDp >= DEFAULT_SMALLEST_WIDTH_DP))

            /** Gboard's halves on a keyboard [width] px wide at [density] px/dp; null when they would not fit apart. */
            fun gboard(width: Int, density: Float): Split? {
                val half = Math.round(HALF_DP * density)
                val margin = Math.round(maxOf(MARGIN_MIN_DP * density, MARGIN_RATIO * width))
                if (width - 2 * margin - 2 * half < half / HALF_UNITS) return null
                return Split(margin, half, width - margin)
            }
        }
    }

    /**
     * A key's horizontal place in its row: [index] in the row spec, its cell's pixel edges, the free space (in slots)
     * the cell holds before and after the cap, the cap's own [width] in slots, and the touch area's edges.
     */
    class Cell(
        val index: Int, val left: Int, val right: Int, val before: Float, val width: Float, val after: Float,
        val hitLeft: Float, val hitRight: Float,
    ) {
        val weight get() = before + width + after
    }

    companion object {
        /**
         * The horizontal cells of one row of keys [widths] slots wide with [leftGaps] before them, in a [columns]-slot
         * grid on a surface [width] px wide with [sidePad] px at each side. Space a row leaves free (an indent, or a
         * narrower row centred) belongs to its first and last keys' cells. With [split], the row goes into the
         * halves ([Split]); [spaceIndex] (or -1) is the space bar that spans the gap in the bottom row. Cells come in
         * key order; a split row's repeated middle key appears twice.
         */
        fun rowCells(
            widths: FloatArray, leftGaps: FloatArray, columns: Float, sidePad: Int, width: Int,
            halves: Split? = null, spaceIndex: Int = -1,
        ): List<Cell> {
            val n = widths.size
            if (n == 0) return emptyList()
            val used = (0 until n).sumOf { (widths[it] + leftGaps[it]).toDouble() }.toFloat()
            val lead = if (leftGaps[0] > 0f) 0f else ((columns - used) / 2f).coerceAtLeast(0f)
            val trail = (columns - used - lead).coerceAtLeast(0f)
            fun before(i: Int) = leftGaps[i] + if (i == 0) lead else 0f
            fun after(i: Int) = if (i == n - 1) trail else 0f
            val out = ArrayList<Cell>(n + 1)
            // Keys [idx] share [total] px from [x0] by weight; the first and last touch areas reach the given edges.
            fun segment(idx: IntArray, x0: Int, total: Int, inHalf: Boolean, hitStart: Float, hitEnd: Float) {
                val k = if (inHalf) Split.SHRINK else 1f
                val b = FloatArray(idx.size) { before(idx[it]) * k }
                val w = FloatArray(idx.size) { if (inHalf) Split.weight(widths[idx[it]]) else widths[idx[it]] }
                val a = FloatArray(idx.size) { after(idx[it]) * k }
                val shares = split(total, FloatArray(idx.size) { b[it] + w[it] + a[it] })
                var x = x0
                for (j in idx.indices) {
                    val l = x
                    val r = x + shares[j]
                    x = r
                    out += Cell(idx[j], l, r, b[j], w[j], a[j],
                        if (j == 0) hitStart else l.toFloat(), if (j == idx.size - 1) hitEnd else r.toFloat())
                }
            }
            val h = halves ?: run {
                segment(IntArray(n) { it }, sidePad, width - 2 * sidePad, false, 0f, width.toFloat())
                return out
            }
            if (spaceIndex >= 0) {
                val unit = h.half / Split.HALF_UNITS
                val left = IntArray(spaceIndex) { it }
                val right = IntArray(n - spaceIndex - 1) { spaceIndex + 1 + it }
                fun px(idx: IntArray) = Math.round(idx.sumOf {
                    (Split.weight(widths[it]) + (before(it) + after(it)) * Split.SHRINK).toDouble()
                }.toFloat() * unit)
                val spaceLeft = h.leftStart + px(left)
                val spaceRight = h.rightEnd - px(right)
                if (left.isNotEmpty()) segment(left, h.leftStart, spaceLeft - h.leftStart, true, 0f, spaceLeft.toFloat())
                out += Cell(spaceIndex, spaceLeft, spaceRight, 0f, 1f, 0f,
                    if (left.isEmpty()) 0f else spaceLeft.toFloat(), if (right.isEmpty()) width.toFloat() else spaceRight.toFloat())
                if (right.isNotEmpty()) segment(right, spaceRight, h.rightEnd - spaceRight, true, spaceRight.toFloat(), width.toFloat())
                return out
            }
            // Touches in the gap go to the nearer half's inner key.
            val mid = (h.leftEnd + h.rightStart) / 2f
            segment(IntArray((n + 1) / 2) { it }, h.leftStart, h.half, true, 0f, mid)
            segment(IntArray(n - n / 2) { n / 2 + it }, h.rightStart, h.half, true, mid, width.toFloat())
            return out
        }

        /**
         * Splits [total] pixels by [weights] the way LinearLayout does: each cell takes the truncated share of what is
         * left, the last cell the rest.
         */
        fun split(total: Int, weights: FloatArray): IntArray {
            val out = IntArray(weights.size)
            var remaining = total
            var remainingWeight = weights.sum()
            for (i in weights.indices) {
                val share = if (i == weights.size - 1 || remainingWeight <= 0f) remaining
                else (weights[i] * remaining / remainingWeight).toInt().coerceIn(0, remaining)
                out[i] = share
                remaining -= share
                remainingWeight -= weights[i]
            }
            return out
        }

        /**
         * Lays [spec] out in a surface [width] px wide, as Gboard does: the body's
         * rows share the height between the top padding and the bottom gap (letter rows 0.254, bottom row 0.238;
         * equal rows on pads), and each row's keys share the width between the side paddings. Both are split into
         * whole pixels the way Android's LinearLayout splits weights, so the cells' pixel edges match Gboard's.
         */
        fun build(spec: LayoutSpec, width: Int, d: Dimens, halves: Split? = null): BuiltKeyboard {
            val keys = mutableListOf<Key>()
            val pad = spec.isPad
            val n = spec.rows.size
            val numberRow = spec.rows.any { it.isNumberRow }
            val bodyH = d.bodyHeight(numberRow || spec.tallBody)

            // Vertical: the rows share the body below the top padding and above the bottom gap (which the bottom row's
            // cell absorbs), split into whole pixels like the columns; a key fills its cell minus the bottom inset.
            val weights = FloatArray(n) { r ->
                when {
                    pad -> 1f
                    spec.rows[r].isNumberRow -> SizeSet.NR_NUMBER_ROW
                    r == n - 1 -> if (numberRow) SizeSet.NR_BOTTOM_ROW else SizeSet.BOTTOM_ROW
                    else -> if (numberRow) SizeSet.NR_LETTER_ROW else SizeSet.LETTER_ROW
                }
            }
            val gap = if (pad) 0 else d.bottomGap.toInt()
            val heights = split(bodyH.toInt() - d.keysTopPad.toInt() - gap, weights)
            val rowTop = FloatArray(n)
            var y = d.keysTopPad.toInt()
            for (r in 0 until n) { rowTop[r] = y.toFloat(); y += heights[r] }
            val rowBottom = FloatArray(n) { r ->
                val inset = when {
                    pad -> d.padBottomInset
                    spec.rows[r].isNumberRow -> d.numberRowBottomInset
                    numberRow && r < n - 1 -> d.keyBottomInsetNumberRow
                    else -> d.keyBottomInset
                }
                (if (r == n - 1) bodyH else rowTop[r + 1]) - inset
            }

            val inset = if (pad) d.padHInset else d.hInset
            val rowSplit = halves?.takeIf { !pad }
            for ((r, row) in spec.rows.withIndex()) {
                val top = rowTop[r]
                val bottom = rowBottom[r]
                val gapAbove = if (r == 0) 0f else top - rowBottom[r - 1]
                val gapBelow = if (r == n - 1) 0f else rowTop[r + 1] - bottom
                val cells = rowCells(
                    FloatArray(row.keys.size) { row.keys[it].width }, FloatArray(row.keys.size) { row.keys[it].leftGap },
                    spec.columns, d.sidePad.toInt(), width, rowSplit,
                    if (rowSplit != null && r == n - 1) row.keys.indexOfFirst { it.code == Codes.SPACE } else -1,
                )
                for (c in cells) {
                    // A key's cap sits at the inner end of a cell wider than the key (indent, centred row).
                    val share = c.right - c.left
                    val capW = if (c.before > 0f || c.after > 0f) (share * c.width / c.weight).toInt() else share
                    val capLeft = when {
                        c.before > 0f && c.after > 0f -> c.left + (share * c.before / c.weight).toInt()
                        c.before > 0f -> c.right - capW
                        else -> c.left
                    }
                    val rect = RectF(capLeft + inset, top, capLeft + capW - inset, bottom)
                    val hit = RectF(
                        c.hitLeft,
                        if (r == 0) 0f else top - gapAbove / 2f,
                        c.hitRight,
                        if (r == n - 1) bodyH else bottom + gapBelow / 2f,
                    )
                    keys += Key(row.keys[c.index], rect, hit, r, c.index)
                }
            }
            if (rowSplit != null) {
                // The engine's unsplit twin: ten bottom-row units wide, so its keys are about as wide as the halves'.
                val virtualWidth = Math.round(10f * rowSplit.half / Split.HALF_UNITS) + 2 * d.sidePad.toInt()
                return BuiltKeyboard(spec, keys, width, bodyH.toInt(), build(spec, virtualWidth, d))
            }
            return BuiltKeyboard(spec, keys, width, bodyH.toInt())
        }
    }
}
