package dev.pboard.prefs

import dev.pboard.ime.toolbar.ToolbarItem

/**
 * The catalogue of every pboard option — the single source of truth the IME reads (through [PboardPrefs]).
 * Defaults are Gboard's — 15.1, updated to 18.6 where they changed; deliberate
 * deviations are marked **pboard:**.
 *
 * Key list (storage key → meaning). Grouped like the settings screens.
 *
 * | Key | Type | Default | Screen › option |
 * |---|---|---|---|
 * | `enabled_languages` | list `tag:layout` | `en-US:qwerty` | Languages (order = switch order; seeded from the system subtypes on first run, see [KeyboardLanguages.seed]) |
 * | `active_language` | string tag | "" | The enabled keyboard in use (language switch); "" = the first one |
 * | `multilingual_typing` | bool | true | Languages › Multilingual typing |
 * | `number_row` | bool | false | Preferences › Number row |
 * | `show_emoji_switch_key` | bool | true | Preferences › Show emoji switch key (exclusive with language key) |
 * | `show_language_switch_key` | bool | true | Preferences › Show language switch key (≥2 languages only) |
 * | `number_row_in_password` | bool | true | Preferences › Number row for passwords (18.6; only while number row is off) |
 * | `show_comma_key` | bool | true | Preferences › Comma key (18.6) |
 * | `show_period_key` | bool | true | Preferences › Period key (18.6) |
 * | `use_system_font` | bool | false | Preferences › Font (false = pboard default) |
 * | `key_font_scale` | int % | 100 | Preferences › Font size (18.6; steps [SCALE_STEPS]) |
 * | `emoji_scale` | int % | 100 | Preferences / Emojis › Emoji size (18.6; steps [SCALE_STEPS]) |
 * | `dark_theme_in_battery_saver` | bool | false | Preferences › Dark theme in battery saver (hidden on API 29+) |
 * | `one_handed_mode` | enum | off | Preferences › One-handed mode (off/right/left) |
 * | `floating_keyboard` | bool | false | Toolbar › Floating (keyboard mode state) |
 * | `split_mode` | enum | auto | Toolbar › Split (auto = Gboard's default: split from 800 dp smallest width; on / off = user's choice) |
 * | `split_duplicate_keys` | bool | false | Preferences › Split layout duplicated keys (large screens) |
 * | `sound_on_keypress` | bool | false | Preferences › Sound on keypress |
 * | `keypress_volume` | int % | -1 (= system) | Preferences › Volume on keypress (0..100) |
 * | `vibrate_on_keypress` | bool | true | Preferences › Vibrate on keypress (only when the system has no keyboard-vibration setting, see [SystemHaptics]) |
 * | `vibration_strength` | int | -1 (= system) | Preferences › Vibration strength (0..100: % amplitude, or ms on devices without primitives) |
 * | `popup_on_keypress` | bool | true | Preferences › Popup on keypress |
 * | `long_press_for_numbers` | bool | true | Preferences › Long press for numbers (hidden, behaviour on) |
 * | `long_press_for_symbols` | bool | false | Preferences › Long press for symbols |
 * | `long_press_delay_ms` | int ms | 300 | Preferences › Key long press delay (100..2000 step 10) |
 * | `long_press_delay_a11y_ms` | int ms | 3000 | Preferences › … for accessibility (500..6000 step 100) |
 * | `flick_sensitivity` | enum | normal | Preferences › Flick input sensitivity |
 * | `auto_switch_after_apostrophe` | bool | true | Preferences › Shortcuts › Auto-switch after apostrophes (18.6) |
 * | `flick_symbols` | bool | false | Preferences › Shortcuts › Flick keys to enter symbols (18.6; Gboard: on for tablets) |
 * | `space_long_press_trackpad` | bool | true | Preferences › Shortcuts › Long press spacebar for trackpad (18.6, not shown by default in Gboard) |
 * | `show_app_icon` | bool | true | Preferences › App icon (Gboard 18.6: on; 15.1 preload: off) |
 * | `theme` | string | `dynamic` | Theme (id from [ThemeCatalog] or `custom_<uuid>`) |
 * | `key_borders` | bool | true | Theme › Key borders |
 * | `rounded_keys` | bool | false | Theme › Key shape: Rounded (18.6, not shown by default in Gboard) |
 * | `custom_themes` | list `id|brightness` | [] | Theme › My themes (images in files/themes/) |
 * | `show_suggestion_strip` | bool | true | Corrections › Word suggestions (15.1 label: Show suggestion strip) |
 * | `suggestion_strip_visible` | bool | true | Preferences/Corrections › Suggestion strip (18.6: the whole top row) |
 * | `next_word_suggestions` | bool | true | Text correction › Next-word suggestions |
 * | `block_offensive_words` | bool | true | Text correction › Don't suggest offensive words |
 * | `suggest_contacts` | bool | false | Text correction › Suggest contact names (needs READ_CONTACTS, asked for when turned on) |
 * | `smart_compose` | bool | true | Text correction › Smart Compose master switch (legacy; the row now writes the scope) |
 * | `smart_compose_scope` | enum | messages | Text correction › Smart Compose: messages only / everywhere / off |
 * | `auto_correction` | bool | true | Text correction › Auto-correction |
 * | `undo_autocorrect_on_backspace` | bool | false | Text correction › Undo auto-correct on backspace |
 * | `autospace_after_punctuation` | bool | false | Text correction › Autospace after punctuation |
 * | `auto_capitalization` | bool | true | Text correction › Auto-capitalization |
 * | `double_space_period` | bool | true | Text correction › Double-space period |
 * | `auto_space_after_suggestions` | bool | true | Corrections › Auto-space after suggestions (18.6, hidden in Gboard) |
 * | `spell_check` | bool | true | Text correction › Spell check (spell-checker service) |
 * | `grammar_check` | bool | true | Text correction › Grammar check (on-device, docs/text-intelligence.md) |
 * | `glide_typing` | bool | true | Glide typing › Glide typing |
 * | `gesture_trail` | bool | true | Glide typing › Show gesture trail |
 * | `gesture_delete` | bool | true | Glide typing › Gesture delete |
 * | `gesture_cursor_control` | bool | true | Glide typing › Gesture cursor control |
 * | `voice_typing` | bool | true | Voice typing › Use voice typing (mic hidden without a local model) |
 * | `seamless_voice_typing` | bool | false | Voice typing › Seamless voice typing (hidden) |
 * | `stylus_handwriting` | bool | true | Write in text fields › Use stylus |
 * | `stylus_speed` | enum | mid_fast | Write in text fields › Handwriting speed |
 * | `stylus_stroke_width` | enum | normal | Write in text fields › Stroke width |
 * | `stylus_show_keyboard` | bool | false | Write in text fields › Show on-screen keyboard |
 * | `stylus_emoji_button` | bool | true | Write in text fields › Emojis on toolbar |
 * | `stylus_commit_immediately` | bool | true | Write in text fields › Commit immediately |
 * | `pk_show_on_screen_keyboard` | bool | false | Physical keyboard › Show on-screen keyboard |
 * | `pk_show_toolbar` | bool | true | Physical keyboard › Show toolbar |
 * | `pk_emoji_button` | bool | true | Physical keyboard › Emojis on toolbar |
 * | `pk_suggestions` | bool | true | Physical keyboard › Suggestions while typing |
 * | `pk_auto_correction` | bool | true | Physical keyboard › Auto-correction |
 * | `pk_auto_capitalization` | bool | true | Physical keyboard › Auto-capitalization |
 * | `pk_long_press_diacritics` | bool | true | Physical keyboard › Long press for diacritical marks |
 * | `clipboard_history` | bool | false | Clipboard panel on/off (unpinned clips expire after 1 h) |
 * | `clipboard_suggestions` | bool | true | Clipboard › Recent copy as suggestion chip |
 * | `clipboard_entities` | bool | true | Clipboard › Split addresses/phone numbers into chips |
 * | `clipboard_retention_hours` | int h | 1 | Clipboard › Keep clipboard history for (18.6; [CLIPBOARD_RETENTION_STEPS]) |
 * | `emoji_fast_access_row` | bool | false | Emojis › Emoji fast-access row |
 * | `emoji_in_symbols` | bool | false | Emojis › Emojis in symbols keyboard |
 * | `emoji_physical_alt_key` | bool | true | Emojis › Emoji with the physical keyboard |
 * | `emojify_button` | bool | true | Emojis › Button to add multiple emojis |
 * | `emoji_suggestions` | bool | true | Emojis › Suggestions while typing › Emojis |
 * | `personalization` | bool | true | Privacy › Personalize for you (on-device learning) |
 * | `learned_data_clear_request` | long | 0 | Privacy › Delete learned words and data (timestamp; IME wipes when it changes) |
 * | `toolbar_order` | list ids | see [ToolbarItem.DEFAULT_ORDER] | Toolbar order (first `toolbar_pinned_count` are on the bar) |
 * | `toolbar_pinned_count` | int | 5 | Toolbar: number of items on the bar (0..8) |
 * | `toolbar_power_key` | string | `voice` | Toolbar: item in the right-hand slot |
 * | `translate_target` | string | "" | Translate bar: last target language ("" = first installed) |
 * | `translate_recent_sources` / `translate_recent_targets` | list | [] | Translate pickers: recent languages |
 * | `geometry_<mode>_<orientation>` | [KeyboardGeometry] | per mode | Resize overlay results |
 */
