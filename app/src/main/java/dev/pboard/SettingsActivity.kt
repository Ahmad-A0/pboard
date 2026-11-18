package dev.pboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.pboard.settings.PboardSettingsApp
import dev.pboard.settings.Route

/**
 * Settings entry point: launcher (through the `LauncherActivity` alias, which "Show app icon" toggles) and the
 * IME's `android:settingsActivity`. The keyboard can deep-link a page with the [EXTRA_ROUTE] extra, e.g.
 * `"theme"`, `"toolbar"`, `"languages"` (see [Route]).
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Settings › Languages drives the keyboard: give it the system's languages if it was never set.
        dev.pboard.prefs.LanguageCatalog.seedIfUnset(this)
        val start = Route.fromExtra(intent?.getStringExtra(EXTRA_ROUTE))
        setContent { PboardSettingsApp(start = start, onExit = { finish() }) }
    }

    companion object {
        const val EXTRA_ROUTE = "dev.pboard.extra.SETTINGS_ROUTE"
    }
}
