package dev.pboard.ime.view

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Long-press alternates panel geometry (overlay coordinates).
 *
 * Arrangement (matches Gboard): up to 5 columns per row, rows = ceil(n/5), columns = ceil(n/rows). The default (first)
 * item sits above the key; each row is filled from that column outward, alternating right then left, limited by the
 * room available on each side of the key. Rows are filled bottom-up.
 *
 * Edges (Gboard 18.6, 440 dpi): the popup never leaves the keyboard. When even the default cell centred on the key
 * would cross an edge (q / p: key centre 58 px from the edge, but half a cell plus the padding is 83 px), the whole
 * popup slides inward until it is flush with that edge, so the default item sits off the key's centre. A single
 * alternate (q's 1) is the one-cell panel drawn as a circle: 166 px, spanning x 0..165 over a key centred at 58.5.
 */
class PopupPanel(
    val items: List<String>,
    val rect: RectF,
    val cells: List<RectF>,
    val colWidth: Float,
    val rowHeight: Float,
    var selected: Int,
) {
    /** Selects the cell under (x, y), using columns/rows extended past the panel edges like Gboard. */
    fun select(x: Float, y: Float) {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, c) in cells.withIndex()) {
            val dx = if (x < c.left) c.left - x else if (x > c.right) x - c.right else 0f
            // Vertical distance counts less, so sliding sideways along the key row keeps selecting the bottom row.
            val dy = if (y < c.top) c.top - y else if (y > c.bottom) (y - c.bottom) * 0.25f else 0f
            val dd = dx * dx + dy * dy
            if (dd < bestD) { bestD = dd; best = i }
        }
        // Moving far below the panel (back over the keyboard) and far sideways deselects nothing: Gboard keeps the
        // nearest item selected.
        if (best >= 0) selected = best
    }

    companion object {
        fun layout(
            items: List<String>,
            keyCenterX: Float,
            keyTop: Float,
            boundsLeft: Float,
            boundsRight: Float,
            colW: Float,
            rowH: Float,
            pad: Float,
            bottomGap: Float,
        ): PopupPanel {
            val n = items.size
            // Up to 5 per row (letters); long lists such as the period's 16 marks use two rows (Gboard: 8 + 8).
            val rows = if (n > 10) 2 else ceil(n / 5f).toInt().coerceAtLeast(1)
            val cols = ceil(n / rows.toFloat()).toInt()
            val (numLeft, numRight) = columnSplit(cols, keyCenterX, boundsLeft, boundsRight, colW, pad)
            val left = keyCenterX - colW / 2f - numLeft * colW
            val bottom = keyTop - bottomGap
            val shift = edgeShift(left - pad, left + cols * colW + pad, boundsLeft, boundsRight)
            val panel = RectF(left - pad + shift, bottom - rows * rowH - 2 * pad, left + cols * colW + pad + shift, bottom)
            // Column order within a row: 0, +1, -1, +2, -2 ... restricted to [-numLeft, numRight].
            val order = mutableListOf(0)
            var k = 1
            while (order.size < cols) {
                if (k <= numRight) order += k
                if (order.size < cols && k <= numLeft) order += -k
                k++
            }
            val cells = ArrayList<RectF>(n)
            for (i in 0 until n) {
                val r = i / cols
                val c = order[i % cols]
                val x = keyCenterX - colW / 2f + c * colW + shift
                val y = bottom - pad - (r + 1) * rowH
                cells += RectF(x, y, x + colW, y + rowH)
            }
            return PopupPanel(items, panel, cells, colW, rowH, 0)
        }

        /**
         * Columns left and right of the default (key) column: as balanced as the room allows, the extra one going
         * right, and a column only on a side where it fits whole (with the padding) inside the bounds.
         */
        fun columnSplit(cols: Int, keyCenterX: Float, boundsLeft: Float, boundsRight: Float, colW: Float, pad: Float): Pair<Int, Int> {
            val maxLeft = max(0, floor((keyCenterX - boundsLeft - colW / 2f - pad) / colW).toInt())
            val maxRight = max(0, floor((boundsRight - keyCenterX - colW / 2f - pad) / colW).toInt())
            val numRight = min(maxRight, (cols - 1) - min(maxLeft, (cols - 1) / 2))
            return (cols - 1 - numRight) to numRight
        }

        /** Horizontal offset that brings the span [left, right] inside [boundsLeft, boundsRight] (flush with the edge). */
        fun edgeShift(left: Float, right: Float, boundsLeft: Float, boundsRight: Float): Float = when {
            right - left >= boundsRight - boundsLeft -> (boundsLeft + boundsRight) / 2f - (left + right) / 2f
            left < boundsLeft -> boundsLeft - left
            right > boundsRight -> boundsRight - right
            else -> 0f
        }
    }
}
