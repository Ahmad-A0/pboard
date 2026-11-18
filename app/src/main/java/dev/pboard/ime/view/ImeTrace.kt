package dev.pboard.ime.view

import android.os.Trace

/**
 * System trace sections for Perfetto and the :baselineprofile macrobenchmarks. They cost a flag check unless a
 * trace is being recorded.
 * - [KEYBOARD_OPEN] (async): from the IME being asked to show its keyboard (input view created or started
 *   while hidden) to the first frame of the key surface.
 * - [section]: named spans around IME lifecycle work (service creation, view creation, view start).
 */
object ImeTrace {
    const val KEYBOARD_OPEN = "pboard:keyboardOpen"
    private const val OPEN_COOKIE = 0x70b0a

    private var opening = false

    /** The keyboard is about to be shown (main thread). */
    fun openStarted() {
        if (opening) return
        opening = true
        Trace.beginAsyncSection(KEYBOARD_OPEN, OPEN_COOKIE)
    }

    /** The key surface drew a frame (main thread). */
    fun keyboardDrawn() {
        if (!opening) return
        opening = false
        Trace.endAsyncSection(KEYBOARD_OPEN, OPEN_COOKIE)
    }

    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }
}
