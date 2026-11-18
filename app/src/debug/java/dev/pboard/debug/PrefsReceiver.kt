package dev.pboard.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Pref
import dev.pboard.prefs.Prefs

/**
 * Debug builds only: writes settings in the app's own process, like the Settings screens do, so device checks can
 * change a setting while the keyboard is shown (the Settings activity always hides it). String and boolean settings,
 * by storage key:
 *
 *   adb shell am broadcast -n dev.pboard/.debug.PrefsReceiver --es theme color_black --ez key_borders false
 */
class PrefsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val prefs = PboardPrefs.get(context)
        prefs.edit {
            for (p in Prefs.entries) {
                if (!extras.containsKey(p.key)) continue
                @Suppress("UNCHECKED_CAST")
                when (p.default) {
                    is String -> extras.getString(p.key)?.let { this[p as Pref<String>] = it }
                    is Boolean -> this[p as Pref<Boolean>] = extras.getBoolean(p.key)
                    else -> {}
                }
            }
        }
    }
}
