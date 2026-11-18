package dev.pboard.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pboard.R
import dev.pboard.ime.toolbar.ToolbarItem
import dev.pboard.ime.toolbar.ToolbarLayout
import dev.pboard.settings.Navigator
import dev.pboard.settings.ui.SettingsFrame
import dev.pboard.settings.ui.rememberPrefs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val PANEL_COLUMNS = 4

private sealed interface DropTarget {
    data class Bar(val index: Int) : DropTarget
    data class Panel(val index: Int) : DropTarget
    data object Power : DropTarget
}

private fun ToolbarLayout.apply(item: ToolbarItem, target: DropTarget?, capacity: Int): ToolbarLayout = when (target) {
    is DropTarget.Bar -> moveToBar(item, target.index, capacity)
    is DropTarget.Panel -> moveToPanel(item, target.index)
    DropTarget.Power -> replacePowerKey(item)
    null -> this
}

/**
 * Toolbar customisation, mirroring the keyboard's own "more" panel: the bar on top,
 * "Hold and drag to customize", then a 4-column grid of the remaining items. Long-press and drag a tile onto the bar,
 * into the grid, or onto the right-hand key to replace it; tap a tile to pin/unpin it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarScreen(nav: Navigator) {
    val prefs = rememberPrefs()
    val largeScreen = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val capacity = if (largeScreen) ToolbarItem.MAX_PINNED else ToolbarItem.BAR_CAPACITY
    val saved by remember { ToolbarLayout.flow(prefs) }.collectAsStateCompat(ToolbarLayout.load(prefs))
    val layout = saved.offered(largeScreen)

    // Drag state (all positions in root coordinates).
    var dragItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val tiles = remember { mutableStateMapOf<ToolbarItem, Rect>() }
    var barRect by remember { mutableStateOf(Rect.Zero) }
    var powerRect by remember { mutableStateOf(Rect.Zero) }
    var panelRect by remember { mutableStateOf(Rect.Zero) }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val cellHeight = with(density) { 84.dp.toPx() }
    // The gesture handlers below are started once (pointerInput(Unit)) and outlive recompositions: everything they
    // call must read the layout through this State, not capture `layout` (a drop after the first change used the
    // layout the page opened with to place the item, so it landed elsewhere than the drag preview showed).
    val currentLayout by rememberUpdatedState(layout)

    fun target(p: Offset, item: ToolbarItem): DropTarget? {
        val layout = currentLayout
        if (powerRect.inflate(with(density) { 8.dp.toPx() }).contains(p)) return DropTarget.Power
        val barBand = barRect.inflate(with(density) { 24.dp.toPx() })
        if (barBand.contains(p)) {
            val others = layout.pinned.count { it != item }
            val slot = floor((p.x - barRect.left) / barRect.width * (others + 1)).toInt()
            return DropTarget.Bar(slot.coerceIn(0, others))
        }
        if (p.y >= panelRect.top && panelRect.width > 0) {
            val others = layout.panel.count { it != item }
            val col = floor((p.x - panelRect.left) / (panelRect.width / PANEL_COLUMNS)).toInt().coerceIn(0, PANEL_COLUMNS - 1)
            val row = floor((p.y - panelRect.top) / cellHeight).toInt().coerceAtLeast(0)
            return DropTarget.Panel((row * PANEL_COLUMNS + col).coerceIn(0, others))
        }
        return null
    }

    val dragging = dragItem
    val shown = if (dragging != null) layout.apply(dragging, target(pointer, dragging), capacity) else layout

    fun hit(p: Offset): ToolbarItem? = tiles.entries.firstOrNull { it.value.contains(p) }?.key
    fun commit(l: ToolbarLayout) = l.save(prefs)

    SettingsFrame(
        stringResource(R.string.customize_toolbar),
        onBack = { nav.pop() },
        actions = {
            IconButton(onClick = { ToolbarLayout.reset(prefs) }) {
                Icon(Icons.Outlined.RestartAlt, stringResource(R.string.toolbar_reset))
            }
        },
    ) { padding, behavior ->
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(behavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState(), enabled = dragging == null)
                .padding(top = padding.calculateTopPadding())
                .onGloballyPositioned { contentOrigin = it.positionInRoot() },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onGloballyPositioned { containerOrigin = it.positionInRoot() }
                    .pointerInput(Unit) {
                        detectTapGestures { local ->
                            hit(local + containerOrigin)?.let { commit(currentLayout.togglePinned(it, capacity)) }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { local ->
                                val p = local + containerOrigin
                                hit(p)?.let { dragItem = it; pointer = p }
                            },
                            onDrag = { change, delta -> change.consume(); pointer += delta },
                            onDragEnd = {
                                val item = dragItem
                                if (item != null) commit(currentLayout.apply(item, target(pointer, item), capacity))
                                dragItem = null
                            },
                            onDragCancel = { dragItem = null },
                        )
                    },
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    val toggle = { item: ToolbarItem -> commit(currentLayout.togglePinned(item, capacity)) }
                    Bar(shown, dragging, tiles, toggle, onBar = { barRect = it }, onPower = { powerRect = it })
                    Text(
                        stringResource(R.string.toolbar_hint),
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                        style = TextStyle(fontSize = 14.sp), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Panel(shown, dragging, tiles, toggle, cellHeightDp = 84) { panelRect = it }
                }
            }
            if (dragging != null) {
                DragBubble(dragging, pointer - contentOrigin)
                if (powerRect != Rect.Zero) ReplaceTooltip(powerRect, contentOrigin)
            }
        }
    }
    LaunchedEffect(layout) { tiles.keys.retainAll((layout.order + listOfNotNull(layout.powerKey)).toSet()) }
}

@Composable
private fun Bar(
    layout: ToolbarLayout,
    dragging: ToolbarItem?,
    tiles: MutableMap<ToolbarItem, Rect>,
    onToggle: (ToolbarItem) -> Unit,
    onBar: (Rect) -> Unit,
    onPower: (Rect) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.GridView, null, Modifier.size(20.dp), tint = colors.onSecondaryContainer)
        }
        Row(
            Modifier.weight(1f).height(48.dp).padding(horizontal = 6.dp).onGloballyPositioned { onBar(it.boundsInRoot()) },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            layout.pinned.forEach { item ->
                Tile(item, Modifier.width(52.dp).height(32.dp), RoundedCornerShape(10.dp), item == dragging, tiles, label = false) {
                    onToggle(item)
                }
            }
        }
        val power = layout.powerKey
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceContainerLowest)
                .onGloballyPositioned { onPower(it.boundsInRoot()) },
            contentAlignment = Alignment.Center,
        ) {
            if (power != null) {
                Icon(power.icon, stringResource(power.label), Modifier.size(22.dp).alpha(if (power == dragging) 0f else 1f),
                    tint = colors.onSurface)
            }
        }
    }
}

@Composable
private fun Panel(
    layout: ToolbarLayout,
    dragging: ToolbarItem?,
    tiles: MutableMap<ToolbarItem, Rect>,
    onToggle: (ToolbarItem) -> Unit,
    cellHeightDp: Int,
    onPanel: (Rect) -> Unit,
) {
    val items = layout.panel
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).onGloballyPositioned { onPanel(it.boundsInRoot()) },
    ) {
        val rows = (items.size + PANEL_COLUMNS - 1) / PANEL_COLUMNS
        for (r in 0 until maxOf(rows, 1)) {
            Row(Modifier.fillMaxWidth().height(cellHeightDp.dp)) {
                for (c in 0 until PANEL_COLUMNS) {
                    val item = items.getOrNull(r * PANEL_COLUMNS + c)
                    Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.TopCenter) {
                        if (item != null) {
                            Tile(item, Modifier.fillMaxWidth().height(47.dp), RoundedCornerShape(12.dp), item == dragging, tiles, label = true) {
                                onToggle(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(
    item: ToolbarItem,
    boxModifier: Modifier,
    shape: RoundedCornerShape,
    isDragged: Boolean,
    tiles: MutableMap<ToolbarItem, Rect>,
    label: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(item.label)
    Column(
        Modifier
            .alpha(if (isDragged) 0f else 1f)
            .onGloballyPositioned { tiles[item] = it.boundsInRoot() }
            .semantics { contentDescription = name; onClick { onToggle(); true } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(boxModifier.clip(shape).background(colors.surfaceContainerLowest), contentAlignment = Alignment.Center) {
            Icon(item.icon, null, Modifier.size(22.dp), tint = colors.onSurface)
        }
        if (label) {
            Spacer(Modifier.height(4.dp))
            Text(name, style = TextStyle(fontSize = 13.sp), color = colors.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun DragBubble(item: ToolbarItem, center: Offset) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val half = with(density) { Offset(40.dp.toPx(), 24.dp.toPx()) }
    Box(
        Modifier
            .offset { IntOffset((center.x - half.x).roundToInt(), (center.y - half.y).roundToInt()) }
            .scale(1.3f)
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerLowest)
            .size(80.dp, 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(item.icon, null, Modifier.size(24.dp), tint = colors.onSurface)
    }
}

@Composable
private fun ReplaceTooltip(power: Rect, origin: Offset) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val widthPx = with(density) { 180.dp.toPx() }
    val heightPx = with(density) { 40.dp.toPx() }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (power.right - origin.x - widthPx).roundToInt().coerceAtLeast(0),
                    (power.bottom - origin.y + 4.dp.toPx()).roundToInt(),
                )
            }
            .size(180.dp, 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.toolbar_drop_replace), color = colors.onTertiaryContainer, style = TextStyle(fontSize = 14.sp))
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateCompat(initial: T) =
    androidx.compose.runtime.produceState(initial, this) { collect { value = it } }
