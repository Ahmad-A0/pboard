package dev.pboard.ime.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputConnection
import dev.pboard.ime.EditorState
import dev.pboard.ime.config.Config
import dev.pboard.ime.layout.FieldKind
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import java.util.Locale

/**
 * Recently-copied chips in the strip (ED-06, ED-07), as Gboard 18.6 shows them (measured on the emulator):
 *
 * * When a field opens and the latest clip is at most 2 minutes old and was not pasted from a chip before, the chip
 *   row shows a clipboard chip with the text (one line, cut at ~104 dp with an ellipsis) followed by one chip per item
 *   found in it ([ClipEntities]: phone number, link, e-mail, code, address; each with its own icon, full text). A clip
 *   that is itself one such item is a single chip with that item's icon. Chips are centred when they fit, else scroll.
 * * Fields narrow the items: e-mail fields offer only e-mail addresses, URL fields links, phone fields numbers, number
 *   fields codes; password fields only the clip itself.
 * * A clip the copying app marked sensitive is shown as one bullet per character (no items) and pastes the real text;
 *   it is offered in password fields too.
 * * Tapping a chip pastes it, marks the clip used (only a newer copy brings chips back) and leaves an "Undo" chip
 *   that removes the pasted text again. Typing, moving the cursor or another field removes the chips.
 * * Not offered when the setting is off or the app asks (`privateImeOptions` disableAutoPaste / disallowClipboard,
 *   bare or package-prefixed).
 */
class ClipboardChips(private val context: Context, private val host: Host) {
    interface Host {
        val dims: Dimens
        val colors: KeyboardColors
        val inputConnection: InputConnection?
        /** Shows [views] in the strip's chip row (empty: none). */
        fun showChips(views: List<View>)
        /** Pastes [text] at the cursor like a panel insert (commits the word in progress first). */
        fun paste(text: String)
    }

