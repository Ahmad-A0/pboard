package dev.pboard.ime.view

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import dev.pboard.ime.layout.Codes
import dev.pboard.ime.layout.EnterAction
import dev.pboard.ime.layout.Key
import dev.pboard.ime.layout.Page

/**
 * TalkBack support for the key surface: every key is a virtual view with a spoken description, explore-by-touch
 * moves accessibility focus from key to key as the finger slides, and lifting the finger on a key types it
 * (Gboard's "lift to type"; no double-tap needed). Double-tap (ACTION_CLICK) on a focused key also types it.
 */
class KeyboardAccessibility(private val view: KeyboardView) : AccessibilityNodeProvider() {
    private val am = view.context.getSystemService(AccessibilityManager::class.java)
    private var focused = View.NO_ID
    private var hovered: Key? = null

    val active: Boolean get() = am?.isEnabled == true && am.isTouchExplorationEnabled

    private fun keys(): List<Key> = view.keyboard?.keys.orEmpty()

    /** Spoken description of a key (Gboard-like names for function keys and punctuation). */
    fun describe(key: Key): String {
        val shifted = view.painter.shiftState != 0
        return when (key.code) {
            Codes.SHIFT -> when (view.painter.shiftState) { 2 -> "Caps lock on"; 1 -> "Shift on"; else -> "Shift" }
            Codes.DELETE -> "Delete"
            Codes.ENTER -> when (view.painter.enterAction) {
                EnterAction.SEARCH -> "Search"; EnterAction.SEND -> "Send"; EnterAction.GO -> "Go"
                EnterAction.NEXT -> "Next"; EnterAction.DONE -> "Done"; EnterAction.PREVIOUS -> "Previous"
                EnterAction.RETURN -> "Enter"
            }
            Codes.SPACE -> "Space"
            Codes.SYMBOLS -> "Symbols"
            Codes.ALPHA -> "Letters"
            Codes.SYMBOLS_MORE -> "More symbols"
            Codes.NUMPAD -> "Numbers"
            Codes.EMOJI -> "Emoji"
            Codes.LANGUAGE -> "Next language"
            Codes.PHONE_SYMBOLS -> "Symbols"
            Codes.PHONE_BACK -> "Numbers"
            else -> {
                val label = key.label(shifted)
                PUNCTUATION[label] ?: if (key.isLetter && shifted) "Capital $label" else label
            }
        }
    }

    // ------------------------------------------------------------------ explore by touch

