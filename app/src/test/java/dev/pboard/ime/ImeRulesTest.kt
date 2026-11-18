package dev.pboard.ime

import android.text.InputType
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gboard 18.6 editing rules measured on the emulator farm (conformance harness and targeted runs). */
class ImeRulesTest {
    @Test fun retypingTheOriginalWhereItWasCorrectedUndoesTheCorrection() {
        // "I met Tolu " -> "Told "; backspace re-opens "Told" at 6; delete "d", type "u": "Tolu" at 6 again.
        assertTrue(ImeRules.retypesCorrectedOriginal("Tolu", 6, "Tolu", 6))
        assertTrue(ImeRules.retypesCorrectedOriginal("tolu", 6, "Tolu", 6))
        // Kept the correction, typed something else, or typed it elsewhere: no revert.
        assertFalse(ImeRules.retypesCorrectedOriginal("Told", 6, "Tolu", 6))
        assertFalse(ImeRules.retypesCorrectedOriginal("Tol", 6, "Tolu", 6))
        assertFalse(ImeRules.retypesCorrectedOriginal("Tolu", 11, "Tolu", 6))
        assertFalse(ImeRules.retypesCorrectedOriginal("", 6, "", 6))
    }

    @Test fun aWordLeftInTheFieldIsLearnedWhenInputEndsOrRestarts() {
        // Session end (focus moved on, keyboard hidden) and an app restart of the field (CO-01 reset between scenarios).
        assertTrue(ImeRules.learnsLeftWord(typedByTaps = true, reopened = false, internalEditor = false, learningAllowed = true))
        assertTrue(ImeRules.learnsLeftWord(true, false, false, true, restart = true, sinceRebuildMs = 60_000))
        // Not right after the keyboard's own rebuild (rotation, theme): the user is likely to type on.
        assertFalse(ImeRules.learnsLeftWord(true, false, false, true, restart = true, sinceRebuildMs = 200))
        // Never for glides, re-opened words, the IME's own boxes, or where learning is off.
        assertFalse(ImeRules.learnsLeftWord(false, false, false, true))
        assertFalse(ImeRules.learnsLeftWord(true, true, false, true))
        assertFalse(ImeRules.learnsLeftWord(true, false, true, true))
        assertFalse(ImeRules.learnsLeftWord(true, false, false, false))
    }

    @Test fun backspaceKeyEventOnlyForASingleChar() {
        assertTrue(ImeRules.backspaceSendsKey("h".length))
        assertTrue(ImeRules.backspaceSendsKey("♥".length))              // ♥: KEYCODE_DEL
        assertFalse(ImeRules.backspaceSendsKey("😀".length))        // 😀: text edit
        assertFalse(ImeRules.backspaceSendsKey("é".length))             // e + combining acute
        assertFalse(ImeRules.backspaceSendsKey("🇬🇧".length)) // 🇬🇧
    }

