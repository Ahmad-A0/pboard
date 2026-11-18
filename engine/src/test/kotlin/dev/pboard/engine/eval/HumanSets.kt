package dev.pboard.engine.eval

import dev.pboard.engine.KeyGeom
import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.TouchPoint
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Reader for the human evaluation splits (tools/data/human/splits/v1, see docs/eval-data.md): gzip JSONL in
 * the trace format, with the geometry in a separate bench-geometry file (screen pixels, hit rectangles).
 *
 * Tap records end with a terminator touch (space/period/synthetic); [EvalRecord.terminatorKey] says which
 * key it lands on in the target geometry (6.9 % of TSI terminators hit the bottom letter row).
 * Text records (`text_*` sets) carry `typed` instead of points.
 */
object HumanSets {
    /** A text-level autocorrect item: what the fingers spelled vs. what was meant. */
    data class TextRecord(val id: String, val context: String, val typed: String, val targets: List<String>, val error: Boolean, val participant: String?)

    fun isHuman(f: File) = f.name.endsWith(".jsonl.gz")

    fun defaultGeometry(root: File) = File(root, "tools/data/human/geometry/gboard_pixel8.json")

    @Suppress("UNCHECKED_CAST")
    fun geometry(file: File): KeyboardGeometry {
        val o = Json.parse(file.readText()) as Map<String, Any?>
        val keys = (o["keys"] as List<Map<String, Any?>>).map { k ->
            val name = k["name"] as String
            val label = k["label"] as String
            val hit = (k["hit"] as List<Number>).map { it.toFloat() }
            val lab = if (label.length == 1 && (label[0].isLetter() || label[0] in ",.'")) label else name
            KeyGeom(lab, (k["cx"] as Number).toFloat(), (k["cy"] as Number).toFloat(), hit[2] - hit[0], hit[3] - hit[1])
        }
        val screen = o["screen"] as Map<String, Any?>
        return KeyboardGeometry(keys, (screen["width"] as Number).toFloat(), (screen["height"] as Number).toFloat())
    }

    private fun lines(file: File): Sequence<String> =
        GZIPInputStream(file.inputStream()).bufferedReader().lineSequence().filter { it.isNotBlank() }

    @Suppress("UNCHECKED_CAST")
    fun traces(file: File, geometry: KeyboardGeometry): EvalSet {
        val recs = lines(file).map { line ->
            val o = Json.parse(line) as Map<String, Any?>
            val pts = (o["points"] as List<List<Number>>).map { TouchPoint(it[0].toFloat(), it[1].toFloat(), it[2].toLong()) }
            EvalRecord(
                o["id"] as String, o["context"] as String, o["target"] as String, o["kind"] as String, pts,
                participant = o["participant"] as String?,
                terminator = o["terminator"] as String?,
                terminatorKey = o["terminatorKeyOnTarget"] as String?,
            )
        }.toList()
        return EvalSet(mapOf("source" to file.name), geometry, recs)
    }

    @Suppress("UNCHECKED_CAST")
    fun text(file: File): List<TextRecord> = lines(file).map { line ->
        val o = Json.parse(line) as Map<String, Any?>
        val targets = (o["targets"] as List<String>?) ?: listOf(o["target"] as String)
        TextRecord(o["id"] as String, (o["context"] as String?) ?: "", o["typed"] as String, targets, o["error"] as Boolean, o["participant"] as String?)
    }.toList()

    fun isTextSet(f: File) = f.name.startsWith("text_")
}
