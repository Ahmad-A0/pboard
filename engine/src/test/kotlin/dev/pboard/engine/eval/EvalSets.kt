package dev.pboard.engine.eval

import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.TouchPoint
import java.io.File

/** One evaluation trace: the word [target] entered after [context] by taps or a swipe. */
data class EvalRecord(
    val id: String, val context: String, val target: String, val kind: String, val points: List<TouchPoint>,
    /** Optional: the misspelling the synthetic typist intended to type (cognitive error). */
    val misspelt: String? = null,
    /** Optional: the user's dialect (e.g. "en-GB"); the record is evaluated with that dialect's model. */
    val dialect: String? = null,
    /** Human sets: participant id (for per-person reporting). */
    val participant: String? = null,
    /** Human tap sets: the last point is a word terminator ("space", "period", "synthetic"). */
    val terminator: String? = null,
    /** Human tap sets: key the terminator lands on in the target geometry (a letter = missed space). */
    val terminatorKey: String? = null,
)

class EvalSet(val meta: Map<String, Any?>, val geometry: KeyboardGeometry, val records: List<EvalRecord>)

/**
 * JSONL persistence of evaluation sets, shared with the device replay harness (which types the same
 * traces into other keyboards). Line 1 is `{"meta": {...}}` with an explicit "geometry" object
 * (keyboard-local pixels, origin at the top-left of the top letter row, y down); every following line is
 * `{"id", "context", "target", "kind": "tap"|"swipe", "points": [[x, y, tMs], ...]}`.
 *  - tap: one point per touch (down = up position), t = touch-down time from the first tap of the word.
 *  - swipe: the full gesture sampled at ~120 Hz, t from touch-down.
 *  - context: the text before the word as it should appear in the field (the replay types it first);
 *    an empty context or one ending in . ! ? means the word starts a sentence (keyboards auto-capitalise).
 *  - target: the intended word (exact spelling, including capitals and apostrophes). Contractions are
 *    tapped letters-only (no apostrophe key), as users rely on autocorrect for "dont" -> "don't".
 */
object EvalSets {
    fun write(file: File, meta: Map<String, Any?>, geometry: KeyboardGeometry, records: List<EvalRecord>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            val m = LinkedHashMap(meta)
            m["geometry"] = geometryJson(geometry)
            w.write(Json.write(mapOf("meta" to m))); w.write("\n")
            for (r in records) {
                val sb = StringBuilder()
                sb.append("{\"id\":").append(Json.quote(r.id))
                sb.append(",\"context\":").append(Json.quote(r.context))
                sb.append(",\"target\":").append(Json.quote(r.target))
                sb.append(",\"kind\":").append(Json.quote(r.kind))
                if (r.misspelt != null) sb.append(",\"misspelt\":").append(Json.quote(r.misspelt))
                if (r.dialect != null) sb.append(",\"dialect\":").append(Json.quote(r.dialect))
                sb.append(",\"points\":[")
                for ((i, p) in r.points.withIndex()) {
                    if (i > 0) sb.append(',')
                    sb.append('[').append(num(p.x)).append(',').append(num(p.y)).append(',').append(p.t).append(']')
                }
                sb.append("]}")
                w.write(sb.toString()); w.write("\n")
            }
        }
    }

    private fun num(f: Float): String {
        val s = f.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun geometryJson(g: KeyboardGeometry): Map<String, Any?> = linkedMapOf(
        "name" to "pixel8-qwerty-letters",
        "width" to g.width, "height" to g.height, "keyWidth" to g.keyWidth,
        "origin" to "top-left of the top letter row (suggestion strip excluded), y down, px at 1080x2400 / 420 dpi",
        "keys" to g.keys.map { linkedMapOf("label" to it.label, "cx" to it.cx, "cy" to it.cy, "w" to it.width, "h" to it.height) },
    )

    @Suppress("UNCHECKED_CAST")
    fun read(file: File): EvalSet {
        val lines = file.readLines().filter { it.isNotBlank() }
        val meta = (Json.parse(lines[0]) as Map<String, Any?>)["meta"] as Map<String, Any?>
        val gj = meta["geometry"] as Map<String, Any?>
        val keys = (gj["keys"] as List<Map<String, Any?>>).map {
            KeyGeom(it["label"] as String, (it["cx"] as Number).toFloat(), (it["cy"] as Number).toFloat(), (it["w"] as Number).toFloat(), (it["h"] as Number).toFloat())
        }
        val geometry = KeyboardGeometry(keys, (gj["width"] as Number).toFloat(), (gj["height"] as Number).toFloat())
        val records = lines.drop(1).map { line ->
            val o = Json.parse(line) as Map<String, Any?>
            val pts = (o["points"] as List<List<Number>>).map { TouchPoint(it[0].toFloat(), it[1].toFloat(), it[2].toLong()) }
            EvalRecord(o["id"] as String, o["context"] as String, o["target"] as String, o["kind"] as String, pts, o["misspelt"] as String?, o["dialect"] as String?)
        }
        return EvalSet(meta, geometry, records)
    }
}

/** Tiny JSON writer/parser (enough for the eval files; avoids a test dependency). */
object Json {
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\t' -> sb.append("\\t")
            '\r' -> sb.append("\\r")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Float -> if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + write(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> quote(v.toString())
    }

    fun parse(s: String): Any? = Parser(s).run { val v = value(); ws(); v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) number() else error("bad json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l.add(value()); ws(); if (s[i++] == ']') return l }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        // The bench geometry labels the delete key "\b": read as 'b' it became a second b key.
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun number(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}
