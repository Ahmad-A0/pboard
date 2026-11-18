package dev.pboard.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.pboard.R
import dev.pboard.prefs.EnumPref
import dev.pboard.prefs.IntPref
import dev.pboard.prefs.Pref
import dev.pboard.prefs.StableId
import kotlin.math.roundToInt

/**
 * Single-choice dialog in the Android 16 settings style: white surface, centred title, options as tall pills with
 * the selected one filled `primaryContainer` and marked with a check.
 */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialDim()
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLowest,
            modifier = Modifier.padding(horizontal = 35.dp).widthIn(max = 560.dp)) {
            Column(Modifier.padding(top = 18.dp, bottom = 12.dp, start = 20.dp, end = 20.dp).verticalScroll(rememberScrollState())) {
                Text(title, Modifier.fillMaxWidth().padding(bottom = 18.dp), style = SettingsType.dialogTitle,
                    color = colors.onSurface, textAlign = TextAlign.Center)
                options.forEachIndexed { i, label ->
                    val isSel = i == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.5.dp)
                            .clip(RoundedCornerShape(36.dp))
                            .background(if (isSel) colors.primaryContainer else colors.surfaceContainerLowest)
                            .selectable(isSel, role = Role.RadioButton) { onSelect(i); onDismiss() }
                            .height(71.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(68.dp), contentAlignment = Alignment.Center) {
                            if (isSel) Icon(Icons.Filled.Check, null, tint = colors.onPrimaryContainer)
                        }
                        Text(label, style = SettingsType.rowTitle,
                            color = if (isSel) colors.onPrimaryContainer else colors.onSurface)
                    }
                }
            }
        }
    }
}

/** Row showing the current choice as summary; opens a [ChoiceDialog]. */
@Composable
fun <E> EnumListPref(pref: EnumPref<E>, title: String, labels: (E) -> String, enabled: Boolean = true)
    where E : Enum<E>, E : StableId {
    val prefs = rememberPrefs()
    val value by pref.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    PrefRow(title, summary = labels(value), enabled = enabled, onClick = { open = true })
    if (open) {
        ChoiceDialog(title, pref.values.map(labels), pref.values.indexOf(value), { prefs[pref] = pref.values[it] }) { open = false }
    }
}

/** Boolean shown as a two-entry list (e.g. Font: pboard default / System default). */
@Composable
fun BooleanListPref(pref: Pref<Boolean>, title: String, falseLabel: String, trueLabel: String) {
    val prefs = rememberPrefs()
    val value by pref.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    PrefRow(title, summary = if (value) trueLabel else falseLabel, onClick = { open = true })
    if (open) {
        ChoiceDialog(title, listOf(falseLabel, trueLabel), if (value) 1 else 0, { prefs[pref] = it == 1 }) { open = false }
    }
}

/**
 * Slider dialog (Default · Cancel · OK) for an [IntPref]. [format] renders a value; values ≤ [systemValue] mean
 * "follow the system" and are shown as [systemLabel]. [preview] is called while dragging (e.g. play a click).
 */
@Composable
fun SliderDialogPref(
    pref: IntPref,
    title: String,
    format: (Int) -> String,
    enabled: Boolean = true,
    sliderMin: Int = pref.min,
    systemLabel: String? = null,
    systemValue: Int = Int.MIN_VALUE,
    preview: (Int) -> Unit = {},
) {
    val prefs = rememberPrefs()
    val value by pref.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    val summary = if (systemLabel != null && value <= systemValue) systemLabel else format(value)
    PrefRow(title, summary = summary, enabled = enabled, onClick = { open = true })
    if (open) {
        val start = if (value < sliderMin) (if (pref.default >= sliderMin) pref.default else (sliderMin + pref.max) / 2) else value
        var current by remember { mutableIntStateOf(start) }
        SliderDialog(
            title = title,
            valueText = format(current),
            value = current,
            min = sliderMin,
            max = pref.max,
            step = pref.step,
            onChange = { current = it; preview(it) },
            onDefault = { prefs.reset(pref); open = false },
            onConfirm = { prefs[pref] = current; open = false },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun SliderDialog(
    title: String,
    valueText: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    onDefault: (() -> Unit)?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialDim()
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 27.dp).widthIn(max = 560.dp)) {
            Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 8.dp)) {
                Text(title, style = SettingsType.smallDialogTitle, color = colors.onSurface)
                Spacer(Modifier.height(14.dp))
                Text(valueText, Modifier.fillMaxWidth(), style = SettingsType.body, color = colors.onSurface, textAlign = TextAlign.Center)
                ThinSlider(
                    value = value, min = min, max = max, step = step, onChange = onChange,
                    modifier = Modifier.padding(start = 12.dp, end = 20.dp).height(32.dp),
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onDefault != null) TextButton(onClick = onDefault) { Text(stringResource(R.string.reset), style = SettingsType.label) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), style = SettingsType.label) }
                    TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok), style = SettingsType.label) }
                }
            }
        }
    }
}

/**
 * Platform-style seek bar (thin 2 dp track, small round thumb) — Material 3's Compose slider has a different,
 * much heavier look than the SeekBar Gboard uses.
 *
 * The slider is laid out as wide as its track, but it takes touches [ThumbReach] beyond both ends, and the thumb's
 * 48 dp target is excluded from system gestures (as the platform's AbsSeekBar does). Without both, a thumb at either
 * end of the range could not be dragged: half of it lay outside the touchable area, and with the usual 24 dp page
 * inset the rest sat inside the gesture-navigation back zone, so grabbing it started a back swipe instead (user
 * report: "Keep clipboard history for" at its default, 1 hour, did not move).
 *
 * The gesture handlers are long-lived coroutines keyed on the range only, so they read [onChange] and [enabled]
 * through [rememberUpdatedState]: capturing them directly kept the values of the first composition (a slider
 * enabled later by a switch on the same page ignored drags; one disabled later kept writing).
 */
