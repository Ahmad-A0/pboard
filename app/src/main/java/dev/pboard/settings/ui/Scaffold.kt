package dev.pboard.settings.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pboard.R
import kotlin.math.roundToInt

private val CollapsedHeight = 64.dp
private val ExpandedHeight = 152.dp

/** Distance from the large title's baseline to the bottom of the expanded bar (Gboard: 16 dp; M3 default: 28 dp). */
private val LargeTitleBaselineInset = 16.dp

/**
 * A settings page: collapsing large-title app bar (152 dp → 64 dp, title at x = 24 dp, like Gboard's settings) over a
 * scrolling list. Background = `surfaceContainer`; the bar turns `surfaceContainerHighest` once content is under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    SettingsFrame(title, onBack, actions, bottomBar) { padding, behavior ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection),
            contentPadding = padding.withBottom(16),
            content = content,
        )
    }
}

/** Same frame as [SettingsPage] for pages that bring their own scrolling container (grids, custom layouts). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFrame(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    body: @Composable (PaddingValues, TopAppBarScrollBehavior) -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { CollapsingTitleBar(title, onBack, actions, behavior) },
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Box(Modifier.fillMaxSize()) { body(padding, behavior) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsingTitleBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    behavior: TopAppBarScrollBehavior,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val limit = with(density) { (CollapsedHeight - ExpandedHeight).toPx() }
    SideEffect {
        if (behavior.state.heightOffsetLimit != limit) behavior.state.heightOffsetLimit = limit
    }
    val fraction = behavior.state.collapsedFraction
    val scrolledUnder = behavior.state.overlappedFraction > 0.01f || fraction > 0.99f
    val container by animateColorAsState(
        if (scrolledUnder) colors.surfaceContainerHighest else colors.surfaceContainer, label = "appbar",
    )
    val extraPx = with(density) { (ExpandedHeight - CollapsedHeight).toPx() } + behavior.state.heightOffset
    Surface(color = container) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().height(CollapsedHeight), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    if (onBack != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_up))
                        }
                    } else {
                        Spacer(Modifier.width(24.dp))
                    }
                }
                Box(Modifier.weight(1f).padding(start = 12.dp).alpha(((fraction - 0.5f) * 2f).coerceIn(0f, 1f))) {
                    Text(title, style = SettingsType.collapsedTitle, color = colors.onSurface, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
                Spacer(Modifier.width(4.dp))
            }
            // Expanded title: baseline LargeTitleBaselineInset above the bottom edge; slides up under the top row.
            Layout(
                content = {
                    Text(
                        title, Modifier.layoutId("t").alpha(1f - fraction), style = SettingsType.largeTitle,
                        color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.fillMaxWidth().clipToBounds(),
            ) { measurables, constraints ->
                val h = extraPx.roundToInt().coerceAtLeast(0)
                val start = 24.dp.roundToPx()
                val p = measurables.first().measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth - start - 24.dp.roundToPx(), minHeight = 0))
                val last = p[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
                val y = h - LargeTitleBaselineInset.roundToPx() - last
                layout(constraints.maxWidth, h) { p.place(start, y) }
            }
        }
    }
}

@Composable
private fun PaddingValues.withBottom(extraDp: Int): PaddingValues {
    val dir = LocalLayoutDirection.current
    val nav = WindowInsets.navigationBars.asPaddingValues()
    return PaddingValues(
        start = calculateStartPadding(dir),
        end = calculateEndPadding(dir),
        top = calculateTopPadding(),
        bottom = maxOf(calculateBottomPadding(), nav.calculateBottomPadding()) + extraDp.dp,
    )
}
