package dev.pboard.settings.screens

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import dev.pboard.R
import dev.pboard.ime.translate.TranslateLanguages
import dev.pboard.ime.translate.TranslatePacks
import dev.pboard.settings.Navigator
import dev.pboard.settings.ui.BodyText
import dev.pboard.settings.ui.Category
import dev.pboard.settings.ui.ConfirmDialog
import dev.pboard.settings.ui.PrefRow
import dev.pboard.settings.ui.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Translate: the installed translation languages (pack apps and imported pack files) and "Import a
 * language pack file" (system file picker; pboard has no network access to download anything). Gboard's own
 * Translate page only has a cloud switch ("Translate your replies"), which pboard omits.
 */
@Composable
fun TranslateScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val packs = remember { TranslatePacks.get(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<List<TranslatePacks.Installed>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TranslatePacks.Installed?>(null) }
    LaunchedEffect(version) { installed = withContext(Dispatchers.IO) { packs.installed() } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { packs.import(uri) }.fold(
                    { ctx.getString(R.string.translate_imported, it.name) },
                    { ctx.getString(R.string.translate_import_failed, it.message ?: it.javaClass.simpleName) },
                )
            }
            busy = false
            version++
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
    SettingsPage(stringResource(R.string.header_translate), onBack = { nav.pop() }) {
        item { BodyText(stringResource(R.string.translate_settings_intro)) }
        item {
            PrefRow(
                stringResource(R.string.translate_import), summary = stringResource(R.string.translate_import_summary),
                icon = Icons.Outlined.FileOpen, enabled = !busy,
                onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                trailing = if (busy) ({ CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }) else null,
            )
        }
        item { Category(stringResource(R.string.translate_installed_header)) }
        val list = installed
        if (list != null && list.isEmpty()) item { BodyText(stringResource(R.string.translate_none_installed)) }
        list?.forEach { p ->
            item(key = p.source.name + p.id) {
                val m = p.manifest
                val pairs = m.models.joinToString(", ") {
                    TranslateLanguages.displayName(it.from) + " → " + TranslateLanguages.displayName(it.to)
                }
                val size = m.models.sumOf { it.size }
                val src = stringResource(if (p.source == TranslatePacks.Source.APK) R.string.translate_source_apk else R.string.translate_source_file)
                val summary = stringResource(R.string.translate_pack_summary, pairs, Formatter.formatShortFileSize(ctx, size), src) +
                    if (m.license.isNotEmpty()) "\n" + stringResource(R.string.translate_licence, m.license) else ""
                PrefRow(
                    m.name, summary = summary,
                    trailing = if (p.source == TranslatePacks.Source.IMPORTED) ({
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Outlined.Delete, stringResource(R.string.translate_delete)) }
                    }) else null,
                )
            }
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            text = p.manifest.name, confirm = stringResource(R.string.translate_delete),
            onConfirm = {
                deleting = null
                scope.launch { withContext(Dispatchers.IO) { packs.delete(p.id) }; version++ }
            },
            onDismiss = { deleting = null },
        )
    }
}
