package dev.pboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Conformance (LT-11 caps-lock-new-field): after caps lock, the app restarting input (or a new field) must not leave
 * the keyboard locked; Gboard starts the session unshifted and then applies auto-capitalisation.
 */
class ShiftStateTest {
    @Test fun appRestartsAndNewFieldsStartUnshifted() {
        for (s in ShiftState.entries) {
            assertEquals(ShiftState.OFF, ShiftState.atStartInput(s, imeInternal = false))
        }
    }

    @Test fun imeInternalRestartsKeepTheState() {
        for (s in ShiftState.entries) {
            assertEquals(s, ShiftState.atStartInput(s, imeInternal = true))
        }
    }
}
