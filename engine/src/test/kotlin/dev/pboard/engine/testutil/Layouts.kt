package dev.pboard.engine.testutil

import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry

/**
 * Pixel-8-like QWERTY letter layer in keyboard-local pixels (origin = top-left of the top key row,
 * suggestion strip excluded). 1080 px wide: horizontal key pitch 108 px with 98 px visible keys, row
 * pitch 140 px. Row 2 is offset by half a key; row 3 has 1.5-wide shift/delete around z..m.
 */
object Layouts {
    const val WIDTH = 1080f
    const val PITCH_X = 108f
    const val KEY_W = 98f
    const val ROW_H = 140f
    const val HEIGHT = ROW_H * 4

    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    fun pixel8Qwerty(): KeyboardGeometry {
        val keys = ArrayList<KeyGeom>()
        for ((r, row) in ROWS.withIndex()) {
            val x0 = when (r) { 0 -> PITCH_X / 2; 1 -> PITCH_X; else -> PITCH_X * 2 }
            for ((i, c) in row.withIndex()) keys.add(KeyGeom(c.toString(), x0 + i * PITCH_X, ROW_H * r + ROW_H / 2, KEY_W, ROW_H))
        }
        val y2 = ROW_H * 2 + ROW_H / 2
        keys.add(KeyGeom("shift", PITCH_X * 0.75f, y2, PITCH_X * 1.5f - 10f, ROW_H))
        keys.add(KeyGeom("delete", WIDTH - PITCH_X * 0.75f, y2, PITCH_X * 1.5f - 10f, ROW_H))
        val y3 = ROW_H * 3 + ROW_H / 2
        keys.add(KeyGeom("?123", PITCH_X * 0.75f, y3, PITCH_X * 1.5f - 10f, ROW_H))
        keys.add(KeyGeom(",", PITCH_X * 2f, y3, KEY_W, ROW_H))
        keys.add(KeyGeom("space", PITCH_X * 5f, y3, PITCH_X * 5f - 10f, ROW_H))
        keys.add(KeyGeom(".", PITCH_X * 8f, y3, KEY_W, ROW_H))
        keys.add(KeyGeom("enter", WIDTH - PITCH_X * 0.75f, y3, PITCH_X * 1.5f - 10f, ROW_H))
        return KeyboardGeometry(keys, WIDTH, HEIGHT)
    }

    /**
     * What the IME registers for a touch: rows are horizontal bands, and within a band the key whose
     * hit box (centre ± half pitch) contains x. Returns the key label or null outside every key.
     */
    fun hitTest(g: KeyboardGeometry, x: Float, y: Float): String? {
        if (y < 0 || y >= g.height || x < 0 || x >= g.width) return null
        val row = (y / ROW_H).toInt()
        var best: KeyGeom? = null
        var bestD = Float.MAX_VALUE
        for (k in g.keys) {
            if ((k.cy / ROW_H).toInt() != row) continue
            val d = kotlin.math.abs(k.cx - x)
            if (d < bestD) { bestD = d; best = k }
        }
        return best?.label
    }

    fun key(g: KeyboardGeometry, label: String): KeyGeom = g.keys.first { it.label == label }
}
