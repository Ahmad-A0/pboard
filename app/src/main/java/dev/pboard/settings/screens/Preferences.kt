package dev.pboard.settings.screens

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pboard.R
import dev.pboard.prefs.OneHandedMode
import dev.pboard.prefs.Prefs
import dev.pboard.prefs.SystemHaptics
import dev.pboard.settings.Navigator
import dev.pboard.settings.Route
import dev.pboard.settings.ui.BooleanListPref
import dev.pboard.settings.ui.Category
import dev.pboard.settings.ui.EnumListPref
import dev.pboard.settings.ui.GearRow
import dev.pboard.settings.ui.PrefRow
import dev.pboard.settings.ui.SettingsPage
import dev.pboard.settings.ui.SliderDialogPref
import dev.pboard.settings.ui.StepSliderPref
import dev.pboard.settings.ui.SwitchPref
import dev.pboard.settings.ui.SwitchRow
import dev.pboard.settings.ui.collectAsState
import dev.pboard.settings.ui.rememberPrefs

@Composable
fun PreferencesScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val prefs = rememberPrefs()
    val largeScreen = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val languages by Prefs.enabledLanguages.collectAsState()
    val emojiKey by Prefs.showEmojiSwitchKey.collectAsState()
    val languageKey by Prefs.showLanguageSwitchKey.collectAsState()
    val sound by Prefs.soundOnKeypress.collectAsState()
    val vibrate by Prefs.vibrateOnKeypress.collectAsState()
    val systemHaptics = remember { SystemHaptics.settingsIntent(ctx) }
    val a11yTouch by rememberTouchExploration(ctx)
    val amplitudeHaptics = remember { supportsClickPrimitive(ctx) }

    val numberRow by Prefs.numberRow.collectAsState()

    // Gboard 18.6 layout: Keys · Appearance · Layout · Shortcuts · Key tap (+ pboard's Toolbar entry).
    SettingsPage(stringResource(R.string.header_preferences), onBack = { nav.pop() }) {
        item { Category(stringResource(R.string.cat_keys)) }
        item { SwitchPref(Prefs.numberRow, stringResource(R.string.number_row), stringResource(R.string.number_row_summary)) }
        item {
            // Only meaningful while the number row is off (Gboard disables it when the number row is on).
            SwitchPref(Prefs.numberRowInPassword, stringResource(R.string.number_row_password),
                stringResource(R.string.number_row_password_summary), enabled = !numberRow)
        }
        item {
            SwitchRow(stringResource(R.string.emoji_switch_key), emojiKey, {
                prefs.edit { this[Prefs.showEmojiSwitchKey] = it; if (it) this[Prefs.showLanguageSwitchKey] = false }
            }, stringResource(R.string.emoji_switch_key_summary))
        }
        if (languages.size > 1) {
            item {
                SwitchRow(stringResource(R.string.language_switch_key), languageKey, {
                    prefs.edit { this[Prefs.showLanguageSwitchKey] = it; if (it) this[Prefs.showEmojiSwitchKey] = false }
                }, stringResource(R.string.language_switch_key_summary))
            }
        }
        item { SwitchPref(Prefs.showCommaKey, stringResource(R.string.comma_key), stringResource(R.string.comma_key_summary)) }
        item { SwitchPref(Prefs.showPeriodKey, stringResource(R.string.period_key), stringResource(R.string.period_key_summary)) }

        item { Category(stringResource(R.string.cat_toolbar)) }
        item {
            PrefRow(stringResource(R.string.customize_toolbar), summary = stringResource(R.string.customize_toolbar_summary),
                onClick = { nav.push(Route.Toolbar) })
        }

        item { Category(stringResource(R.string.cat_appearance)) }
        item {
            BooleanListPref(Prefs.useSystemFont, stringResource(R.string.font), stringResource(R.string.font_pboard),
                stringResource(R.string.system_default))
        }
        item { StepSliderPref(Prefs.keyFontScale, Prefs.SCALE_STEPS, stringResource(R.string.font_size)) { ctx.getString(R.string.value_percent, it) } }
        item {
            StepSliderPref(Prefs.emojiScale, Prefs.SCALE_STEPS, stringResource(R.string.emoji_size),
                summary = stringResource(R.string.emoji_size_summary)) { ctx.getString(R.string.value_percent, it) }
        }

        item { Category(stringResource(R.string.cat_layout)) }
        item {
            SwitchPref(Prefs.suggestionStripVisible, stringResource(R.string.suggestion_strip),
                stringResource(R.string.suggestion_strip_summary))
        }
        if (!largeScreen) {
            item {
                val labels = mapOf(
                    OneHandedMode.OFF to stringResource(R.string.off),
                    OneHandedMode.RIGHT to stringResource(R.string.one_handed_right),
                    OneHandedMode.LEFT to stringResource(R.string.one_handed_left),
                )
                EnumListPref(Prefs.oneHandedMode, stringResource(R.string.one_handed_mode), { labels.getValue(it) })
            }
        }
        // No "Split layout to include duplicated keys" row (large screens): the split keyboard does not repeat the middle
        // column yet (Prefs.splitDuplicateKeys is not read).

        item { Category(stringResource(R.string.cat_shortcuts)) }
        item {
            SwitchPref(Prefs.doubleSpacePeriod, stringResource(R.string.double_space_period),
                stringResource(R.string.double_space_period_summary))
        }
        item { SwitchPref(Prefs.autoSpaceAfterPunctuation, stringResource(R.string.autospace), stringResource(R.string.autospace_summary)) }
        item {
            SwitchPref(Prefs.autoSwitchAfterApostrophe, stringResource(R.string.auto_switch_apostrophe),
                stringResource(R.string.auto_switch_apostrophe_summary))
        }
        item {
            SwitchPref(Prefs.longPressForSymbols, stringResource(R.string.long_press_symbols),
                stringResource(R.string.long_press_symbols_summary))
        }
        // No "Flick keys to enter symbols" / "Flick input sensitivity" rows: the keyboard has no flick input yet
        // (Prefs.flickSymbols and Prefs.flickSensitivity are not read).

        item { Category(stringResource(R.string.cat_key_press)) }
        item { SwitchPref(Prefs.soundOnKeypress, stringResource(R.string.sound_on_keypress), stringResource(R.string.sound_summary)) }
        item {
            val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            SliderDialogPref(
                Prefs.keypressVolume, stringResource(R.string.volume_on_keypress),
                format = { ctx.getString(R.string.value_percent, it) },
                enabled = sound, sliderMin = 0,
                systemLabel = stringResource(R.string.system_default), systemValue = Prefs.SYSTEM_DEFAULT,
                preview = { audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, it / 100f) },
            )
        }
        if (systemHaptics != null) {
            item {
                val on by rememberSystemHapticsEnabled(ctx)
                GearRow(stringResource(R.string.haptic_on_keypress),
                    stringResource(if (on) R.string.on else R.string.off),
                    onGear = { runCatching { ctx.startActivity(systemHaptics) } })
            }
        } else {
            item { SwitchPref(Prefs.vibrateOnKeypress, stringResource(R.string.vibrate_on_keypress)) }
            item {
                SliderDialogPref(
                    Prefs.vibrationStrength, stringResource(R.string.vibration_strength),
                    format = { ctx.getString(if (amplitudeHaptics) R.string.value_percent else R.string.value_ms, it) },
                    enabled = vibrate, sliderMin = 0,
                    systemLabel = stringResource(R.string.system_default), systemValue = Prefs.SYSTEM_DEFAULT,
                )
            }
        }
        item { SwitchPref(Prefs.popupOnKeypress, stringResource(R.string.popup_on_keypress), stringResource(R.string.popup_summary)) }
        item {
            SliderDialogPref(Prefs.longPressDelayMs, stringResource(R.string.long_press_delay),
                format = { ctx.getString(R.string.value_ms, it) })
        }
        if (a11yTouch) {
            item {
                SliderDialogPref(Prefs.longPressDelayA11yMs, stringResource(R.string.long_press_delay_a11y),
                    format = { ctx.getString(R.string.value_ms, it) })
            }
        }
        item { ShowAppIconRow() }
    }
}