@Composable
fun ThinSlider(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val range = (max - min).coerceAtLeast(1)
    val fraction = ((value - min).toFloat() / range).coerceIn(0f, 1f)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestEnabled by rememberUpdatedState(enabled)
    var trackPx by remember { mutableFloatStateOf(1f) }
    val reachPx = with(LocalDensity.current) { ThumbReach.toPx() }
    fun valueAt(x: Float): Int {
        val raw = min + ((x - reachPx) / trackPx).coerceIn(0f, 1f) * range
        return (min + ((raw - min) / step).roundToInt() * step).coerceIn(min, max)
    }
    fun set(v: Int) {
        if (latestEnabled) latestOnChange(v)
    }
    val active = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    val inactive = if (enabled) colors.outlineVariant else colors.onSurface.copy(alpha = 0.12f)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            // Measured as the track, placed ThumbReach wider on both sides (touch area only; nothing is drawn there).
            .layout { measurable, constraints ->
                val extra = ThumbReach.roundToPx()
                val p = measurable.measure(constraints.offset(horizontal = 2 * extra))
                layout((p.width - 2 * extra).coerceAtLeast(0), p.height) { p.place(-extra, 0) }
            }
            .onSizeChanged { trackPx = (it.width - 2 * reachPx).coerceAtLeast(1f) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat(), range / step - 1)
                if (enabled) setProgress { set(it.roundToInt().coerceIn(min, max)); true }
            }
            .pointerInput(min, max, step) {
                detectTapGestures { set(valueAt(it.x)) }
            }
            .pointerInput(min, max, step) {
                detectDragGestures { change, _ -> set(valueAt(change.position.x)) }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val y = size.height / 2
            val start = reachPx
            val end = size.width - reachPx
            val x = start + (end - start) * fraction
            val track = 2.dp.toPx()
            drawLine(inactive, Offset(start, y), Offset(end, y), track, StrokeCap.Round)
            drawLine(active, Offset(start, y), Offset(x, y), track, StrokeCap.Round)
            drawCircle(active, radius = 6.dp.toPx(), center = Offset(x, y))
        }
        // The thumb's touch target, kept out of the system back gesture. A node of its own that moves with the thumb:
        // the exclusion rectangle is only recomputed when the node is placed somewhere else.
        Box(
            Modifier
                .matchParentSize()
                .wrapContentWidth(Alignment.Start)
                .offset { IntOffset((trackPx * fraction).roundToInt(), 0) }
                .width(ThumbReach * 2)
                .systemGestureExclusion(),
        )
    }
}

/** Half the thumb's 48 dp touch target: how far the slider takes touches beyond the ends of its track. */
private val ThumbReach = 24.dp

/** Inline seek bar row with discrete labelled stops (Write in text fields › Handwriting speed). */
@Composable
fun <E> InlineStepSliderPref(pref: EnumPref<E>, title: String, labels: (E) -> String, enabled: Boolean = true)
    where E : Enum<E>, E : StableId {
    val prefs = rememberPrefs()
    val value by pref.collectAsState()
    val idx = pref.values.indexOf(value)
    Column(Modifier.fillMaxWidth()) {
        PrefRow(title, summary = labels(value), enabled = enabled, modifier = Modifier.padding(bottom = 0.dp))
        ThinSlider(
            value = idx, min = 0, max = pref.values.lastIndex, step = 1,
            onChange = { prefs[pref] = pref.values[it] },
            modifier = Modifier.padding(start = 24.dp, end = 24.dp).padding(bottom = 8.dp),
            enabled = enabled,
        )
    }
}

/**
 * Inline slider over a fixed list of values (Gboard 18.6 "Font size", "Emoji size", "Keep clipboard history for").
 * The summary shows [summary] (if any) and the current value.
 */
@Composable
fun StepSliderPref(
    pref: IntPref,
    steps: List<Int>,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    format: (Int) -> String,
) {
    val prefs = rememberPrefs()
    val value by pref.collectAsState()
    val idx = steps.indexOf(value).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= value }.coerceAtLeast(0)
    Column(Modifier.fillMaxWidth()) {
        PrefRow(title, summary = listOfNotNull(summary, format(steps[idx])).joinToString(" · "), enabled = enabled)
        ThinSlider(
            value = idx, min = 0, max = steps.lastIndex, step = 1,
            onChange = { prefs[pref] = steps[it] },
            modifier = Modifier.padding(start = 24.dp, end = 24.dp).padding(bottom = 8.dp),
            enabled = enabled,
        )
    }
}

/** Simple confirmation dialog. */
@Composable
fun ConfirmDialog(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        MaterialDim()
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)) {
                Text(text, style = SettingsType.body, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), style = SettingsType.label) }
                    TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, style = SettingsType.label) }
                }
            }
        }
    }
}

/** A question with two ways to go ahead (e.g. merge or replace) next to Cancel. */
@Composable
fun TwoActionDialog(text: String, first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        MaterialDim()
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)) {
                Text(text, style = SettingsType.body, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), style = SettingsType.label) }
                    TextButton(onClick = { onSecond(); onDismiss() }) { Text(second, style = SettingsType.label) }
                    TextButton(onClick = { onFirst(); onDismiss() }) { Text(first, style = SettingsType.label) }
                }
            }
        }
    }
}

/** Material 3 scrim strength (32 %) instead of the platform's darker default window dim. */
@Composable
private fun MaterialDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(0.32f) }
}
