package dev.pboard.ime.hardware

import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.input.InputManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import dev.pboard.ime.InputController
import dev.pboard.ime.config.Config
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.view.StripItem
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Prefs

/**
 * Physical keyboards (CX-12), as Gboard 18.6 behaves on the emulator with a USB keyboard attached (uinput):
 *
 * * Attaching a keyboard changes nothing until a key is typed on it. The first physical key switches to
 *   "physical-keyboard mode": the on-screen keys disappear (the app is no longer resized) and only a small round
 *   button with a menu icon floats at the left edge; the mode lasts across fields until the keyboard is unplugged (or
 *   "Show on-screen keyboard", Alt+K). Settings › Physical keyboard › Show on-screen keyboard keeps the keys.
 * * Typing still goes through the keyboard: words are composed (no underline), auto-corrected on space and
 *   punctuation ("becuase " -> "because "), auto-capitalised; the correction that space would apply is previewed in a
 *   small card just above the word. Backspace, Enter, Tab, arrows, Escape and every Ctrl/Meta shortcut (Ctrl+A ...)
 *   reach the app (the word being typed is committed first). Shift+Space switches language when there are several,
 *   otherwise it types a space.
 * * The floating button opens Gboard's shortcut menu: Settings (Alt+I), Show clipboard (Alt+V), Show emoji keyboard
 *   (Alt+.), Show on-screen keyboard (Alt+K), Voice (Alt+D). The Alt shortcuts work while typing. (Gboard also lists
 *   Translate, a server feature pboard omits, and the system's keyboard-shortcut list.)
 *
 * Only real keyboards count: key events from virtual devices (`adb shell input`, other IMEs' key injection) are left
 * alone, so on-screen behaviour and tests are unaffected.
 */
class HardwareKeyboard(private val ime: InputMethodService, private val host: Host) {
    interface Host {
        val controller: InputController
        val colors: KeyboardColors
        /** The current on-screen keyboard view (to return to). */
        fun keyboardView(): View?
        fun setInputView(view: View)
        fun openSettings()
        fun openEmoji()
        fun openClipboard()
        fun startVoiceInput()
        fun switchLanguage()
        fun hasSeveralLanguages(): Boolean
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = PboardPrefs.get(ime)

    /** Physical-keyboard mode: the on-screen keys are replaced by the floating button. */
    var active = false
        private set

    /** The user asked for the on-screen keyboard while the physical one stays attached (Alt+K / menu). */
    private var forceOnScreen = false
    private var view: HardwareKeyboardView? = null
    private val consumed = HashSet<Int>()
    private var deadChar = 0

