package dev.pboard.ime.view

/**
 * Who owns a touch between the suggestion bar and the keys, and when key trackers are stale. Pure rules, kept apart
 * from the views so they can be unit-tested.
 */
object TouchRouting {
    /**
     * A touch in the bar below its buttons and suggestion pills belongs to the keys. Pills and buttons span
     * [buttonsBottom] at most; below that is the band above the top key row, which Gboard 18.6 types on.
     *
     * Head-to-head, TSI user10 item 278: a tap at y 1631 on the 420 dpi emulator, 12 px below the mic button and 23 px
     * above `p`. Gboard typed through it. pboard's bar resolved taps by x alone, so this one started voice typing and
     * the next items' taps were lost.
     */
    fun barTouchToKeys(barY: Float, buttonsBottom: Float): Boolean = barY > buttonsBottom

    /**
     * A first finger going down while key trackers are still live means an up or cancel never arrived (a touch stream
     * cut short): those trackers are stale and are reset, so no stuck tracker can swallow later taps.
     */
    fun staleOnFirstDown(firstPointerDown: Boolean, liveTrackers: Int): Boolean = firstPointerDown && liveTrackers > 0
}
