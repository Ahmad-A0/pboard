package dev.pboard.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pboard.R
import dev.pboard.prefs.KeyboardLanguages
import dev.pboard.prefs.LanguageCatalog
import dev.pboard.prefs.Prefs
import dev.pboard.settings.Navigator
import dev.pboard.settings.Route
import dev.pboard.settings.ui.HomeRow
import dev.pboard.settings.ui.SettingsPage
import dev.pboard.settings.ui.collectAsState

/** Settings home: Gboard 18.6's entries, order and grouping, minus the server-backed ones. */
@Composable
fun HomeScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val languages by Prefs.enabledLanguages.collectAsState()
    val summary = LanguageCatalog.summary(ctx, KeyboardLanguages.enabled(languages, LanguageCatalog.available(ctx)))
    SettingsPage(stringResource(R.string.settings_title), onBack = { nav.pop() }) {
        // Gboard 18.6 order, in its four groups (separated by a gap; Gboard's grouped-card styling is unverified).
        item { HomeRow(Icons.Outlined.Language, stringResource(R.string.header_languages), summary) { nav.push(Route.Languages) } }
        item { HomeRow(Icons.Outlined.Tune, stringResource(R.string.header_preferences)) { nav.push(Route.Preferences) } }
        item { HomeRow(Icons.Outlined.Palette, stringResource(R.string.header_theme)) { nav.push(Route.Theme) } }
        item { GroupGap() }
        item { HomeRow(Icons.Outlined.Spellcheck, stringResource(R.string.header_text_correction)) { nav.push(Route.TextCorrection) } }
        item { HomeRow(Icons.Outlined.Gesture, stringResource(R.string.header_glide_typing)) { nav.push(Route.Glide) } }
        item { HomeRow(Icons.Filled.Mic, stringResource(R.string.header_voice_typing)) { nav.push(Route.Voice) } }
        item { HomeRow(Icons.Outlined.KeyboardAlt, stringResource(R.string.header_physical_keyboard)) { nav.push(Route.PhysicalKeyboard) } }
        // No "Write in text fields" entry: stylus handwriting needs an on-device handwriting recogniser (unbuilt), so
        // none of its settings (Prefs.stylus*) would do anything. Gboard shows it only on stylus-capable devices.
        item { HomeRow(Icons.Outlined.SentimentSatisfied, stringResource(R.string.header_emoji)) { nav.push(Route.Emoji) } }
        item { GroupGap() }
        item { HomeRow(Icons.Outlined.Assignment, stringResource(R.string.header_clipboard)) { nav.push(Route.Clipboard) } }
        item { HomeRow(Icons.AutoMirrored.Outlined.ChromeReaderMode, stringResource(R.string.header_dictionary)) { nav.push(Route.Dictionary) } }
        item { HomeRow(Icons.Outlined.Translate, stringResource(R.string.header_translate)) { nav.push(Route.Translate) } }
        item { GroupGap() }
        item { HomeRow(Icons.Outlined.Shield, stringResource(R.string.header_privacy)) { nav.push(Route.Privacy) } }
        item { HomeRow(Icons.Outlined.Info, stringResource(R.string.header_about)) { nav.push(Route.About) } }
    }
}

@Composable
private fun GroupGap() {
    Spacer(Modifier.height(16.dp))
}
