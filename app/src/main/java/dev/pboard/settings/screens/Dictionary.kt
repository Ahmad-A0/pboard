package dev.pboard.settings.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pboard.R
import dev.pboard.ime.engine.Engines
import dev.pboard.prefs.GboardDictionary
import dev.pboard.prefs.LearnedDataBackup
import dev.pboard.prefs.SystemUserDictionary
import dev.pboard.settings.ui.Category
import dev.pboard.settings.ui.TwoActionDialog
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pboard.prefs.LanguageCatalog
import dev.pboard.prefs.PersonalDictionary
import dev.pboard.prefs.Prefs
import dev.pboard.settings.Navigator
import dev.pboard.settings.Route
import dev.pboard.settings.ui.BodyText
import dev.pboard.settings.ui.PrefRow
import dev.pboard.settings.ui.SettingsPage
import dev.pboard.settings.ui.SettingsType
import dev.pboard.settings.ui.collectAsState as collectPref

/**
 * Dictionary: the personal dictionary, bringing words in from Gboard or Android's user dictionary, and backing up /
 * restoring everything pboard has learned. Files are picked with the system's document picker (no permission); what
 * they contain is parsed as untrusted input (GboardDictionary, LearnedDataBackup) off the main thread.
 */
@Composable
fun DictionaryScreen(nav: Navigator) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val dict = remember { PersonalDictionary.get(ctx) }
    val enabledRaw by Prefs.enabledLanguages.collectPref()
    val enabledTags = enabledRaw.mapNotNull(LanguageCatalog.Entry::decode).map { it.languageTag }.distinct()
    var pending by remember { mutableStateOf<LearnedDataBackup.Backup?>(null) }
    fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    /** Adds imported words and reports how many were new. */
    fun addWords(words: List<GboardDictionary.Word>, skipped: Int): String {
        val entries = GboardDictionary.toEntries(words, enabledTags)
        val added = dict.addAll(entries)
        val present = entries.size - added
        return if (skipped > 0) ctx.getString(R.string.import_result_skipped, added, present, skipped) else ctx.getString(R.string.import_result, added, present)
    }

    val gboardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val message = withContext(Dispatchers.IO) {
                val result = runCatching { ctx.contentResolver.openInputStream(uri)?.use { GboardDictionary.parse(it) } }.getOrNull()
                when (result?.error) {
                    GboardDictionary.Error.TOO_LARGE -> ctx.getString(R.string.import_too_large)
                    GboardDictionary.Error.NOT_A_DICTIONARY -> ctx.getString(R.string.import_not_dictionary)
                    GboardDictionary.Error.UNSUPPORTED_VERSION -> ctx.getString(R.string.import_unsupported)
                    GboardDictionary.Error.UNREADABLE -> ctx.getString(R.string.import_unreadable)
                    null -> if (result == null) ctx.getString(R.string.import_unreadable) else addWords(result.words, result.skipped)
                }
            }
            toast(message)
        }
    }
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = LearnedDataBackup.encode(Engines.currentUserData(ctx), dict.entries.value)
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                }.getOrDefault(false)
            }
            toast(ctx.getString(if (ok) R.string.backup_saved else R.string.backup_failed))
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = GboardDictionary.readCapped(input, LearnedDataBackup.MAX_BYTES)
                        if (bytes == null) LearnedDataBackup.Decoded(null, LearnedDataBackup.Error.TOO_LARGE) else LearnedDataBackup.decode(bytes)
                    }
                }.getOrNull()
            }
            when (decoded?.error) {
                LearnedDataBackup.Error.TOO_LARGE -> toast(ctx.getString(R.string.restore_too_large))
                LearnedDataBackup.Error.NOT_A_BACKUP -> toast(ctx.getString(R.string.restore_not_backup))
                LearnedDataBackup.Error.NEWER_VERSION -> toast(ctx.getString(R.string.restore_newer))
                LearnedDataBackup.Error.CORRUPT -> toast(ctx.getString(R.string.restore_corrupt))
                null -> if (decoded?.backup == null) toast(ctx.getString(R.string.import_unreadable)) else pending = decoded.backup
            }
        }
    }
    /** Applies a validated backup: the learned data first (all or nothing), the dictionary only when that worked. */
    fun restore(backup: LearnedDataBackup.Backup, merge: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val learned = backup.learned
                val applied = learned == null || Engines.restoreUserData(ctx, learned, merge)
                if (applied) { if (merge) dict.addAll(backup.dictionary) else dict.replaceAll(backup.dictionary) }
                applied
            }
            toast(ctx.getString(if (ok) R.string.restore_done else R.string.restore_failed))
        }
    }

    SettingsPage(stringResource(R.string.header_dictionary), onBack = { nav.pop() }) {
        item { PrefRow(stringResource(R.string.personal_dictionary), onClick = { nav.push(Route.DictionaryLanguages) }) }
        item { Category(stringResource(R.string.cat_import_words)) }
        item {
            PrefRow(stringResource(R.string.import_gboard), summary = stringResource(R.string.import_gboard_summary),
                onClick = { gboardPicker.launch(arrayOf("*/*")) })
        }
        item {
            PrefRow(stringResource(R.string.import_system), summary = stringResource(R.string.import_system_summary), onClick = {
                scope.launch {
                    val message = withContext(Dispatchers.IO) {
                        val words = SystemUserDictionary.read(ctx)
                        if (words.isNullOrEmpty()) ctx.getString(R.string.import_system_none) else addWords(words, 0)
                    }
                    toast(message)
                }
            })
        }
        item { Category(stringResource(R.string.cat_backup)) }
        item {
            PrefRow(stringResource(R.string.backup_learned), summary = stringResource(R.string.backup_learned_summary), onClick = {
                val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                backupSaver.launch("pboard-backup-$day.${LearnedDataBackup.FILE_EXTENSION}")
            })
        }
        item {
            PrefRow(stringResource(R.string.restore_learned), summary = stringResource(R.string.restore_learned_summary),
                onClick = { backupPicker.launch(arrayOf("*/*")) })
        }
        item { BodyText(stringResource(R.string.backup_note)) }
    }
    pending?.let { backup ->
        val learned = stringResource(if (backup.learned != null) R.string.restore_has_learned else R.string.restore_no_learned)
        TwoActionDialog(
            stringResource(R.string.restore_question, backup.dictionary.size, learned),
            first = stringResource(R.string.restore_merge), onFirst = { restore(backup, merge = true) },
            second = stringResource(R.string.restore_replace), onSecond = { restore(backup, merge = false) },
            onDismiss = { pending = null },
        )
    }
}

