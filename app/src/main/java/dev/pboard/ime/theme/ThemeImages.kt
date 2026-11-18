package dev.pboard.ime.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The picture of the current image theme ([KeyboardColors.backgroundImage]), decoded once off the main thread and
 * kept while the file is unchanged. Image themes live in credential-protected storage: before the first unlock there
 * is no picture and the keyboard shows the theme's plain background.
 */
object ThemeImages {
    private var key: String? = null
    private var bitmap: Bitmap? = null
    private var loading: String? = null

    private fun keyOf(f: File) = "${f.path}:${f.lastModified()}:${f.length()}"

    /** The decoded picture, or null while it is not (yet) decoded; never decodes on the caller's thread. */
    @Synchronized
    fun peek(context: Context, path: String): Bitmap? =
        bitmap.takeIf { key == keyOf(File(context.filesDir, path)) }

    /** Decodes [path] in the background unless it is cached or already loading; [onReady] runs on that thread. */
    fun prefetch(context: Context, path: String, onReady: () -> Unit) {
        val f = File(context.filesDir, path)
        val k = keyOf(f)
        synchronized(this) {
            if (key == k || loading == k) return
            loading = k
        }
        Thread({
            val bmp = if (f.isFile) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
            synchronized(this) {
                key = k
                bitmap = bmp
                if (loading == k) loading = null
            }
            onReady()
        }, "pboard-theme-image").start()
    }
}