object Prefs {
    private val all = mutableListOf<Pref<*>>()

    /** Font size / emoji size steps in percent (Gboard 18.6). */
    val SCALE_STEPS = listOf(85, 100, 115, 130, 150, 180, 200)

    /** Every declared entry (for debugging, export and tests). */
    val entries: List<Pref<*>> get() = all

    private fun <T : Any, P : Pref<T>> reg(p: P): P = p.also { all += it }
    private fun bool(key: String, default: Boolean) = reg(Pref(key, default, Codec.BoolCodec))
    private fun long(key: String, default: Long) = reg(Pref(key, default, Codec.LongCodec))
    private fun string(key: String, default: String) = reg(Pref(key, default, Codec.StringCodec))
    private fun stringList(key: String, default: List<String>) = reg(Pref(key, default, Codec.StringListCodec))
    private fun int(key: String, default: Int, min: Int, max: Int, step: Int = 1) =
        reg(IntPref(key, default, min, max, step))

    private inline fun <reified E> enum(key: String, default: E): EnumPref<E> where E : Enum<E>, E : StableId =
        reg(EnumPref(key, default, enumValues<E>().toList()))

    // ---- Languages ----
    /** Enabled keyboards as `languageTag:layoutId`, in switching order. See [LanguageCatalog]. */
    val enabledLanguages = stringList("enabled_languages", listOf("en-US:qwerty"))
    /** The enabled keyboard in use, by language tag ([KeyboardLanguages.active]); the language switch moves it. */
    val activeLanguage = string("active_language", "")
    val multilingualTyping = bool("multilingual_typing", true)

