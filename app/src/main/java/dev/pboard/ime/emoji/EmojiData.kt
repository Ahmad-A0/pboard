package dev.pboard.ime.emoji

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.util.Log

/**
 * One emoji-panel cell: [text] is what the grid shows, [rows] the long-press picker (skin tones; for people also
 * the woman / man forms, and the facing-right forms; for two-person emoji a 5x5 tone grid under the base),
 * top row first. Empty when the emoji has no variants.
 */
class Emoji(val text: String, val rows: List<List<String>> = emptyList())

class EmojiCategory(val id: Int, val title: String, val emoji: List<Emoji>)

/**
 * The emoji panel's cells (assets/emoji/emoji.txt, built by tools/emoji_data.py from Unicode's emoji-test.txt),
 * filtered to what the system emoji font can draw. Recently used emoji are remembered in app-private storage.
 */
object EmojiData {
    val TITLES = listOf(
        "Smileys and emotions", "People", "Animals and nature", "Food and drink", "Travel and places",
        "Activities", "Objects", "Symbols", "Flags",
    )

    @Volatile private var categories: List<EmojiCategory>? = null

    fun categories(context: Context): List<EmojiCategory> = categories ?: load(context).also { categories = it }

    /** What emoji search may offer: the cells and their woman / man forms (no skin-tone forms, as in the grid). */
    fun searchable(categories: List<EmojiCategory>): Set<String> {
        fun toned(v: String) = v.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF }
        return categories.flatMapTo(HashSet()) { c -> c.emoji.flatMap { e -> listOf(e.text) + e.rows.flatten().filterNot(::toned) } }
    }

    private fun load(context: Context): List<EmojiCategory> {
        val paint = Paint()
        val cats = context.assets.open("emoji/emoji.txt").bufferedReader().useLines { parse(it, paint::hasGlyph) }
        val pickers = cats.sumOf { c -> c.emoji.count { it.rows.isNotEmpty() } }
        Log.i("EmojiData", "cells ${cats.map { it.emoji.size }}, $pickers with variants")
        return cats
    }

    /**
     * Parses emoji.txt ("<category>\t<emoji>[\t<row of space-separated variants>...]"), keeping what [drawable] says
     * the font can show (Gboard does the same with Paint.hasGlyph): undrawable variants and rows are dropped, a cell
     * whose own emoji is undrawable shows its first drawable variant instead, a cell with nothing drawable is dropped.
     */
    fun parse(lines: Sequence<String>, drawable: (String) -> Boolean): List<EmojiCategory> {
        val groups = List(TITLES.size) { ArrayList<Emoji>() }
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val g = parts[0].toIntOrNull()?.takeIf { it in groups.indices } ?: continue
            var rows = parts.drop(2).map { r -> r.split(' ').filter { it.isNotEmpty() && drawable(it) } }
                .filter { it.isNotEmpty() }
            // A tone grid the font only draws the diagonal of (two people, one tone each) is one row, as in Gboard.
            if (rows.size > 1 && rows.all { it.size == 1 }) rows = listOf(rows.flatten())
            val text = parts[1].takeIf(drawable) ?: rows.firstOrNull()?.first() ?: continue
            groups[g] += Emoji(text, if (rows.sumOf { it.size } > 1) rows else emptyList())
        }
        return groups.mapIndexed { i, list -> EmojiCategory(i, TITLES[i], list) }
    }

    // --- recents ---
    private const val PREFS = "emoji"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 27

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recents(context: Context): List<String> =
        prefs(context).getString(KEY_RECENT, "").orEmpty().split('\u0001').filter { it.isNotEmpty() }

    fun clearRecents(context: Context) = prefs(context).edit().remove(KEY_RECENT).apply()

    fun addRecent(context: Context, emoji: String) {
        val list = recents(context).toMutableList()
        list.remove(emoji)
        list.add(0, emoji)
        prefs(context).edit().putString(KEY_RECENT, list.take(MAX_RECENT).joinToString("\u0001")).apply()
    }
}
