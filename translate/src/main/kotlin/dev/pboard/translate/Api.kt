package dev.pboard.translate

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/*
 * On-device translation API shared by the IME (:app) and the engine. Pure Kotlin/JVM, no Android dependencies.
 *
 * A translation pack (see docs/translate.md and [PackManifest]) holds one or more one-directional models. The app
 * finds packs (pack APKs, imported files), picks the [ModelSpec] for the chosen direction and calls [Translators.load]
 * on a background thread; the returned [Translator] is then used from one worker thread at a time.
 */

/** A model or pack file is damaged, truncated or of an unsupported kind. */
class TranslateFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Read access to the files of one pack, by the relative paths named in its manifest. */
interface PackStorage {
    fun open(path: String): InputStream

    /** Reads a whole file; [expectedSize] (if known) avoids buffer regrowth. */
    fun readBytes(path: String): ByteArray = open(path).use { it.readBytes() }

    /** A pack stored as a plain directory (imported packs, tests, host tools). */
    class Directory(val root: File) : PackStorage {
        override fun open(path: String): InputStream = file(path).inputStream()
        override fun readBytes(path: String): ByteArray = file(path).readBytes()
        private fun file(path: String): File {
            require(PackManifest.isSafePath(path)) { "unsafe pack path: $path" }
            return File(root, path)
        }
    }
}

/** A loaded one-directional translation model. Not thread-safe: use from one thread at a time. */
interface Translator : Closeable {
    val from: String
    val to: String

    /**
     * Translates [text] (any number of sentences and lines; whitespace between sentences is kept). Returns null if
     * [cancelled] turned true before it finished (polled between decoder steps, so a new keystroke can abort it).
     */
    fun translate(text: String, cancelled: () -> Boolean = { false }): String?

    override fun close() {}
}

object Translators {
    /** Loads the model [spec] from [storage] (reads ~30-45 MB; call off the main thread). */
    fun load(storage: PackStorage, spec: ModelSpec): Translator = when (spec.type) {
        ModelSpec.TYPE_MARIAN -> MarianTranslator.load(storage, spec)
        else -> throw TranslateFormatException("unsupported model type '${spec.type}'")
    }
}
