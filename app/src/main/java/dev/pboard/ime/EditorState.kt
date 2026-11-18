package dev.pboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.pboard.ime.config.Config
import dev.pboard.ime.layout.EnterAction
import dev.pboard.ime.layout.FieldKind
import dev.pboard.ime.layout.FieldVariant
import dev.pboard.ime.layout.Page

/**
 * What the current editor allows, derived once per onStartInput from its EditorInfo. [internal] marks the keyboard's
 * own text box (emoji search): no emoji key on its letters page.
 */
class EditorState(val info: EditorInfo?, val internal: Boolean = false) {
    val inputType = info?.inputType ?: InputType.TYPE_NULL
    private val cls = inputType and InputType.TYPE_MASK_CLASS
    private val variation = inputType and InputType.TYPE_MASK_VARIATION
    private val flags = inputType and InputType.TYPE_MASK_FLAGS
    private val imeOptions = info?.imeOptions ?: 0

    val isNull = inputType == InputType.TYPE_NULL

    val kind: FieldKind = when (cls) {
        InputType.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
        InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
        InputType.TYPE_CLASS_DATETIME -> FieldKind.DATETIME
        InputType.TYPE_CLASS_TEXT -> when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
            InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
            else -> FieldKind.TEXT
        }
        else -> FieldKind.TEXT
    }

    val isPassword = kind == FieldKind.PASSWORD ||
        (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

    val isMultiLine = flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 ||
        flags and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0

    /** Incognito: the app asked us not to learn from this field. */
    val incognito = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    private val isTextClass = cls == InputType.TYPE_CLASS_TEXT
    private val capWordsOrChars = flags and (InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0

    /**
     * The field asks for capitals (CAP_SENTENCES, CAP_WORDS or CAP_CHARACTERS): Gboard 18.6 then capitalises a
     * lower-case non-word proper noun on space ("brian " -> "Brian "), and keeps "london" in a field without them.
     */
    val capitals = flags and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
        InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0

    /**
     * Show the suggestion strip and compose words. Like Gboard, TYPE_TEXT_FLAG_NO_SUGGESTIONS does not hide the strip
     * (nor turn auto-correction off); passwords never get suggestions.
     */
    val suggestions = isTextClass && !isPassword && Config.current.showSuggestionStrip && Config.current.suggestionStripVisible

    /** The field itself allows auto-correction (Gboard 18.6's field policy, [ImeRules.fieldAutoCorrects]). */
    private val fieldCorrects = ImeRules.fieldAutoCorrects(inputType)

    /** Replace mistyped words on space/punctuation: the field allows it, the strip is on and so is the setting. */
    val autoCorrect = suggestions && Config.current.autoCorrection && fieldCorrects

    /**
     * No learning in passwords, incognito fields, non-text fields, CAP_WORDS/CAP_CHARACTERS fields and fields that do
     * not allow auto-correction; email-address fields are the exception that does learn (Gboard). Independent of the
     * auto-correction setting.
     */
    val noLearning = incognito || isPassword || !isTextClass || capWordsOrChars ||
        (!fieldCorrects && kind != FieldKind.EMAIL)

    /** Glide typing: every text field but passwords, URL and e-mail fields included ([ImeRules.gestureTypingAllowed]). */
    val gesture = ImeRules.gestureTypingAllowed(inputType) && Config.current.gestureTyping

    /** CAP_CHARACTERS shows as shifted (not caps lock) and re-arms after every letter via the caps mode. */
    val capsMode = CAPS_AUTO

    val enterAction: EnterAction
        get() {
            // Measured: in multi-line fields Enter always inserts a newline, even when the action is Send.
            if (isMultiLine) return EnterAction.RETURN
            return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO -> EnterAction.GO
                EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
                EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
                EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
                else -> if (isMultiLine || isNull) EnterAction.RETURN else EnterAction.DONE
            }
        }

    /** The editor action to perform for enter, or null to insert a newline. */
    val editorAction: Int?
        get() = when (enterAction) {
            EnterAction.RETURN -> null
            else -> (imeOptions and EditorInfo.IME_MASK_ACTION).takeIf { it != EditorInfo.IME_ACTION_NONE && it != EditorInfo.IME_ACTION_UNSPECIFIED }
                ?: EditorInfo.IME_ACTION_DONE
        }

    val initialPage: Page = when (kind) {
        FieldKind.NUMBER -> if (isPassword) Page.NUMBER_PASSWORD else Page.NUMBER
        FieldKind.DATETIME -> Page.NUMBER
        FieldKind.PHONE -> Page.PHONE
        else -> Page.LETTERS
    }

    fun variant(multiLanguage: Boolean) = FieldVariant(
        kind = kind,
        enter = enterAction,
        showEmojiKey = Config.current.emojiSwitchKey && !isPassword && !internal,
        // Gboard 18.6 "Number row for passwords" (only meaningful while the number row itself is off).
        numberRow = Config.current.numberRow || (isPassword && Config.current.numberRowInPassword),
        numberHints = Config.current.numberHints,
        multiLanguage = multiLanguage,
        commaKey = Config.current.showCommaKey,
        periodKey = Config.current.showPeriodKey,
        dateKind = when (variation) {
            InputType.TYPE_DATETIME_VARIATION_DATE -> "date"
            InputType.TYPE_DATETIME_VARIATION_TIME -> "time"
            else -> "datetime"
        },
    )

    /** Rich content (stickers/GIFs) is only offered when the editor accepts images. */
    val acceptsImages = info?.contentMimeTypes?.any { it.startsWith("image/") } == true

    /** Gboard 18.6 offers voice typing in every text field except passwords (15.1 also disabled it in e-mail fields). */
    val voiceAllowed = isTextClass && !isPassword

    /** Digits are sent as KEYCODE_0..9 key events, not committed text: number, phone and date/time fields (Gboard 18.6, CX-03). */
    val digitKeyEvents = ImeRules.digitKeyEvents(inputType)

    /** CAP_CHARACTERS text fields: shift stays on mid-word and suggestions are upper case (Gboard 18.6). */
    val capCharacters = isTextClass && flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0

    /** Fields where every tap types exactly the key hit (no tap-time key resolution): URL, e-mail, passwords, non-text. */
    val exactKeys = !isTextClass || isPassword || kind == FieldKind.URL || kind == FieldKind.EMAIL

    companion object {
        const val CAPS_AUTO = 0
        const val CAPS_ALL = 1
    }
}