    // ---- Preferences › Keys ----
    val numberRow = bool("number_row", false)
    val showEmojiSwitchKey = bool("show_emoji_switch_key", true)
    val showLanguageSwitchKey = bool("show_language_switch_key", true)

    /** Digits row in password fields even when [numberRow] is off (Gboard 18.6, default on). */
    val numberRowInPassword = bool("number_row_in_password", true)
    val showCommaKey = bool("show_comma_key", true)
    val showPeriodKey = bool("show_period_key", true)

    // ---- Preferences › Appearance ----
    val useSystemFont = bool("use_system_font", false)
    val darkThemeInBatterySaver = bool("dark_theme_in_battery_saver", false)

    /** Key-label size in percent; the UI offers [SCALE_STEPS]. */
    val keyFontScale = int("key_font_scale", 100, SCALE_STEPS.first(), SCALE_STEPS.last())

    /** Emoji size in percent (bigger emoji = fewer per row); the UI offers [SCALE_STEPS]. */
    val emojiScale = int("emoji_scale", 100, SCALE_STEPS.first(), SCALE_STEPS.last())

    // ---- Preferences › Layout / keyboard modes ----
    val oneHandedMode = enum("one_handed_mode", OneHandedMode.OFF)
    val floatingKeyboard = bool("floating_keyboard", false)
    val splitMode = enum("split_mode", SplitMode.AUTO)
    val splitDuplicateKeys = bool("split_duplicate_keys", false)

    // ---- Preferences › Key press ----
    val soundOnKeypress = bool("sound_on_keypress", false)

    /** −1 = system default volume, otherwise 0..100 %. */
    val keypressVolume = int("keypress_volume", SYSTEM_DEFAULT, SYSTEM_DEFAULT, 100)
    val vibrateOnKeypress = bool("vibrate_on_keypress", true)

