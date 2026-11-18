package dev.pboard.ime.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock

/**
 * Minimal on-device clipboard history (opt-in; Gboard keeps clips for one hour).
 *
 * Privacy: the history lives in memory only — nothing is ever written to storage — and clips the source app marked
 * sensitive (ClipDescription.EXTRA_IS_SENSITIVE, e.g. copied passwords) or copied while an incognito/password field
 * was focused are never recorded at all.
 */
class ClipboardHistory(context: Context) {
    class Clip(val text: String, val time: Long, var pinned: Boolean = false)

    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val items = ArrayList<Clip>()
    var enabled = false
        set(value) {
            field = value
            if (!value) items.removeAll { !it.pinned }
        }
    /** Set while a field that must not be recorded (password, incognito) has focus. */
    var paused = false
    var onChanged: (() -> Unit)? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    fun start() { cm?.addPrimaryClipChangedListener(listener) }
    fun stop() { cm?.removePrimaryClipChangedListener(listener) }

    private fun capture() {
        if (!enabled || paused) return
        val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return
        if (isSensitive(clip.description)) return
        val text = clip.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() } ?: return
        items.removeAll { it.text == text && !it.pinned }
        items.add(0, Clip(text, SystemClock.elapsedRealtime()))
        while (items.size > MAX_ITEMS) items.removeAt(items.indexOfLast { !it.pinned }.takeIf { it >= 0 } ?: break)
        onChanged?.invoke()
    }

    private fun isSensitive(d: ClipDescription?): Boolean {
        val extras: PersistableBundle = d?.extras ?: return false
        return if (Build.VERSION.SDK_INT >= 33) extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)
        else extras.getBoolean("android.content.extra.IS_SENSITIVE")
    }

    /** How long unpinned clips are kept (Gboard 18.6 "Keep clipboard history for", 1..24 h; default 1 h). */
    var retentionMs = MAX_AGE_MS

    /** Current clips, pinned first, dropping unpinned ones older than the retention time. */
    fun clips(): List<Clip> {
        val now = SystemClock.elapsedRealtime()
        items.removeAll { !it.pinned && now - it.time > retentionMs }
        return items.sortedByDescending { it.pinned }
    }

    fun remove(c: Clip) { items.remove(c); onChanged?.invoke() }
    fun togglePin(c: Clip) { c.pinned = !c.pinned; onChanged?.invoke() }
    fun clear() { items.clear(); onChanged?.invoke() }

    companion object {
        const val MAX_ITEMS = 50
        const val MAX_AGE_MS = 60 * 60 * 1000L
    }
}
