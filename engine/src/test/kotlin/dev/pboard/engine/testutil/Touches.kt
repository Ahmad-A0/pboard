package dev.pboard.engine.testutil

import dev.pboard.engine.Context
import dev.pboard.engine.KeyboardEngine
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.Suggestions
import dev.pboard.engine.TouchPoint

/** Helpers to produce touches for tests. */
object Touches {
    /** One tap per character of [keys] on the key with that label, offset by ([dx], [dy]) key widths. */
    fun taps(keys: String, g: KeyboardGeometry = Layouts.pixel8Qwerty(), dx: Float = 0f, dy: Float = 0.05f): List<TouchPoint> =
        keys.mapIndexed { i, c ->
            val k = g.keys.first { it.label == c.lowercaseChar().toString() }
            TouchPoint(k.cx + dx * g.keyWidth, k.cy + dy * g.keyWidth, i * 180L)
        }

    /** A straight-segment gesture through the keys of [keys], sampled every [step] px. */
    fun swipe(keys: String, g: KeyboardGeometry = Layouts.pixel8Qwerty(), step: Float = 12f): List<TouchPoint> {
        val pts = keys.map { c -> g.keys.first { it.label == c.toString() }.let { it.cx to it.cy } }
        val out = ArrayList<TouchPoint>()
        var t = 0L
        out.add(TouchPoint(pts[0].first, pts[0].second, t))
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            val d = kotlin.math.hypot(x1 - x0, y1 - y0)
            val n = maxOf(1, (d / step).toInt())
            for (s in 1..n) { t += 8; out.add(TouchPoint(x0 + (x1 - x0) * s / n, y0 + (y1 - y0) * s / n, t)) }
        }
        return out
    }

    /** Types [typed] tap by tap (like the IME) and returns the final suggestions. */
    fun type(engine: KeyboardEngine, typed: String, context: Context = Context(listOf("so"), false), keys: String = typed): Suggestions {
        val taps = taps(keys)
        var s = Suggestions.EMPTY
        for (k in 1..typed.length) s = engine.suggestForTaps(context, taps.subList(0, k), typed.substring(0, k))
        return s
    }

    /** What pressing space would commit. */
    fun committed(s: Suggestions, typed: String): String = if (s.autoCorrectIndex >= 0) s.candidates[s.autoCorrectIndex].text else typed
}
