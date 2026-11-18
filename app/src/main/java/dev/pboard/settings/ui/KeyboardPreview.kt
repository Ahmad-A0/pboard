package dev.pboard.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.ime.theme.Palette
import dev.pboard.prefs.LanguageCatalog
import dev.pboard.prefs.ThemeCatalog
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colours used to draw a keyboard preview / theme thumbnail. Built only from the keyboard's own colours
 * ([KeyboardTheme.resolve], see [paletteFor] and [imagePalette]).
 */
@Immutable
data class PreviewPalette(
    val background: Color,
    /** Bottom stop of a gradient background; equal to [background] for solid themes. */
    val backgroundEnd: Color = background,
    val key: Color,
    /** Space bar colour in the thumbnail and in borderless mode (Gboard's thumbnails show it lighter than keys). */
    val spaceBar: Color,
    val special: Color,
    val accent: Color,
    val text: Color,
    val hint: Color,
    val specialText: Color,
    val accentText: Color,
    /** The ?123 key when key borders are off. */
    val borderlessSymbols: Color = special,
    /** Header strip colour when key borders are off (coloured themes darken it); null = same as background. */
    val borderlessHeader: Color? = null,
    val image: ImageBitmap? = null,
    /** 0..1 dimming applied over [image]. */
    val imageScrim: Float = 0f,
)

/** Preview palette for a catalogue theme in this context (system night mode, Material You palette). */
@Composable
fun previewPaletteFor(spec: ThemeCatalog.ThemeSpec): PreviewPalette {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    // Also keyed on the configuration: the Material You palette changes with the wallpaper (a config change).
    val config = LocalConfiguration.current
    return remember(spec.id, dark, config) { paletteFor(spec, dark) { Palette.resolve(ctx, it) } }
}

/**
 * The preview colours of [spec]: exactly the keyboard's colours, resolved by [KeyboardTheme.resolve] like the
 * keyboard does (with the same [dynamic] Material You palette for Dynamic Color).
 */
fun paletteFor(spec: ThemeCatalog.ThemeSpec, systemDark: Boolean, dynamic: (dark: Boolean) -> Palette): PreviewPalette =
    previewPalette(KeyboardTheme.resolve(spec.id, systemDark, dynamic))

fun previewPalette(k: KeyboardColors): PreviewPalette = PreviewPalette(
    background = Color(k.background),
    backgroundEnd = Color(k.backgroundEnd ?: k.background),
    key = Color(k.key),
    spaceBar = Color(k.spaceBar),
    special = Color(k.functionalKey),
    accent = Color(k.enterKey),
    text = Color(k.keyLabel),
    hint = Color(k.hintLabel),
    specialText = Color(k.functionalIcon),
    accentText = Color(k.enterIcon),
    borderlessHeader = k.borderlessHeader?.let { Color(it) },
    borderlessSymbols = Color(k.borderlessSymbolKey),
    imageScrim = k.imageScrim,
)

/**
 * Palette for the user image theme [id] at [brightness] (0..100) showing [image]: the keyboard's colours for that
 * theme ([KeyboardTheme.resolve]), so the tile, the preview sheet and the keyboard agree.
 */
fun imagePalette(id: String, image: ImageBitmap?, brightness: Int): PreviewPalette {
    val theme = ThemeCatalog.CustomTheme(id, brightness)
    return previewPalette(KeyboardTheme.resolve(id, true, { Palette.baseline(it) }, listOf(theme))).copy(image = image)
}

private val rowsFor = mapOf(
    LanguageCatalog.Layout.QWERTY to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    LanguageCatalog.Layout.QWERTZ to listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
    LanguageCatalog.Layout.AZERTY to listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
    LanguageCatalog.Layout.DVORAK to listOf("',.pyfgcrl", "aoeuidhtns", "qjkxbmwv"),
    LanguageCatalog.Layout.COLEMAK to listOf("qwfpgjluy;", "arstdhneio", "zxcvbkm"),
    LanguageCatalog.Layout.PC to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
)

/**
 * Scaled-down keyboard (toolbar strip + 4 rows) drawn on a Canvas. Used by the theme preview sheet and the layout
 * carousel. Proportions follow Gboard's default portrait keyboard.
 */
