package dev.pboard.ime.textintel

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Which fields count as "messages" for Smart Compose's default scope. Gboard 18.6 shows inline completions only in an
 * allow-list of messaging apps (Messages, WhatsApp, Telegram, Slack, ...); pboard decides from the field itself, with no
 * app list:
 *  - the message variations apps declare: TYPE_TEXT_VARIATION_SHORT_MESSAGE (chat boxes) and LONG_MESSAGE (e-mail
 *    bodies and other long messages);
 *  - a multi-line, sentence-capitalised plain text field whose enter key inserts a new line
 *    (IME_FLAG_NO_ENTER_ACTION): how most chat and e-mail compose boxes that declare no variation look.
 * Search boxes, single-line forms, URL/e-mail address/password/number fields and lower-case fields do not qualify.
 */
object MessageFields {
    fun isMessage(inputType: Int, imeOptions: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> return true
            InputType.TYPE_TEXT_VARIATION_NORMAL -> {}
            else -> return false
        }
        val multiLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val sentences = inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        val newLineEnter = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        return multiLine && sentences && newLineEnter
    }
}
