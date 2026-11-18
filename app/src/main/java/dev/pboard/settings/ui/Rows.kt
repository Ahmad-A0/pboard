package dev.pboard.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pboard.R
import dev.pboard.prefs.Pref

/** Disabled rows use the Material "38 %" content alpha, like platform preferences. */
private const val DISABLED_ALPHA = 0.38f

/**
 * Base preference row: 24 dp start inset (72 dp when [icon] is present, as on the home list), 16 dp vertical
 * padding, 20 sp title + 14 sp summary, optional [trailing] widget 16 dp from the end.
 */
@Composable
fun PrefRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    startInset: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 61.dp)
            .padding(start = startInset, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(24.dp).alpha(if (enabled) 1f else DISABLED_ALPHA), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
            // Vertical paddings emulate TextView font padding so row heights match Gboard's (61 dp single-line).
            Text(title, Modifier.padding(vertical = 2.1.dp), style = SettingsType.rowTitle, color = colors.onSurface)
            if (!summary.isNullOrEmpty()) {
                Text(summary, Modifier.padding(vertical = 1.7.dp), style = SettingsType.rowSummary, color = colors.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** Category header in `primary`, 16 dp above, nothing below (the next row brings its own padding). */
@Composable
fun Category(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
        style = SettingsType.category,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    startInset: Dp = 24.dp,
) {
    PrefRow(
        title = title,
        summary = summary,
        enabled = enabled,
        startInset = startInset,
        modifier = Modifier.toggleable(checked, enabled, Role.Switch) { onCheckedChange(it) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A [SwitchRow] bound to a boolean preference. */
@Composable
fun SwitchPref(
    pref: Pref<Boolean>,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    startInset: Dp = 24.dp,
) {
    val prefs = rememberPrefs()
    val checked by pref.collectAsState()
    SwitchRow(title, checked, { prefs[pref] = it }, summary, enabled, startInset)
}

/** Row + vertical divider + gear button (e.g. "Haptic feedback on keypress" → system settings). */
@Composable
fun GearRow(title: String, summary: String?, onGear: () -> Unit, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    PrefRow(
        title = title,
        summary = summary,
        onClick = onClick ?: onGear,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(Modifier.height(32.dp), color = colors.outlineVariant)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onGear) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings_gear), tint = colors.primary)
                }
            }
        },
    )
}

/** Home-list entry: 24 dp icon at x = 24 dp, text at x = 72 dp. */
@Composable
fun HomeRow(icon: ImageVector, title: String, summary: String? = null, onClick: () -> Unit) {
    PrefRow(title = title, summary = summary, icon = icon, onClick = onClick)
}

/** Full-width pill with a switch (Android "main switch" style), used on the Voice typing page. */
@Composable
fun MainSwitchBar(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (checked) colors.primaryContainer else colors.surfaceContainerHighest)
                .toggleable(checked, role = Role.Switch) { onCheckedChange(it) }
                .heightIn(min = 76.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                Modifier.weight(1f),
                style = SettingsType.rowTitle,
                color = if (checked) colors.onPrimaryContainer else colors.onSurface,
            )
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Plain paragraph text with the page's 24/16 dp insets. */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        style = SettingsType.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Gboard 18.6 two-target row: tapping the text opens a sub-page, the switch (after a divider) toggles in place. */
@Composable
fun TwoTargetSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    PrefRow(
        title = title,
        onClick = onOpen,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(Modifier.height(32.dp), color = colors.outlineVariant)
                Spacer(Modifier.width(16.dp))
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        },
    )
}
