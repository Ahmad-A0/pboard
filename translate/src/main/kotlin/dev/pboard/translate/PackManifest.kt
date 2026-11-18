package dev.pboard.translate

import dev.pboard.translate.pack.Json

/**
 * `manifest.json` of a translation pack (format 1). Example:
 *
 * ```json
 * { "format": 1, "id": "es", "version": 1, "name": "Spanish",
 *   "license": "MPL-2.0", "attribution": "Firefox Translations models, Mozilla",
 *   "models": [
 *     { "from": "en", "to": "es", "type": "marian-ssru",
 *       "model": "en-es/model.enes.intgemm.alphas.bin", "vocab": "en-es/vocab.enes.spm",
 *       "shortlist": "en-es/lex.50.50.enes.s2t.bin", "size": 36576277 } ] }
 * ```
 *
 * Separate source/target vocabularies use "srcVocab"/"trgVocab" instead of "vocab". Paths are relative to the pack
 * root, may not leave it, and use '/'. Unknown keys are ignored so newer packs stay readable.
 */
data class PackManifest(
    val format: Int,
    val id: String,
    val version: Int,
    val name: String,
    val license: String,
    val attribution: String,
    val models: List<ModelSpec>,
) {
    /** All language codes this pack can translate to or from. */
    val languages: Set<String> get() = models.flatMap { listOf(it.from, it.to) }.toSet()

    fun model(from: String, to: String): ModelSpec? = models.firstOrNull { it.from == from && it.to == to }

    companion object {
        const val FILE = "manifest.json"
        const val FORMAT = 1
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val LANG = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*")

        fun isSafePath(path: String): Boolean =
            path.isNotEmpty() && path.length < 256 && !path.startsWith("/") && '\\' !in path && '\u0000' !in path &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        fun parse(text: String): PackManifest {
            val root = Json.parse(text) as? Map<*, *> ?: throw TranslateFormatException("manifest is not an object")
            fun str(m: Map<*, *>, k: String, def: String? = null): String =
                (m[k] as? String) ?: def ?: throw TranslateFormatException("manifest lacks \"$k\"")
            fun int(m: Map<*, *>, k: String, def: Int? = null): Int =
                (m[k] as? Double)?.toInt() ?: def ?: throw TranslateFormatException("manifest lacks \"$k\"")
            val format = int(root, "format")
            if (format != FORMAT) throw TranslateFormatException("unsupported pack format $format")
            val id = str(root, "id")
            if (!ID.matches(id)) throw TranslateFormatException("bad pack id '$id'")
            val models = (root["models"] as? List<*>)?.map { mm ->
                val m = mm as? Map<*, *> ?: throw TranslateFormatException("model entry is not an object")
                val vocab = m["vocab"] as? String
                val spec = ModelSpec(
                    from = str(m, "from"), to = str(m, "to"), type = str(m, "type", ModelSpec.TYPE_MARIAN),
                    model = str(m, "model"), srcVocab = vocab ?: str(m, "srcVocab"), trgVocab = vocab ?: str(m, "trgVocab"),
                    shortlist = m["shortlist"] as? String, size = (m["size"] as? Double)?.toLong() ?: 0L,
                )
                if (!LANG.matches(spec.from) || !LANG.matches(spec.to) || spec.from == spec.to)
                    throw TranslateFormatException("bad language pair ${spec.from}-${spec.to}")
                for (p in spec.files) if (!isSafePath(p)) throw TranslateFormatException("unsafe path '$p'")
                spec
            } ?: throw TranslateFormatException("manifest lacks \"models\"")
            if (models.isEmpty()) throw TranslateFormatException("pack has no models")
            return PackManifest(format, id, int(root, "version", 1), str(root, "name", id), str(root, "license", ""),
                str(root, "attribution", ""), models)
        }
    }
}

/** One translation direction inside a pack. [size] is the total size of its files in bytes (0 if not given). */
data class ModelSpec(
    val from: String,
    val to: String,
    val type: String,
    val model: String,
    val srcVocab: String,
    val trgVocab: String,
    val shortlist: String?,
    val size: Long = 0L,
) {
    val files: List<String> get() = listOfNotNull(model, srcVocab, trgVocab, shortlist).distinct()

    companion object {
        /** Marian transformer with SSRU decoder, int8 weights (Firefox Translations / Bergamot models). */
        const val TYPE_MARIAN = "marian-ssru"
    }
}