/** The launcher entry is an activity-alias; hiding the icon disables it (settings stay reachable from the IME). */
@Composable
private fun ShowAppIconRow() {
    val ctx = LocalContext.current
    val prefs = rememberPrefs()
    val shown by Prefs.showAppIcon.collectAsState()
    SwitchRow(stringResource(R.string.show_app_icon), shown, {
        prefs[Prefs.showAppIcon] = it
        setLauncherIconVisible(ctx, it)
    }, stringResource(R.string.show_app_icon_summary))
}

fun setLauncherIconVisible(ctx: Context, visible: Boolean) {
    ctx.packageManager.setComponentEnabledSetting(
        ComponentName(ctx, "dev.pboard.LauncherActivity"),
        if (visible) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
}

private fun supportsClickPrimitive(ctx: Context): Boolean {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
}

/**
 * The system keyboard-vibration switch, re-read whenever it changes: the row links to the system page, and its
 * On/Off summary must be current when the user comes back (it was read once and stayed stale until the page left).
 */
@Composable
private fun rememberSystemHapticsEnabled(ctx: Context): State<Boolean> {
    val state = remember { mutableStateOf(SystemHaptics.isEnabled(ctx)) }
    DisposableEffect(ctx) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = SystemHaptics.isEnabled(ctx)
            }
        }
        SystemHaptics.settingUris().forEach { ctx.contentResolver.registerContentObserver(it, false, observer) }
        state.value = SystemHaptics.isEnabled(ctx)
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

/** TalkBack's touch exploration, followed live (it decides whether the accessibility long-press delay row shows). */
@Composable
private fun rememberTouchExploration(ctx: Context): State<Boolean> {
    val am = remember { ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    val state = remember { mutableStateOf(am.isTouchExplorationEnabled) }
    DisposableEffect(am) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { state.value = it }
        am.addTouchExplorationStateChangeListener(listener)
        state.value = am.isTouchExplorationEnabled
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }
    return state
}
