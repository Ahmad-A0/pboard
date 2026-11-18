package dev.pboard.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Pref

@Composable
fun rememberPrefs(): PboardPrefs {
    val ctx = LocalContext.current
    return remember { PboardPrefs.get(ctx) }
}

/** Observes a preference as Compose state (initial value read synchronously, so there is no first-frame flicker). */
@Composable
fun <T : Any> Pref<T>.collectAsState(): State<T> {
    val prefs = rememberPrefs()
    val flow = remember(this) { prefs.flow(this) }
    return flow.collectAsState(initial = prefs[this])
}
