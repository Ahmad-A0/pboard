package dev.pboard.ime.config

import dev.pboard.prefs.ThemeCatalog

/**
 * Every user-configurable behaviour the keyboard reads, in one place.
 *
 * The keyboard only ever reads [Config.current]. The IME installs [PrefsKeyboardConfig] (backed by dev.pboard.prefs,
 * the settings store) at startup; [GboardDefaults] is the in-code fallback used by tests and before installation.
 * Implementations must be cheap to read (they are queried on every key press).
 */
interface KeyboardConfig {
    // Feedback
    val hapticOnKeypress: Boolean
    /** Vibration duration override in ms; -1 means "system default" (HapticFeedbackConstants.KEYBOARD_TAP). */
    val hapticDurationMs: Int
    val soundOnKeypress: Boolean
    /** 0..1, or -1 for the system default volume. */
    val soundVolume: Float
    val popupOnKeypress: Boolean
    val longPressDelayMs: Int

    // Layout & look
    val numberRow: Boolean
    val keyBorders: Boolean
    /** Number hints on the top row (Gboard shows them by default) — long-pressing the top row yields digits. */
    val numberHints: Boolean
    /** Gboard "Long press for symbols": shows hint symbols on every letter key. */
    val longPressSymbols: Boolean
    /** Keyboard height multiplier (Gboard's "Keyboard height"; 1.0 = default). */
    val heightScale: Float
    /** Gboard 18.6 "Number row for passwords": digits on top of password keyboards while [numberRow] is off. */
    val numberRowInPassword: Boolean get() = true
    /** Gboard 18.6 "Comma key" / "Period key" beside the space bar. */
    val showCommaKey: Boolean get() = true
    val showPeriodKey: Boolean get() = true
    /** Gboard 18.6 "Font size": key-label scale (0.85..2.0). */
    val keyFontScale: Float get() = 1f
    /** Preferences › Font: the system's default font instead of pboard's (dev.pboard.ime.theme.KeyFonts). */
    val useSystemFont: Boolean get() = false
    /** Gboard 18.6 "Emoji size": emoji scale in the panel (0.85..2.0); bigger emoji, fewer per row. */
    val emojiScale: Float get() = 1f
    /** Gboard 18.6 "Rounded" key shape (fully rounded caps). */
    val roundedKeys: Boolean get() = false
    /** Gboard 18.6 "Auto-switch after apostrophes": back to letters after typing ' on the symbols page. */
    val autoSwitchAfterApostrophe: Boolean get() = true
    /** Gboard 18.6 "Suggestion strip": the whole top row (suggestions and toolbar). */
    val suggestionStripVisible: Boolean get() = true
    /** Gboard 18.6 "Keep clipboard history for" (hours, 1..24). */
    val clipboardRetentionHours: Int get() = 1
    /** Theme id from dev.pboard.prefs.ThemeCatalog ("dynamic" = Material You following the system). */
    val themeId: String
    /** The user's image themes ("My themes"); [themeId] may name one of them. */
    val customThemes: List<ThemeCatalog.CustomTheme> get() = emptyList()
    val emojiSwitchKey: Boolean
    val languageSwitchKey: Boolean
    val showToolbar: Boolean
    /** Settings › Voice typing › Use voice typing: off hides the mic (power key, toolbar items, physical-keyboard menu). */
    val voiceTyping: Boolean get() = true
    /** Settings › Physical keyboard › Show toolbar: the floating button in physical-keyboard mode. */
    val pkShowToolbar: Boolean get() = true
    /** Settings › Physical keyboard › Suggestions while typing: the card above the word in physical-keyboard mode. */
    val pkSuggestions: Boolean get() = true
    /** Ordered ids of the toolbar items pinned on the bar (dev.pboard.ime.toolbar.ToolbarItem ids). */
    val toolbarItems: List<String>
    /** The item at the right end of the bar (Gboard: voice), or null. */
    val toolbarPowerKey: String?

