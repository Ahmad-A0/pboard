package dev.pboard.ime.rich

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sending images into apps through the image keyboard API (InputConnection.commitContent): the app gets a content URI
 * from pboard's FileProvider with a temporary read grant, nothing else. Only offered when the editor lists a MIME type
 * the image matches (EditorInfo.contentMimeTypes, e.g. "image/png" or "image/ *").
 */
object RichContent {
    const val AUTHORITY = "dev.pboard.content"

    /** True when the editor accepts content of [mime] (exact type or a wildcard such as image/ *). */
    fun accepts(info: EditorInfo?, mime: String): Boolean {
        val types = info?.contentMimeTypes ?: return false
        return types.any { ClipDescription.compareMimeTypes(mime, it) }
    }

    fun acceptsAnyImage(info: EditorInfo?): Boolean =
        info?.contentMimeTypes?.any { it.startsWith("image/") || it == "*/*" } == true

    /** Commits [file] as rich content; false when the editor refused it or does not take that type. */
    fun commit(context: Context, ic: InputConnection?, info: EditorInfo?, file: File, mime: String, description: String): Boolean {
        if (ic == null || !accepts(info, mime)) return false
        val uri: Uri = runCatching { FileProvider.getUriForFile(context, AUTHORITY, file) }.getOrNull() ?: return false
        val content = InputContentInfo(uri, ClipDescription(description, arrayOf(mime)), null)
        return runCatching { ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) }
            .getOrDefault(false)
    }
}
