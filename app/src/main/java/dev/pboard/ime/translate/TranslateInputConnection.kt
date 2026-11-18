package dev.pboard.ime.translate

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/**
 * The translate box's text. While Translate is open the IME's input controller types into this connection instead of
 * the app's, so composing, suggestions, auto-capitalisation and delete all work on the source text exactly as in a
 * field. [onChange] gets the text and caret after every (batched) edit; the action key calls [onDone].
 */
class TranslateInputConnection(
    view: View,
    private val onChange: (text: String, caret: Int) -> Unit,
    private val onDone: () -> Unit,
) : BaseInputConnection(view, true) {
    // A caret at 0 from the start: without a selection span getCursorCapsMode() reports no auto-capitalisation.
    private val text = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
    private var batch = 0

    override fun getEditable(): Editable = text

    val value: String get() = text.toString()
    val caret: Int get() = Selection.getSelectionEnd(text).coerceAtLeast(0)

    private fun changed() { if (batch == 0) onChange(text.toString(), caret) }

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

    /** Key events (delete, arrows from the text-editing paths, Enter) apply to the box, never to the app. */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                val s = Selection.getSelectionStart(text)
                val e = Selection.getSelectionEnd(text)
                if (s != e && s >= 0) { text.delete(minOf(s, e), maxOf(s, e)); changed() } else deleteSurroundingText(1, 0)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> onDone()
            KeyEvent.KEYCODE_DPAD_LEFT -> caret.let { if (it > 0) setSelection(it - 1, it - 1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> caret.let { if (it < text.length) setSelection(it + 1, it + 1) }
            KeyEvent.KEYCODE_MOVE_HOME -> setSelection(0, 0)
            KeyEvent.KEYCODE_MOVE_END -> setSelection(text.length, text.length)
            else -> event.unicodeChar.takeIf { it > 0 }?.let { commitText(String(Character.toChars(it)), 1) }
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean { onDone(); return true }

    fun clear() {
        text.clear()
        removeComposingSpans(text)
        Selection.setSelection(text, 0)
        changed()
    }
}
