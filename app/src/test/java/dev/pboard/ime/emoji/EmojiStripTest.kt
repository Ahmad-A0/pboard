package dev.pboard.ime.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Strip emoji while typing, pinned to Gboard 18.6's behaviour as measured on a farm pool device (each word typed
 * key by key into a "default" field, the strip read after every key and after space). See [EmojiStrip].
 */
class EmojiStripTest {
    private val annotations = EmojiAnnotations.parse(
        listOf(File("src/main/assets/emoji/annotations_en.txt").readLines().asSequence()),
        File("src/main/assets/emoji/frequency.txt").readLines().asSequence(),
    )
    private val hasEmoji: (String) -> Boolean = { annotations.suggest(it).isNotEmpty() }

    private fun word(typed: String, centre: String?, correcting: Boolean = false) =
        EmojiStrip.wordWhileTyping(typed, centre, correcting, hasEmoji)

    @Test fun noEmojiAtTheFirstLetter() {
        assertNull(word("P", "Please"))
        assertNull(word("L", "Lol"))
        assertNull(word("D", "Do"))
    }

    @Test fun typedWordWithEmojiGetsThem() {
        assertEquals("Cat", word("Cat", "Car")) // Gboard: Cat | Car | 🐈 😺
        assertEquals("Dog", word("Dog", "Dig"))
        assertEquals("Sad", word("Sad", "Dad"))
        assertEquals("Fire", word("Fire", "Fireworks"))
        assertEquals("Pizza", word("Pizza", "Pizzas"))
        assertEquals("Hello", word("Hello", "Jello"))
    }

    @Test fun theAutocorrectionGetsEmojiAPlainCompletionDoesNot() {
        assertEquals("Happy", word("Hap", "Happy", correcting = true)) // Gboard: Hap | **Happy** | 😊 😁
        assertEquals("Love", word("Lov", "Love", correcting = true)) // Gboard: Lov | **Love** | 💕 😘
        assertNull(word("Hel", "Hello")) // Gboard: Hel | Hello | Help (three words)
        assertNull(word("Than", "Thanks"))
        assertNull(word("Piz", "Pizza"))
    }

    @Test fun noEmojiForPrefixesOrFunctionWords() {
        assertNull(word("Do", "Don't")) // Gboard: Do | So | Don't (pboard showed 📋 ⛔)
        assertNull(word("Pi", "Pick"))
        assertNull(word("Ca", "Can"))
        assertNull(word("Fir", "For")) // Gboard: Fir | For | First
        assertNull(word("Tha", "That's"))
        assertNull(word("Thank", "Thanks")) // Gboard: Thank | Thanks | Thankfully
        assertNull(word("List", "Lost")) // Gboard: List | Lost | Listen (pboard showed 📋)
        assertNull(word("Ca", "Cat")) // two letters: too early for a completion's emoji
        assertNull(word("Up", "Upon")) // a two-letter CLDR keyword (⬆️ and 20 more), mostly the start of a word
        assertEquals("Hi", word("Hi", "Hi")) // a whole message on its own
    }

    @Test fun emojiForWords() {
        // The named emoji first, then keyword matches by popularity (Emoji Sentiment Ranking counts).
        assertEquals("🐈", annotations.suggest("cat")[0]) // Gboard: 🐈 😺
        assertEquals(listOf("🐕", "🐶"), annotations.suggest("dog")) // Gboard: 🐶 🐕
        assertEquals(listOf("🍕"), annotations.suggest("pizza")) // Gboard: 🍕 alone (not the newer mushroom)
        assertEquals(listOf("☕"), annotations.suggest("coffee"))
        assertEquals(listOf("🚗", "🚨"), annotations.suggest("car")) // Gboard: 🚗 🚨 (was 🚃 🚞)
        assertEquals(setOf("🏠", "🏡"), annotations.suggest("home").toSet()) // Gboard: 🏠 🏡 (was 🪹 🛖)
        assertEquals(listOf("⭐", "✨"), annotations.suggest("star")) // Gboard: ⭐ ✨
        assertEquals(emptyList<String>(), annotations.suggest("do"))
        assertEquals(emptyList<String>(), annotations.suggest("can"))
    }
}
