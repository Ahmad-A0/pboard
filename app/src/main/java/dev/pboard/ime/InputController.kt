package dev.pboard.ime

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.pboard.engine.Candidate
import dev.pboard.engine.CandidateKind
import dev.pboard.engine.Context
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.ime.engine.EngineRunner
import dev.pboard.engine.Suggestions
import dev.pboard.engine.TouchPoint
import dev.pboard.ime.config.Config
import dev.pboard.ime.layout.Codes
import dev.pboard.ime.layout.Key
import dev.pboard.ime.layout.KeyboardLanguage
import dev.pboard.ime.layout.Page
import dev.pboard.ime.view.KeyboardActionListener
import dev.pboard.ime.view.StripItem
import dev.pboard.ime.view.TopBarListener
import java.text.BreakIterator
import java.util.Locale

/** Shift state of the letters page. */
enum class ShiftState { OFF, ON, AUTO, LOCKED;
    val active get() = this != OFF

    companion object {
        /**
         * Shift when input (re)starts: a new input session starts unshifted, also when the app restarts input on the
         * same field (Gboard: caps lock and a one-shot shift do not carry over; auto-capitalisation is then applied
         * from the editor's caps mode). Only the IME's own restarts (e.g. leaving emoji search) keep it.
         */
        fun atStartInput(current: ShiftState, imeInternal: Boolean): ShiftState = if (imeInternal) current else OFF
    }
}

/** The UI operations the controller needs from the IME service. */
interface KeyboardHost {
    val inputConnection: InputConnection?
    fun setPage(page: Page)
    fun currentPage(): Page
    fun onShiftChanged(state: ShiftState)
    fun showStrip(items: List<StripItem?>)
    fun showToolbar()
    fun keyCenter(c: Char): TouchPoint?
    fun openEmoji()
    fun openClipboard()
    fun openSettings()
    fun openSettings(route: String?)
    fun switchLanguage()
    fun showInputMethodPicker()
    fun performFeedbackKeyDown(code: Int)
    fun tick()
    fun sendKey(keyCode: Int)
    fun performEditorAction(action: Int)
    fun hideKeyboard()
    fun startVoiceInput()
    fun toggleToolbarPanel()
    fun learnedDataChanged()
    /** One-handed mode on/off (toolbar item, comma long-press). */
    fun toggleOneHanded() {}
    /** Toolbar "Text editing" panel. */
    fun openTextEditing() {}
    /** Toolbar "Translate": opens (or closes) the on-device translate bar. */
    fun openTranslate() {}
    /** Toolbar "Floating": undock / dock. */
    fun toggleFloating() {}
    /** Toolbar "Split" (large screens): split layout off / on. */
    fun toggleSplit() {}
    /** Toolbar "Resize" overlay. */
    fun openResize() {}
    /** An emoji was typed from the strip (for the emoji panel's recents). */
    fun emojiCommitted(emoji: String) {}
    /** Toolbar "Stickers": the user's own sticker panel (dev.pboard.ime.rich). */
    fun openStickers() {}
    /** Toolbar "Scan text" (dev.pboard.ime.scan). */
    fun openScanText() {}
}

/**
 * Text handling: composing-region management, suggestions, auto-correct (and its revert), auto-capitalisation,
 * smart punctuation spacing, double-space period, deletion, cursor control and gesture commits.
 */
class InputController(private val host: KeyboardHost) : KeyboardActionListener, TopBarListener {
    /** All engine calls go through this background runner (decoding never blocks touch handling). */
    val runner = EngineRunner()
    /** Context captured when the current word started (avoids an editor round-trip per keystroke). */
    private var composingContext: Context? = null
    var locale: Locale = Locale.US
    var editor = EditorState(null)
        private set

    var shift = ShiftState.OFF
        private set
    private var lastShiftDown = 0L
    /** Double-tap lock is timed from the first tap's release (measured: second tap within ~300 ms). */
    private var lastShiftUp = 0L
    private var shiftWasActiveAtDown = false
    /** The shift state before the last shift touch-down changed it (restored when the tap turns out to be a letter). */
    private var shiftBeforeDown = ShiftState.OFF

    // Composing word
    private val composing = StringBuilder()
    private val taps = ArrayList<TouchPoint>()
    /** The composing word was re-opened from text (no touches recorded for its letters yet). */
    private var textOnly = false
    /** The composing word is field text re-opened as it stood (focus, or the cursor placed at it), not edited since. */
    private var reopenedAsIs = false
    private var composingFromGesture = false
    private var suggestions = Suggestions.EMPTY
    /** Candidates shown in the strip slots (left, middle, right, and a second emoji sharing the right slot). */
    private val slots = arrayOfNulls<Candidate>(4)

    /** Emoji for a word (CLDR keywords), or null while the annotations are not loaded. */
    var emojiFor: ((String) -> List<String>)? = null
    /** Strip shows next-word predictions (no composing word). */
    private var showingPredictions = false

    // Editor text model
    private var cursor = -1
    private var selEnd = -1
    private var composingStart = -1

    // Smart behaviours
    private class AutoCorrection(val typed: String, val corrected: String, val separator: String, val end: Int)
    private var lastAutoCorrection: AutoCorrection? = null
    /** The original of an auto-correction the user backspaced into; shown in the left slot to restore it. */
    private var revertOffer: AutoCorrection? = null
    /**
     * The auto-correction the user backspaced into, while they edit that word (it starts at [correctionUnderEditStart]):
     * typing the original back there and committing it undoes the correction ([ImeRules.retypesCorrectedOriginal]).
     * Survives the word being deleted to nothing and retyped; dropped on the next commit or when the cursor moves away.
     */
    private var correctionUnderEdit: AutoCorrection? = null
    private var correctionUnderEditStart = -1
    private val rejectedCorrections = HashSet<String>()
    /** A space we inserted automatically (after a picked suggestion): punctuation swaps with it. */
    private var autoSpaceAt = -1
    /** After a gesture: a space is added before the next word typed (not before punctuation). */
    private var phantomSpace = false
    private var gestureSpaceBefore = false
    private var lastSpaceTime = 0L
    private var lastSpaceAt = -1

    // Delete swipe
    private var swipeAnchor = -1
    private var swipeText = ""

    private var icTarget: InputConnection? = null
    private var icLazy: InputConnection? = null

    /** The editor, through [LazyRegion] (a re-opened word is marked as composing only once it is edited). */
    private val ic: InputConnection?
        get() {
            val t = host.inputConnection ?: return null
            if (t !== icTarget) { icTarget = t; icLazy = LazyRegion(t) }
            return icLazy
        }

    /**
     * The editor range of a word re-opened for suggestions but not yet marked as composing in the editor.
     *
     * Gboard 18.6 shows suggestions for the word at the cursor when a field opens or a tap puts the cursor at the end
     * of a word, but sets no composing region until the word is edited (measured: composingStart stays -1). A
     * composing region on a word nobody is editing gets it spell-checked at once. A tap into a word the spell checker
     * marked opens the app's own suggestion popup, and that popup takes the window focus from the field and the
     * keyboard: conformance farm-440-19, CO-04 and ED-01. pboard used to mark the word at once.
     */
    private var pendingRegion: IntRange? = null

    /**
     * The open word is one the cursor was tapped into, not typed, with letters after the cursor (Gboard 18.6 CO-04: a
     * tap into "their" offers "their | theirs | there", and picking one replaces the whole word). Only a strip pick
     * edits it as a whole; any other input first leaves it ([leaveTouchedWord]) and edits at the cursor as usual.
     */
    private var touchedMidWord = false

    /** Drops a touched mid-word word without editing anything (nothing was marked in the editor). */
    private fun leaveTouchedWord() {
        if (!touchedMidWord) return
        touchedMidWord = false
        resetComposing(finish = false)
    }

