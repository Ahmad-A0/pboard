package dev.pboard.ime.emoji

/**
 * Which word the strip's emoji are for while a word is being typed.
 *
 * Measured on Gboard 18.6 (farm pool device, 14 words typed key by key, strip read after every key and after space):
 *  - never at the first letter;
 *  - for the typed word as soon as it is a word with emoji ("Cat" -> 🐈 😺, "Dog", "Sad", "Fire", "Car", "Home"),
 *    whatever it later becomes;
 *  - for the autocorrection in the centre (bold: "Lov" -> **Love**, "Hap" -> **Happy**, "Hom" -> **Home**);
 *  - never for a function word at the start of a longer one ("Do" shows Don't, not 📋 ⛔);
 *  - after space, the finished word's emoji stay among the predictions.
 * A plain completion of a prefix gets no emoji, and the strip keeps three words: "Hel" shows "Hel | Hello | Help"
 * (visual pass, 2026-11-09), "Than" shows "Thanks | Thank", "Happ" shows "Happy | Happened". In 18.6 the emoji for
 * a word candidate are ranked against the word candidates by score.
 * So a completion sometimes wins the slot ("Piz" -> 🍕, "Cof" -> ☕), but less often than a third word.
 */
object EmojiStrip {
    /**
     * Two-letter words that get emoji of their own. Other two-letter CLDR keywords are abbreviations or fragments
     * ("pi" for 👲, "do" for 📋, "up", "at", "id", "dr"), which as typed text are mostly the start of a longer word:
     * Gboard showed none at "Pi", "Do", "Sa", "He", "Ho", "Li", "Fi", "Co" or "Th".
     */
    private val SHORT_WORDS = setOf("hi", "ok", "oh", "ew", "yo")

    /** Whether [word] may have emoji of its own in the strip (typed, or just finished before a space). */
    fun ownsEmoji(word: String): Boolean = word.length >= 3 || word.lowercase() in SHORT_WORDS

    /**
     * The word to offer emoji for while [typed] is being composed, given the [centre] candidate (null when the
     * strip has none) and whether the centre is the autocorrection that space will commit ([correcting]);
     * [hasEmoji] says whether a word has any (stop words and unknown words have none). Null: no emoji.
     */
    fun wordWhileTyping(typed: String, centre: String?, correcting: Boolean, hasEmoji: (String) -> Boolean): String? {
        if (typed.length < 2) return null
        if (correcting && centre != null) return centre.takeIf { ownsEmoji(it) && hasEmoji(it) }
        if (ownsEmoji(typed) && hasEmoji(typed)) return typed
        return null
    }
}
