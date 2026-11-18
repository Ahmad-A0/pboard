package dev.pboard.engine.testutil

import dev.pboard.engine.decode.TextRules

/** English helpers for tests and the eval harness (the engine itself has no English assumptions). */
object En {
    val rules: TextRules = TextRules.forLanguageTag("en")

    /** Keys typed for [word] on an English letter layout (skeleton), or null if it needs other keys. */
    fun keys(word: String): String? = rules.skeleton(word).takeIf { s -> s.isNotEmpty() && s.all { it in 'a'..'z' } }
}