    // Text correction
    val showSuggestionStrip: Boolean
    val autoCorrection: Boolean
    val undoAutoCorrectOnBackspace: Boolean
    val autoCapitalization: Boolean
    val autoSpaceAfterPunctuation: Boolean
    val doubleSpacePeriod: Boolean
    val nextWordSuggestions: Boolean
    val emojiSuggestions: Boolean
    val personalizedSuggestions: Boolean
    val blockOffensiveWords: Boolean
    /** On-device grammar check: marks in the field and a fix chip in the strip (dev.pboard.ime.textintel). */
    val grammarCheck: Boolean get() = true
    /** Smart Compose: inline completion of the phrase being typed (dev.pboard.ime.textintel). */
    val smartCompose: Boolean get() = true
    /** Where Smart Compose shows: "messages" (message-like fields, default), "everywhere" or "off". */
    val smartComposeScope: String get() = "messages"

    // Gesture typing
    val gestureTyping: Boolean
    val gestureTrail: Boolean
    val gestureDelete: Boolean
    val gestureCursorControl: Boolean

    // Clipboard
    val clipboardHistory: Boolean
    /** Offer a recently copied clip (<= 2 minutes old) as a chip in the strip. */
    val clipboardSuggestions: Boolean

    /** Timestamp of the last "clear learned data" request; learned data is wiped whenever it increases. */
    val learnedDataClearRequest: Long
}

object ToolbarItems {
    const val EXPAND = "expand"
    const val STICKER = "sticker"
    const val GIF = "gif"
    const val CLIPBOARD = "clipboard"
    const val SETTINGS = "settings"
    const val THEME = "theme"
    const val EMOJI = "emoji"
    const val TRANSLATE = "translate"
    const val VOICE = "voice"
    const val ONE_HANDED = "one_handed"
    const val TEXT_EDITING = "text_editing"
    const val UNDO = "undo"
    const val RESIZE = "resize"
    const val FLOATING = "floating"
    const val SPLIT = "split"
}

/** Gboard 15 defaults (Pixel, English), as measured on-device. */
open class GboardDefaults : KeyboardConfig {
    override val hapticOnKeypress = true
    override val hapticDurationMs = -1
    override val soundOnKeypress = false
    override val soundVolume = -1f
    override val popupOnKeypress = true
    override val longPressDelayMs = 300
    override val numberRow = false
    override val keyBorders = true
    override val numberHints = true
    override val longPressSymbols = false
    override val heightScale = 1f
    override val themeId = "dynamic"
    override val emojiSwitchKey = true
    override val languageSwitchKey = true
    override val showToolbar = true
    override val toolbarItems = listOf(
        ToolbarItems.STICKER, ToolbarItems.GIF, ToolbarItems.CLIPBOARD, ToolbarItems.SETTINGS, ToolbarItems.THEME,
    )
    override val toolbarPowerKey: String? = ToolbarItems.VOICE
    override val showSuggestionStrip = true
    override val autoCorrection = true
    override val undoAutoCorrectOnBackspace = false
    override val autoCapitalization = true
    override val autoSpaceAfterPunctuation = false
    override val doubleSpacePeriod = true
    override val nextWordSuggestions = true
    override val emojiSuggestions = true
    override val personalizedSuggestions = true
    override val blockOffensiveWords = true
    override val gestureTyping = true
    override val gestureTrail = true
    override val gestureDelete = true
    override val gestureCursorControl = true
    override val clipboardHistory = false
    override val clipboardSuggestions = true
    override val learnedDataClearRequest = 0L
}

object Config {
    @Volatile var current: KeyboardConfig = GboardDefaults()
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** Repoints the keyboard at another config source and notifies listeners. */
    fun install(config: KeyboardConfig) {
        current = config
        notifyChanged()
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    fun notifyChanged() { synchronized(listeners) { listeners.toList() }.forEach { it() } }
}