    /** −1 = system default; otherwise 0..100 (% amplitude of a click primitive, or ms on older vibrators). */
    val vibrationStrength = int("vibration_strength", SYSTEM_DEFAULT, SYSTEM_DEFAULT, 100)
    val popupOnKeypress = bool("popup_on_keypress", true)
    val longPressForNumbers = bool("long_press_for_numbers", true)
    val longPressForSymbols = bool("long_press_for_symbols", false)
    val longPressDelayMs = int("long_press_delay_ms", 300, 100, 2000, 10)
    val longPressDelayA11yMs = int("long_press_delay_a11y_ms", 3000, 500, 6000, 100)
    val flickSensitivity = enum("flick_sensitivity", FlickSensitivity.NORMAL)

    /** Back to the letters layer after typing an apostrophe on the symbols layer. */
    val autoSwitchAfterApostrophe = bool("auto_switch_after_apostrophe", true)

    /** Pull a key downward to type its hinted symbol. Gboard: off on phones, on on tablets. */
    val flickSymbols = bool("flick_symbols", false)

    /** Long-press the space bar to enter trackpad (free cursor) mode; swiping the space bar works regardless. */
    val spaceLongPressTrackpad = bool("space_long_press_trackpad", true)

    /** Launcher icon. Gboard 18.6 defaults to on as well (15.1 preload: off). */
    val showAppIcon = bool("show_app_icon", true)

    // ---- Theme ----
    val theme = string("theme", ThemeCatalog.DYNAMIC)
    val keyBorders = bool("key_borders", true)

    /** Gboard 18.6 "Key shape: Rounded" (fully rounded key caps); only meaningful with [keyBorders]. */
    val roundedKeys = bool("rounded_keys", false)

    /** User image themes as `id|brightness` (brightness 0..100); image at `files/themes/<id>.jpg`. */
    val customThemes = stringList("custom_themes", emptyList())

    // ---- Text correction ----
    /**
     * Word suggestions in the strip (Gboard 18.6 "Word suggestions"; 15.1 called this "Show suggestion strip", hence the
     * key). When off, the bar keeps showing toolbar items while typing.
     */
    val showSuggestionStrip = bool("show_suggestion_strip", true)

    /**
     * The whole suggestion strip / toolbar row (Gboard 18.6 "Suggestion strip: show suggestions and access features").
     * When off, the keyboard shows no top row at all (word suggestions are then off too).
     */
    val suggestionStripVisible = bool("suggestion_strip_visible", true)
    val nextWordSuggestions = bool("next_word_suggestions", true)
    val blockOffensiveWords = bool("block_offensive_words", true)
    /**
     * Suggest contact names (opt-in, off by default). Only in effect while READ_CONTACTS is granted, which the settings
     * switch asks for when it is turned on (dev.pboard.ime.contacts): names are read into memory, never stored.
     */
    val suggestContacts = bool("suggest_contacts", false)
    /** Smart Compose master switch (schema 1); [smartComposeScope] is what the settings row edits. */
    val smartCompose = bool("smart_compose", true)
    /** Where Smart Compose shows; off when [smartCompose] is false (a store from before the scope existed). */
    val smartComposeScope = enum("smart_compose_scope", SmartComposeScope.MESSAGES)
    val autoCorrection = bool("auto_correction", true)
    val undoAutoCorrectOnBackspace = bool("undo_autocorrect_on_backspace", false)
    val autoSpaceAfterPunctuation = bool("autospace_after_punctuation", false)
    val autoCapitalization = bool("auto_capitalization", true)
    val doubleSpacePeriod = bool("double_space_period", true)

    /** Add a space after a picked suggestion. */
    val autoSpaceAfterSuggestions = bool("auto_space_after_suggestions", true)
    val spellCheck = bool("spell_check", true)

    /** On-device grammar check (docs/text-intelligence.md: 91 % of flags are real errors on native text); Gboard: on. */
    val grammarCheck = bool("grammar_check", true)

    // ---- Glide typing ----
    val glideTyping = bool("glide_typing", true)
    val gestureTrail = bool("gesture_trail", true)
    val gestureDelete = bool("gesture_delete", true)
    val gestureCursorControl = bool("gesture_cursor_control", true)

    // ---- Voice typing ----
    val voiceTyping = bool("voice_typing", true)
    val seamlessVoiceTyping = bool("seamless_voice_typing", false)

