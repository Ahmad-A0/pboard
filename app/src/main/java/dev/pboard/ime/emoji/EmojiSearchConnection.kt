package dev.pboard.ime.emoji

import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/**
 * The emoji search box's text, edited by the keyboard exactly like an app's field: while emoji search is open the
 * IME's input controller talks to this connection instead of the app's, so composing, suggestions and delete all
 * work on the query. [onChange] gets the query after every edit.
 */
class EmojiSearchConnection(view: View, private val onChange: (String) -> Unit) : BaseInputConnection(view, true) {
    private val text = SpannableStringBuilder()
    private var batch = 0

    override fun getEditable(): Editable = text

    val query: String get() = text.toString()

    private fun changed() { if (batch == 0) onChange(text.toString()) }

    override fun beginBatchEdit(): Boolean { batch++; return true }

    override fun endBatchEdit(): Boolean {
        if (batch > 0) batch--
        changed()
        return batch > 0
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int) =
        super.commitText(text, newCursorPosition).also { changed() }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
        super.setComposingText(text, newCursorPosition).also { changed() }

    override fun finishComposingText() = super.finishComposingText().also { changed() }

    override fun setComposingRegion(start: Int, end: Int) = super.setComposingRegion(start, end).also { changed() }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
        super.deleteSurroundingText(beforeLength, afterLength).also { changed() }

    override fun setSelection(start: Int, end: Int) = super.setSelection(start, end).also { changed() }

    /** Key events (backspace from a null-editor path, arrows) are applied to the query, not sent to the app. */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> deleteSurroundingText(1, 0)
            KeyEvent.KEYCODE_ENTER -> {}
            else -> event.unicodeChar.takeIf { it > 0 }?.let { commitText(String(Character.toChars(it)), 1) }
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean = true

    fun clear() {
        text.clear()
        BaseInputConnection.removeComposingSpans(text)
        changed()
    }
}
