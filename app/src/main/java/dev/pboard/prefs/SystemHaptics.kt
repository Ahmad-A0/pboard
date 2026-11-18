package dev.pboard.prefs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Newer Android builds have a system-wide "keyboard vibration" switch (Settings › Vibration & haptics). When it is
 * present, keyboards are expected to follow it: the settings screen shows a "Haptic feedback on keypress" row that
 * links there instead of pboard's own [Prefs.vibrateOnKeypress]/[Prefs.vibrationStrength].
 *
 * IME rule: `if (SystemHaptics.isAvailable(ctx)) SystemHaptics.isEnabled(ctx) else prefs[Prefs.vibrateOnKeypress]`.
 */
object SystemHaptics {
    private const val ACTION = "android.settings.KEYBOARD_VIBRATION_SETTINGS"
    private const val KEY_KEYBOARD_VIBRATION = "keyboard_vibration_enabled"
    private const val KEY_VIBRATE_ON = "vibrate_on"

    /** Intent of the system keyboard-vibration screen, or null when this Android build has none. */
    fun settingsIntent(context: Context): Intent? {
        val intent = Intent(ACTION)
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    fun isAvailable(context: Context) = settingsIntent(context) != null

    fun isEnabled(context: Context): Boolean {
        val cr = context.contentResolver
        return Settings.System.getInt(cr, KEY_VIBRATE_ON, 1) == 1 &&
            Settings.System.getInt(cr, KEY_KEYBOARD_VIBRATION, 1) == 1
    }

    /** The system settings [isEnabled] reads, for a ContentObserver. */
    fun settingUris(): List<Uri> = listOf(KEY_VIBRATE_ON, KEY_KEYBOARD_VIBRATION).map { Settings.System.getUriFor(it) }
}