    private val state = runCatching { context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE) }.getOrNull()
    private var usedClipTime = state?.getLong(KEY_USED, 0L) ?: 0L
    private var shown = false
    /** The selection we expect next (where the field started, or right after our paste); anything else dismisses. */
    private var expectedSel = -1
    private var undo: String? = null

    fun offer(cm: ClipboardManager?, editor: EditorState, selStart: Int) {
        dismiss()
        if (!Config.current.clipboardSuggestions || editor.isNull || editor.internal) return
        val opts = editor.info?.privateImeOptions.orEmpty()
        if (opts.contains("disableAutoPaste") || opts.contains("disallowClipboard")) return
        val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return
        val desc = clip.description ?: return
        val time = desc.timestamp
        if (time <= 0L || time == usedClipTime || SystemClock.elapsedRealtime() - time > MAX_AGE_MS) return
        val text = clip.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() } ?: return
        val oneLine = text.trim().replace(Regex("\\s*\\n\\s*"), " ")
        val sensitive = isSensitive(desc)
        val region = regions(editor)
        val chips = ArrayList<View>()
        fun chip(icon: ClipChipView.Icon, label: String, max: Float?, description: String, paste: String) {
            chips += ClipChipView(context, host.dims, host.colors, icon, label, max, description) { onPaste(paste, time) }
        }
        val whole = if (sensitive) null else ClipEntities.classifyWhole(text, region)
        when {
            sensitive -> chip(ClipChipView.Icon.CLIPBOARD, "•".repeat(text.length.coerceAtMost(MAX_BULLETS)), FULL_CLIP_MAX_DP,
                "Paste copied text", text)
            whole != null -> chip(iconFor(whole), oneLine, ENTITY_MAX_DP, "Paste $oneLine", text.trim())
            else -> {
                chip(ClipChipView.Icon.CLIPBOARD, oneLine, FULL_CLIP_MAX_DP, "Paste copied text: $oneLine", text)
                val allowed = allowedTypes(editor)
                if (dev.pboard.prefs.PboardPrefs.get(context)[dev.pboard.prefs.Prefs.clipboardEntities] && allowed.isNotEmpty()) {
                    for (e in ClipEntities.extract(text, region).filter { it.type in allowed }) {
                        chip(iconFor(e.type), e.text, ENTITY_MAX_DP, "Paste ${e.text}", e.text)
                    }
                }
            }
        }
        expectedSel = selStart
        shown = true
        host.showChips(chips)
    }

    /** The app reported a new selection: chips stay only while the cursor is where we expect it. */
    fun onSelectionChanged(selStart: Int, selEnd: Int) {
        if (!shown) return
        if (selStart == expectedSel && selEnd == selStart) return
        dismiss()
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        undo = null
        host.showChips(emptyList())
    }

    private fun onPaste(text: String, time: Long) {
        usedClipTime = time
        runCatching { state?.edit()?.putLong(KEY_USED, time)?.apply() }
        host.paste(text)
        // Gboard leaves an "Undo" chip that takes the paste back. The cursor was at expectedSel (else the chips would
        // have gone), so it is now right after the pasted text.
        undo = text
        if (expectedSel >= 0) expectedSel += text.length
        host.showChips(listOf(ClipChipView(context, host.dims, host.colors, ClipChipView.Icon.UNDO, "Undo", null, "Undo paste") { undoPaste() }))
    }

    private fun undoPaste() {
        val text = undo ?: return dismiss()
        val ic = host.inputConnection
        val before = ic?.getTextBeforeCursor(text.length, 0)?.toString()
        if (ic != null && before == text) ic.deleteSurroundingText(text.length, 0)
        dismiss()
    }

    /**
     * Regions to read national phone numbers in: the device's, the field's hint locales and the system locales, then
     * the common English-speaking ones (a number copied from a UK message on a US-set phone is still a number).
     */
    private fun regions(editor: EditorState): List<String> {
        val out = LinkedHashSet<String>()
        out += Locale.getDefault().country
        editor.info?.hintLocales?.let { l -> for (i in 0 until l.size()) out += l[i].country }
        val sys = android.os.LocaleList.getDefault()
        for (i in 0 until sys.size()) out += sys[i].country
        out += FALLBACK_REGIONS
        return out.filter { it.length == 2 }
    }

    private fun allowedTypes(editor: EditorState): Set<EntityType> = when {
        editor.isPassword -> emptySet()
        editor.kind == FieldKind.EMAIL -> setOf(EntityType.EMAIL)
        editor.kind == FieldKind.URL -> setOf(EntityType.LINK)
        editor.kind == FieldKind.PHONE -> setOf(EntityType.PHONE)
        editor.kind == FieldKind.NUMBER || editor.kind == FieldKind.DATETIME -> setOf(EntityType.OTP)
        else -> EntityType.entries.toSet()
    }

    private fun iconFor(t: EntityType) = when (t) {
        EntityType.PHONE -> ClipChipView.Icon.PHONE
        EntityType.LINK -> ClipChipView.Icon.LINK
        EntityType.EMAIL -> ClipChipView.Icon.MAIL
        EntityType.OTP -> ClipChipView.Icon.NUMBERS
        EntityType.ADDRESS -> ClipChipView.Icon.LOCATION
    }

    private fun isSensitive(d: ClipDescription): Boolean {
        val extras = d.extras ?: return false
        return if (Build.VERSION.SDK_INT >= 33) extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)
        else extras.getBoolean("android.content.extra.IS_SENSITIVE")
    }

    companion object {
        /** Gboard: a clip is offered for 2 minutes after it was copied. */
        const val MAX_AGE_MS = 2 * 60 * 1000L
        const val FULL_CLIP_MAX_DP = 104f
        const val ENTITY_MAX_DP = 240f
        private const val MAX_BULLETS = 24
        private val FALLBACK_REGIONS = listOf("GB", "US", "IN", "AU", "CA", "IE", "NZ")
        private const val STATE_FILE = "clipboard_state"
        private const val KEY_USED = "used_clip_time"
    }
}