    private val inputManager = ime.getSystemService(InputManager::class.java)
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) = checkStillAttached()
        override fun onInputDeviceRemoved(deviceId: Int) = checkStillAttached()
    }

    init {
        inputManager?.registerInputDeviceListener(deviceListener, main)
    }

    fun destroy() {
        inputManager?.unregisterInputDeviceListener(deviceListener)
    }

    /** The view to show as the IME's input view: the floating button in physical-keyboard mode, else [keyboard]. */
    fun inputView(keyboard: View): View = if (active) pkView() else keyboard

    private fun pkView(): HardwareKeyboardView =
        (view ?: HardwareKeyboardView(ime, host.colors).also { v ->
            v.listener = object : HardwareKeyboardView.Listener {
                override fun onMenuItem(item: HardwareKeyboardView.Item) = runMenuItem(item)
                override fun onLayoutChanged() = Unit
            }
            view = v
        }).also {
            it.colors = host.colors
            it.showButton = Config.current.pkShowToolbar
            it.items = HardwareKeyboardView.Item.entries.filter { item ->
                item != HardwareKeyboardView.Item.VOICE || Config.current.voiceTyping
            }
        }

    // ------------------------------------------------------------------ keys

    private fun isPhysical(e: KeyEvent): Boolean {
        val dev = e.device ?: InputDevice.getDevice(e.deviceId) ?: return false
        if (dev.isVirtual || e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        return dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && (e.source and InputDevice.SOURCE_KEYBOARD) != 0
    }

    private fun enterMode() {
        if (active || forceOnScreen || prefs[Prefs.pkShowOnScreenKeyboard]) return
        active = true
        host.setInputView(pkView())
        // As Gboard: no "hide keyboard" back button in the navigation bar while only the floating button shows.
        ime.backDisposition = InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING
        requestCursorUpdates(true)
    }

    private fun leaveMode() {
        if (!active) return
        active = false
        view?.setCandidate(null, null)
        requestCursorUpdates(false)
        host.keyboardView()?.let { host.setInputView(it) }
        ime.backDisposition = InputMethodService.BACK_DISPOSITION_DEFAULT
    }

    /** No alphabetic hardware keyboard left: back to the on-screen keys. */
    private fun checkStillAttached() {
        val ids = InputDevice.getDeviceIds()
        val any = ids.any { id ->
            InputDevice.getDevice(id)?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && it.isEnabled } == true
        }
        if (!any) { forceOnScreen = false; leaveMode() }
    }

    fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        // Back (navigation bar, or a keyboard's own Back key) always reaches InputMethodService: it hides the keyboard.
        if (keyCode == KeyEvent.KEYCODE_BACK || !isPhysical(e)) return false
        enterMode()
        val handled = handleDown(keyCode, e)
        if (handled) consumed += keyCode else consumed -= keyCode
        return handled
    }

    fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || !isPhysical(e)) return false
        return consumed.remove(keyCode)
    }

    private fun handleDown(keyCode: Int, e: KeyEvent): Boolean {
        val c = host.controller
        val alt = e.isAltPressed
        val ctrl = e.isCtrlPressed
        val meta = e.isMetaPressed
        if (alt && !ctrl && !meta) when (keyCode) {
            KeyEvent.KEYCODE_K -> { runMenuItem(HardwareKeyboardView.Item.ON_SCREEN); return true }
            KeyEvent.KEYCODE_V -> { runMenuItem(HardwareKeyboardView.Item.CLIPBOARD); return true }
            KeyEvent.KEYCODE_PERIOD -> { runMenuItem(HardwareKeyboardView.Item.EMOJI); return true }
            KeyEvent.KEYCODE_I -> { runMenuItem(HardwareKeyboardView.Item.SETTINGS); return true }
            KeyEvent.KEYCODE_D -> { if (Config.current.voiceTyping) runMenuItem(HardwareKeyboardView.Item.VOICE); return true }
        }
        if (KeyEvent.isModifierKey(keyCode)) return false
        // Fields the keyboard does not compose in (passwords, numbers, raw-key editors) get the keys as they are.
        if (!c.editor.suggestions || c.editor.isNull) return false
        val autoCorrect = prefs[Prefs.pkAutoCorrection]
        if (ctrl || meta || alt) { c.commitComposing(""); return false }
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> { c.onDelete(false); return true }
            KeyEvent.KEYCODE_SPACE -> {
                if (e.isShiftPressed && host.hasSeveralLanguages()) { c.commitComposing(""); host.switchLanguage(); return true }
                c.onHardwareSpace(autoCorrect)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                c.commitComposing("", allowAutoCorrect = autoCorrect)
                return false
            }
        }
        val uc = e.getUnicodeChar(e.metaState)
        if (uc == 0 || Character.isISOControl(uc and KeyCharacterMap.COMBINING_ACCENT_MASK)) {
            // Navigation, Tab, Escape, function keys: the app's, after the word in progress is kept as typed.
            c.commitComposing("")
            return false
        }
        if (uc and KeyCharacterMap.COMBINING_ACCENT != 0) {
            deadChar = uc and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        var ch = uc
        if (deadChar != 0) {
            val combined = KeyEvent.getDeadChar(deadChar, uc)
            ch = if (combined != 0) combined else uc
            if (combined == 0) c.onHardwareText(String(Character.toChars(deadChar)), autoCap = false)
            deadChar = 0
        }
        c.onHardwareText(String(Character.toChars(ch)), autoCap = prefs[Prefs.pkAutoCapitalization])
        return true
    }

    private fun runMenuItem(item: HardwareKeyboardView.Item) {
        view?.dismissMenu()
        when (item) {
            HardwareKeyboardView.Item.SETTINGS -> host.openSettings()
            HardwareKeyboardView.Item.CLIPBOARD -> { forceOnScreen = true; leaveMode(); main.post { host.openClipboard() } }
            HardwareKeyboardView.Item.EMOJI -> { forceOnScreen = true; leaveMode(); main.post { host.openEmoji() } }
            HardwareKeyboardView.Item.ON_SCREEN -> if (active) { forceOnScreen = true; leaveMode() } else {
                forceOnScreen = false; enterMode()
            }
            HardwareKeyboardView.Item.VOICE -> host.startVoiceInput()
        }
    }

    // ------------------------------------------------------------------ candidate card

    /** Strip content from the controller: the card previews the auto-correction space would apply (bold centre slot). */
    fun onStrip(items: List<StripItem?>?) {
        val v = view ?: return
        if (!active) return
        // Settings › Physical keyboard › Suggestions while typing.
        if (!Config.current.pkSuggestions) { v.setCandidate(null, null); return }
        val correction = items?.getOrNull(1)?.takeIf { it.emphasized }?.text
        v.setCandidate(correction, composingRect)
    }

    private var composingRect: RectF? = null

    private fun requestCursorUpdates(on: Boolean) {
        val ic: InputConnection = ime.currentInputConnection ?: return
        runCatching {
            ic.requestCursorUpdates(if (on) InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE else 0)
        }
    }

    fun onStartInput() {
        if (!active) return
        ime.backDisposition = InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING
        view?.setCandidate(null, null)
        main.post { requestCursorUpdates(true) }
    }

    /** Where the composing word (or the cursor) is on screen, from the editor. */
    fun onCursorAnchorInfo(info: CursorAnchorInfo) {
        if (!active) return
        val start = info.composingTextStart
        val m = Matrix(info.matrix)
        val r = RectF()
        val first = if (start >= 0) info.getCharacterBounds(start) else null
        if (first != null) {
            r.set(first)
        } else if (!info.insertionMarkerHorizontal.isNaN()) {
            r.set(info.insertionMarkerHorizontal, info.insertionMarkerTop, info.insertionMarkerHorizontal, info.insertionMarkerBottom)
        } else return
        m.mapRect(r)
        composingRect = r
        view?.moveCandidate(r)
    }

    /** Touchable parts of the window in physical-keyboard mode, and the app keeps the whole screen. */
    fun computeInsets(outInsets: InputMethodService.Insets): Boolean {
        val v = view ?: return false
        if (!active || !v.isLaidOut) return false
        outInsets.contentTopInsets = v.height
        outInsets.visibleTopInsets = v.height
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        v.touchableRegion(outInsets.touchableRegion)
        return true
    }
}