    @Test fun glideWorksInEveryTextFieldButPasswords() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(ImeRules.gestureTypingAllowed(text))
        assertTrue(ImeRules.gestureTypingAllowed(text or InputType.TYPE_TEXT_VARIATION_URI)) // fuzz/1/6: "people"
        assertTrue(ImeRules.gestureTypingAllowed(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(ImeRules.gestureTypingAllowed(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertFalse(ImeRules.gestureTypingAllowed(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(ImeRules.gestureTypingAllowed(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(ImeRules.gestureTypingAllowed(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(ImeRules.gestureTypingAllowed(InputType.TYPE_CLASS_NUMBER))
        assertFalse(ImeRules.gestureTypingAllowed(InputType.TYPE_CLASS_PHONE))
    }

    @Test fun touchedWordRunsToItsEnd() {
        assertEquals(2, ImeRules.wordTailLength("ir yesterday")) // "the|ir" -> the whole "their" (CO-04)
        assertEquals(1, ImeRules.wordTailLength("b cd")) // "a|b cd" (TP-02 pick-inside-word)
        assertEquals(0, ImeRules.wordTailLength(" yesterday")) // at the end of a word
        assertEquals(0, ImeRules.wordTailLength("")) // at the end of the text
        assertEquals(2, ImeRules.wordTailLength("'t do")) // apostrophe inside: "don|'t" -> "don't"
        assertEquals(0, ImeRules.wordTailLength("' x")) // a closing quote is not part of the word
        assertEquals(3, ImeRules.wordTailLength("ird."))
        assertEquals(-1, ImeRules.wordTailLength("a".repeat(48))) // runs past what was read
    }

    @Test fun glideCapitalisationFollowsTheShiftOnly() {
        // Empty field without CAP_SENTENCES: no auto-shift, so "hello" stays lower case (440 delta, cluster 15).
        assertFalse(ImeRules.glideSentenceStart(textAtSentenceStart = true, shiftActive = false))
        // Field with CAP_SENTENCES at a sentence start: the auto-shift capitalises it.
        assertTrue(ImeRules.glideSentenceStart(textAtSentenceStart = true, shiftActive = true))
        // Mid-sentence the engine's sentence start is off whatever the shift (a manual shift capitalises afterwards).
        assertFalse(ImeRules.glideSentenceStart(textAtSentenceStart = false, shiftActive = true))
    }

    @Test fun fieldTextReopenedAsItStoodIsNotLearned() {
        // fuzz/1/1: prefilled "one two three four", a delete swipe commits the re-opened "four": not learned.
        assertFalse(ImeRules.learnsCommittedWord("four", "four", reopenedAsIs = true))
        // Typed (or edited after re-opening): learned.
        assertTrue(ImeRules.learnsCommittedWord("four", "four", reopenedAsIs = false))
        // Re-opened and corrected on commit: the correction is learned.
        assertTrue(ImeRules.learnsCommittedWord("teh", "the", reopenedAsIs = true))
    }

    @Test fun fieldCorrectionPolicyAsGboard() {
        val text = InputType.TYPE_CLASS_TEXT
        val af = InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        fun corrects(t: Int) = ImeRules.fieldAutoCorrects(t)
        // corrected on Gboard 18.6 (the emulator farm): "this becuase i dont " -> "this because I don't "
        assertTrue(corrects(text))
        assertTrue(corrects(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertTrue(corrects(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
        assertTrue(corrects(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS))
        assertTrue(corrects(text or InputType.TYPE_TEXT_VARIATION_URI or af))
        assertTrue(corrects(text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or af or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertTrue(corrects(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertTrue(corrects(text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT))
        assertTrue(corrects(text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS))
        // left as typed
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or af))
        assertFalse(corrects(text or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_FILTER))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_PHONETIC))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_PASSWORD or af))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(corrects(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(corrects(InputType.TYPE_NULL))
        assertFalse(corrects(InputType.TYPE_CLASS_NUMBER or af))
        assertFalse(corrects(InputType.TYPE_CLASS_PHONE))
        assertFalse(corrects(InputType.TYPE_CLASS_DATETIME))
    }

    @Test fun digitsAreKeyEventsInNumberPhoneAndDateFields() {
        val number = InputType.TYPE_CLASS_NUMBER
        assertEquals(KeyEvent.KEYCODE_4, ImeRules.digitKeyCode("4", number))
        assertEquals(KeyEvent.KEYCODE_0, ImeRules.digitKeyCode("0", number or InputType.TYPE_NUMBER_FLAG_SIGNED))
        assertEquals(KeyEvent.KEYCODE_9, ImeRules.digitKeyCode("9", InputType.TYPE_CLASS_PHONE))
        assertEquals(KeyEvent.KEYCODE_5, ImeRules.digitKeyCode("5", InputType.TYPE_CLASS_DATETIME))
        assertNull(ImeRules.digitKeyCode(".", number))
        assertNull(ImeRules.digitKeyCode("4", InputType.TYPE_CLASS_TEXT))
    }

    @Test fun capCharactersKeepsShiftInsideTheWord() {
        assertTrue(ImeRules.autoCapsApplies(composingEmpty = true, capCharacters = false))
        assertFalse(ImeRules.autoCapsApplies(composingEmpty = false, capCharacters = false))
        assertTrue(ImeRules.autoCapsApplies(composingEmpty = false, capCharacters = true))
    }

    @Test fun noSuggestionsFieldsOnlyFixCaseAndApostrophes() {
        assertTrue(ImeRules.caseOrApostropheOnly("i", "I"))
        assertTrue(ImeRules.caseOrApostropheOnly("im", "I'm"))
        assertTrue(ImeRules.caseOrApostropheOnly("dont", "don't"))
        assertTrue(ImeRules.caseOrApostropheOnly("dont", "don’t"))
        assertFalse(ImeRules.caseOrApostropheOnly("teh", "the"))
        assertFalse(ImeRules.caseOrApostropheOnly("becuase", "because"))
    }

    /** Deletes in a hold of [holdMs] when each repeat runs [latencyMs] after it was due. */
    private fun deletes(holdMs: Long, latencyMs: Double = 0.3): Int {
        var t = ImeRules.DELETE_REPEAT_START_MS.toDouble()
        var n = 0
        while (t <= holdMs) { n++; t += latencyMs + ImeRules.DELETE_REPEAT_INTERVAL_MS }
        return n
    }

    @Test fun holdDeleteCountsMatchGboard() {
        assertEquals(51, deletes(3000))
        assertEquals(20, deletes(1400))
        assertEquals(16, deletes(1200))
        assertEquals(8, deletes(800))
        assertEquals(2, deletes(500))
    }

    // Space drags at 440 dpi (2.75 px/dp): 8 pt steps, fast above 176 dp/s.
    private val density = 2.75f
    private val stepPx = 8f * density * 160f / 72f

    /**
     * A linear drag of [px] (negative: left) over [ms] after holding [preMs], sampled like the harness (every ~16 ms),
     * through the KeyboardView start rule (200 ms hold and one step of travel). Returns the cursor movement.
     */
    private fun drag(px: Float, ms: Int, preMs: Int = 0): Int {
        val pts = ArrayList<Pair<Float, Long>>()
        if (preMs > 0) pts += 0f to preMs.toLong()
        val n = maxOf(2, ms / 16)
        for (i in 1..n) pts += (px * i / n) to (preMs + ms.toDouble() * i / n).toLong()
        var scrub: ImeRules.Scrub? = null
        var early = false
        var moved = 0
        for ((dx, t) in pts) {
            val x = 592f + dx
            if (scrub == null) {
                val held = t >= 200
                if (!held && kotlin.math.abs(dx) >= stepPx) early = true
                if (held && kotlin.math.abs(dx) >= stepPx) {
                    scrub = ImeRules.Scrub(stepPx, ImeRules.Scrub.FAST_DP_PER_MS * density)
                    moved += scrub.start(dx, x, t, 0L, early)
                }
            } else {
                moved += scrub.move(dx, x, t)
            }
        }
        return moved
    }

    @Test fun cursorDragStepsMatchGboard() {
        assertEquals(-6, drag(-245.1f, 600))           // ED-01 drag-234 (pboard used to move 5)
        assertEquals(-5, drag(-251.3f, 1500))          // slow: one character per step
        assertEquals(-5, drag(-251.3f, 1000))
        assertEquals(-8, drag(-251.3f, 450))           // fast: two per step
        assertEquals(-5, drag(-251.3f, 600, preMs = 300)) // waited before moving: one per step
        assertEquals(-2, drag(-125.7f, 600))
        assertEquals(-1, drag(-62.9f, 600))
    }
}
