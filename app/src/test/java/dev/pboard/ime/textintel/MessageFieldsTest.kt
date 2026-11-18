package dev.pboard.ime.textintel

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart Compose's default scope: message-like fields only, decided from inputType/imeOptions. */
class MessageFieldsTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val caps = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    private val multi = InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val auto = InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
    private val noEnter = EditorInfo.IME_FLAG_NO_ENTER_ACTION

    @Test fun messageVariationsQualify() {
        assertTrue(MessageFields.isMessage(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or caps, EditorInfo.IME_ACTION_SEND))
        assertTrue(MessageFields.isMessage(text or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE or caps or multi, noEnter))
    }

    @Test fun aMultiLineSentenceFieldWithANewLineEnterQualifies() {
        // a chat box that declares no variation: TextView adds IME_FLAG_NO_ENTER_ACTION to multi-line fields
        assertTrue(MessageFields.isMessage(text or caps or auto or multi, noEnter))
    }

    @Test fun otherFieldsDoNot() {
        assertFalse(MessageFields.isMessage(text or caps or auto or multi, EditorInfo.IME_ACTION_NONE)) // no new-line enter
        assertFalse(MessageFields.isMessage(text or auto or multi, noEnter)) // not sentence-capitalised
        assertFalse(MessageFields.isMessage(text, EditorInfo.IME_ACTION_DONE)) // single-line form field
        assertFalse(MessageFields.isMessage(text or caps, EditorInfo.IME_ACTION_SEARCH))
        assertFalse(MessageFields.isMessage(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_NEXT))
        assertFalse(MessageFields.isMessage(text or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO))
        assertFalse(MessageFields.isMessage(text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT or caps, 0))
        assertFalse(MessageFields.isMessage(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        assertFalse(MessageFields.isMessage(InputType.TYPE_CLASS_NUMBER, 0))
    }
}