    // ---- Write in text fields (stylus) ----
    val stylusHandwriting = bool("stylus_handwriting", true)
    val stylusSpeed = enum("stylus_speed", HandwritingSpeed.MID_FAST)
    val stylusStrokeWidth = enum("stylus_stroke_width", StrokeWidth.NORMAL)
    val stylusShowKeyboard = bool("stylus_show_keyboard", false)
    val stylusEmojiButton = bool("stylus_emoji_button", true)
    val stylusCommitImmediately = bool("stylus_commit_immediately", true)

    // ---- Physical keyboard ----
    val pkShowOnScreenKeyboard = bool("pk_show_on_screen_keyboard", false)
    val pkShowToolbar = bool("pk_show_toolbar", true)
    val pkEmojiButton = bool("pk_emoji_button", true)
    val pkSuggestions = bool("pk_suggestions", true)
    val pkAutoCorrection = bool("pk_auto_correction", true)
    val pkAutoCapitalization = bool("pk_auto_capitalization", true)
    val pkLongPressDiacritics = bool("pk_long_press_diacritics", true)

    // ---- Clipboard ----
    /** Clipboard history on/off (toggled in the clipboard panel). Unpinned clips expire after [clipboardRetentionHours]. */
    val clipboardHistory = bool("clipboard_history", false)
    val clipboardSuggestions = bool("clipboard_suggestions", true)
    val clipboardEntities = bool("clipboard_entities", true)

    /** How long unpinned clips are kept, in hours; the UI offers [CLIPBOARD_RETENTION_STEPS]. */
    val clipboardRetentionHours = int("clipboard_retention_hours", 1, 1, 24)
    val CLIPBOARD_RETENTION_STEPS = listOf(1, 2, 3, 6, 12, 18, 24)

    /** Default retention (1 h). Prefer `prefs[clipboardRetentionHours]`. */
    const val CLIP_TTL_MS = 60 * 60 * 1000L

    // ---- Emojis, Stickers & GIFs ----
    val emojiFastAccessRow = bool("emoji_fast_access_row", false)
    val emojiInSymbols = bool("emoji_in_symbols", false)
    val emojiPhysicalAltKey = bool("emoji_physical_alt_key", true)
    val emojifyButton = bool("emojify_button", true)
    val emojiSuggestions = bool("emoji_suggestions", true)

    // ---- Privacy ----
    val personalization = bool("personalization", true)

    /**
     * Set to the current time by "Delete learned words and data". The IME (and engine) must wipe learned n-grams,
     * history and caches whenever this value increases.
     */
    val learnedDataClearRequest = long("learned_data_clear_request", 0L)

    // ---- Translate (toolbar item; dev.pboard.ime.translate) ----
    /** Last target language (kept between sessions, like Gboard); "" = pick from the installed packs. */
    val translateTarget = string("translate_target", "")
    /** Recently picked source / target languages, most recent first (top of the language pickers). */
    val translateRecentSources = stringList("translate_recent_sources", emptyList())
    val translateRecentTargets = stringList("translate_recent_targets", emptyList())

    // ---- Toolbar (see dev.pboard.ime.toolbar.ToolbarLayout) ----
    val toolbarOrder = stringList("toolbar_order", ToolbarItem.DEFAULT_ORDER.map { it.id })
    val toolbarPinnedCount = int("toolbar_pinned_count", ToolbarItem.DEFAULT_BAR_ITEMS, 0, ToolbarItem.MAX_PINNED)
    val toolbarPowerKey = string("toolbar_power_key", ToolbarItem.VOICE.id)

    // ---- Keyboard geometry (resize overlay) ----
    private val geometryPrefs: Map<Pair<KeyboardMode, ScreenOrientation>, Pref<KeyboardGeometry>> =
        KeyboardMode.entries.flatMap { mode ->
            ScreenOrientation.entries.map { o ->
                (mode to o) to reg(
                    Pref(
                        "geometry_${mode.id}_${o.id}",
                        KeyboardGeometry.defaultFor(mode),
                        Codec.StringEncoded(KeyboardGeometry::encode, KeyboardGeometry::decode),
                    ),
                )
            }
        }.toMap()

    fun geometry(mode: KeyboardMode, orientation: ScreenOrientation): Pref<KeyboardGeometry> =
        geometryPrefs.getValue(mode to orientation)

    /** Sentinel for "follow the system" in [keypressVolume] and [vibrationStrength]. */
    const val SYSTEM_DEFAULT = -1
}
