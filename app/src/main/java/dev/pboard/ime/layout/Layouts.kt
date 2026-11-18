package dev.pboard.ime.layout

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Which page of the keyboard is showing. */
enum class Page(val id: String) {
    LETTERS("letters"), SYMBOLS("symbols"), SYMBOLS_MORE("symbols_more"), NUMPAD("numpad"),
    NUMBER("number"), NUMBER_PASSWORD("number_password"), PHONE("phone"), PHONE_SYMBOLS("phone_symbols"),
}

enum class FieldKind { TEXT, EMAIL, URL, PASSWORD, NUMBER, PHONE, DATETIME }

enum class EnterAction { RETURN, DONE, GO, SEARCH, SEND, NEXT, PREVIOUS }

/** Editor-dependent switches that select layout variants. */
data class FieldVariant(
    val kind: FieldKind = FieldKind.TEXT,
    val enter: EnterAction = EnterAction.RETURN,
    val showEmojiKey: Boolean = true,
    val numberRow: Boolean = false,
    val numberHints: Boolean = true,
    /** Gboard "Long press for symbols": a symbol in the corner of the letter keys, typed by a long press. */
    val symbolHints: Boolean = false,
    val multiLanguage: Boolean = false,
    /** Gboard 18.6 "Comma key" / "Period key" (text fields; e-mail and URL keep their @ / . keys). */
    val commaKey: Boolean = true,
    val periodKey: Boolean = true,
    /** For date/time fields: "date", "time" or "datetime". */
    val dateKind: String = "datetime",
)

class LayoutRow(val keys: List<KeySpec>, val isNumberRow: Boolean = false)

/** [tallBody]: the keyboard has the number row on, so every page gets Gboard's taller number-row body. */
class LayoutSpec(
    val page: Page, val rows: List<LayoutRow>, val columns: Float = 10f, val isPad: Boolean = false,
    val tallBody: Boolean = false,
)

/**
 * A keyboard language layout loaded from `assets/layouts/<locale>.json` plus the shared `common.json`.
 *
 * File format (JSON):
 * ```
 * { "locale": "en-US", "name": "English (US)", "shortName": "EN", "rtl": false,
 *   "pages": { "<page id>": { "rows": [ <row> ... ], "more": { "<key text>": ["alt", ...] }, "pad": false } } }
 * ```
 * A row is `{ "keys": [<key> ...], "hints": ["1", ...], "symbols": ["%", ...], "indent": 0.5 }` or
 * `{ "template": "<name>" }`
 * referring to `common.json`'s `templates`. A key is either a plain string (a character key producing that text) or
 * an object with:
 *  - `key`: produced text, or `fn`: one of shift, delete, enter, space, symbols, symbols_more, alpha, numpad, emoji,
 *    globe, comma, period, phone_symbols, phone_back
 *  - `w` width in key slots (default 1), `label`, `sub` (secondary label), `more` (long-press alternates, first =
 *    default), `style` (normal|functional|accent|plain), `shape` (rounded|pill), `out` (multi-char output),
 *    `shifted` (label/output when shifted; default is the locale's upper case),
 *    page-level `labelOffset` (px at 420 dpi) shifts character labels horizontally (phone pad),
 *    `size` (punct|fn), `if` (condition: emoji, multilang, email, url, datetime, password; ! negates, & combines), `grow` (absorbs the width of
 *    keys removed by conditions).
 * Long-press alternates: a row's `hints[i]` is shown in the key corner and becomes the default (first) alternate;
 * the page's `more` map supplies the rest. `symbols[i]` is the key's "Long press for symbols" symbol (see keyHint). The popup arrangement is computed (see PopupKeysLayout).
 */