    /**
     * The editor seen through pboard's composing model, without composing text in the editor.
     *
     * Gboard 18.6 never opens a composition (measured on a farm gb18-1 instance while typing "hel" / "hello wor":
     * composing region -1 in native EditText fields of every input type tried, and no compositionstart event in WebView
     * input, textarea and contenteditable fields). It edits the word with plain inserts and replacements. Pages that
     * format their value on every input event skip events during a composition, so composing text left words
     * unformatted ("HELLO wor", app-compat finding 4).
     *
     * InputController still keeps its word as "composing": composingStart, the composing buffer and the cursor. This
     * wrapper turns the composing calls into plain edits of that word's range:
     * - setComposingRegion marks a range;
     * - setComposingText replaces the marked range, or inserts at the cursor. It does that with an insert of only the
     *   new letters when the word just grew (one input event per key, as with Gboard), else with delete plus commit in
     *   one batch;
     * - commitText replaces the marked range and closes it;
     * - finishComposingText only closes it.
     * [pendingRegion] (a re-opened word) becomes the marked range on its first edit. A real composition is still opened
     * for a grammar-marked word ([composeForReal]), as Gboard does.
     */
    private inner class LazyRegion(target: InputConnection) : android.view.inputmethod.InputConnectionWrapper(target, false) {
        /** The word's range in the editor while it is open: start, end (exclusive); -1 when none. */
        private var vStart = -1
        private var vEnd = -1
        /** The word's text as last written, when known (a key event or the app may have changed it since). */
        private var vText: String? = null
        /** A real composition is open in the editor (a grammar-marked word). */
        private var real = false
        val isReal get() = real

        /** Cursor positions our own recent edits put the editor at (its selection updates may arrive late). */
        private val recent = IntArray(8) { -1 }
        private var recentAt = 0
        private fun expect(pos: Int) { recent[recentAt] = pos; recentAt = (recentAt + 1) % recent.size }
        fun isOwnCursor(pos: Int) = pos >= 0 && recent.any { it == pos }

        private fun close() { vStart = -1; vEnd = -1; vText = null }

        private fun attach() {
            val r = pendingRegion ?: return
            pendingRegion = null
            mark(r.first, r.last + 1)
        }

        private fun mark(start: Int, end: Int) { vStart = start; vEnd = end; vText = null }

        /** Replaces the open word (or inserts at the cursor) with [t]; the cursor is [cursor] as pboard knows it. */
        private fun replace(t: CharSequence) {
            if (vStart < 0) { vStart = cursor; vEnd = cursor }
            val before = (cursor - vStart).coerceAtLeast(0)
            val after = (vEnd - cursor).coerceAtLeast(0)
            val known = vText
            if (after == 0 && known != null && known.length == before && t.length >= known.length && t.startsWith(known)) {
                if (t.length > known.length) super.commitText(t.subSequence(known.length, t.length), 1)
            } else {
                super.beginBatchEdit()
                if (before > 0 || after > 0) super.deleteSurroundingText(before, after)
                if (t.isNotEmpty()) super.commitText(t, 1)
                super.endBatchEdit()
            }
            vEnd = vStart + t.length
            vText = t.toString()
            expect(vEnd)
        }

        /** A key event (sent outside this wrapper) deleted [n] chars before the cursor, inside the open word. */
        fun keyDeleted(n: Int) {
            expect(cursor - n)
            if (vStart < 0) return
            vEnd = (vEnd - n).coerceAtLeast(vStart)
            vText = null
        }

        /** A real composition over [start, end) showing [text] (the grammar-marked word, tinted as on Gboard). */
        fun composeForReal(start: Int, end: Int, text: CharSequence) {
            close()
            pendingRegion = null
            super.beginBatchEdit()
            super.setComposingRegion(start, end)
            super.setComposingText(text, 1)
            super.endBatchEdit()
            real = true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (real) return super.setComposingText(text, newCursorPosition)
            attach(); replace(text ?: ""); return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (real) { real = false; return super.commitText(text, newCursorPosition) }
            attach()
            if (vStart < 0) { if (newCursorPosition == 1 && selEnd == cursor) expect(cursor + (text?.length ?: 0)); return super.commitText(text, newCursorPosition) }
            replace(text ?: ""); close(); return true
        }
        override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean { attach(); return super.commitCorrection(info) }
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean { attach(); return super.commitCompletion(text) }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { pendingRegion = null; close(); return super.deleteSurroundingText(beforeLength, afterLength) }
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean { pendingRegion = null; close(); return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
        override fun sendKeyEvent(event: KeyEvent?): Boolean { vText = null; return super.sendKeyEvent(event) }
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            pendingRegion = null
            if (real) { real = false; super.finishComposingText() }
            mark(start, end)
            return true
        }
        override fun setSelection(start: Int, end: Int): Boolean { pendingRegion = null; close(); return super.setSelection(start, end) }
        override fun finishComposingText(): Boolean {
            pendingRegion = null
            close()
            if (real) { real = false; return super.finishComposingText() }
            return true
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** When the keyboard last rebuilt its views or the configuration changed (see [ImeRules.learnsLeftWord]). */
    private var lastRebuildAt = Long.MIN_VALUE / 2

    /** The keyboard rebuilds its views (configuration or settings change): an input restart right after keeps the word. */
    fun noteRebuild() { lastRebuildAt = SystemClock.uptimeMillis() }

    fun startInput(state: EditorState, selStart: Int, selEndArg: Int, restarting: Boolean, imeInternal: Boolean = false) {
        // The app restarted input on the field (or a new one) while a word was being typed: it is left in the field as
        // typed, like at the end of a session (CO-01: the harness resets the field between scenarios). Judged against
        // the editor the word was typed in, before it is replaced.
        if (!imeInternal) abandonComposing(restart = true)
        editor = state
        // Never drop a composing word silently: input restarts on the same editor without finishing it (rotation and
        // theme changes restart the input view, so do keyboard rebuilds and the app's restartInput), and the editor
        // still holds the composing region, which the next word typed would replace (app-compat run: "hello wor",
        // rotate, "ld " gave "Hello ld "). Gboard closes the composition there too.
        if (composing.isNotEmpty()) ic?.finishComposingText()
        resetComposing(finish = false)
        cursor = selStart
        selEnd = selEndArg
        // EditorInfo.initialSel* is where the cursor was when the field first got focus: after a rotation or when the
        // keyboard reopens in the same field it is stale. Ask the editor where the cursor is now.
        if (!imeInternal) editorSelection()?.let { cursor = it[0]; selEnd = it[1] }
        lastAutoCorrection = null
        correctionUnderEdit = null
        autoSpaceAt = -1
        phantomSpace = false
        if (!restarting) { rejectedCorrections.clear(); editorReportsComposing = false }
        // A new input session starts unshifted (then auto-caps as its caps mode says): a manual shift or caps lock
        // does not carry over, also not when the app restarts input on the same field (measured on Gboard; pboard
        // used to keep caps lock there and typed "A" mid-word).
        if (!imeInternal) shiftHeld = false
        setShift(ShiftState.atStartInput(shift, imeInternal))
        updateShift(force = true)
        startStrip()
        prepareContext()
    }

    /**
     * The context changed outside a commit (a field opened, the cursor moved, a word re-opened): the engine prepares
     * it on its thread (the neural LM's state for the sentence so far) so the first tap or glide there does not pay.
     */
    fun prepareContext() {
        if (!editor.suggestions) return
        val ctx = composingContext ?: context()
        runner.run("prepare") { it.prepareContext(ctx) }
    }

    /**
     * The strip when a field is focused (CX-08, measured on Gboard 18.6): a word ending at the cursor gets its
     * suggestions at once ("hel" -> "hel | hello | help"), a sentence start after ". " shows the toolbar, other
     * text ending in a space shows next-word predictions ("see you at the ").
     */
    private fun startStrip() {
        val before = if (selEnd == cursor && cursor > 0) ic?.getTextBeforeCursor(48, 0)?.toString() else null
        when {
            before.isNullOrEmpty() -> refreshStrip()
            Character.isLetter(before.codePointBefore(before.length)) -> {
                restartComposingAtCursor(mark = false)
                if (composing.isEmpty()) refreshStrip()
            }
            SENTENCE_END.containsMatchIn(before) -> { runner.invalidate(); slots.fill(null); showingPredictions = false; host.showToolbar() }
            else -> refreshStrip()
        }
    }

    fun finishInput() {
        cancelRestart()
        abandonComposing()
        if (composing.isNotEmpty()) ic?.finishComposingText()
        resetComposing(finish = false)
    }

    /**
     * SU-09: the word being typed is left in the field as typed (the session ends, or the cursor leaves it). Gboard
     * 18.6 learns it: an abandoned "becuase" is offered from "bec" and kept on space the next time. Only words typed
     * key by key (not glides, not re-opened words); nothing is learned where learning is off.
     */
    private fun abandonComposing(restart: Boolean = false) {
        if (composing.isEmpty()) return
        val ctx = composingContext ?: return
        if (!ImeRules.learnsLeftWord(!composingFromGesture && taps.isNotEmpty(), textOnly, editor.internal, ctx.learningAllowed,
                restart, SystemClock.uptimeMillis() - lastRebuildAt)) return
        val word = composing.toString()
        runner.run("abandon") { it.onWordAbandoned(ctx, word) }
        host.learnedDataChanged()
    }

    // ------------------------------------------------------------------ KeyboardActionListener

    override fun onPress(key: Key) = host.performFeedbackKeyDown(key.code)

    private var lastTypedAt = 0L

    override fun onCharacter(key: Key, text: String, x: Float, y: Float, time: Long) {
        leaveTouchedWord()
        emojiSource = null
        lastTypedAt = SystemClock.uptimeMillis()
        val touch = TouchPoint(x, y, time)
        // TF-01 (c): inside a word a tap on a letter next to the space bar or the delete key may end the word or delete;
        // a tap on the edge of "." or "," may type the letter it borders ("ho" + "." under `m`: "hom"), or a space when it is
        // next to the space bar and the sentence goes on ("Where can my little" + "." just right of the bar: a space).
        when (val r = resolveTap(key, text, touch)) {
            RESOLVED_SPACE -> { onSpaceKey(); return }
            RESOLVED_DELETE -> { onDelete(false); return }
            else -> if (!key.isLetter && r != text) { typeResolvedLetter(r, touch); return } else typeText(r, touch)
        }
        // Gboard 18.6 "Auto-switch after apostrophes": an apostrophe typed on a symbols page returns to letters.
        if (text == "'" && Config.current.autoSwitchAfterApostrophe &&
            (host.currentPage() == Page.SYMBOLS || host.currentPage() == Page.SYMBOLS_MORE)
        ) {
            host.setPage(Page.LETTERS)
            updateShift(force = true)
        }
        if (shift == ShiftState.ON || shift == ShiftState.AUTO) {
            if (!shiftHeld) setShift(ShiftState.OFF)
        }
        updateShift()
    }

    /**
     * TF-01 (c): inside a word a tap on the space bar's top edge or the delete key's edge may be a missed letter
     * ("ba" + a tap on the bar's top edge types "c"); the engine decides from the touch and the word so far.
     */
    override fun onSpaceTap(key: Key, x: Float, y: Float, time: Long) {
        leaveTouchedWord()
        val touch = TouchPoint(x, y, time)
        val r = if (composing.isEmpty()) resolveAtWordStart(RESOLVED_SPACE, touch) else resolveInWord(RESOLVED_SPACE, touch)
        when (r) {
            RESOLVED_SPACE -> onFunction(key)
            RESOLVED_DELETE -> onDelete(false)
            else -> typeResolvedLetter(r, touch)
        }
    }

    override fun onDeleteTap(x: Float, y: Float, time: Long) {
        leaveTouchedWord()
        val touch = TouchPoint(x, y, time)
        when (val r = resolveInWord(RESOLVED_DELETE, touch)) {
            RESOLVED_DELETE -> onDelete(false)
            RESOLVED_SPACE -> onSpaceKey()
            else -> typeResolvedLetter(r, touch)
        }
    }

    /** A letter the engine resolved a space-bar or delete tap to (lower case: the shift state applies). */
    private fun typeResolvedLetter(letter: String, touch: TouchPoint) {
        emojiSource = null
        lastTypedAt = SystemClock.uptimeMillis()
        typeText(if (shift.active) letter.uppercase(locale) else letter, touch)
        if ((shift == ShiftState.ON || shift == ShiftState.AUTO) && !shiftHeld) setShift(ShiftState.OFF)
        updateShift()
    }

    private fun onSpaceKey() {
        onSpace()
        // TF-08 (measured on 15.1 and 18.6): a space typed on a symbols page returns to the letters, also after
        // digits ("?123 1 space" -> "1 " on letters).
        if (host.currentPage() == Page.SYMBOLS || host.currentPage() == Page.SYMBOLS_MORE) {
            host.setPage(Page.LETTERS)
            updateShift(force = true)
        }
    }

    override fun onFunction(key: Key) {
        leaveTouchedWord()
        when (key.code) {
            Codes.SPACE -> onSpaceKey()
            Codes.ENTER -> onEnter()
            Codes.SYMBOLS -> host.setPage(Page.SYMBOLS)
            Codes.SYMBOLS_MORE -> host.setPage(Page.SYMBOLS_MORE)
            Codes.ALPHA -> { host.setPage(Page.LETTERS); updateShift(force = true) }
            Codes.NUMPAD -> host.setPage(Page.NUMPAD)
            Codes.PHONE_SYMBOLS -> host.setPage(Page.PHONE_SYMBOLS)
            Codes.PHONE_BACK -> host.setPage(Page.PHONE)
            Codes.EMOJI -> { commitComposing(""); host.openEmoji() }
            // A thumb drifting onto the globe right after typing does not switch language (Gboard: 500 ms).
            Codes.LANGUAGE -> if (SystemClock.uptimeMillis() - lastTypedAt >= GLOBE_IGNORE_MS) {
                commitComposing(""); host.switchLanguage()
            }
        }
    }

    private var shiftHeld = false

    override fun onShiftPress(downTime: Long) {
        if (recaseSelection()) return
        shiftHeld = true
        val now = downTime
        shiftWasActiveAtDown = shift.active
        shiftBeforeDown = shift
        if (shift == ShiftState.LOCKED) {
            setShift(ShiftState.OFF)
        } else if (shift == ShiftState.ON && now - lastShiftUp < SHIFT_DOUBLE_TAP_MS) {
            // TP-07: caps lock when the second touch-down follows the first tap's release within Gboard's window
            // (measured on 18.6 with exact event times: release-to-down 280 and 285 ms lock, 290 ms does not; with
            // 200-300 ms holds, touch-downs 400 ms apart still lock).
            setShift(ShiftState.LOCKED)
        } else if (shift.active) {
            setShift(ShiftState.OFF)
        } else {
            setShift(ShiftState.ON)
        }
        lastShiftDown = now
    }

    /** With text selected, shift cycles its case: lower -> Title -> UPPER -> lower (Gboard ED-03). */
    private fun recaseSelection(): Boolean {
        if (selEnd <= cursor || cursor < 0) return false
        val ic = ic ?: return false
        val sel = ic.getSelectedText(0)?.toString()?.takeIf { it.any(Char::isLetter) } ?: return false
        val title = sel.split(' ').joinToString(" ") { w -> w.lowercase(locale).replaceFirstChar { it.titlecase(locale) } }
        val next = when (sel) {
            sel.lowercase(locale) -> title
            title -> sel.uppercase(locale)
            else -> sel.lowercase(locale)
        }
        val start = cursor
        ic.beginBatchEdit()
        ic.commitText(next, 1)
        ic.setSelection(start, start + next.length)
        ic.endBatchEdit()
        selEnd = start + next.length
        return true
    }

    /**
     * TF-01: inside a word a tap on the shift key's edge may be the letter it borders ("de" + shift under `a`: "dea");
     * the shift the touch-down toggled is then undone.
     */
    override fun onShiftTap(chorded: Boolean, x: Float, y: Float, upTime: Long) {
        if (!chorded) {
            val touch = TouchPoint(x, y, upTime)
            val r = if (composing.isEmpty()) resolveAtWordStart(RESOLVED_SHIFT, touch) else resolveInWord(RESOLVED_SHIFT, touch)
            if (r.codePointCount(0, r.length) == 1 && Character.isLetter(r.codePointAt(0))) {
                lastShiftUp = upTime
                shiftHeld = false
                setShift(shiftBeforeDown)
                typeResolvedLetter(r, TouchPoint(x, y, upTime))
                return
            }
        }
        onShiftRelease(chorded, upTime)
    }

    override fun onShiftRelease(chorded: Boolean, upTime: Long) {
        lastShiftUp = upTime
        shiftHeld = false
        // Shift held while typing letters acts as a modifier: release returns to lower case.
        if (chorded && shift != ShiftState.LOCKED) setShift(ShiftState.OFF)
    }

    override fun onDelete(word: Boolean) {
        leaveTouchedWord()
        val ic = ic ?: return
        if (editor.isNull) { host.sendKey(KeyEvent.KEYCODE_DEL); return }
        phantomSpace = false
        // Right after an auto-correction, backspace removes the separator and re-opens the corrected word; the
        // word as originally typed is offered in the strip's left slot (Gboard 15 measured behaviour).
        val ac = lastAutoCorrection
        if (ac != null && cursor == ac.end && composing.isEmpty() && !word && ac.separator.isNotEmpty()) {
            lastAutoCorrection = null
            val before = ic.getTextBeforeCursor(ac.corrected.length + ac.separator.length, 0)?.toString()
            if (before == ac.corrected + ac.separator && Config.current.undoAutoCorrectOnBackspace) {
                // "Undo auto-correct on backspace" (off by default): restore exactly what was typed.
                ic.beginBatchEdit()
                ic.deleteSurroundingText(before.length, 0)
                cursor -= before.length
                rejectedCorrections += ac.typed.lowercase(locale)
                val ctx = context()
                runner.run { it.onAutoCorrectReverted(ctx, ac.typed, ac.corrected) }
                composingStart = cursor
                composing.setLength(0)
                composing.append(ac.typed)
                textOnly = true
                ic.setComposingText(plain(ac.typed), 1)
                cursor += ac.typed.length
                selEnd = cursor
                ic.endBatchEdit()
                updateSuggestions()
                updateShift()
                return
            }
            if (before == ac.corrected + ac.separator) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(ac.separator.length, 0)
                cursor -= ac.separator.length
                selEnd = cursor
                revertOffer = ac
                correctionUnderEdit = ac
                correctionUnderEditStart = cursor - ac.corrected.length
                restartComposingAtCursor()
                ic.endBatchEdit()
                updateShift()
                return
            }
        }
        lastAutoCorrection = null
        autoSpaceAt = -1
        if (composing.isNotEmpty() && composingFromGesture && !word) {
            // One backspace removes a whole glided word; the engine is told so a re-glide of the same path offers
            // something else. Gboard 18.6 keeps the space it inserted before the word ("Hello world" -> "Hello ";
            // 15.1 removed it too) and offers the deleted word back in the strip.
            val deleted = composing.toString()
            val gctx = context()
            runner.run { (it as? PboardEngine)?.onGestureWordDeleted(gctx, deleted) }
            ic.beginBatchEdit()
            ic.commitText("", 1)
            cursor = composingStart
            ic.endBatchEdit()
            selEnd = cursor
            resetComposing(finish = false)
            phantomSpace = false
            updateShift()
            runner.invalidate()
            slots.fill(null)
            slots[1] = Candidate(deleted, 0f, CandidateKind.PREDICTION)
            showingPredictions = true
            host.showStrip(listOf(null, StripItem(deleted), null))
            return
        }
        if (composing.isNotEmpty()) {
            val n = lastGraphemeLength(composing)
            if (word) {
                ic.commitText("", 1)
                cursor = composingStart
                resetComposing(finish = false)
            } else if (ImeRules.backspaceSendsKey(n)) {
                // Gboard 18.6 (conformance harness, farm-440-11/12): every plain backspace reaches the app as a
                // KEYCODE_DEL key event, also inside the word being typed ("h" + delete, a burst correcting "need").
                // The app shrinks the composing span itself. A word re-opened from the text (no touches) is re-read
                // once the app applied the key; a typed word keeps its touches and is re-decoded at once.
                host.sendKey(KeyEvent.KEYCODE_DEL)
                (icLazy as? LazyRegion)?.keyDeleted(n)
                pendingRegion = pendingRegion?.let { r -> (r.first until r.last + 1 - n).takeUnless { it.isEmpty() } }
                composing.setLength(composing.length - n)
                reopenedAsIs = false
                if (!textOnly && taps.isNotEmpty()) taps.removeAt(taps.size - 1)
                cursor -= n
                selEnd = cursor
                val reread = textOnly || composing.isEmpty()
                if (composing.isEmpty()) resetComposing(finish = false)
                afterKeyDelete = true
                mainHandler.removeCallbacks(keyDeleteSettled)
                mainHandler.postDelayed(keyDeleteSettled, KEY_DELETE_SETTLE_MS)
                if (reread) return
                updateSuggestions()
                return
            } else {
                // A grapheme longer than one UTF-16 char (an emoji, a sequence, a letter with a combining mark) is
                // removed whole as a text edit, without a key event (Gboard 18.6, LT-05).
                composing.setLength(composing.length - n)
                reopenedAsIs = false
                if (taps.isNotEmpty()) taps.removeAt(taps.size - 1)
                if (composing.isEmpty()) {
                    ic.commitText("", 1)
                    cursor = composingStart
                    resetComposing(finish = false)
                } else {
                    ic.setComposingText(plain(composing), 1)
                    cursor = composingStart + composing.length
                }
            }
            updateSuggestions()
            updateShift()
            return
        }
        if (selEnd > cursor && cursor >= 0) {
            ic.commitText("", 1)
            selEnd = cursor
            updateShift()
            refreshStrip()
            return
        }
        val before = ic.getTextBeforeCursor(if (word) 64 else 16, 0)
        if (before.isNullOrEmpty()) {
            host.sendKey(KeyEvent.KEYCODE_DEL)
            return
        }
        val n = if (word) wordDeleteLength(before) else lastGraphemeLength(before)
        // TP-17 (Gboard 18.6, measured by the conformance harness): a plain backspace reaches the app as a
        // KEYCODE_DEL key event (apps such as chip inputs rely on it), held delete too; word deletes, selections and
        // graphemes longer than one UTF-16 char (any emoji outside the BMP, sequences, combining marks: LT-05) stay text
        // edits ("a😀" and "a🇬🇧" delete as text, "a♥" as a key event).
        if (!word && ImeRules.backspaceSendsKey(n)) {
            host.sendKey(KeyEvent.KEYCODE_DEL)
            cursor -= n
            selEnd = cursor
            // The app applies the key event asynchronously: re-read its text (word at the cursor, caps mode, strip)
            // once it reports the new selection.
            afterKeyDelete = true
            mainHandler.removeCallbacks(keyDeleteSettled)
            mainHandler.postDelayed(keyDeleteSettled, KEY_DELETE_SETTLE_MS)
            return
        }
        ic.deleteSurroundingText(n, 0)
        cursor -= n
        selEnd = cursor
        // After a grapheme the word before it is re-opened, as after a key-event backspace ("a🇬🇧" -> "a": a | and).
        if (!word) restartComposingAtCursor()
        updateShift()
        if (composing.isEmpty()) refreshStrip()
    }

    private var afterKeyDelete = false

    /**
     * After a backspace sent as a key event has been applied by the app ([candStart]/[candEnd]: the composing span
     * the app reported, or -2 when unknown). A typed word keeps its touches; the span is re-set only if the app lost it.
     */
    private fun keyDeleteApplied(candStart: Int = -2, candEnd: Int = -2) {
        afterKeyDelete = false
        mainHandler.removeCallbacks(keyDeleteSettled)
        if (composing.isNotEmpty() && !textOnly) {
            if (candStart != -2 && (candStart != composingStart || candEnd != cursor)) {
                ic?.setComposingRegion(composingStart, cursor)
            }
            checkHoldUndo()
            return
        }
        restartComposingAtCursor()
        updateShift()
        if (composing.isEmpty()) refreshStrip()
        checkHoldUndo()
    }

    override fun onDeleteSwipe(words: Int) {
        leaveTouchedWord()
        val ic = ic ?: return
        if (swipeAnchor < 0) {
            commitComposing("")
            swipeAnchor = cursor
            // The text before the anchor, read before any selection exists: once words are selected the editor's
            // "text before the cursor" ends at the selection start, not at the anchor.
            swipeText = ic.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        }
        if (words <= 0) {
            ic.setSelection(swipeAnchor, swipeAnchor)
            return
        }
        // Whole words back from the anchor; the space before the first selected word stays (Gboard: "one two three
        // four" swiped two words -> "one two ").
        val text = swipeText
        var start = text.length
        repeat(words) { start = previousWordStart(text, start) }
        ic.setSelection(swipeAnchor - (text.length - start), swipeAnchor)
        host.tick()
    }

    override fun onDeleteSwipeEnd(commit: Boolean) {
        val ic = ic ?: return
        if (swipeAnchor < 0) return
        var deleted: String? = null
        if (commit) {
            deleted = ic.getSelectedText(0)?.toString()
            ic.commitText("", 1)
            cursor = swipeAnchor - (deleted?.length ?: 0)
        } else {
            ic.setSelection(swipeAnchor, swipeAnchor)
            cursor = swipeAnchor
        }
        selEnd = cursor
        swipeAnchor = -1
        updateShift()
        if (!deleted.isNullOrEmpty()) offerUndo(deleted) else refreshStrip()
    }

    /**
     * Gboard 18.6 offers an "Undo" chip after a delete swipe and after a held delete that empties the field; tapping
     * it puts the deleted text back.
     */
    private fun offerUndo(deleted: String) {
        redoChip = null
        restoreChip = deleted
        runner.invalidate()
        slots.fill(null)
        showingPredictions = false
        host.showStrip(listOf(null, StripItem(deleted.trim(), restore = true), null))
    }

    /** Gboard 18.6: after Undo the chip turns into "Redo", which deletes the restored text again (then Undo again). */
    private fun offerRedo(restored: String) {
        restoreChip = null
        redoChip = restored
        runner.invalidate()
        slots.fill(null)
        showingPredictions = false
        host.showStrip(listOf(null, StripItem(restored.trim(), restore = true, redo = true), null))
    }

    private var redoChip: String? = null

    /** Text before the cursor when a held delete started (for its Undo chip), and the text to offer once applied. */
    private var holdStartText: String? = null
    private var pendingHoldUndo: String? = null

    override fun onDeleteHoldStart() {
        val ic = ic ?: return
        if (ic.getTextAfterCursor(1, 0).isNullOrEmpty()) holdStartText = ic.getTextBeforeCursor(HOLD_UNDO_MAX, 0)?.toString()
    }

    override fun onDeleteHoldEnd() {
        val text = holdStartText ?: return
        holdStartText = null
        // The last key events may still be on their way to the app: decide once it has applied them.
        pendingHoldUndo = text
        if (!afterKeyDelete) checkHoldUndo()
    }

    /** After a held delete: the Undo chip if the field is now empty. */
    private fun checkHoldUndo() {
        val text = pendingHoldUndo ?: return
        pendingHoldUndo = null
        val ic = ic ?: return
        if (composing.isNotEmpty()) return
        UndoChip.afterHold(text, ic.getTextBeforeCursor(1, 0), ic.getTextAfterCursor(1, 0), editor.isPassword)?.let { offerUndo(it) }
    }

    /** Text removed by the last delete swipe, offered in the strip for restoring. */
    private var restoreChip: String? = null

    /** A recently copied clip offered in the strip (Gboard ED-06), and the copy time of the last one used. */
    private var clipChip: String? = null
    private var usedClipTime = 0L

    /**
     * Offers the latest clip as a chip when it is at most 2 minutes old and was not used before; sensitive clips
     * are masked (one bullet per character) but paste the real text. Never in password fields.
     */
    fun offerClipboardChip(cm: android.content.ClipboardManager?) {
        clipChip = null
        if (!Config.current.clipboardSuggestions || editor.isPassword || composing.isNotEmpty()) return
        val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return
        val desc = clip.description ?: return
        val time = desc.timestamp
        if (time <= 0L || time == usedClipTime || SystemClock.elapsedRealtime() - time > CLIP_CHIP_MAX_AGE_MS) return
        val text = clip.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() } ?: return
        val sensitive = desc.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        val shown = if (sensitive) "•".repeat(text.length) else text.replace('\n', ' ')
        clipChip = text
        clipChipTime = time
        slots.fill(null)
        host.showStrip(listOf(null, StripItem(shown, clip = true), null))
    }

