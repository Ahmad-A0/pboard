package dev.pboard.settings.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Material You theme for the settings app. Dynamic colour on Android 12+, otherwise a static scheme generated from
 * the same blue seed as the default Pixel wallpaper (so screenshots line up with Gboard's on the emulator).
 */
@Composable
fun PboardSettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Text styles measured from Gboard's settings (Google Sans sizes, rendered here in the system font). */
object SettingsType {
    /** Views-style line boxes (no trimming of the first/last line), so row heights match platform preferences. */
    private val noTrim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private fun style(size: Int, line: Float, weight: FontWeight? = null) =
        TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, lineHeightStyle = noTrim)

    val largeTitle = style(28, 36f)
    val collapsedTitle = style(22, 28f)
    /** Line pitch 25 sp; [PrefRow] adds the platform's font padding (≈2 dp) above the first/below the last line. */
    val rowTitle = style(20, 25.1f)
    val rowSummary = style(14, 17.9f)
    val category = style(14, 19.4f)
    val dialogTitle = style(24, 32f)
    val smallDialogTitle = style(20, 28f, FontWeight.Medium)
    val body = style(16, 24f)
    val label = style(14, 20f, FontWeight.Medium)
}

private val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF475D92), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF), onPrimaryContainer = Color(0xFF2E4578),
    secondary = Color(0xFF575E71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F9), onSecondaryContainer = Color(0xFF404659),
    tertiary = Color(0xFF725572), tertiaryContainer = Color(0xFFFDD7FA),
    background = Color(0xFFFAF8FF), onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC), onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780), outlineVariant = Color(0xFFC5C6D0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4), surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF), onPrimary = Color(0xFF152E60),
    primaryContainer = Color(0xFF2E4578), onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC0C6DC), onSecondary = Color(0xFF2A3042),
    secondaryContainer = Color(0xFF404659), onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFFE0BBDD), tertiaryContainer = Color(0xFF593D59),
    background = Color(0xFF121318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F), onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099), outlineVariant = Color(0xFF44464F),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33343A),
)
