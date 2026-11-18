package dev.pboard.translate.pack

import dev.pboard.translate.TranslateFormatException

/**
 * Minimal JSON reader for pack manifests (objects, arrays, strings, numbers, booleans, null). Produces Map / List /
 * String / Double / Boolean / null. Strict: rejects trailing garbage, limits nesting depth.
 */
internal class Json private constructor(private val s: String) {
    private var p = 0

    companion object {
        fun parse(text: String): Any? {
            val j = Json(text)
            val v = j.value(0)
            j.ws()
            if (j.p != text.length) j.fail("trailing characters")
            return v
        }
    }

    private fun fail(msg: String): Nothing = throw TranslateFormatException("manifest JSON: $msg at $p")

    private fun ws() {
        while (p < s.length && (s[p] == ' ' || s[p] == '\n' || s[p] == '\r' || s[p] == '\t')) p++
    }

    private fun value(depth: Int): Any? {
        if (depth > 32) fail("too deep")
        ws()
        if (p >= s.length) fail("unexpected end")
        return when (val c = s[p]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        if (!s.startsWith(word, p)) fail("bad literal")
        p += word.length
        return v
    }

    private fun num(): Double {
        val st = p
        if (s[p] == '-') p++
        while (p < s.length && (s[p].isDigit() || s[p] in ".eE+-")) p++
        return s.substring(st, p).toDoubleOrNull() ?: fail("bad number")
    }

    private fun str(): String {
        p++ // opening quote
        val b = StringBuilder()
        while (true) {
            if (p >= s.length) fail("unterminated string")
            val c = s[p++]
            when (c) {
                '"' -> return b.toString()
                '\\' -> {
                    if (p >= s.length) fail("bad escape")
                    when (val e = s[p++]) {
                        '"', '\\', '/' -> b.append(e)
                        'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                        'u' -> {
                            if (p + 4 > s.length) fail("bad \\u escape")
                            b.append(s.substring(p, p + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                            p += 4
                        }
                        else -> fail("bad escape")
                    }
                }
                else -> if (c < ' ') fail("control character in string") else b.append(c)
            }
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        p++
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (p < s.length && s[p] == '}') { p++; return m }
        while (true) {
            ws()
            if (p >= s.length || s[p] != '"') fail("expected key")
            val k = str()
            ws()
            if (p >= s.length || s[p] != ':') fail("expected ':'")
            p++
            m[k] = value(depth + 1)
            ws()
            if (p >= s.length) fail("unterminated object")
            if (s[p] == ',') { p++; continue }
            if (s[p] == '}') { p++; return m }
            fail("expected ',' or '}'")
        }
    }

    private fun arr(depth: Int): List<Any?> {
        p++
        val l = ArrayList<Any?>()
        ws()
        if (p < s.length && s[p] == ']') { p++; return l }
        while (true) {
            l.add(value(depth + 1))
            ws()
            if (p >= s.length) fail("unterminated array")
            if (s[p] == ',') { p++; continue }
            if (s[p] == ']') { p++; return l }
            fail("expected ',' or ']'")
        }
    }
}
