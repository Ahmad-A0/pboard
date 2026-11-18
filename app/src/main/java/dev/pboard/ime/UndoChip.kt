package dev.pboard.ime

/** Gboard 18.6's "Undo" chip after deleting text (ED-02 delete swipe, TP-16 held delete). */
object UndoChip {
    /**
     * The text a held delete's Undo chip puts back: everything that was before the cursor when the hold began, offered
     * only when the hold emptied the field (nothing before or after the cursor any more) and never in password fields.
     */
    fun afterHold(startText: String?, beforeNow: CharSequence?, afterNow: CharSequence?, password: Boolean): String? =
        startText?.takeIf { it.isNotEmpty() && !password && beforeNow.isNullOrEmpty() && afterNow.isNullOrEmpty() }
}
