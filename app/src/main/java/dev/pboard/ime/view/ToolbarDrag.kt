package dev.pboard.ime.view

/**
 * In-keyboard toolbar customisation (Gboard "Hold and drag to customize"): the bar and the "more" panel report a
 * long-press drag of an item in screen coordinates; the host works out the drop target and applies the
 * [dev.pboard.ime.toolbar.ToolbarLayout] rules.
 */
interface ToolbarDragHost {
    fun dragStart(id: String, fromBar: Boolean, rawX: Float, rawY: Float)
    fun dragMove(rawX: Float, rawY: Float)
    fun dragEnd(rawX: Float, rawY: Float)
}

/** Long-press delay before a tile lifts for dragging. */
const val TOOLBAR_DRAG_DELAY_MS = 400L
