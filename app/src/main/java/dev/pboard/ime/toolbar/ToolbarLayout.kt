package dev.pboard.ime.toolbar

import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The user's toolbar arrangement, mirroring Gboard's model: **one ordered list** of items; the first [pinnedCount]
 * sit on the bar (between the "more" button and the power key), the rest fill the "more" panel in order.
 * [powerKey] is the item in the right-hand slot (voice by default; replaceable by dropping an item on it).
 *
 * All operations are pure and return a new layout; persist with [save].
 */
data class ToolbarLayout(
    val order: List<ToolbarItem>,
    val pinnedCount: Int,
    val powerKey: ToolbarItem?,
) {
    init {
        require(pinnedCount in 0..order.size) { "pinnedCount $pinnedCount out of 0..${order.size}" }
        require(powerKey == null || powerKey !in order) { "power key must not also be in the list" }
    }

    /** Items on the bar, left to right. */
    val pinned: List<ToolbarItem> get() = order.take(pinnedCount)

    /** Items in the "more" panel, in grid order. */
    val panel: List<ToolbarItem> get() = order.drop(pinnedCount)

    fun isPinned(item: ToolbarItem) = order.indexOf(item).let { it in 0 until pinnedCount }

    /**
     * Drop [item] onto the bar at [index] (0 = leftmost). If the bar already holds [capacity] items, its last item
     * spills to the front of the panel — exactly what Gboard does.
     */
    fun moveToBar(item: ToolbarItem, index: Int, capacity: Int = ToolbarItem.BAR_CAPACITY): ToolbarLayout {
        val (list, count) = without(item)
        val bar = list.take(count).toMutableList()
        val rest = list.drop(count).toMutableList()
        bar.add(index.coerceIn(0, bar.size), item)
        // Over capacity: the last *other* item spills, so dropping onto the end of a full bar takes the last slot.
        while (bar.size > capacity.coerceAtLeast(1)) rest.add(0, bar.removeAt(bar.indexOfLast { it != item }))
        return copy(order = bar + rest, pinnedCount = bar.size)
    }

    /** Drop [item] into the panel at [index] (0 = first cell). Unpins it if it was on the bar. */
    fun moveToPanel(item: ToolbarItem, index: Int): ToolbarLayout {
        val (list, count) = without(item)
        val bar = list.take(count)
        val rest = list.drop(count).toMutableList()
        rest.add(index.coerceIn(0, rest.size), item)
        return copy(order = bar + rest, pinnedCount = bar.size)
    }

    /** Put [item] in the power-key slot; the previous occupant goes to the front of the panel. */
    fun replacePowerKey(item: ToolbarItem): ToolbarLayout {
        if (item == powerKey) return this
        val (list, count) = without(item)
        val rest = list.drop(count).toMutableList()
        powerKey?.let { rest.add(0, it) }
        return copy(order = list.take(count) + rest, pinnedCount = count, powerKey = item)
    }

    /** Tap-to-toggle helper for accessibility: pin at the end of the bar, or unpin to the front of the panel. */
    fun togglePinned(item: ToolbarItem, capacity: Int = ToolbarItem.BAR_CAPACITY) =
        if (isPinned(item)) moveToPanel(item, 0) else moveToBar(item, pinnedCount, capacity)

    /**
     * Only the items offered on this device ([ToolbarItem.isOffered]); counts adjusted accordingly. The keyboard
     * passes [voiceTyping] (no mic while voice typing is off); the settings editor keeps the stored arrangement.
     */
    fun offered(largeScreen: Boolean, voiceTyping: Boolean = true): ToolbarLayout {
        val keep = order.filter { it.isOffered(largeScreen, voiceTyping) }
        val count = order.take(pinnedCount).count { it.isOffered(largeScreen, voiceTyping) }
        return ToolbarLayout(keep, count, powerKey?.takeIf { it.isOffered(largeScreen, voiceTyping) })
    }

    fun save(prefs: PboardPrefs) = prefs.edit {
        this[Prefs.toolbarOrder] = order.map { it.id }
        this[Prefs.toolbarPinnedCount] = pinnedCount
        this[Prefs.toolbarPowerKey] = powerKey?.id ?: ""
    }

    private fun without(item: ToolbarItem): Pair<List<ToolbarItem>, Int> {
        val i = order.indexOf(item)
        if (i < 0) return order to pinnedCount
        return (order - item) to (if (i < pinnedCount) pinnedCount - 1 else pinnedCount)
    }

    companion object {
        val DEFAULT = ToolbarLayout(
            ToolbarItem.DEFAULT_ORDER, ToolbarItem.DEFAULT_BAR_ITEMS, ToolbarItem.DEFAULT_POWER_KEY,
        )

        /**
         * Rebuilds a layout from stored values: unknown ids are dropped, items missing from the stored list (e.g.
         * added in a newer version, or filtered out by [offered] before saving) are appended to the panel, and the
         * power key is never duplicated in the list.
         */
        fun decode(orderIds: List<String>, pinnedCount: Int, powerKeyId: String): ToolbarLayout {
            val power = ToolbarItem.byId(powerKeyId)
            val stored = orderIds.mapNotNull(ToolbarItem::byId).distinct().filter { it != power }
            val missing = ToolbarItem.entries.filter { it !in stored && it != power }
            val order = stored + missing
            return ToolbarLayout(order, pinnedCount.coerceIn(0, minOf(stored.size, ToolbarItem.MAX_PINNED)), power)
        }

        fun load(prefs: PboardPrefs) =
            decode(prefs[Prefs.toolbarOrder], prefs[Prefs.toolbarPinnedCount], prefs[Prefs.toolbarPowerKey])

        /** Current layout, then every change. */
        fun flow(prefs: PboardPrefs): Flow<ToolbarLayout> = combine(
            prefs.flow(Prefs.toolbarOrder),
            prefs.flow(Prefs.toolbarPinnedCount),
            prefs.flow(Prefs.toolbarPowerKey),
        ) { o, c, p -> decode(o, c, p) }

        fun reset(prefs: PboardPrefs) = prefs.edit {
            reset(Prefs.toolbarOrder)
            reset(Prefs.toolbarPinnedCount)
            reset(Prefs.toolbarPowerKey)
        }
    }
}
