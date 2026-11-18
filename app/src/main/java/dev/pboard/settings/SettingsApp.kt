package dev.pboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.pboard.settings.screens.AboutScreen
import dev.pboard.settings.screens.TranslateScreen
import dev.pboard.settings.screens.AddLanguageScreen
import dev.pboard.settings.screens.AutoCorrectionScreen
import dev.pboard.settings.screens.ClipboardScreen
import dev.pboard.settings.screens.CrashReportsScreen
import dev.pboard.settings.screens.DictionaryLanguagesScreen
import dev.pboard.settings.screens.DictionaryScreen
import dev.pboard.settings.screens.EditWordScreen
import dev.pboard.settings.screens.EmojiScreen
import dev.pboard.settings.screens.GlideScreen
import dev.pboard.settings.screens.HomeScreen
import dev.pboard.settings.screens.LanguageScreen
import dev.pboard.settings.screens.LanguagesScreen
import dev.pboard.settings.screens.PhysicalKeyboardScreen
import dev.pboard.settings.screens.PreferencesScreen
import dev.pboard.settings.screens.PrivacyScreen
import dev.pboard.settings.screens.StylusScreen
import dev.pboard.settings.screens.TextCorrectionScreen
import dev.pboard.settings.screens.ThemeScreen
import dev.pboard.settings.screens.ToolbarScreen
import dev.pboard.settings.screens.VoiceScreen
import dev.pboard.settings.screens.WordListScreen
import dev.pboard.settings.ui.PboardSettingsTheme

/** Settings destinations. Encoded as strings so the back stack survives process death. */
sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Languages : Route("languages")
    data object AddLanguage : Route("add_language")
    data class Language(val tag: String, val adding: Boolean) : Route("language/$tag/$adding")
    data object Preferences : Route("preferences")
    data object Theme : Route("theme")
    data object TextCorrection : Route("text_correction")
    data object AutoCorrection : Route("auto_correction")
    data object Glide : Route("glide")
    data object Voice : Route("voice")
    data object Stylus : Route("stylus")
    data object PhysicalKeyboard : Route("physical_keyboard")
    data object Clipboard : Route("clipboard")
    data object Dictionary : Route("dictionary")
    data object DictionaryLanguages : Route("dictionary_languages")
    data class WordList(val tag: String) : Route("words/$tag")
    data class EditWord(val tag: String, val word: String?) : Route("edit_word/$tag/${word.orEmpty()}")
    data object Emoji : Route("emoji")
    data object Privacy : Route("privacy")
    data object About : Route("about")
    data object Toolbar : Route("toolbar")
    data object Translate : Route("translate")
    data object CrashReports : Route("crash_reports")

    companion object {
        private val simple = listOf(
            Home, Languages, AddLanguage, Preferences, Theme, TextCorrection, AutoCorrection, Glide, Voice, Stylus, PhysicalKeyboard,
            Clipboard, Dictionary, DictionaryLanguages, Emoji, Privacy, About, Toolbar, Translate, CrashReports,
        ).associateBy { it.path }

        fun decode(path: String): Route? {
            simple[path]?.let { return it }
            val p = path.split('/')
            return when (p[0]) {
                "language" -> if (p.size == 3) Language(p[1], p[2].toBoolean()) else null
                "words" -> if (p.size == 2) WordList(p[1]) else null
                "edit_word" -> if (p.size >= 2) EditWord(p[1], p.drop(2).joinToString("/").ifEmpty { null }) else null
                else -> null
            }
        }

        /** Deep-link names accepted by SettingsActivity's `route` extra (e.g. from the keyboard's toolbar). */
        fun fromExtra(name: String?): Route? = name?.let { decode(it) }
    }
}

class Navigator(private val stack: SnapshotStateList<Route>, private val onExit: () -> Unit) {
    val current: Route get() = stack.last()
    val depth: Int get() = stack.size
    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit()
    }

    /** Replace the top of the stack (e.g. after "Done" on Add language → back to the list). */
    fun popTo(route: Route) {
        while (stack.size > 1 && stack.last() != route) stack.removeAt(stack.lastIndex)
    }
}

private val stackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { list -> list.map { it.path } },
    restore = { paths -> mutableStateListOf<Route>().apply { addAll(paths.mapNotNull(Route::decode)) } },
)

@Composable
fun PboardSettingsApp(start: Route?, onExit: () -> Unit) {
    val stack = rememberSaveable(saver = stackSaver) {
        mutableStateListOf<Route>(Route.Home).apply { if (start != null && start != Route.Home) add(start) }
    }
    val nav = Navigator(stack, onExit)
    PboardSettingsTheme {
        BackHandler(enabled = stack.size > 1) { nav.pop() }
        AnimatedContent(
            targetState = stack.size to stack.last(),
            transitionSpec = {
                val forward = targetState.first >= initialState.first
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(300)) { dir * it / 5 } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(300)) { -dir * it / 5 } + fadeOut(tween(150)))
            },
            label = "settings-nav",
        ) { (_, route) ->
            Screen(route, nav)
        }
    }
}

@Composable
private fun Screen(route: Route, nav: Navigator) {
    when (route) {
        Route.Home -> HomeScreen(nav)
        Route.Languages -> LanguagesScreen(nav)
        Route.AddLanguage -> AddLanguageScreen(nav)
        is Route.Language -> LanguageScreen(nav, route.tag, route.adding)
        Route.Preferences -> PreferencesScreen(nav)
        Route.Theme -> ThemeScreen(nav)
        Route.TextCorrection -> TextCorrectionScreen(nav)
        Route.AutoCorrection -> AutoCorrectionScreen(nav)
        Route.Glide -> GlideScreen(nav)
        Route.Voice -> VoiceScreen(nav)
        Route.Stylus -> StylusScreen(nav)
        Route.PhysicalKeyboard -> PhysicalKeyboardScreen(nav)
        Route.Clipboard -> ClipboardScreen(nav)
        Route.Dictionary -> DictionaryScreen(nav)
        Route.DictionaryLanguages -> DictionaryLanguagesScreen(nav)
        is Route.WordList -> WordListScreen(nav, route.tag)
        is Route.EditWord -> EditWordScreen(nav, route.tag, route.word)
        Route.Emoji -> EmojiScreen(nav)
        Route.Privacy -> PrivacyScreen(nav)
        Route.About -> AboutScreen(nav)
        Route.Toolbar -> ToolbarScreen(nav)
        Route.Translate -> TranslateScreen(nav)
        Route.CrashReports -> CrashReportsScreen(nav)
    }
}