@Composable
fun KeyboardPreview(
    palette: PreviewPalette,
    modifier: Modifier = Modifier,
    layout: LanguageCatalog.Layout = LanguageCatalog.Layout.QWERTY,
    keyBorders: Boolean = true,
    numberHints: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val mic = rememberVectorPainter(Icons.Filled.Mic)
    val shift = rememberVectorPainter(Icons.Outlined.ArrowUpward)
    val back = rememberVectorPainter(Icons.AutoMirrored.Outlined.Backspace)
    val emoji = rememberVectorPainter(Icons.Outlined.SentimentSatisfied)
    val enter = rememberVectorPainter(Icons.AutoMirrored.Outlined.KeyboardReturn)
    val rows = rowsFor.getValue(layout)
    Canvas(modifier) {
        drawBackground(palette)
        val w = size.width
        val h = size.height
        val headerH = h * 0.15f
        if (!keyBorders && palette.borderlessHeader != null) drawRect(palette.borderlessHeader, size = Size(w, headerH))
        // mic on the right of the toolbar strip
        val micS = headerH * 0.42f
        drawIcon(mic, Offset(w - headerH * 0.5f - micS / 2, (headerH - micS) / 2), micS, palette.text)

        val pad = w * 0.012f
        val gap = w * 0.011f
        val unit = (w - 2 * pad - 9 * gap) / 10f + gap
        val rowH = (h - headerH - pad) / 4f
        val keyH = rowH * 0.84f
        val radius = CornerRadius(min(unit, keyH) * 0.22f)
        val textStyle = TextStyle(color = palette.text, fontSize = (keyH * 0.42f).toSp())
        val hintStyle = TextStyle(color = palette.hint, fontSize = (keyH * 0.2f).toSp())
        val specialStyle = TextStyle(color = palette.specialText, fontSize = (keyH * 0.3f).toSp())

        fun key(x: Float, y: Float, widthUnits: Float, fill: Color?, pill: Boolean = false): Rect {
            val r = Rect(x, y + (rowH - keyH) / 2, x + widthUnits * unit - gap, y + (rowH - keyH) / 2 + keyH)
            if (fill != null) {
                drawRoundRect(fill, r.topLeft, r.size, if (pill) CornerRadius(keyH / 2) else radius)
            }
            return r
        }

        fun label(r: Rect, s: String, style: TextStyle) {
            val l = measurer.measure(s, style)
            drawText(l, topLeft = Offset(r.center.x - l.size.width / 2f, r.center.y - l.size.height / 2f))
        }

        val letterFill = if (keyBorders) palette.key else null
        val specialFill = if (keyBorders) palette.special else null
        var y = headerH
        rows.forEachIndexed { ri, row ->
            val n = row.length
            val units = if (ri == 2) n + 3f else n.toFloat()
            var x = pad + (10f - units) * unit / 2f
            if (ri == 2) {
                val r = key(x, y, 1.5f, specialFill)
                drawIcon(shift, r.center - Offset(keyH * 0.2f, keyH * 0.2f), keyH * 0.4f, palette.specialText)
                x += 1.5f * unit
            }
            row.forEachIndexed { ci, ch ->
                val r = key(x, y, 1f, letterFill)
                label(r, ch.toString(), textStyle)
                if (ri == 0 && numberHints) {
                    val d = measurer.measure(((ci + 1) % 10).toString(), hintStyle)
                    drawText(d, topLeft = Offset(r.right - d.size.width - unit * 0.06f, r.top + keyH * 0.04f))
                }
                x += unit
            }
            if (ri == 2) {
                val r = key(x, y, 1.5f, specialFill)
                drawIcon(back, r.center - Offset(keyH * 0.2f, keyH * 0.2f), keyH * 0.4f, palette.specialText)
            }
            y += rowH
        }
        // bottom row: ?123 , ☺ space . ⏎
        var x = pad
        val sym = key(x, y, 1.5f, if (keyBorders) palette.special else palette.borderlessSymbols, pill = true); label(sym, "?123", specialStyle); x += 1.5f * unit
        val punctStyle = textStyle.copy(color = palette.specialText)
        val comma = key(x, y, 1f, specialFill); label(comma, ",", punctStyle); x += unit
        val emo = key(x, y, 1f, letterFill)
        drawIcon(emoji, emo.center - Offset(keyH * 0.2f, keyH * 0.2f), keyH * 0.4f, palette.text); x += unit
        key(x, y, 4f, if (keyBorders) palette.key else palette.spaceBar); x += 4f * unit
        val dot = key(x, y, 1f, specialFill); label(dot, ".", punctStyle); x += unit
        val ent = key(x, y, 1.5f, palette.accent, pill = true)
        drawIcon(enter, ent.center - Offset(keyH * 0.2f, keyH * 0.2f), keyH * 0.4f, palette.accentText)
    }
}

private fun DrawScope.drawBackground(p: PreviewPalette) {
    if (p.image != null) {
        val img = p.image
        // centre-crop
        val scale = maxOf(size.width / img.width, size.height / img.height)
        val sw = (size.width / scale).roundToInt().coerceAtMost(img.width)
        val sh = (size.height / scale).roundToInt().coerceAtMost(img.height)
        drawImage(
            img,
            srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
            srcSize = IntSize(sw, sh),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
        drawRect(Color.Black.copy(alpha = p.imageScrim))
    } else {
        drawRect(Brush.verticalGradient(listOf(p.background, p.backgroundEnd)))
    }
}

private fun DrawScope.drawIcon(painter: Painter, topLeft: Offset, s: Float, tint: Color) {
    translate(topLeft.x, topLeft.y) {
        with(painter) { draw(Size(s, s), colorFilter = ColorFilter.tint(tint)) }
    }
}

/**
 * Theme tile as in Gboard's picker: rounded background, a "space bar" bar and an accent pill near the bottom.
 * [rightHalf] draws the right half with another palette (the light/dark "System Auto" tile).
 */
@Composable
fun ThemeThumbnail(palette: PreviewPalette, modifier: Modifier = Modifier, rightHalf: PreviewPalette? = null) {
    Canvas(modifier) {
        drawThumb(palette)
        if (rightHalf != null) clipRect(left = size.width / 2) { drawThumb(rightHalf) }
    }
}

private fun DrawScope.drawThumb(palette: PreviewPalette) {
    drawBackground(palette)
    val w = size.width
    val h = size.height
    val barH = h * 0.09f
    val y = h * 0.775f
    drawRoundRect(palette.spaceBar, Offset(w * 0.30f, y), Size(w * 0.395f, barH), CornerRadius(barH * 0.25f))
    drawRoundRect(palette.accent, Offset(w * 0.79f, y), Size(w * 0.13f, barH), CornerRadius(barH / 2))
}