class KeyboardLanguage(
    val locale: Locale,
    val name: String,
    val shortName: String,
    val rtl: Boolean,
    private val pages: JSONObject,
    private val common: JSONObject,
) {
    fun build(page: Page, v: FieldVariant): LayoutSpec {
        val pageJson = pages.optJSONObject(page.id) ?: common.getJSONObject("pages").optJSONObject(page.id)
            ?: common.getJSONObject("pages").getJSONObject(Page.LETTERS.id)
        val more = pageJson.optJSONObject("more") ?: JSONObject()
        val isPad = pageJson.optBoolean("pad", false)
        val labelOffset = pageJson.optDouble("labelOffset", 0.0).toFloat()
        val rowsJson = pageJson.getJSONArray("rows")
        val rows = mutableListOf<LayoutRow>()
        val hintsAllowed = v.numberHints && !v.numberRow && page == Page.LETTERS
        if (page == Page.LETTERS && v.numberRow) {
            rows += LayoutRow(
                // Gboard 18.6: a real row of letter-style keys (also in password fields).
                "1234567890".map { KeySpec(it.code, style = KeyStyle.NORMAL, labelSize = LabelSize.NUMBER_ROW) },
                isNumberRow = true,
            )
        }
        for (r in 0 until rowsJson.length()) {
            val rowJson = rowsJson.getJSONObject(r)
            val keysJson: JSONArray = rowJson.optString("template").takeIf { it.isNotEmpty() }
                ?.let { common.getJSONObject("templates").getJSONArray(it) }
                ?: rowJson.getJSONArray("keys")
            val hints = rowJson.optJSONArray("hints")
            val symbols = rowJson.optJSONArray("symbols")
            val indent = rowJson.optDouble("indent", 0.0).toFloat()
            val keys = mutableListOf<KeySpec>()
            for (i in 0 until keysJson.length()) {
                val k = keysJson.get(i)
                val obj = k as? JSONObject
                val cond = obj?.optString("if").orEmpty()
                if (cond.isNotEmpty() && !condition(cond, v)) continue
                val hint = hints?.optString(i)?.takeIf { it.isNotEmpty() }
                val symbol = symbols?.optString(i)?.takeIf { it.isNotEmpty() && page == Page.LETTERS }
                val (shown, lead) = keyHint(hint, symbol, hintsAllowed, v.symbolHints)
                keys += parseKey(k, page, isPad, v, more, shown, lead).let {
                    if (labelOffset != 0f && it.code > 0) it.copy(labelDx = labelOffset) else it
                }
            }
            // The "grow" key (space) absorbs keys removed or added by conditions so the row stays full width.
            val g = keys.indexOfFirst { it.grows }
            if (g >= 0) {
                val total = keys.sumOf { it.width.toDouble() }.toFloat()
                keys[g] = keys[g].copy(width = keys[g].width + (COLUMNS - total))
            }
            if (indent > 0f && keys.isNotEmpty()) keys[0] = keys[0].copy(leftGap = indent)
            rows += LayoutRow(keys)
        }
        return LayoutSpec(page, rows, isPad = isPad, tallBody = v.numberRow && !isPad)
    }

    private fun condition(c: String, v: FieldVariant): Boolean {
        if ('&' in c) return c.split('&').all { condition(it, v) }
        val neg = c.startsWith("!")
        val result = when (c.removePrefix("!")) {
            "emoji" -> v.showEmojiKey
            "multilang" -> v.multiLanguage
            "email" -> v.kind == FieldKind.EMAIL
            "url" -> v.kind == FieldKind.URL
            "datetime" -> v.kind == FieldKind.DATETIME && v.dateKind == "datetime"
            "date" -> v.kind == FieldKind.DATETIME && v.dateKind == "date"
            "time" -> v.kind == FieldKind.DATETIME && v.dateKind == "time"
            "password" -> v.kind == FieldKind.PASSWORD
            "comma" -> v.commaKey || v.kind == FieldKind.EMAIL || v.kind == FieldKind.URL
            "period" -> v.periodKey || v.kind == FieldKind.EMAIL || v.kind == FieldKind.URL
            else -> true
        }
        return result != neg
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    private fun parseKey(
        k: Any, page: Page, isPad: Boolean, v: FieldVariant, more: JSONObject, hint: String?, lead: List<String>,
    ): KeySpec {
        val letters = page == Page.LETTERS
        val defaultSize = when {
            isPad -> LabelSize.NUMPAD
            else -> LabelSize.LETTER
        }
        if (k is String) {
            val alts = strings(more.optJSONArray(k))
            return KeySpec(
                code = k.codePointAt(0), label = k, output = k.takeIf { it.codePointCount(0, it.length) > 1 },
                hint = hint, popup = lead + alts, labelSize = defaultSize,
                shiftedLabel = if (letters) k.uppercase(locale) else null,
            )
        }
        val o = k as JSONObject
        val w = o.optDouble("w", 1.0).toFloat()
        val shape = if (o.optString("shape") == "pill" || isPad) KeyShape.PILL else KeyShape.ROUNDED
        val styleName = o.optString("style")
        fun style(default: KeyStyle) = when (styleName) {
            "functional" -> KeyStyle.FUNCTIONAL
            "accent" -> KeyStyle.ACCENT
            "plain" -> KeyStyle.PLAIN
            "normal" -> KeyStyle.NORMAL
            else -> default
        }
        val label = o.optString("label").takeIf { o.has("label") }
        val size = when (o.optString("size")) {
            "punct" -> LabelSize.PUNCTUATION
            "fn" -> LabelSize.FUNCTION_TEXT
            else -> defaultSize
        }
        val fn = o.optString("fn")
        if (fn.isEmpty()) {
            val text = o.getString("key")
            val alts = strings(o.optJSONArray("more")) + strings(more.optJSONArray(text))
            return KeySpec(
                code = text.codePointAt(0), label = label ?: text,
                output = o.optString("out").takeIf { it.isNotEmpty() }
                    ?: text.takeIf { it.codePointCount(0, it.length) > 1 },
                hint = hint, popup = lead + alts, width = w, style = style(KeyStyle.NORMAL),
                shape = shape, subLabel = o.optString("sub").takeIf { it.isNotEmpty() }, labelSize = size,
                shiftedLabel = o.optString("shifted").takeIf { it.isNotEmpty() }
                    ?: if (letters) text.uppercase(locale) else null,
            )
        }
        val fnStyle = style(KeyStyle.FUNCTIONAL)
        return when (fn) {
            "shift" -> KeySpec(Codes.SHIFT, "", width = w, style = fnStyle, icon = KeyIcon.SHIFT)
            "delete" -> KeySpec(Codes.DELETE, "", width = w, style = fnStyle, shape = shape, icon = KeyIcon.DELETE, repeats = true)
            "enter" -> KeySpec(Codes.ENTER, "", width = w, style = style(KeyStyle.ACCENT), shape = KeyShape.PILL, icon = KeyIcon.ENTER)
            "space" -> KeySpec(Codes.SPACE, "", width = w, style = style(KeyStyle.NORMAL), shape = shape, icon = KeyIcon.SPACE,
                grows = o.optBoolean("grow"))
            "symbols" -> KeySpec(Codes.SYMBOLS, label ?: "?123", width = w, style = fnStyle, shape = shape, labelSize = LabelSize.FUNCTION_TEXT)
            "symbols_more" -> KeySpec(Codes.SYMBOLS_MORE, label ?: "=\\<", width = w, style = fnStyle, shape = shape, labelSize = LabelSize.FUNCTION_TEXT)
            "alpha" -> KeySpec(Codes.ALPHA, label ?: "ABC", width = w, style = fnStyle, shape = shape, labelSize = LabelSize.FUNCTION_TEXT)
            "numpad" -> KeySpec(Codes.NUMPAD, label ?: "12\n34", width = w, style = style(KeyStyle.NORMAL), labelSize = LabelSize.STACKED)
            "emoji" -> KeySpec(Codes.EMOJI, "", width = w, style = style(KeyStyle.NORMAL), icon = KeyIcon.EMOJI)
            "globe" -> KeySpec(Codes.LANGUAGE, "", width = w, style = fnStyle, icon = KeyIcon.GLOBE)
            "phone_symbols" -> KeySpec(Codes.PHONE_SYMBOLS, label ?: "* #", width = w, style = style(KeyStyle.NORMAL), shape = shape, labelSize = defaultSize)
            "phone_back" -> KeySpec(Codes.PHONE_BACK, label ?: "123", width = w, style = style(KeyStyle.NORMAL), shape = shape, labelSize = defaultSize)
            "comma" -> when (v.kind) {
                FieldKind.EMAIL -> KeySpec('@'.code, width = w, style = fnStyle, labelSize = LabelSize.PUNCTUATION)
                FieldKind.URL -> KeySpec('/'.code, width = w, style = fnStyle, labelSize = LabelSize.PUNCTUATION)
                else -> KeySpec(','.code, width = w, style = fnStyle, popup = listOf(POPUP_SETTINGS, POPUP_ONE_HANDED),
                    labelSize = LabelSize.PUNCTUATION)
            }
            "period" -> when (v.kind) {
                FieldKind.EMAIL, FieldKind.URL -> KeySpec('.'.code, width = w, style = fnStyle, popup = DOMAINS,
                    labelSize = LabelSize.PUNCTUATION)
                else -> KeySpec('.'.code, width = w, style = fnStyle, popup = PERIOD_MORE, labelSize = LabelSize.PUNCTUATION)
            }
            else -> error("unknown fn $fn")
        }
    }

    companion object {
        /** Special popup entries that are actions rather than text. */
        const val COLUMNS = 10f
        const val POPUP_SETTINGS = "\u0000settings"
        const val POPUP_ONE_HANDED = "\u0000onehanded"
        /** Gboard en: bottom row "; / ( ) # ! [,] ?", top row "& % + \" - : ' @" (first = default). */
        val PERIOD_MORE = listOf(",", "?", "!", "#", ")", "(", "/", ";", "'", "@", ":", "-", "\"", "+", "%", "&")
        val DOMAINS = listOf(".com", ".net", ".org", ".edu", ".gov")

        private var commonCache: JSONObject? = null

        /**
         * A letter key's corner hint and the alternates its long-press popup starts with, from the row's number hint
         * ([number], top row) and its "Long press for symbols" symbol ([symbol]). The digit is always offered on a
         * long press; the corner shows it while [numbersShown] (Touch & hold keys for numbers, number row off),
         * otherwise the symbol while [symbolsOn], which then comes first in the popup (the hinted character is the
         * default alternate, as in Gboard).
         */
        fun keyHint(number: String?, symbol: String?, numbersShown: Boolean, symbolsOn: Boolean): Pair<String?, List<String>> {
            if (number != null && numbersShown) return number to listOf(number)
            if (symbol != null && symbolsOn) return symbol to listOfNotNull(symbol, number)
            return null to listOfNotNull(number)
        }

        fun load(assets: AssetManager, tag: String): KeyboardLanguage {
            val common = commonCache ?: JSONObject(assets.open("layouts/common.json").bufferedReader().readText())
                .also { commonCache = it }
            val file = tag.replace('-', '_')
            val json = JSONObject(assets.open("layouts/$file.json").bufferedReader().readText())
            return KeyboardLanguage(
                locale = Locale.forLanguageTag(json.optString("locale", tag)),
                name = json.optString("name", tag),
                shortName = json.optString("shortName", tag.take(2).uppercase()),
                rtl = json.optBoolean("rtl", false),
                pages = json.getJSONObject("pages"),
                common = common,
            )
        }
    }
}
