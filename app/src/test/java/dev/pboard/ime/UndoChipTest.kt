package dev.pboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TP-16: Gboard 18.6 offers "Undo" when a held delete empties the field ("Have thiscould" held to empty). */
class UndoChipTest {
    @Test fun heldDeleteThatEmptiesTheFieldOffersItsText() {
        assertEquals("Have thiscould", UndoChip.afterHold("Have thiscould", "", "", password = false))
        assertEquals("Have thiscould", UndoChip.afterHold("Have thiscould", null, null, password = false))
    }

    @Test fun noChipWhenTextIsLeftOrInPasswords() {
        assertNull(UndoChip.afterHold("Have thiscould", "Hav", "", password = false))
        assertNull(UndoChip.afterHold("Have thiscould", "", " more", password = false))
        assertNull(UndoChip.afterHold("secret", "", "", password = true))
        assertNull(UndoChip.afterHold("", "", "", password = false))
        assertNull(UndoChip.afterHold(null, "", "", password = false))
    }
}