    /** Handles hover events while touch exploration is on; returns true when consumed. */
    fun onHover(e: MotionEvent): Boolean {
        if (!active) return false
        val kb = view.keyboard ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val k = kb.keyAt(e.x, e.y)
                if (k !== hovered) {
                    hovered?.let { send(idOf(it), AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) }
                    hovered = k
                    k?.let {
                        send(idOf(it), AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                        performAction(idOf(it), AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                    }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val k = hovered
                hovered = null
                // Lifting the finger inside the keyboard on a key types it.
                if (k != null && e.x >= 0 && e.y >= 0 && e.x < view.width && e.y < view.height) {
                    send(idOf(k), AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    view.performKeyFromAccessibility(k)
                }
            }
        }
        return true
    }

    private fun idOf(k: Key) = keys().indexOf(k)

    private fun send(id: Int, type: Int) {
        if (id < 0 || am?.isEnabled != true) return
        val ev = AccessibilityEvent.obtain(type)
        ev.packageName = view.context.packageName
        ev.className = KEY_CLASS
        ev.setSource(view, id)
        keys().getOrNull(id)?.let { ev.contentDescription = describe(it) }
        view.parent?.requestSendAccessibilityEvent(view, ev)
    }

    /** Announces text (e.g. what a key typed or a page change) through the accessibility framework. */
    fun announce(text: CharSequence) {
        if (am?.isEnabled == true) view.announceForAccessibility(text)
    }

    // ------------------------------------------------------------------ node provider

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == View.NO_ID || virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(view)
            view.onInitializeAccessibilityNodeInfo(info)
            for (i in keys().indices) info.addChild(view, i)
            return info
        }
        val key = keys().getOrNull(virtualViewId) ?: return null
        val info = AccessibilityNodeInfo.obtain(view, virtualViewId)
        info.packageName = view.context.packageName
        info.className = KEY_CLASS
        info.contentDescription = describe(key)
        info.setParent(view)
        val r = Rect()
        key.rect.roundOut(r)
        info.setBoundsInParent(r)
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        r.offset(loc[0], loc[1])
        info.setBoundsInScreen(r)
        info.isEnabled = true
        info.isVisibleToUser = true
        info.isClickable = true
        info.isFocusable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        if (focused == virtualViewId) {
            info.isAccessibilityFocused = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == View.NO_ID || virtualViewId == HOST_VIEW_ID) return view.performAccessibilityAction(action, arguments)
        val key = keys().getOrNull(virtualViewId) ?: return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focused == virtualViewId) return false
                val prev = focused
                focused = virtualViewId
                if (prev != View.NO_ID) send(prev, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                view.invalidate()
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focused != virtualViewId) return false
                focused = View.NO_ID
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> { view.performKeyFromAccessibility(key); true }
            else -> false
        }
    }

    /**
     * After a key typed through TalkBack: announces the resulting keyboard mode when a shift or page key changed it
     * (wording as AOSP LatinIME's spoken descriptions; automatic shifts stay silent, each key's description covers them).
     */
    fun announceModeChange(key: Key, pageBefore: Page?, shiftBefore: Int) {
        val page = view.keyboard?.page ?: return
        val shift = view.painter.shiftState
        val text = when {
            page != pageBefore -> when (page) {
                Page.LETTERS -> "Letters mode"
                Page.SYMBOLS -> "Symbols mode"
                Page.SYMBOLS_MORE -> "More symbols mode"
                Page.PHONE -> "Phone mode"
                Page.PHONE_SYMBOLS -> "Phone symbols mode"
                Page.NUMPAD, Page.NUMBER, Page.NUMBER_PASSWORD -> "Numbers mode"
            }
            key.code == Codes.SHIFT && shift != shiftBefore -> when (shift) {
                2 -> "Caps lock enabled"; 1 -> "Shift enabled"; else -> "Shift disabled"
            }
            else -> null
        }
        text?.let { announce(it) }
    }

    /** The layout changed (page switch, shift): virtual views are rebuilt. */
    fun invalidateRoot(keepFocus: Boolean = false) {
        if (!keepFocus) {
            focused = View.NO_ID
            hovered = null
        }
        if (am?.isEnabled == true) {
            val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            ev.setSource(view)
            view.parent?.requestSendAccessibilityEvent(view, ev)
        }
    }

    companion object {
        private const val KEY_CLASS = "android.inputmethodservice.Keyboard\$Key"
        private val PUNCTUATION = mapOf(
            "," to "Comma", "." to "Period", "?" to "Question mark", "!" to "Exclamation mark", "'" to "Apostrophe",
            "\"" to "Quote", ":" to "Colon", ";" to "Semicolon", "-" to "Dash", "_" to "Underscore", "@" to "At",
            "#" to "Hash", "$" to "Dollar", "£" to "Pound", "€" to "Euro", "&" to "Ampersand", "%" to "Percent",
            "+" to "Plus", "=" to "Equals", "/" to "Slash", "\\" to "Backslash", "(" to "Left parenthesis",
            ")" to "Right parenthesis", "*" to "Asterisk", "~" to "Tilde", "`" to "Backtick", "|" to "Vertical bar",
            "^" to "Caret", "<" to "Less than", ">" to "Greater than", "{" to "Left brace", "}" to "Right brace",
            "[" to "Left bracket", "]" to "Right bracket",
        )
    }
}
