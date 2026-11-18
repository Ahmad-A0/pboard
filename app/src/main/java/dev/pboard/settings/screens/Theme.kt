package dev.pboard.settings.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pboard.R
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.prefs.Prefs
import dev.pboard.prefs.ThemeCatalog
import dev.pboard.prefs.ThemeCatalog.CustomTheme
import dev.pboard.settings.Navigator
import dev.pboard.settings.ui.KeyboardPreview
import dev.pboard.settings.ui.PreviewPalette
import dev.pboard.settings.ui.SettingsFrame
import dev.pboard.settings.ui.ThemeThumbnail
import dev.pboard.settings.ui.ThinSlider
import dev.pboard.settings.ui.collectAsState
import dev.pboard.settings.ui.imagePalette
import dev.pboard.settings.ui.previewPaletteFor
import dev.pboard.settings.ui.rememberPrefs
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TileShape = RoundedCornerShape(16.dp)
private val SectionTitle = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp)
private val SheetLabel = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)

/**
 * Theme picker, laid out as Gboard's: My themes, Default, Colors (expandable), Light/Dark gradients in a
 * 3-column grid; tapping a tile opens a preview sheet with the Key borders switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val prefs = rememberPrefs()
    val current by Prefs.theme.collectAsState()
    val customRaw by Prefs.customThemes.collectAsState()
    val customs = customRaw.mapNotNull(CustomTheme::decode)
    // The check mark goes on the theme the keyboard draws (Dynamic Color when the stored id names no theme).
    val shown = KeyboardTheme.renderedThemeId(current, customs)
    var colorsExpanded by rememberSaveable { mutableStateOf(false) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var newCustom by rememberSaveable { mutableStateOf<String?>(null) } // image imported but not applied yet

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val id = ThemeCatalog.CUSTOM_PREFIX + UUID.randomUUID().toString().take(8)
            if (importImage(ctx, uri, id)) {
                newCustom = id
                previewId = id
            }
        }
    }

    SettingsFrame(stringResource(R.string.header_theme), onBack = { nav.pop() }) { padding, behavior ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 24.dp, end = 24.dp, top = padding.calculateTopPadding(), bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            section(ctx.getString(R.string.my_themes))
            item(key = "add") {
                AddTile(stringResource(R.string.create_theme)) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            customs.forEach { c ->
                item(key = c.id) {
                    val image by rememberThemeImage(c.id)
                    ThemeTile(imagePalette(c.id, image, c.brightness), stringResource(R.string.theme_custom), c.id == shown,
                        label = null) { previewId = c.id }
                }
            }

            section(ctx.getString(ThemeCatalog.Section.DEFAULT.title))
            ThemeCatalog.defaults.forEach { spec ->
                item(key = spec.id) {
                    val split = if (spec.kind == ThemeCatalog.Kind.SYSTEM_AUTO) {
                        previewPaletteFor(ThemeCatalog.byId(ThemeCatalog.DEFAULT_DARK)!!) to
                            previewPaletteFor(ThemeCatalog.byId(ThemeCatalog.DEFAULT_LIGHT)!!)
                    } else null
                    ThemeTile(split?.second ?: previewPaletteFor(spec), stringResource(spec.name), spec.id == shown,
                        label = stringResource(spec.name), rightHalf = split?.first) { previewId = spec.id }
                }
            }

            val colorTitle = ctx.getString(ThemeCatalog.Section.COLORS.title)
            section(colorTitle, expanded = colorsExpanded) { colorsExpanded = !colorsExpanded }
            val colors = if (colorsExpanded) ThemeCatalog.colors else ThemeCatalog.colors.take(ThemeCatalog.COLORS_COLLAPSED)
            colors.forEach { spec ->
                item(key = spec.id) {
                    ThemeTile(previewPaletteFor(spec), stringResource(spec.name), spec.id == shown, label = null) {
                        previewId = spec.id
                    }
                }
            }

            for ((sectionKind, list) in listOf(
                ThemeCatalog.Section.LIGHT_GRADIENT to ThemeCatalog.lightGradients,
                ThemeCatalog.Section.DARK_GRADIENT to ThemeCatalog.darkGradients,
            )) {
                section(ctx.getString(sectionKind.title))
                list.forEach { spec ->
                    item(key = spec.id) {
                        val name = stringResource(spec.name, spec.number)
                        ThemeTile(previewPaletteFor(spec), name, spec.id == shown, label = null) { previewId = spec.id }
                    }
                }
            }
        }
    }

    val id = previewId
    if (id != null) {
        ThemePreviewSheet(
            themeId = id,
            initialBrightness = customs.firstOrNull { it.id == id }?.brightness ?: CustomTheme.DEFAULT_BRIGHTNESS,
            onApply = { borders, brightness ->
                prefs.edit {
                    this[Prefs.theme] = id
                    this[Prefs.keyBorders] = borders
                    if (ThemeCatalog.isCustom(id)) {
                        val others = customs.filter { it.id != id }
                        this[Prefs.customThemes] = (listOf(CustomTheme(id, brightness)) + others).map { it.encode() }
                    }
                }
                newCustom = null
                previewId = null
            },
            onDelete = if (ThemeCatalog.isCustom(id) && id != newCustom) {
                {
                    prefs.edit {
                        this[Prefs.customThemes] = customs.filter { it.id != id }.map { it.encode() }
                        if (current == id) reset(Prefs.theme)
                    }
                    File(ctx.filesDir, ThemeCatalog.customImagePath(id)).delete()
                    previewId = null
                }
            } else null,
            onDismiss = {
                if (id == newCustom) {
                    File(ctx.filesDir, ThemeCatalog.customImagePath(id)).delete()
                    newCustom = null
                }
                previewId = null
            },
        )
    }
}

private fun LazyGridScope.section(title: String, expanded: Boolean? = null, onToggle: (() -> Unit)? = null) {
    item(key = "section_$title", span = { GridItemSpan(maxLineSpan) }) {
        val colors = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = SectionTitle, color = colors.primary)
            if (expanded != null && onToggle != null) {
                val desc = stringResource(if (expanded) R.string.show_less else R.string.show_more, title)
                IconButton(onClick = onToggle, modifier = Modifier.semantics { contentDescription = desc }) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                            Modifier.size(22.dp), tint = colors.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTile(description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .aspectRatio(302f / 226f)
            .clip(TileShape)
            .border(BorderStroke(3.dp, colors.primary), TileShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(40.dp), tint = colors.primary)
    }
}

@Composable
private fun ThemeTile(
    palette: PreviewPalette,
    name: String,
    selected: Boolean,
    label: String?,
    rightHalf: PreviewPalette? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .aspectRatio(302f / 226f)
                .clip(TileShape)
                .background(if (selected) colors.primary.copy(alpha = 0.8f) else colors.surfaceContainerHighest)
                .clickable(onClick = onClick)
                .semantics { contentDescription = name; this.selected = selected }
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            ThemeThumbnail(palette, Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)), rightHalf)
            if (selected) {
                Box(Modifier.size(31.dp).clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, stringResource(R.string.selected), Modifier.size(22.dp), tint = colors.onPrimary)
                }
            }
        }
        if (label != null) {
            Text(label, Modifier.padding(top = 4.dp, bottom = 2.dp), style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePreviewSheet(
    themeId: String,
    initialBrightness: Int,
    onApply: (borders: Boolean, brightness: Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val currentBorders by Prefs.keyBorders.collectAsState()
    var borders by rememberSaveable(themeId) { mutableStateOf(currentBorders) }
    var brightness by rememberSaveable(themeId) { mutableIntStateOf(initialBrightness) }
    val custom = ThemeCatalog.isCustom(themeId)
    val image by rememberThemeImage(if (custom) themeId else null)
    val spec = ThemeCatalog.byId(themeId)
    val palette = if (custom || spec == null) imagePalette(themeId, image, brightness) else previewPaletteFor(spec)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        // Landscape: the full-width preview alone was taller than the screen and the sheet does not scroll by itself,
        // so Key borders and Apply were out of reach. Cap the preview at 42 % of the screen height (portrait is
        // unaffected) and let the content scroll on anything smaller still.
        val maxPreviewWidth = sheetPreviewMaxWidthDp(LocalConfiguration.current.screenHeightDp).dp
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 16.dp).navigationBarsPadding(),
        ) {
            KeyboardPreview(
                palette, keyBorders = borders,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = maxPreviewWidth).fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT).shadow(2.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(16.dp))
            if (custom) {
                Text(stringResource(R.string.brightness), style = SheetLabel, color = colors.onSurface)
                ThinSlider(brightness, 0, 100, 1, { brightness = it })
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(borders, role = Role.Switch) { borders = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.key_borders), Modifier.weight(1f), style = SheetLabel, color = colors.onSurface)
                Switch(checked = borders, onCheckedChange = null)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onDismiss, modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                ) {
                    Text(stringResource(R.string.cancel), fontSize = 16.sp)
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.padding(start = 8.dp).height(40.dp)) {
                        Text(stringResource(R.string.delete), fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { onApply(borders, brightness) }, modifier = Modifier.height(40.dp)) {
                    Text(stringResource(R.string.apply), fontSize = 16.sp)
                }
            }
        }
    }
}

/** Width / height of the theme sheet's keyboard preview. */
internal const val PREVIEW_ASPECT = 952f / 658f

/** The sheet's preview is at most 42 % of the screen height tall (landscape); portrait keeps the full width. */
internal fun sheetPreviewMaxWidthDp(screenHeightDp: Int): Float = screenHeightDp * 0.42f * PREVIEW_ASPECT

@Composable
private fun rememberThemeImage(id: String?): androidx.compose.runtime.State<ImageBitmap?> {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(null, id) {
        value = if (id == null) null else withContext(Dispatchers.IO) {
            val f = File(ctx.filesDir, ThemeCatalog.customImagePath(id))
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
        }
    }
}

/** Copies a picked image into app-private storage, downscaled to at most 1440 px on the long side. */
private fun importImage(ctx: Context, uri: Uri, id: String): Boolean = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1440) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return false
    val f = File(ctx.filesDir, ThemeCatalog.customImagePath(id))
    f.parentFile?.mkdirs()
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    true
}.getOrDefault(false)