@Composable
fun DictionaryLanguagesScreen(nav: Navigator) {
    val raw by Prefs.enabledLanguages.collectPref()
    val tags = raw.mapNotNull(LanguageCatalog.Entry::decode).map { it.languageTag }.distinct()
    SettingsPage(stringResource(R.string.personal_dictionary), onBack = { nav.pop() }) {
        tags.forEach { tag ->
            item(key = tag) { PrefRow(LanguageCatalog.displayName(tag), onClick = { nav.push(Route.WordList(tag)) }) }
        }
        item {
            PrefRow(stringResource(R.string.all_languages_dictionary),
                onClick = { nav.push(Route.WordList(PersonalDictionary.ALL_LANGUAGES)) })
        }
    }
}

private fun titleFor(ctx: android.content.Context, tag: String) =
    if (tag == PersonalDictionary.ALL_LANGUAGES) ctx.getString(R.string.all_languages_dictionary) else LanguageCatalog.displayName(tag)

@Composable
fun WordListScreen(nav: Navigator, tag: String) {
    val ctx = LocalContext.current
    val dict = remember { PersonalDictionary.get(ctx) }
    val all by dict.entries.collectAsState()
    val words = all.filter { it.languageTag == tag }
    var menu by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { dict.exportTsv(it) } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val n = runCatching { ctx.contentResolver.openInputStream(uri)?.use { dict.importTsv(it, tag) } ?: 0 }.getOrDefault(0)
            Toast.makeText(ctx, ctx.getString(R.string.imported_n, n), Toast.LENGTH_SHORT).show()
        }
    }
    SettingsPage(
        titleFor(ctx, tag),
        onBack = { nav.pop() },
        actions = {
            IconButton(onClick = { nav.push(Route.EditWord(tag, null)) }) { Icon(Icons.Filled.Add, stringResource(R.string.add)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_)) }, onClick = {
                        menu = false; importer.launch(arrayOf("text/*"))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export)) }, onClick = {
                        menu = false; exporter.launch("pboard-dictionary.tsv")
                    })
                }
            }
        },
    ) {
        if (words.isEmpty()) {
            item { BodyText(stringResource(R.string.dictionary_empty)) }
        }
        words.forEach { e ->
            item(key = e.word) {
                PrefRow(e.word, summary = e.shortcut.ifEmpty { null }, onClick = { nav.push(Route.EditWord(tag, e.word)) })
            }
        }
    }
}

/** Add/edit a word. Saved when leaving the screen (back), deleted with the trash action — like Gboard. */
@Composable
fun EditWordScreen(nav: Navigator, tag: String, word: String?) {
    val ctx = LocalContext.current
    val dict = remember { PersonalDictionary.get(ctx) }
    val original = remember { word?.let { w -> dict.forLanguage(tag).firstOrNull { it.word == w } } }
    var text by rememberSaveable { mutableStateOf(original?.word.orEmpty()) }
    var shortcut by rememberSaveable { mutableStateOf(original?.shortcut.orEmpty()) }
    val colors = MaterialTheme.colorScheme
    fun saveAndExit() {
        if (text.isNotBlank()) dict.put(PersonalDictionary.Entry(text, shortcut, tag), replacing = original)
        nav.pop()
    }
    BackHandler { saveAndExit() }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = colors.primary, unfocusedIndicatorColor = colors.onSurfaceVariant,
    )
    SettingsPage(
        titleFor(ctx, tag),
        onBack = { saveAndExit() },
        actions = {
            IconButton(onClick = { original?.let(dict::remove); nav.pop() }) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
            }
        },
    ) {
        item {
            TextField(
                text, { text = it }, singleLine = true, textStyle = SettingsType.rowTitle, colors = fieldColors,
                placeholder = { Text(stringResource(R.string.type_a_word), style = SettingsType.rowTitle) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Text(stringResource(R.string.shortcut), Modifier.padding(start = 10.dp, top = 16.dp), style = SettingsType.rowSummary,
                color = colors.onSurface)
        }
        item {
            TextField(
                shortcut, { shortcut = it }, singleLine = true, textStyle = SettingsType.rowTitle, colors = fieldColors,
                placeholder = { Text(stringResource(R.string.optional_shortcut), style = SettingsType.rowTitle) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
