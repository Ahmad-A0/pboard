package dev.pboard.ime

import android.text.InputType
import android.view.KeyEvent

/**
 * Editing rules measured on Gboard 18.6 (farm conformance harness and targeted runs), kept free of Android state so
 * they can be unit-tested.
 */
object ImeRules {
    /**
     * SU-09 / CO-01 (farm conformance): a word left in the field as typed is learned, as Gboard 18.6 does, whether the
     * input session ends ([restart] false: focus moved on, keyboard hidden, cursor moved away) or the app restarts
     * input on the field (a reset field, a changed input type). Not for glides, re-opened words, the IME's own boxes
     * (emoji search, translate), where learning is off, or when the restart is the keyboard's own rebuild (rotation,
     * theme, settings: [sinceRebuildMs] within [REBUILD_WINDOW_MS]) and the user is likely to type on.
     */
    fun learnsLeftWord(
        typedByTaps: Boolean, reopened: Boolean, internalEditor: Boolean, learningAllowed: Boolean,
        restart: Boolean = false, sinceRebuildMs: Long = Long.MAX_VALUE,
    ): Boolean = typedByTaps && !reopened && !internalEditor && learningAllowed && !(restart && sinceRebuildMs < REBUILD_WINDOW_MS)

    const val REBUILD_WINDOW_MS = 1500L

    /**
     * A plain backspace over a grapheme of [graphemeChars] UTF-16 chars reaches the app as a KEYCODE_DEL key event
     * only for a single char (TP-17, also inside the word being typed); longer graphemes (any emoji outside the BMP,
     * sequences, combining marks) are one text edit (LT-05: "a😀", "a🇬🇧", "aé" as text, "a♥" as a key).
     */
    fun backspaceSendsKey(graphemeChars: Int) = graphemeChars == 1

