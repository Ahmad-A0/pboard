package dev.pboard.settings.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pboard.R
import dev.pboard.prefs.KeyboardLanguages
import dev.pboard.prefs.LanguageCatalog
import dev.pboard.prefs.LanguageCatalog.Entry
import dev.pboard.prefs.Prefs
import dev.pboard.prefs.ThemeCatalog
import dev.pboard.settings.Navigator
import dev.pboard.settings.Route
import dev.pboard.settings.ui.Category
import dev.pboard.settings.ui.KeyboardPreview
import dev.pboard.settings.ui.PrefRow
import dev.pboard.settings.ui.SettingsPage
import dev.pboard.settings.ui.SettingsType
import dev.pboard.settings.ui.SwitchRow
import dev.pboard.settings.ui.collectAsState
import dev.pboard.settings.ui.previewPaletteFor
import dev.pboard.settings.ui.rememberPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LanguagesScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val prefs = rememberPrefs()
    val raw by Prefs.enabledLanguages.collectAsState()
    val available = remember { LanguageCatalog.available(ctx) }
    val entries = KeyboardLanguages.enabled(raw, available)
    val addable = KeyboardLanguages.addable(entries, available)
    var editing by rememberSaveable { mutableStateOf(false) }
    val checked = remember { mutableStateListOf<String>() }
    val colors = MaterialTheme.colorScheme
    SettingsPage(
        stringResource(R.string.header_languages),
        onBack = { if (editing) editing = false else nav.pop() },
        actions = {
            if (editing) {
                IconButton(
                    enabled = checked.isNotEmpty() && checked.size < entries.size,
                    onClick = {
                        prefs[Prefs.enabledLanguages] = entries.map { it.encode() }.filter { it !in checked }
                        checked.clear(); editing = false
                    },
                ) { Icon(Icons.Outlined.Delete, stringResource(R.string.remove_languages)) }
            } else if (entries.size > 1) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit_languages)) }
            }
        },
    ) {
        item { Category(stringResource(R.string.languages_category)) }
        entries.forEach { e ->
            item(key = e.encode()) {
                val code = e.encode()
                PrefRow(
                    LanguageCatalog.displayName(e.languageTag),
                    summary = ctx.getString(e.layout.label),
                    onClick = {
                        if (editing) {
                            if (code in checked) checked.remove(code) else checked.add(code)
                        } else {
                            nav.push(Route.Language(e.languageTag, adding = false))
                        }
                    },
                    trailing = if (editing) ({ Checkbox(code in checked, null) }) else null,
                )
            }
        }
        item { HorizontalDivider(thickness = 1.dp, color = colors.outline) }
        // "Add keyboard" offers only languages the keyboard can type in (a layout file + language-model dialect each, see
        // KeyboardLanguages): English (UK) and English (US) today, so it disappears once both are enabled. Other
        // languages come back here as their packs are added; nothing below is English-specific.
        if (!editing && addable.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(Route.AddLanguage) }.heightIn(min = 49.dp)
                        .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = colors.primary)
                    Spacer(Modifier.width(22.dp))
                    Text(stringResource(R.string.add_keyboard), style = SettingsType.rowTitle, color = colors.primary)
                }
            }
        }
    }
}

@Composable
fun AddLanguageScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val raw by Prefs.enabledLanguages.collectAsState()
    val available = remember { LanguageCatalog.available(ctx) }
    val addable = KeyboardLanguages.addable(KeyboardLanguages.enabled(raw, available), available)
    SettingsPage(stringResource(R.string.add_keyboard), onBack = { nav.pop() }) {
        // Only languages with a layout and language model (KeyboardLanguages); the full ICU locale list with search
        // returns when other language packs exist.
        item { Category(stringResource(R.string.all_languages)) }
        addable.forEach { tag ->
            item(key = tag) {
                PrefRow(LanguageCatalog.displayName(tag), onClick = { nav.push(Route.Language(tag, adding = true)) })
            }
        }
    }
}

/**
 * One language: layout carousel (keyboard previews with selection chips) and language settings. In "adding" mode
 * a bottom bar confirms the new keyboard (Cancel / Done), as in Gboard.
 */
@Composable
fun LanguageScreen(nav: Navigator, tag: String, adding: Boolean) {
    val ctx = LocalContext.current
    val prefs = rememberPrefs()
    val colors = MaterialTheme.colorScheme
    val raw by Prefs.enabledLanguages.collectAsState()
    val available = remember { LanguageCatalog.available(ctx) }
    val entries = KeyboardLanguages.enabled(raw, available)
    val existing = entries.firstOrNull { it.languageTag == tag }
    // Only layouts that exist for this language (QWERTY today).
    val layouts = remember(tag) { KeyboardLanguages.layoutsFor(tag, available).ifEmpty { listOf(LanguageCatalog.Layout.QWERTY) } }
    var selected by rememberSaveable(tag) { mutableStateOf((existing?.layout ?: layouts.first()).id) }

    fun commit(layoutId: String) {
        val layout = LanguageCatalog.Layout.entries.first { it.id == layoutId }
        val e = Entry(tag, layout).encode()
        val list = entries.map { it.encode() }
        prefs[Prefs.enabledLanguages] = if (existing != null) list.map { if (it == existing.encode()) e else it } else list + e
    }

    SettingsPage(
        LanguageCatalog.displayName(tag),
        onBack = { nav.pop() },
        bottomBar = {
            if (adding) {
                Surface(color = colors.surfaceContainer) {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = colors.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(stringResource(R.string.n_selected, 1), Modifier.weight(1f), style = TextStyle(fontSize = 16.sp),
                                color = colors.onSurface)
                            OutlinedButton(
                                onClick = { nav.pop() }, modifier = Modifier.height(48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                            ) {
                                Text(stringResource(R.string.cancel), fontSize = 16.sp)
                            }
                            Button(onClick = { commit(selected); nav.popTo(Route.Languages) }, modifier = Modifier.height(48.dp)) {
                                Text(stringResource(R.string.done), fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        },
    ) {
        item {
            val palette = previewPaletteFor(ThemeCatalog.byId(ThemeCatalog.DYNAMIC)!!)
            LazyRow(
                Modifier.fillMaxWidth().background(colors.surfaceContainerHighest).padding(vertical = 24.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(layouts, key = { it.id }) { layout ->
                    val isSel = layout.id == selected
                    val select = { selected = layout.id; if (!adding) commit(layout.id) }
                    Column(Modifier.width(150.dp).selectable(isSel, role = Role.RadioButton, onClick = select)) {
                        KeyboardPreview(
                            palette, layout = layout,
                            modifier = Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.height(16.dp))
                        LayoutChip(stringResource(layout.label), isSel)
                    }
                }
            }
        }
        // No "Multilingual typing" section: the decoder only has English models (en-US / en-GB dialects of one pack),
        // so typing in a second language at once cannot work yet (Prefs.multilingualTyping is not read).
    }
}

@Composable
private fun LayoutChip(label: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(colors.primary) else Modifier.border(BorderStroke(1.dp, colors.outline), CircleShape))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = colors.onPrimary)
            else Box(Modifier.size(16.dp).border(BorderStroke(1.5.dp, colors.onSurfaceVariant), CircleShape))
        }
        Text(
            label, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1,
            style = TextStyle(fontSize = 15.sp), color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}
