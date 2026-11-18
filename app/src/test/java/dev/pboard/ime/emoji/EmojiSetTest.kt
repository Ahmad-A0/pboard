package dev.pboard.ime.emoji

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the emoji panel's set (assets/emoji/emoji.txt from tools/emoji_data.py) to the rules Gboard 18.6's panel
 * follows: only fully-qualified forms, no components, skin tones and woman / man forms folded into the long-press
 * picker of one cell, Gboard's nine categories. Checked against Unicode's emoji-test.txt (third_party/unicode).
 */
class EmojiSetTest {
    private class Entry(val status: String, val group: String)

    private val unicode: Map<String, Entry> = HashMap<String, Entry>().also { m ->
        var group = ""
        File("../third_party/unicode/emoji-test.txt").forEachLine { line ->
            when {
                line.startsWith("# group:") -> group = line.substringAfter(':').trim()
                line.isBlank() || line.startsWith("#") -> {}
                else -> {
                    val (cps, rest) = line.split(';', limit = 2)
                    val emoji = cps.trim().split(' ').joinToString("") { String(Character.toChars(it.toInt(16))) }
                    m[emoji] = Entry(rest.substringBefore('#').trim(), group)
                }
            }
        }
    }

    private val categories = File("src/main/assets/emoji/emoji.txt").useLines { EmojiData.parse(it) { true } }
    private val cells = categories.flatMap { it.emoji }
    private fun cell(text: String) = cells.single { it.text == text }

    private fun String.hasSkinTone() = codePoints().anyMatch { it in 0x1F3FB..0x1F3FF }

    @Test fun categoryCounts() {
        // Unicode 18.0, before the font filter. On the Android 16 reference emulator the font leaves
        // 240 / 113 / 215 / 131 / 129 / 122 / 260 / 224 / 270 = 1704, the same 1704 emoji Gboard 18.6 lists there as
        // 248 / 114 / 223 / 132 / 122 / 117 / 266 / 260 / 270 (Google's own per-emoji groupings; 48 emoji in two tabs).
        assertEquals(listOf(245, 115, 219, 132, 130, 123, 263, 224, 270), categories.map { it.emoji.size })
        assertEquals(cells.size, cells.map { it.text }.toSet().size)
    }

    @Test fun onlyFullyQualifiedNonComponentEmoji() {
        for (e in cells.flatMap { listOf(it.text) + it.rows.flatten() }) {
            val u = unicode[e]
            assertTrue("not in emoji-test.txt: $e", u != null)
            assertEquals("qualification of $e", "fully-qualified", u!!.status)
            assertFalse("component $e", u.group == "Component")
        }
        // Unqualified / minimally-qualified twins never get a cell of their own.
        assertTrue(cells.none { it.text == "☺" || it.text == "❤" || it.text == "🕵" })
        assertTrue(cells.any { it.text == "☺️" } && cells.any { it.text == "❤️" })
    }

    @Test fun skinTonesAreNeverCellsButAllReachable() {
        assertTrue(cells.none { it.text.hasSkinTone() })
        val variants = cells.flatMap { it.rows.flatten() }
        assertEquals("a variant in two pickers", variants.size, variants.toSet().size)
        val toned = unicode.filter { (e, u) ->
            u.status == "fully-qualified" && u.group != "Component" && e.hasSkinTone()
        }
        assertEquals(emptyList<String>(), toned.keys.filter { it !in variants })
    }

    @Test fun genderedFormsFoldUnderTheNeutralOne() {
        val rows = cell("🧑").rows   // person: [person + tones], [woman + tones], [man + tones]
        assertEquals(listOf("🧑", "👩", "👨"), rows.map { it.first() })
        assertTrue(rows.all { it.size == 6 })
        for ((form, neutral) in listOf(
            "👦" to "🧒",                                  // boy -> child
            "🎅" to "🧑‍🎄",               // santa -> mx claus
            "👮‍♀️" to "👮",               // woman police officer
            "👩‍⚕️" to "🧑‍⚕️", // woman health worker
            "🚶‍➡️" to "🚶",               // person walking facing right
        )) {
            assertTrue(cells.none { it.text == form })
            assertTrue("$form under $neutral", form in cell(neutral).rows.flatten())
        }
        assertEquals(6, cell("🚶").rows.size)                       // neutral, woman, man; each also facing right
        assertEquals(listOf(listOf("🧞", "🧞‍♀️", "🧞‍♂️")),
            cell("🧞").rows)                                         // genie: no tones, one row
        // Gendered families are not listed (the gender-neutral ones are), as in Gboard.
        assertTrue(cells.none { it.text == "👪" || it.text == "👨‍👩‍👦" })
        assertTrue(cells.any { it.text == "🧑‍🧑‍🧒" })
    }

    @Test fun twoPersonEmojiGetAToneGrid() {
        // Handshake, couples, holding hands, wrestlers, bunny ears: own cells per gender, base over a 5x5 grid.
        for (e in listOf("🤝", "👫", "🧑‍🤝‍🧑",
                "🤼‍♂️", "👯‍♀️")) {
            val rows = cell(e).rows
            assertEquals(listOf(e), rows[0])
            assertEquals(List(5) { 5 }, rows.drop(1).map { it.size })
        }
        assertEquals("🤝🏽", cell("🤝").rows[3][2])   // diagonal: one-tone form
    }

    @Test fun categoriesFollowGboard() {
        fun cat(e: String) = categories.single { c -> c.emoji.any { it.text == e } }.id
        assertEquals(0, cat("👋"))          // waving hand: hands sit with the smileys
        assertEquals(1, cat("👮"))          // police officer: people
        assertEquals(2, cat("☀️"))          // sun: sky and weather with nature
        assertEquals(6, cat("⌚"))                // watch: time with objects
        assertEquals(5, cat("🎸"))          // guitar: instruments with activities
    }

    @Test fun undrawableEmojiAreHidden() {
        val lines = sequenceOf("1\tA\tA A1 A2\tW W1 W2", "1\tB\tB B1", "0\tC", "0\tD\tD D1", "1\tP\tP\tP1 X\tY P2")
        val undrawable = setOf("A", "B1", "C", "X", "Y")
        val cats = EmojiData.parse(lines) { it !in undrawable }
        val people = cats[1].emoji
        assertEquals(listOf("A1", "B", "P"), people.map { it.text })  // A not drawable: its first drawable variant
        assertEquals(listOf(listOf("A1", "A2"), listOf("W", "W1", "W2")), people[0].rows)
        assertEquals(emptyList<List<String>>(), people[1].rows)        // only B left: no picker
        assertEquals(listOf(listOf("P", "P1", "P2")), people[2].rows)  // only the grid's diagonal drawn: one row
        assertEquals(listOf("D"), cats[0].emoji.map { it.text })
    }
}