    /**
     * Gesture typing (glide) works in every text field except passwords. Gboard 18.6 decodes glides in URL fields too
     * (conformance fuzz/1/6: a glide typed "people" there), and e-mail fields follow the same rule; taps in those fields
     * still type exactly the key hit.
     */
    fun gestureTypingAllowed(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
            else -> true
        }
    }

    /**
     * How far the word the cursor was tapped into goes on after the cursor: its letters (and apostrophes between
     * letters) at the start of [after], the text read after the cursor (at most [readLimit] chars); -1 when the word
     * runs past what was read. Gboard 18.6 offers suggestions for the whole touched word (CO-04: a tap between "the" and
     * "ir" of "their" offers "their | theirs | there"; picking one replaces the word).
     */
    fun wordTailLength(after: String, readLimit: Int = 48): Int {
        var i = 0
        while (i < after.length) {
            val cp = after.codePointAt(i)
            val n = Character.charCount(cp)
            val apostrophe = (cp == '\''.code || cp == '’'.code) && i + n < after.length && Character.isLetter(after.codePointAt(i + n))
            if (Character.isLetter(cp) || apostrophe) i += n else break
        }
        return if (i == after.length && after.length >= readLimit) -1 else i
    }

    /**
     * A glided word is capitalised by the shift state alone. At the start of a sentence it is capitalised only when the
     * shift is on (auto-caps or the user's), never because the field's text is at a sentence start. Gboard 18.6
     * conformance (440 delta, 4 scenarios): a glide of "hello" into an empty field without CAP_SENTENCES gives "hello",
     * where pboard gave "Hello".
     */
    fun glideSentenceStart(textAtSentenceStart: Boolean, shiftActive: Boolean) = textAtSentenceStart && shiftActive

    /**
     * Whether committing the composing word ([typed], committed as [committed]) teaches it to the user model. Field
     * text re-opened as it stood ([reopenedAsIs]: on focus, or with the cursor placed at it) and committed unchanged
     * was not typed by the user. Learning it let an app's prefilled "one two three four" teach "four" when a delete
     * swipe started on it, and the next glide at a sentence start gave "Four" where Gboard 18.6 gives "For"
     * (conformance fuzz/1/1 step 11). A correction of it, or letters typed or deleted in it, is learned as before.
     */
    fun learnsCommittedWord(typed: String, committed: String, reopenedAsIs: Boolean) = !reopenedAsIs || committed != typed

    /**
     * Whether a field allows auto-correction, apart from the user's setting. This is Gboard 18.6's policy,
     * measured on a farm gb18-1 instance by typing "this becuase i dont " into fields of each kind.
     * - Never: no input type, passwords, number / phone / date classes, CAP_CHARACTERS fields (Gboard typed "THIS
     *   BECUASE I DONT"), and e-mail address fields (literal only, no alternatives in the strip).
     * - Only with TYPE_TEXT_FLAG_AUTO_CORRECT: URI, filter, phonetic, web edit text and web e-mail fields (URI and web
     *   edit text: nothing corrected without the flag, "because I don't" with it).
     * - Always, with or without the flag: every other text field. That includes normal, short and long messages,
     *   e-mail subject, postal address, person name ("because I don't") and NO_SUGGESTIONS fields, which Gboard
     *   corrects in full ("this because I don't"). CAP_WORDS and NO_SUGGESTIONS do not matter.
     * Learning is decided apart (EditorState.noLearning).
     */
    fun fieldAutoCorrects(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return false
        val autoCorrectFlag = inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> false
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_PHONETIC,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> autoCorrectFlag
            else -> true
        }
    }

    /** Number, phone and date/time fields get digits as KEYCODE_0..9 key events, text fields as text (CX-03). */
    fun digitKeyEvents(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
    }

    /** The key code for typing [text] as a digit key event, or null when it is committed as text. */
    fun digitKeyCode(text: String, inputType: Int): Int? =
        if (digitKeyEvents(inputType) && text.length == 1 && text[0] in '0'..'9') KeyEvent.KEYCODE_0 + (text[0] - '0') else null

    /**
     * Auto-capitalisation applies between words; in CAP_CHARACTERS fields also inside the word (shift stays on after
     * a letter, CX-02 capchars).
     */
    fun autoCapsApplies(composingEmpty: Boolean, capCharacters: Boolean) = composingEmpty || capCharacters

    /**
     * NO_SUGGESTIONS fields apply only corrections that change case or add apostrophes ("i" -> "I", "im" -> "I'm",
     * "dont" -> "don't"; not "teh" -> "the").
     */
    fun caseOrApostropheOnly(typed: String, corrected: String) =
        withoutApostrophes(corrected).equals(withoutApostrophes(typed), ignoreCase = true)

    private fun withoutApostrophes(s: String) = s.replace("'", "").replace("’", "")

    /**
     * Revert by retyping (Gboard 18.6 tracks auto-correction reverts in its decoder, not only through the off-by-default
     * "undo auto-correct on backspace"): the user backspaced into an auto-correction ([original] -> corrected, the
     * corrected word starting at [correctionStart]) and, by editing it or deleting and retyping it, put back the word as
     * originally typed ([typed], now starting at [start]). Committing it undoes the correction: it is not applied again,
     * the pair is remembered as rejected and the word is learned as intended. "Tolu " -> "Told "; backspace, delete
     * "d", type "u", space gives "Tolu ".
     */
    fun retypesCorrectedOriginal(typed: String, start: Int, original: String, correctionStart: Int) =
        start == correctionStart && typed.isNotEmpty() && typed.equals(original, ignoreCase = true)

    /** Delete repeat: the next repeat runs this long after the previous one ran (mean 51.3 ms on Gboard 18.6). */
    const val DELETE_REPEAT_START_MS = 420L
    const val DELETE_REPEAT_INTERVAL_MS = 51L

    /**
     * Space-bar cursor drag (ED-01): a step per [stepPx] of travel from the touch-down point. The travel done before
     * the scrub starts moves one character per step; afterwards a step moves two characters when the finger is faster
     * than [fastPxPerMs] (176 dp/s), and also within [BOOST_MS] of a start whose hold time ran out after the finger had
     * already passed a step. Gboard 18.6: -234 px in 600 ms moves 6, a drag that waits before moving one per step.
     */
    class Scrub(private val stepPx: Float, private val fastPxPerMs: Float) {
        private var steps = 0
        private var startT = 0L
        private var boost = false
        private var speedX = 0f
        private var speedT = 0L
        private var speed = 0f

        /** Starts the scrub at [x] ([dx] from touch-down, [downT]); returns the cursor movement. */
        fun start(dx: Float, x: Float, time: Long, downT: Long, travelledEarly: Boolean): Int {
            steps = (dx / stepPx).toInt()
            startT = time
            boost = travelledEarly
            speed = kotlin.math.abs(dx) / maxOf(1L, time - downT)
            speedX = x
            speedT = time
            return steps
        }

        /** The finger moved to [x] ([dx] from touch-down); returns the cursor movement (0 for none). */
        fun move(dx: Float, x: Float, time: Long): Int {
            if (time - speedT >= SPEED_WINDOW_MS) {
                speed = kotlin.math.abs(x - speedX) / (time - speedT)
                speedX = x
                speedT = time
            }
            val now = (dx / stepPx).toInt()
            if (now == steps) return 0
            val fast = speed > fastPxPerMs || (boost && time - startT < BOOST_MS)
            val delta = (now - steps) * (if (fast) 2 else 1)
            steps = now
            return delta
        }

        companion object {
            const val FAST_DP_PER_MS = 0.176f
            const val BOOST_MS = 140L
            const val SPEED_WINDOW_MS = 48L
        }
    }
}
