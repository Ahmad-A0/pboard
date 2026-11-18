package dev.pboard.ime.textintel

import android.os.Build
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.view.inputmethod.InputConnection
import dev.pboard.ime.view.StripItem

/**
 * The keyboard half of grammar check (Gboard 18.6 behaviour, measured): when the cursor comes to rest in or at the
 * end of a word the field has marked as a grammar error, the strip shows a single chip with the fix; tapping it
 * replaces the marked text in one edit and leaves the cursor at the end of the replacement.
 *
 * The marks themselves come from the system spell checker ([PboardSpellCheckerService] when pboard is selected, or any
 * other checker that reports grammar errors): they are read back from the field as [SuggestionSpan]s with
 * FLAG_GRAMMAR_ERROR (Android 12+), so the chip always matches what is underlined.
 */
class GrammarAssist {
    /** The fix on offer: replace [start, end) (which read [original]) by [replacement]; offered at [cursor]. */
    class Fix(val start: Int, val end: Int, val original: String, val replacement: String, val cursor: Int)

    var enabled = true
    var offered: Fix? = null
        private set

    /** The grammar mark containing or ending at [cursor], if the field reports one. */
    fun markAt(ic: InputConnection, cursor: Int): Fix? {
        if (!enabled || Build.VERSION.SDK_INT < 31 || cursor < 0) return null
        val before = ic.getTextBeforeCursor(WINDOW, InputConnection.GET_TEXT_WITH_STYLES) ?: return null
        val after = ic.getTextAfterCursor(WINDOW, InputConnection.GET_TEXT_WITH_STYLES) ?: ""
        // a mark the cursor is inside of, or at the end of, reaches the end of the text before the cursor (a span
        // crossing the cursor is cut there); a cursor at a mark's start is not "in" it
        val sb = before as? Spanned ?: return null
        val (s, fix) = grammarSpans(sb).lastOrNull { (sp, _) -> sb.getSpanEnd(sp) == sb.length && sb.getSpanStart(sp) < sb.length }
            ?: return null
        val startInBefore = sb.getSpanStart(s)
        val continued = grammarSpans(after).firstOrNull { (a, f) -> (after as Spanned).getSpanStart(a) == 0 && f == fix }
        val endInAfter = continued?.let { (after as Spanned).getSpanEnd(it.first) } ?: 0
        val original = before.subSequence(startInBefore, before.length).toString() + after.subSequence(0, endInAfter).toString()
        return Fix(cursor - (before.length - startInBefore), cursor + endInAfter, original, fix, cursor)
    }

    private fun grammarSpans(text: CharSequence): List<Pair<SuggestionSpan, String>> {
        if (text !is Spanned) return emptyList()
        return text.getSpans(0, text.length, SuggestionSpan::class.java)
            .filter { it.flags and FLAG_GRAMMAR_ERROR != 0 && it.suggestions.isNotEmpty() }
            .map { it to it.suggestions[0] }
    }

    /** Tint of the word whose fix is on offer (Gboard: the functional-key colour at 60 % opacity, #99DCE2F9 light). */
    var tintColor: Int = 0x99DCE2F9.toInt()

    /** For the underline style of the reopened word's span (set by the keyboard service). */
    var context: android.content.Context? = null

    /**
     * The marked word as composing text the way Gboard reopens it: tinted, still underlined (the grammar span is
     * carried along, since composing text replaces the field's own spans).
     */
    fun styled(fix: Fix, locale: java.util.Locale): CharSequence {
        val s = android.text.SpannableString(fix.original)
        s.setSpan(android.text.style.BackgroundColorSpan(tintColor), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val ctx = context
        if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
            // with a context the span takes the theme's grammar underline (colour and thickness); without, none
            s.setSpan(SuggestionSpan(ctx, locale, arrayOf(fix.replacement), FLAG_GRAMMAR_ERROR, null), 0, s.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** Strip items offering [fix] (a single centred chip). */
    fun stripFor(fix: Fix): List<StripItem?> {
        offered = fix
        return listOf(null, StripItem(fix.replacement, fix = true), null)
    }

    /** The strip shows something else now. */
    fun dismiss() { offered = null }

    /**
     * The chip was tapped with the cursor at [cursor]: replaces the marked text if the field still holds it there.
     * Returns the new cursor (end of the replacement), or null if nothing was done.
     */
    fun apply(ic: InputConnection, cursor: Int): Int? {
        val f = offered ?: return null
        offered = null
        if (cursor != f.cursor) return null
        val before = ic.getTextBeforeCursor(cursor - f.start, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(f.end - cursor, 0)?.toString() ?: return null
        if (before + after != f.original) return null
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.setComposingRegion(f.start, f.end)
        ic.commitText(f.replacement, 1)
        val end = f.start + f.replacement.length
        ic.setSelection(end, end)
        ic.endBatchEdit()
        return end
    }

    companion object {
        /** SuggestionSpan.FLAG_GRAMMAR_ERROR (API 31). */
        const val FLAG_GRAMMAR_ERROR = 0x0008
        private const val WINDOW = 64
    }
}
