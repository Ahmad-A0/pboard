package dev.pboard.ime.config

import android.content.Context
import android.content.res.Configuration
import android.view.accessibility.AccessibilityManager
import dev.pboard.ime.toolbar.ToolbarItem
import dev.pboard.ime.toolbar.ToolbarLayout
import dev.pboard.prefs.KeyboardMode
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Prefs
import dev.pboard.prefs.ScreenOrientation
import dev.pboard.prefs.SystemHaptics
import dev.pboard.prefs.ThemeCatalog

/**
 * [KeyboardConfig] backed by the real settings store ([PboardPrefs], the single source of truth).
 * Reads are synchronous in-memory lookups, so the keyboard can query them on every key press.
 */
class PrefsKeyboardConfig(context: Context) : KeyboardConfig {
    private val ctx = context.applicationContext
    val prefs = PboardPrefs.get(ctx)
    /** Resolved once: a PackageManager query is too slow for every key press. */
    private val systemHaptics by lazy { SystemHaptics.isAvailable(ctx) }

    override val hapticOnKeypress: Boolean
        get() = if (systemHaptics) SystemHaptics.isEnabled(ctx) else prefs[Prefs.vibrateOnKeypress]
    override val hapticDurationMs: Int
        get() = prefs[Prefs.vibrationStrength].let { if (it == Prefs.SYSTEM_DEFAULT || systemHaptics) -1 else it }
    override val soundOnKeypress get() = prefs[Prefs.soundOnKeypress]
    override val soundVolume: Float
        get() = prefs[Prefs.keypressVolume].let { if (it == Prefs.SYSTEM_DEFAULT) -1f else it / 100f }
    override val popupOnKeypress get() = prefs[Prefs.popupOnKeypress]
    private val accessibility = ctx.getSystemService(AccessibilityManager::class.java)
    /** Gboard: "Touch & hold duration for accessibility" replaces the normal delay while TalkBack explores by touch. */
    override val longPressDelayMs: Int
        get() = if (accessibility?.isTouchExplorationEnabled == true) prefs[Prefs.longPressDelayA11yMs] else prefs[Prefs.longPressDelayMs]

    override val numberRow get() = prefs[Prefs.numberRow]
    override val numberRowInPassword get() = prefs[Prefs.numberRowInPassword]
    override val showCommaKey get() = prefs[Prefs.showCommaKey]
    override val showPeriodKey get() = prefs[Prefs.showPeriodKey]
    override val keyFontScale get() = prefs[Prefs.keyFontScale] / 100f
    override val useSystemFont get() = prefs[Prefs.useSystemFont]
    override val emojiScale get() = prefs[Prefs.emojiScale] / 100f
    override val roundedKeys get() = prefs[Prefs.roundedKeys]
    override val autoSwitchAfterApostrophe get() = prefs[Prefs.autoSwitchAfterApostrophe]
    override val suggestionStripVisible get() = prefs[Prefs.suggestionStripVisible]
    override val clipboardRetentionHours get() = prefs[Prefs.clipboardRetentionHours]
    override val keyBorders get() = prefs[Prefs.keyBorders]
    override val numberHints get() = prefs[Prefs.longPressForNumbers]
    override val longPressSymbols get() = prefs[Prefs.longPressForSymbols]
    override val heightScale: Float
        get() {
            val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val o = if (landscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT
            return prefs[Prefs.geometry(KeyboardMode.NORMAL, o)].heightRatio
        }
    override val themeId get() = prefs[Prefs.theme]
    override val customThemes get() = prefs[Prefs.customThemes].mapNotNull(ThemeCatalog.CustomTheme::decode)
    override val emojiSwitchKey get() = prefs[Prefs.showEmojiSwitchKey]
    override val languageSwitchKey get() = prefs[Prefs.showLanguageSwitchKey]
    override val showToolbar get() = true
    override val voiceTyping get() = prefs[Prefs.voiceTyping]
    override val pkShowToolbar get() = prefs[Prefs.pkShowToolbar]
    override val pkSuggestions get() = prefs[Prefs.pkSuggestions]
    override val toolbarItems: List<String>
        get() = ToolbarLayout.load(prefs).pinned
            .filter { it.availability == ToolbarItem.Availability.ALWAYS && it.isOffered(largeScreen = false, voiceTyping) }
            .map { it.id }
    override val toolbarPowerKey: String?
        get() = ToolbarLayout.load(prefs).powerKey?.takeIf { it.isOffered(largeScreen = true, voiceTyping) }?.id

    override val showSuggestionStrip get() = prefs[Prefs.showSuggestionStrip]
    override val autoCorrection get() = prefs[Prefs.autoCorrection]
    override val undoAutoCorrectOnBackspace get() = prefs[Prefs.undoAutoCorrectOnBackspace]
    override val autoCapitalization get() = prefs[Prefs.autoCapitalization]
    override val autoSpaceAfterPunctuation get() = prefs[Prefs.autoSpaceAfterPunctuation]
    override val doubleSpacePeriod get() = prefs[Prefs.doubleSpacePeriod]
    override val nextWordSuggestions get() = prefs[Prefs.nextWordSuggestions]
    override val emojiSuggestions get() = prefs[Prefs.emojiSuggestions]
    override val personalizedSuggestions get() = prefs[Prefs.personalization]
    override val blockOffensiveWords get() = prefs[Prefs.blockOffensiveWords]
    override val grammarCheck get() = prefs[Prefs.grammarCheck]
    override val smartCompose get() = prefs[Prefs.smartCompose] && prefs[Prefs.smartComposeScope] != dev.pboard.prefs.SmartComposeScope.OFF
    override val smartComposeScope: String
        get() = if (!prefs[Prefs.smartCompose]) "off" else prefs[Prefs.smartComposeScope].id

    override val gestureTyping get() = prefs[Prefs.glideTyping]
    override val gestureTrail get() = prefs[Prefs.gestureTrail]
    override val gestureDelete get() = prefs[Prefs.gestureDelete]
    override val gestureCursorControl get() = prefs[Prefs.gestureCursorControl]

    override val clipboardHistory get() = prefs[Prefs.clipboardHistory]
    override val clipboardSuggestions get() = prefs[Prefs.clipboardSuggestions]
    override val learnedDataClearRequest get() = prefs[Prefs.learnedDataClearRequest]
}