    private var clipChipTime = 0L

    /** Smart Compose (dev.pboard.ime.textintel): accepts a shown inline completion; false when none is shown. */
    var acceptCompletion: (() -> Boolean)? = null

    /**
     * Inserts an accepted inline completion at the cursor: the rest of the word being typed (if any) and the words
     * after it, then a space. The composing word is kept as typed (the completion was made for those letters).
     */
    fun insertCompletion(text: String) {
        val ic = ic ?: return
        val typed = composing.toString()
        val insert = typed + text + " "
        ic.beginBatchEdit()
        ic.commitText(insert, 1)
        cursor = (if (composing.isNotEmpty()) composingStart else cursor) + insert.length
        selEnd = cursor
        ic.endBatchEdit()
        resetComposing(finish = false)
        autoSpaceAt = cursor
        phantomSpace = false
        lastAutoCorrection = null
        if (shift == ShiftState.ON || shift == ShiftState.AUTO) setShift(ShiftState.OFF)
        updateShift()
        refreshStrip()
    }

    override fun onCursorMove(delta: Int) {
        leaveTouchedWord()
        // a rightward space-bar swipe accepts a shown completion instead of moving the cursor (Gboard)
        if (delta > 0 && acceptCompletion?.invoke() == true) return
        val ic = ic ?: return
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            resetComposing(finish = false)
        }
        val target = (cursor + delta).coerceAtLeast(0)
        val after = if (delta > 0) ic.getTextAfterCursor(delta, 0)?.length ?: 0 else 0
        val t = if (delta > 0) cursor + after.coerceAtMost(delta) else target
        if (t != cursor) {
            ic.setSelection(t, t)
            cursor = t; selEnd = t
            host.tick()
        }
    }

    override fun onCursorMoveEnd() {
        updateShift()
        refreshStrip()
        // Once the cursor settles where the drag left it, the word there gets its suggestions, as after a tap
        // (Gboard 18.6, ED-01 drag-right).
        if (selEnd == cursor) scheduleRestart()
    }

    override fun onPopupItem(key: Key, item: String) {
        leaveTouchedWord()
        when (item) {
            KeyboardLanguage.POPUP_SETTINGS -> host.openSettings()
            KeyboardLanguage.POPUP_ONE_HANDED -> host.toggleOneHanded()
            else -> {
                typeText(item, TouchPoint(key.rect.centerX(), key.rect.centerY(), SystemClock.uptimeMillis()))
                if (shift == ShiftState.ON || shift == ShiftState.AUTO) setShift(ShiftState.OFF)
                updateShift()
            }
        }
    }

    override fun onLongPress(key: Key) {
        when (key.code) {
            // Gboard 15 no longer opens the picker from the space bar (measured); the globe key still does.
            Codes.LANGUAGE -> host.showInputMethodPicker()
            Codes.SHIFT -> { shiftHeld = false; setShift(ShiftState.LOCKED) }
            Codes.SYMBOLS, Codes.EMOJI -> {}
        }
    }

    override fun onMomentaryRelease(pageKeyCode: Int) {
        when (pageKeyCode) {
            Codes.SYMBOLS -> { host.setPage(Page.LETTERS); updateShift(force = true) }
            Codes.ALPHA -> host.setPage(Page.SYMBOLS)
            Codes.SYMBOLS_MORE -> host.setPage(Page.SYMBOLS)
            Codes.NUMPAD -> host.setPage(Page.SYMBOLS)
            Codes.PHONE_SYMBOLS -> host.setPage(Page.PHONE)
            Codes.PHONE_BACK -> host.setPage(Page.PHONE_SYMBOLS)
        }
    }

    override fun gestureAllowed(): Boolean = editor.gesture && host.currentPage() == Page.LETTERS

    override fun onGestureStart() {
        leaveTouchedWord()
        if (composing.isNotEmpty()) commitComposing("", allowAutoCorrect = false)
        gestureContext = null
        // Live previews and the final decode are one gesture for the engine (CO-06 re-glide demotion).
        runner.run { it.onGestureStarted() }
    }

    private var gestureContext: Context? = null

    /** The decode context of a glide: its capitalisation follows the shift only ([ImeRules.glideSentenceStart]). */
    private fun glideContext(): Context = context().let {
        it.copy(sentenceStart = ImeRules.glideSentenceStart(it.sentenceStart, shift != ShiftState.OFF))
    }

    override fun onGestureUpdate(path: List<TouchPoint>) {
        val ctx = gestureContext ?: glideContext().also { gestureContext = it }
        runner.request("swipe-live", { it.suggestForSwipePreview(ctx, path) }) { s ->
            val top = s.candidates.firstOrNull() ?: return@request
            host.showStrip(listOf(null, StripItem(applyShift(top.text)), null))
        }
    }

    override fun onGestureEnd(path: List<TouchPoint>) {
        val ctx = gestureContext ?: glideContext()
        gestureContext = null
        runner.request("swipe", { it.suggestForSwipe(ctx, path) }) { s -> commitGesture(s) }
    }

    private fun commitGesture(s: Suggestions) {
        val ic = ic ?: return
        val top = s.candidates.firstOrNull() ?: return
        val word = applyShift(top.text)
        ic.beginBatchEdit()
        gestureSpaceBefore = phantomSpace || needsSpaceBefore()
        if (gestureSpaceBefore) {
            ic.commitText(" ", 1)
            cursor += 1
        }
        phantomSpace = false
        composingStart = cursor
        composing.setLength(0)
        composing.append(word)
        composingFromGesture = true
        taps.clear()
        ic.setComposingText(plain(word), 1)
        cursor = composingStart + word.length
        selEnd = cursor
        ic.endBatchEdit()
        suggestions = Suggestions(s.candidates.map { it.copy(text = applyShift(it.text)) }, 0)
        layoutSlots(gesture = true)
        if (shift == ShiftState.ON || shift == ShiftState.AUTO) setShift(ShiftState.OFF)
        phantomSpace = true
        updateShift()
    }

    // ------------------------------------------------------------------ TopBarListener

    override fun onToolbarItem(id: String) {
        when (id) {
            dev.pboard.ime.config.ToolbarItems.EXPAND -> host.toggleToolbarPanel()
            dev.pboard.ime.config.ToolbarItems.CLIPBOARD -> host.openClipboard()
            dev.pboard.ime.config.ToolbarItems.SETTINGS -> host.openSettings()
            dev.pboard.ime.config.ToolbarItems.EMOJI -> host.openEmoji()
            dev.pboard.ime.config.ToolbarItems.THEME -> host.openSettings("theme")
            dev.pboard.ime.config.ToolbarItems.UNDO -> undo()
            dev.pboard.ime.config.ToolbarItems.VOICE -> host.startVoiceInput()
            dev.pboard.ime.config.ToolbarItems.ONE_HANDED -> host.toggleOneHanded()
            dev.pboard.ime.config.ToolbarItems.TEXT_EDITING -> host.openTextEditing()
            dev.pboard.ime.config.ToolbarItems.FLOATING -> host.toggleFloating()
            dev.pboard.ime.config.ToolbarItems.SPLIT -> host.toggleSplit()
            dev.pboard.ime.config.ToolbarItems.RESIZE -> host.openResize()
            dev.pboard.ime.config.ToolbarItems.TRANSLATE -> host.openTranslate()
            dev.pboard.ime.config.ToolbarItems.STICKER -> { commitComposing(""); host.openStickers() }
            "scan_text" -> { commitComposing(""); host.openScanText() }
        }
    }

    /** Long-press + release on a strip slot: "remove suggestion" (forget the learned word / stop offering it). */
    override fun onSuggestionLongPress(slot: Int) {
        val c = slots.getOrNull(slot) ?: return
        if (c.kind == CandidateKind.TYPED) return
        val word = c.text
        runner.run { it.removeSuggestion(word) }
        host.learnedDataChanged()
        if (composing.isNotEmpty()) updateSuggestions() else refreshStrip()
    }

    override fun onSuggestion(slot: Int) {
        val ic = ic ?: return
        if (grammar.offered != null && slot == 1) {
            // Gboard: the fix replaces the mark in one edit, the cursor ends after it, the strip then shows
            // alternatives for the word at the cursor
            grammar.apply(ic, cursor)?.let { end ->
                cursor = end; selEnd = end
                resetComposing(finish = false)
                updateShift()
                restartComposingAtCursor()
                if (composing.isEmpty()) refreshStrip()
                return
            }
        }
        clipChip?.let { text ->
            clipChip = null
            if (slot == 1) {
                usedClipTime = clipChipTime
                insertText(text)
                refreshStrip()
                return
            }
        }
        restoreChip?.let { text ->
            restoreChip = null
            if (slot == 1) {
                ic.commitText(text, 1)
                cursor += text.length
                selEnd = cursor
                updateShift()
                offerRedo(text)
                return
            }
        }
        redoChip?.let { text ->
            redoChip = null
            if (slot == 1) {
                if (ic.getTextBeforeCursor(text.length, 0)?.toString() == text) {
                    ic.deleteSurroundingText(text.length, 0)
                    cursor -= text.length
                    selEnd = cursor
                    updateShift()
                    offerUndo(text)
                } else refreshStrip()
                return
            }
        }
        val c = slots.getOrNull(slot) ?: return
        if (c.kind == CandidateKind.EMOJI) { pickEmoji(c.text); return }
        emojiSource = null
        val offerTaken = revertOffer?.takeIf { c.text == it.typed }?.also { r ->
            rejectedCorrections += r.typed.lowercase(locale)
            val ctx = context()
            runner.run { it.onAutoCorrectReverted(ctx, r.typed, r.corrected) }
        } != null
        // The original retyped where it was corrected, then picked as typed: the same revert as committing it.
        if (!offerTaken && c.text == composing.toString()) retypedCorrection(c.text)?.let { r ->
            rejectedCorrections += c.text.lowercase(locale)
            val ctx = composingContext ?: context()
            runner.run { it.onAutoCorrectReverted(ctx, c.text, r.corrected) }
        }
        revertOffer = null
        correctionUnderEdit = null
        val replaced = if (composing.isNotEmpty() && !touchedMidWord) joinedPart(c) else ""
        val pickCtx = (composingContext ?: context()).let { if (replaced.isNotEmpty()) it.copy(words = it.words.dropLast(1)) else it }
        val pickTyped = composing.toString()
        runner.run { it.onSuggestionPicked(pickCtx, c, pickTyped) }
        ic.beginBatchEdit()
        if (replaced.isNotEmpty()) {
            // Split-word repair: the picked word also replaces the part before the accidental space.
            composingStart -= replaced.length
            ic.setComposingRegion(composingStart, composingStart + replaced.length + composing.length)
        }
        if (composing.isNotEmpty() || composingStart >= 0) {
            ic.setComposingText(plain(c.text), 1)
            cursor = composingStart + c.text.length
        } else {
            if (phantomSpace || needsSpaceBefore()) { ic.commitText(" ", 1); cursor++ }
            ic.commitText(c.text, 1)
            cursor += c.text.length
        }
        ic.finishComposingText()
        // One space after the word; if one already follows, the cursor jumps past it instead.
        val next = ic.getTextAfterCursor(1, 0)
        if (!next.isNullOrEmpty() && next[0] == ' ') ic.setSelection(cursor + 1, cursor + 1) else ic.commitText(" ", 1)
        cursor += 1
        selEnd = cursor
        ic.endBatchEdit()
        learn(c.text, literally = c.kind == CandidateKind.TYPED)
        resetComposing(finish = false)
        autoSpaceAt = cursor
        phantomSpace = false
        lastAutoCorrection = null
        if (shift == ShiftState.ON || shift == ShiftState.AUTO) setShift(ShiftState.OFF)
        updateShift()
        refreshStrip()
    }

    // ------------------------------------------------------------------ typing

    /** Toolbar "Undo": asks the editor to undo (Ctrl+Z), after committing the composing word as typed. */
    private fun undo() {
        val ic = ic ?: return
        if (composing.isNotEmpty()) commitComposing("", allowAutoCorrect = false)
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
    }

    // ------------------------------------------------------------------ physical keyboard (dev.pboard.ime.hardware)

    /**
     * A character typed on a physical keyboard: composed like a tap on its key's centre (no touch position exists),
     * capitalised at a sentence start when [autoCap] (Gboard: auto-capitalisation on the physical keyboard).
     */
    fun onHardwareText(text: String, autoCap: Boolean) {
        leaveTouchedWord()
        emojiSource = null
        lastTypedAt = SystemClock.uptimeMillis()
        // From the editor's caps mode, not the on-screen shift (its page may not be the letters, or not be shown).
        val capped = autoCap && Config.current.autoCapitalization && composing.isEmpty() && text.length == 1 &&
            text[0].isLowerCase() && (ic?.getCursorCapsMode(editor.inputType) ?: 0) != 0
        val t = if (capped) text.uppercase(locale) else text
        val k = host.keyCenter(text[0].lowercaseChar())
        typeText(t, TouchPoint(k?.x ?: 0f, k?.y ?: 0f, lastTypedAt))
        if ((shift == ShiftState.ON || shift == ShiftState.AUTO) && !shiftHeld) setShift(ShiftState.OFF)
        updateShift()
    }

    /** Space on a physical keyboard: ends the word (auto-corrected when [autoCorrect]); no double-space period. */
    fun onHardwareSpace(autoCorrect: Boolean) {
        leaveTouchedWord()
        val ic = ic ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitComposing(" ", allowAutoCorrect = autoCorrect)
        } else {
            if (selEnd > cursor) ic.commitText("", 1)
            ic.commitText(" ", 1)
            cursor += 1
            lastAutoCorrection = null
        }
        selEnd = cursor
        phantomSpace = false
        autoSpaceAt = -1
        ic.endBatchEdit()
        updateShift()
        refreshStrip()
    }

    /** Inserts text from a panel (emoji, clipboard): commits the composing word as typed, then the text. */
    fun insertText(text: String) {
        leaveTouchedWord()
        val ic = ic ?: return
        emojiSource = null
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) commitComposing("", allowAutoCorrect = false)
        ic.commitText(text, 1)
        cursor += text.length
        selEnd = cursor
        ic.endBatchEdit()
        autoSpaceAt = -1
        phantomSpace = false
        lastAutoCorrection = null
        updateShift()
    }

    private fun isWordChar(cp: Int): Boolean =
        Character.isLetter(cp) || (composing.isNotEmpty() && (cp == '\''.code || cp == '’'.code || Character.isDigit(cp))) ||
            Character.getType(cp) == Character.NON_SPACING_MARK.toInt()

    private fun typeText(text: String, tap: TouchPoint) {
        val ic = ic ?: return
        cancelRestart()
        val cp = text.codePointAt(0)
        lastAutoCorrection = null
        if (editor.suggestions && text.codePointCount(0, text.length) == 1 && isWordChar(cp)) {
            if (composingFromGesture) commitComposing("", allowAutoCorrect = false)
            ic.beginBatchEdit()
            if (composing.isEmpty()) {
                if (phantomSpace) { ic.commitText(" ", 1); cursor++ }
                phantomSpace = false
                autoSpaceAt = -1
                if (selEnd > cursor) { ic.commitText("", 1); selEnd = cursor }
                composingStart = cursor
            }
            if (textOnly) {
                taps.clear()
                for (ch in composing) host.keyCenter(ch.lowercaseChar())?.let { taps += it }
                textOnly = false
                reopenedAsIs = false
            }
            composing.append(text)
            taps += tap
            ic.setComposingText(plain(composing), 1)
            cursor = composingStart + composing.length
            selEnd = cursor
            ic.endBatchEdit()
            updateSuggestions()
            return
        }
        // Separators and symbols.
        ic.beginBatchEdit()
        val punct = text.length == 1 && text[0] in SWAP_PUNCTUATION
        if (composing.isNotEmpty()) {
            commitComposing("", allowAutoCorrect = text.length == 1 && text[0] in CORRECTING_SEPARATORS)
            phantomSpace = false
        }
        if (punct && autoSpaceAt == cursor && autoSpaceAt > 0) {
            // "word |" + "," -> "word,|": an automatic space before closing punctuation is removed.
            ic.deleteSurroundingText(1, 0)
            cursor -= 1
            ic.commitText(text, 1)
            cursor += text.length
            autoSpaceAt = -1
        } else if (ImeRules.digitKeyCode(text, editor.inputType) != null) {
            // CX-03 (Gboard 18.6, conformance harness): in number, phone and date/time fields a digit reaches the app as its key event
            // (KEYCODE_0..KEYCODE_9), the way InputMethodService.sendKeyChar sends digits.
            if (selEnd > cursor) { ic.commitText("", 1); selEnd = cursor }
            host.sendKey(ImeRules.digitKeyCode(text, editor.inputType)!!)
            cursor += 1
            autoSpaceAt = -1
        } else {
            if (phantomSpace && !punct && text != ")" && !Character.isWhitespace(cp)) { ic.commitText(" ", 1); cursor++ }
            ic.commitText(text, 1)
            cursor += text.length
            autoSpaceAt = -1
        }
        selEnd = cursor
        phantomSpace = false
        ic.endBatchEdit()
        refreshStrip()
    }

    private fun onSpace() {
        val ic = ic ?: return
        val now = SystemClock.uptimeMillis()
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitComposing(" ", allowAutoCorrect = true)
            lastSpaceTime = now
            lastSpaceAt = cursor
        } else if (Config.current.doubleSpacePeriod && editor.suggestions && lastSpaceAt == cursor &&
            now - lastSpaceTime < DOUBLE_SPACE_MS && canDoubleSpacePeriod()
        ) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            cursor += 1
            lastSpaceAt = -1
            lastAutoCorrection = null
        } else if (autoSpaceAt == cursor && autoSpaceAt > 0) {
            // The suggestion pick already added a space; a manual space right after is swallowed once.
            autoSpaceAt = -1
            lastSpaceTime = now
            lastSpaceAt = cursor
        } else {
            if (selEnd > cursor) ic.commitText("", 1)
            ic.commitText(" ", 1)
            cursor += 1
            lastSpaceTime = now
            lastSpaceAt = cursor
            lastAutoCorrection = null
        }
        selEnd = cursor
        phantomSpace = false
        ic.endBatchEdit()
        updateShift()
        refreshStrip()
    }

    private fun canDoubleSpacePeriod(): Boolean {
        val before = ic?.getTextBeforeCursor(3, 0) ?: return false
        if (before.length < 2 || before[before.length - 1] != ' ') return false
        val c = before[before.length - 2]
        return Character.isLetterOrDigit(c) || c == ')' || c == '"' || c == '\''
    }

    private fun onEnter() {
        val ic = ic ?: return
        if (composing.isNotEmpty()) commitComposing("", allowAutoCorrect = true)
        phantomSpace = false
        autoSpaceAt = -1
        lastAutoCorrection = null
        val action = editor.editorAction
        if (editor.isNull) {
            host.sendKey(KeyEvent.KEYCODE_ENTER)
        } else if (action != null) {
            host.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
            cursor += 1
            selEnd = cursor
        }
        updateShift()
        refreshStrip()
    }

    /** Commits the composing word (auto-corrected if allowed) followed by [separator]. */
    fun commitComposing(separator: String, allowAutoCorrect: Boolean = false) {
        val ic = ic ?: return
        if (composing.isEmpty()) {
            if (separator.isNotEmpty()) { ic.commitText(separator, 1); cursor += separator.length }
            return
        }
        if (allowAutoCorrect) runner.awaitLatest(COMMIT_WAIT_MS)
        val typed = composing.toString()
        retypedCorrection(typed)?.let { r ->
            // The user put back what they had typed where it was auto-corrected: undo the correction for good.
            rejectedCorrections += typed.lowercase(locale)
            val ctx = composingContext ?: context()
            runner.run { it.onAutoCorrectReverted(ctx, typed, r.corrected) }
        }
        correctionUnderEdit = null
        var word = typed
        val acIndex = suggestions.autoCorrectIndex
        var replaced = ""
        if (allowAutoCorrect && !composingFromGesture && acIndex >= 0 &&
            correctionAllowed(typed, suggestions.candidates[acIndex].text) &&
            typed.lowercase(locale) !in rejectedCorrections
        ) {
            val cand = suggestions.candidates[acIndex]
            word = matchCase(cand.text, typed)
            replaced = joinedPart(cand)
        }
        ic.beginBatchEdit()
        // Split-word repair: the word the space bar cut ("th" + space) goes with the correction ("this").
        if (replaced.isNotEmpty()) { composingStart -= replaced.length; ic.setComposingRegion(composingStart, composingStart + replaced.length + typed.length) }
        ic.setComposingText(plain(word), 1)
        ic.finishComposingText()
        if (separator.isNotEmpty()) ic.commitText(separator, 1)
        ic.endBatchEdit()
        cursor = composingStart + word.length + separator.length
        selEnd = cursor
        lastAutoCorrection = if (word != typed && replaced.isEmpty()) AutoCorrection(typed, word, separator, cursor) else null
        if (replaced.isNotEmpty()) composingContext = composingContext?.let { it.copy(words = it.words.dropLast(1)) }
        learn(word, literally = word == typed, teach = ImeRules.learnsCommittedWord(typed, word, reopenedAsIs))
        resetComposing(finish = false)
    }

    /**
     * The text before the composing word that committing [c] also replaces ([Candidate.replacesBefore]: the first
     * part of a word split by an accidental space), when the field really has it there; else "".
     */
    private fun joinedPart(c: Candidate): String {
        val part = c.replacesBefore
        if (part.isEmpty() || composingStart < part.length) return ""
        val ic = ic ?: return ""
        val before = ic.getTextBeforeCursor(composing.length + part.length, 0)?.toString() ?: return ""
        return if (before == part + composing.toString()) part else ""
    }

    private fun resetComposing(finish: Boolean) {
        if (finish && composing.isNotEmpty()) ic?.finishComposingText()
        pendingRegion = null
        touchedMidWord = false
        composing.setLength(0)
        taps.clear()
        composingStart = -1
        composingFromGesture = false
        suggestions = Suggestions.EMPTY
        revertOffer = null
        composingContext = null
        textOnly = false
        reopenedAsIs = false
    }

    /** The auto-correction that composing [typed] at [composingStart] undoes by retyping its original, if any. */
    private fun retypedCorrection(typed: String): AutoCorrection? = correctionUnderEdit?.takeIf {
        !composingFromGesture && ImeRules.retypesCorrectedOriginal(typed, composingStart, it.typed, correctionUnderEditStart)
    }

    /** The engine's correction of [typed] to [corrected] may be applied in this field. */
    private fun correctionAllowed(typed: String, corrected: String): Boolean =
        editor.autoCorrect

    /** Reports the committed [word] to the engine; with [teach] false it is not learned (the engine still tracks the commit). */
    private fun learn(word: String, literally: Boolean, teach: Boolean = true) {
        val ctx = (composingContext ?: context()).let { if (teach) it else it.copy(learningAllowed = false) }
        runner.run("commit") { it.onWordCommitted(ctx, word, literally) }
        if (ctx.learningAllowed) host.learnedDataChanged()
    }

    // ------------------------------------------------------------------ suggestions

    private fun updateSuggestions() {
        restoreChip = null
        redoChip = null
        clipChip = null
        grammar.dismiss()
        if (!editor.suggestions) return
        if (composing.isEmpty()) { refreshStrip(); return }
        val typed = composing.toString()
        val ctx = composingContext ?: context().also { composingContext = it }
        // Text-only re-decoding (re-opened word, revert): no touches, as the engine contract asks.
        val tapsCopy = if (textOnly) emptyList() else ArrayList(taps)
        // Until the engine answers, the strip shows the literal word so it reacts on the same frame.
        if (suggestions.candidates.isEmpty()) host.showStrip(listOf(null, StripItem(typed), null))
        runner.request("taps", { it.suggestForTaps(ctx, tapsCopy, typed) }) { s ->
            if (composing.toString() != typed) return@request
            suggestions = Suggestions(s.candidates.map { it.copy(text = matchCase(it.text, typed)) }, s.autoCorrectIndex)
            layoutSlots(gesture = false)
        }
    }

    /**
     * Gboard ordering (engine contract): candidates[0] is the centre slot (what space commits when autoCorrectIndex
     * is 0); the literal typed word goes left when it differs from the centre, otherwise the next candidate; the
     * right slot takes the next alternative.
     */
    private fun layoutSlots(gesture: Boolean) {
        slots.fill(null)
        val typed = composing.toString()
        val cands = suggestions.candidates
        if (cands.isEmpty()) { host.showStrip(listOf(null, StripItem(typed), null)); return }
        val ac = if (!gesture && suggestions.autoCorrectIndex == 0 && correctionAllowed(typed, cands[0].text) &&
            typed.lowercase(locale) !in rejectedCorrections && retypedCorrection(typed) == null) 0 else -1
        if (editor.kind == dev.pboard.ime.layout.FieldKind.EMAIL && !gesture) {
            // Measured: email fields show only the literal in the centre, no alternatives.
            slots[1] = cands.firstOrNull { it.text == typed } ?: Candidate(typed, 0f, CandidateKind.TYPED)
            host.showStrip(listOf(null, StripItem(typed), null))
            return
        }
        if (gesture) {
            // Measured (Gboard 15.1): after a glide the committed word is not repeated in the strip; the slots show
            // its alternative decodings, best in the middle.
            val alts = cands.drop(1)
            slots[1] = alts.getOrNull(0); slots[0] = alts.getOrNull(1); slots[2] = alts.getOrNull(2)
        } else {
            // Measured (Gboard 15.1): the typed word always sits on the left ("Hel | Hello | Hell",
            // "The | They | Then", "becuase | because"), the best other candidate in the middle (bold when it will
            // auto-correct), the next on the right. Backspacing into an auto-correction offers the original on the
            // left with the current word in the middle ("becuase | because").
            val literal = cands.firstOrNull { it.kind == CandidateKind.TYPED || it.text == typed }
                ?: Candidate(typed, 0f, CandidateKind.TYPED)
            val others = cands.filter { it !== literal && it.text != typed }
            val r = revertOffer?.takeIf { typed.equals(it.corrected, ignoreCase = true) }
            if (r != null) {
                slots[0] = Candidate(r.typed, 0f, CandidateKind.TYPED)
                slots[1] = literal
                slots[2] = others.firstOrNull { !it.text.equals(r.typed, ignoreCase = true) }
            } else if (others.isEmpty()) {
                slots[1] = literal
            } else {
                slots[0] = literal
                slots[1] = others[0]
                slots[2] = others.getOrNull(1)
            }
        }
        val correcting = ac >= 0 && !gesture && slots[1] === cands[0] && cands[0].text != typed
        if (gesture) addGestureEmoji(cands[0].text) else {
            val word = dev.pboard.ime.emoji.EmojiStrip.wordWhileTyping(typed, slots[1]?.text, correcting) {
                !emojiFor?.invoke(it).isNullOrEmpty()
            }
            slots[3] = null
            if (word != null) addEmojiCandidates(word)
        }
        host.showStrip(slots.mapIndexed { i, c ->
            c?.let { StripItem(it.text, emphasized = i == 1 && correcting, isEmoji = it.kind == CandidateKind.EMOJI) }
        })
    }

    /**
     * After a glide (Gboard 18.6, measured "Help | 🤗 | Hey"): three slots, an emoji for the glided word in the
     * middle, the best alternative on the right and the next on the left (15.1 split the right slot instead).
     */
    private fun addGestureEmoji(word: String) {
        slots[3] = null
        if (!Config.current.emojiSuggestions || editor.isPassword || editor.internal) return
        val emoji = emojiFor?.invoke(word)?.firstOrNull() ?: return
        val best = slots[1]
        slots[1] = Candidate(emoji, 0f, CandidateKind.EMOJI)
        slots[2] = best
    }

    /**
     * Gboard puts emoji for the word in the right slot: one emoji takes the slot, two share it (half width each).
     */
    private fun addEmojiCandidates(word: String) {
        slots[3] = null
        if (!Config.current.emojiSuggestions || editor.isPassword || editor.internal) return
        val emoji = emojiFor?.invoke(word).orEmpty()
        if (emoji.isEmpty()) return
        slots[2] = Candidate(emoji[0], 0f, CandidateKind.EMOJI)
        slots[3] = emoji.getOrNull(1)?.let { Candidate(it, 0f, CandidateKind.EMOJI) }
    }

    /** Shows predictions (after a space) or the toolbar. */
    private fun refreshStrip() {
        restoreChip = null
        redoChip = null
        clipChip = null
        grammar.dismiss()
        if (composing.isNotEmpty()) return
        if (!editor.suggestions || runner.engine == null || !Config.current.nextWordSuggestions) {
            runner.invalidate(); slots.fill(null); host.showToolbar(); return
        }
        val before = ic?.getTextBeforeCursor(40, 0)?.toString()
        // Predictions follow a space, or an emoji picked from the strip (measured: "👋" then "I | and | 👋 | 🤗").
        val afterEmoji = before != null && emojiSource != null && endsWithEmoji(before)
        if (before.isNullOrEmpty() || (before.last() != ' ' && !afterEmoji)) {
            runner.invalidate(); slots.fill(null); showingPredictions = false; host.showToolbar(); return
        }
        // Emoji for the word just finished stay in the right slot among the predictions ("Hello " -> "my | is | 👋 | 🤗").
        val emojiWord = if (afterEmoji) emojiSource else Regex("(\\p{L}[\\p{L}'’]*)\\s$").find(before)?.groupValues?.get(1)
        val ctx = context()
        val at = cursor
        runner.request("predict", { it.predictNext(ctx) }) { p ->
            if (composing.isNotEmpty() || cursor != at) return@request
            slots.fill(null)
            val caps = shift.active && host.currentPage() == Page.LETTERS
            val c = p.candidates.map { it.copy(text = if (shift == ShiftState.LOCKED) it.text.uppercase(locale) else if (caps) capitalize(it.text) else it.text) }
            slots[1] = c.getOrNull(0); slots[0] = c.getOrNull(1); slots[2] = c.getOrNull(2)
            emojiWord?.takeIf { dev.pboard.ime.emoji.EmojiStrip.ownsEmoji(it) }?.let { addEmojiCandidates(it) }
            if (slots.all { it == null }) { host.showToolbar(); return@request }
            showingPredictions = true
            host.showStrip(slots.map { it?.let { s -> StripItem(s.text, isEmoji = s.kind == CandidateKind.EMOJI) } })
        }
    }

    /** The word an emoji picked from the strip replaced (its emoji are offered again after it). */
    private var emojiSource: String? = null

    private fun endsWithEmoji(text: String): Boolean {
        if (text.isEmpty()) return false
        val cp = text.codePointBefore(text.length)
        return cp == 0xFE0F || cp == 0x200D || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
            Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
    }

    /**
     * An emoji candidate from the strip. Measured (Gboard 15.1): it replaces the word being typed or the glided word
     * ("Hello" -> "👋"), with no space after it; after a space it is inserted at the cursor.
     */
    private fun pickEmoji(emoji: String) {
        val ic = ic ?: return
        val source = if (composing.isNotEmpty()) composing.toString()
            else ic.getTextBeforeCursor(40, 0)?.let { Regex("(\\p{L}[\\p{L}'’]*)\\s$").find(it)?.groupValues?.get(1) }
        revertOffer = null
        ic.beginBatchEdit()
        if (composing.isNotEmpty() || composingStart >= 0) {
            ic.setComposingText(emoji, 1)
            cursor = composingStart + emoji.length
            ic.finishComposingText()
        } else {
            ic.commitText(emoji, 1)
            cursor += emoji.length
        }
        selEnd = cursor
        ic.endBatchEdit()
        resetComposing(finish = false)
        host.emojiCommitted(emoji)
        autoSpaceAt = -1
        phantomSpace = false
        lastAutoCorrection = null
        emojiSource = source
        if (shift == ShiftState.ON || shift == ShiftState.AUTO) setShift(ShiftState.OFF)
        updateShift()
        refreshStrip()
    }

    /**
     * TF-01 (Gboard): the letter a tap commits is decided at touch-up from the touch position and the word so far
     * ("wh" + a tap just inside `r` next to `e` types "e"). Only letter keys on the letters page; the engine keeps
     * taps literal near key centres, in exact-key fields and with autocorrect off. The key preview showed the key
     * physically touched; the word decoder still gets the raw touch point.
     */
    private fun resolveTap(key: Key, text: String, touch: TouchPoint): String {
        val engine = runner.engine ?: return text
        if (host.currentPage() != Page.LETTERS || !editor.suggestions) return text
        // The keys next to letters other than space and delete ("." and ","): inside a word, and only into a letter; "."
        // at a word's start too (the engine keeps a deliberate period after a word).
        if (!key.isLetter && composing.isEmpty() && text == ".") return resolveAtWordStart(text, touch)
        if (!key.isLetter && (composing.isEmpty() || (text != "." && text != ","))) return text
        if (text.codePointCount(0, text.length) != 1 || composingFromGesture) return text
        val ctx = composingContext ?: context()
        return runCatching { engine.resolveTap(ctx, composing.toString(), touch, text) }.getOrDefault(text)
            .takeIf { it.isNotEmpty() } ?: text
    }

    /**
     * The space bar, "." or shift ([rectKey] "space" / "." / "shift") as the first tap of a word, after a space or at the
     * start of the field: the engine may return the letter it borders (only near the key's edge, with a strong prior for
     * the key itself: a deliberate space or period after a word stays one). Otherwise [rectKey] itself.
     */
    private fun resolveAtWordStart(rectKey: String, touch: TouchPoint): String {
        val engine = runner.engine ?: return rectKey
        if (composing.isNotEmpty() || composingFromGesture || host.currentPage() != Page.LETTERS || !editor.suggestions) return rectKey
        if (selEnd != cursor) return rectKey
        val before = ic?.getTextBeforeCursor(1, 0)?.toString() ?: return rectKey
        if (before.isNotEmpty() && !before[0].isWhitespace()) return rectKey
        val r = runCatching { engine.resolveTap(context(), "", touch, rectKey) }.getOrDefault(rectKey)
        return if (r.codePointCount(0, r.length) == 1 && Character.isLetter(r.codePointAt(0))) r else rectKey
    }

    /**
     * The space bar or delete key ([rectKey] "space" / "delete") inside a word typed by taps: the engine may return a
     * neighbouring letter instead. Outside a word, or where tap resolution does not apply, [rectKey] itself.
     */
    private fun resolveInWord(rectKey: String, touch: TouchPoint): String {
        val engine = runner.engine ?: return rectKey
        if (composing.isEmpty() || composingFromGesture || host.currentPage() != Page.LETTERS || !editor.suggestions) return rectKey
        val ctx = composingContext ?: context()
        val r = runCatching { engine.resolveTap(ctx, composing.toString(), touch, rectKey) }.getOrDefault(rectKey)
        return if (r == RESOLVED_SPACE || r == RESOLVED_DELETE || (r.codePointCount(0, r.length) == 1 && Character.isLetter(r.codePointAt(0)))) r else rectKey
    }

    private fun context(): Context {
        val before = ic?.getTextBeforeCursor(200, 0)?.toString().orEmpty()
        // The word's letters before the cursor (all of it, unless the cursor was tapped into the word).
        val head = if (touchedMidWord) composing.substring(0, (cursor - composingStart).coerceIn(0, composing.length)) else composing.toString()
        val text = if (head.isNotEmpty() && before.endsWith(head)) before.dropLast(head.length) else before
        // The sentence so far (and the end of the previous one): words plus sentence-ending punctuation as
        // tokens, so the LM sees real sentence boundaries; a context shorter than the LM's order would
        // otherwise be read as the start of the field.
        val words = CONTEXT_TOKEN.findAll(text).map { it.value }.toList().takeLast(12)
        val trimmed = text.trimEnd()
        val textStart = trimmed.isEmpty() || trimmed.last() in ".!?\n"
        // A word the user deliberately started in lower case at a sentence start (shift turned off) keeps its
        // case: the engine capitalises candidates only when the sentence start is in effect.
        val sentenceStart = textStart && (composing.isEmpty() || !composing[0].isLowerCase())
        return Context(
            words = words,
            sentenceStart = sentenceStart,
            learningAllowed = !editor.noLearning && Config.current.personalizedSuggestions,
            autoCorrectAllowed = editor.autoCorrect,
            exactKeys = editor.exactKeys,
            capitalsAllowed = editor.capitals,
        )
    }

    // ------------------------------------------------------------------ shift

    private fun setShift(s: ShiftState) {
        if (shift != s) { shift = s; host.onShiftChanged(s) }
    }

    /** Auto-capitalisation from the editor's caps mode (sentence starts), unless shift is locked or held. */
    fun updateShift(force: Boolean = false) {
        if (shift == ShiftState.LOCKED || shiftHeld) return
        if (host.currentPage() != Page.LETTERS) { if (force) setShift(ShiftState.OFF); return }
        val ic = ic
        // CAP_CHARACTERS: shift stays on inside the word too (Gboard 18.6, CX-02 capchars), other caps modes only
        // apply between words.
        val auto = Config.current.autoCapitalization && ic != null && ImeRules.autoCapsApplies(composing.isEmpty(), editor.capCharacters) &&
            ic.getCursorCapsMode(editor.inputType) != 0 && editor.inputType != 0
        if (auto) {
            if (shift == ShiftState.OFF || force) setShift(ShiftState.AUTO)
        } else if (shift == ShiftState.AUTO) {
            setShift(ShiftState.OFF)
        }
    }

    private fun applyShift(word: String) = when (shift) {
        ShiftState.LOCKED -> word.uppercase(locale)
        ShiftState.ON, ShiftState.AUTO -> capitalize(word)
        ShiftState.OFF -> word
    }

    private fun capitalize(w: String) = if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1)

    /** Applies the typed word's capitalisation pattern to a candidate. */
    private fun matchCase(candidate: String, typed: String): String {
        if (typed.isEmpty()) return candidate
        val allCaps = (typed.length > 1 || editor.capCharacters) && typed.all { !it.isLetter() || it.isUpperCase() }
        return when {
            allCaps -> candidate.uppercase(locale)
            typed[0].isUpperCase() -> capitalize(candidate)
            else -> candidate
        }
    }

    // ------------------------------------------------------------------ selection tracking

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        val ours = newSelStart == cursor && newSelEnd == selEnd.coerceAtLeast(cursor)
        if (swipeAnchor >= 0) return
        if (candidatesStart >= 0) editorReportsComposing = true
        if (afterKeyDelete && ours) { keyDeleteApplied(candidatesStart, candidatesEnd); return }
        if (ours) {
            // (only for editors that do report their composing region: others always say -1)
            if (composing.isNotEmpty() && candidatesStart < 0 && editorReportsComposing && (icLazy as? LazyRegion)?.isReal == true) {
                // The editor no longer has a composing region although we are composing: the app dropped it (a web
                // page rewriting its value as the user types, a field the app reformats). Close ours as well, or the
                // next setComposingText would insert the whole word a second time (app-compat run, a page that
                // upper-cases its value: "Changed" + " text " gave "CHANGEDCHANGED TEXT "). Finishing is safe even
                // if this update is belated and the region still exists: it closes that region too.
                ic?.finishComposingText()
                resetComposing(finish = false)
                refreshStrip()
            }
            return
        }
        // A late selection update from one of our own edits of the open word (typing faster than the editor reports):
        // the word is still open and our cursor is ahead of it. With a real composition its region told us so.
        if (composing.isNotEmpty() && candidatesStart < 0 && newSelStart == newSelEnd &&
            (icLazy as? LazyRegion)?.isOwnCursor(newSelStart) == true) return
        afterKeyDelete = false
        // The app or the user moved the cursor (or changed the text).
        cursor = newSelStart
        selEnd = newSelEnd
        if (composing.isNotEmpty()) {
            val stillComposing = candidatesStart >= 0 && newSelStart == newSelEnd && newSelEnd == candidatesEnd
            if (!stillComposing) {
                abandonComposing()
                ic?.finishComposingText()
                resetComposing(finish = false)
            } else return
        }
        lastAutoCorrection = null
        correctionUnderEdit = null
        autoSpaceAt = -1
        phantomSpace = false
        // Re-open the word only once the cursor settles (no rewrite of text the app may still be changing).
        if (newSelStart == newSelEnd) scheduleRestart() else cancelRestart()
        updateShift()
        if (composing.isEmpty()) refreshStrip()
    }

    /** The editor of this input session has reported a composing region at least once (it tracks composing text). */
    private var editorReportsComposing = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Fallback for editors that do not report the selection after a key-event backspace. */
    private val keyDeleteSettled = Runnable { if (afterKeyDelete) keyDeleteApplied() }
    private var restartAt = -1
    private val restartRunnable = Runnable {
        // Only if nothing moved since: the cursor has settled where the app (or the user's tap) put it.
        if (cursor == restartAt && selEnd == restartAt && composing.isEmpty()) {
            if (offerGrammarFix()) return@Runnable
            restartComposingAtCursor(mark = false)
            if (composing.isEmpty()) refreshStrip()
            // The context of the re-opened word, or of the text before the cursor.
            prepareContext()
        }
    }

    /** Grammar check (dev.pboard.ime.textintel): the fix chip for a marked word the cursor rests in. */
    val grammar = dev.pboard.ime.textintel.GrammarAssist()

    private fun offerGrammarFix(): Boolean {
        if (!Config.current.grammarCheck || !editor.suggestions) return false
        val ic = ic ?: return false
        val fix = grammar.markAt(ic, cursor) ?: return false
        // Gboard reopens a marked word that ends at the cursor as composing text, tinted
        if (fix.end == cursor && fix.original.none { it.isWhitespace() }) {
            (ic as? LazyRegion)?.composeForReal(fix.start, cursor, grammar.styled(fix, locale))
            composingStart = fix.start
            composing.setLength(0)
            composing.append(fix.original)
            taps.clear()
            textOnly = true
        }
        slots.fill(null)
        host.showStrip(grammar.stripFor(fix))
        return true
    }

    private fun scheduleRestart() {
        mainHandler.removeCallbacks(restartRunnable)
        restartAt = cursor
        mainHandler.postDelayed(restartRunnable, RESTART_SETTLE_MS)
    }

    private fun cancelRestart() = mainHandler.removeCallbacks(restartRunnable)

    /**
     * Re-opens the word ending at the cursor for editing (after backspacing into it or tapping at its end). With
     * [mark] false the word gets its suggestions but is marked as composing in the editor only once edited
     * ([pendingRegion]).
     */
    private fun restartComposingAtCursor(mark: Boolean = true) {
        if (!editor.suggestions) return
        val ic = ic ?: return
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
        val afterText = ic.getTextAfterCursor(48, 0)?.toString().orEmpty()
        if (before.isEmpty() || !Character.isLetter(before.codePointBefore(before.length))) return
        // Inside a word (a tap during a session): its suggestions are offered for the whole word, unmarked.
        val tail = if (mark) 0 else ImeRules.wordTailLength(afterText)
        if (tail < 0) return
        if (tail == 0 && afterText.isNotEmpty() && Character.isLetterOrDigit(afterText.codePointAt(0))) return
        var start = before.length
        while (start > 0) {
            val cp = before.codePointBefore(start)
            if (Character.isLetter(cp) || cp == '\''.code || cp == '’'.code) start -= Character.charCount(cp) else break
        }
        if (start == 0 && before.length >= 48) return
        val word = before.substring(start) + afterText.substring(0, tail)
        if (word.isEmpty()) return
        composingStart = cursor - (before.length - start)
        touchedMidWord = tail > 0
        if (mark) {
            ic.beginBatchEdit()
            ic.setComposingRegion(composingStart, cursor)
            ic.endBatchEdit()
        } else {
            pendingRegion = composingStart until composingStart + word.length
        }
        composing.setLength(0)
        composing.append(word)
        taps.clear()
        textOnly = true
        reopenedAsIs = !mark
        updateSuggestions()
    }

    // ------------------------------------------------------------------ text helpers

    /**
     * The editor's selection now (absolute start, end), or null when the editor cannot say. Editors that implement
     * getSurroundingText (EditText, Chrome/WebView) give the offset directly; otherwise the length of the text before
     * the cursor is it, when that text is short enough to be complete (the approach of AOSP LatinIME's
     * tryFixLyingCursorPosition, Apache-2.0).
     */
    private fun editorSelection(): IntArray? {
        val ic = ic ?: return null
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val st = runCatching { ic.getSurroundingText(0, 0, 0) }.getOrNull()
            if (st != null && st.offset >= 0 && st.selectionStart >= 0 && st.selectionEnd >= 0) {
                return intArrayOf(st.offset + minOf(st.selectionStart, st.selectionEnd), st.offset + maxOf(st.selectionStart, st.selectionEnd))
            }
        }
        val before = runCatching { ic.getTextBeforeCursor(SELECTION_PROBE_CHARS, 0) }.getOrNull() ?: return null
        if (before.length >= SELECTION_PROBE_CHARS) return null
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()?.length ?: 0
        return intArrayOf(before.length, before.length + selected)
    }

    /**
     * Composing text is passed as a Spannable: the framework only adds its default composing underline to plain
     * strings, and Gboard's composing word is not underlined.
     */
    private fun plain(s: CharSequence): CharSequence = android.text.SpannableString(s)

    private fun needsSpaceBefore(): Boolean {
        val before = ic?.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return false
        val c = before[0]
        return !(Character.isWhitespace(c) || c in "([{\"'“‘¿¡/@#")
    }

    private fun lastGraphemeLength(text: CharSequence): Int {
        val it = BreakIterator.getCharacterInstance(locale)
        val s = text.toString()
        it.setText(s)
        val end = it.last()
        val start = it.preceding(end)
        return if (start == BreakIterator.DONE) 1 else end - start
    }

    private fun wordDeleteLength(text: CharSequence): Int {
        val s = text.toString()
        return s.length - previousWordStart(s, s.length)
    }

    /** Start of the word before [end] (skipping whitespace/punctuation right before it). */
    private fun previousWordStart(s: String, end: Int): Int {
        var i = end
        while (i > 0 && !Character.isLetterOrDigit(s.codePointBefore(i))) i -= Character.charCount(s.codePointBefore(i))
        while (i > 0 && (Character.isLetterOrDigit(s.codePointBefore(i)) || s[i - 1] == '\'')) i -= Character.charCount(s.codePointBefore(i))
        return i
    }

    /** Called when the keyboard page changes, e.g. to drop auto-caps on symbols. */
    fun onPageChanged() = updateShift(force = true)

    /** Strips the composing state when the keyboard is hidden or the field changes. */
    fun hasComposing() = composing.isNotEmpty()

    companion object {
        const val DOUBLE_TAP_MS = 300L
        /** Labels the engine's tap resolution uses for the space bar and the delete key (KeyboardEngine.resolveTap). */
        const val RESOLVED_SPACE = "space"
        const val RESOLVED_DELETE = "delete"
        const val RESOLVED_SHIFT = "shift"
        /** Shift double-tap window from the first tap's release to the second touch-down (Gboard 18.6: 285 locks, 290 not). */
        const val SHIFT_DOUBLE_TAP_MS = 290L
        const val KEY_DELETE_SETTLE_MS = 150L
        /** Text read before the cursor to locate it when the editor cannot report its selection offset. */
        const val SELECTION_PROBE_CHARS = 1024
        /** Longest text a held delete's Undo chip restores. */
        const val HOLD_UNDO_MAX = 10_000
        /** Text ending a sentence and the space after it (the strip shows the toolbar there when a field is focused). */
        private val SENTENCE_END = Regex("[.!?]\\s+$")
        const val CLIP_CHIP_MAX_AGE_MS = 2 * 60 * 1000L
        /** External cursor moves re-open the word under the cursor after it has been still this long. */
        const val RESTART_SETTLE_MS = 300L
        const val GLOBE_IGNORE_MS = 500L
        /** A fast space waits this long for the decoder's up-to-date result before committing. */
        const val COMMIT_WAIT_MS = 40L
        /** Measured on Gboard: the second space must follow within about 900-1000 ms. */
        const val DOUBLE_SPACE_MS = 1000L
        const val SWAP_PUNCTUATION = ".,!?;:)]}"
        const val CORRECTING_SEPARATORS = ".,!?;:)]}\"\n"
        private val WORD = Regex("[\\p{L}\\p{M}'’]+")
        private val CONTEXT_TOKEN = Regex("[\\p{L}\\p{M}'’]+|[.!?…]+")
    }
}
