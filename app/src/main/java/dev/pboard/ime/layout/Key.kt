package dev.pboard.ime.layout

import android.graphics.RectF

/** Key codes. Positive values are Unicode code points; negative values are keyboard functions. */
object Codes {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -10       // "?123": letters -> symbols page 1
    const val ALPHA = -11         // "ABC": back to letters
    const val SYMBOLS_MORE = -12  // "=\<": symbols page 2
    const val NUMPAD = -13        // "12 34": numeric pad from the symbols pages
    const val EMOJI = -14
    const val LANGUAGE = -15
    const val PHONE_SYMBOLS = -17 // "* #" on the phone pad
    const val PHONE_BACK = -18    // "123" back from phone symbols
    const val NONE = 0
    const val SPACE = ' '.code
}

enum class KeyStyle {
    /** Letter/character key: surface colour, label colour. */
    NORMAL,
    /** Shift, delete, ?123, comma/period: secondary container. */
    FUNCTIONAL,
    /** Enter. */
    ACCENT,
    /** Keys drawn without a background (e.g. the password number row). */
    PLAIN,
}

enum class KeyShape { ROUNDED, PILL }

enum class KeyIcon { SHIFT, DELETE, ENTER, EMOJI, SPACE, GLOBE, NONE }

enum class LabelSize { LETTER, PUNCTUATION, FUNCTION_TEXT, NUMPAD, STACKED, NUMBER_ROW }

/** Static description of a key within a layout row. [width] is in key-slot units (a letter key = 1). */
data class KeySpec(
    val code: Int,
    val label: String = if (code > 0) String(Character.toChars(code)) else "",
    val hint: String? = null,
    val popup: List<String> = emptyList(),
    val width: Float = 1f,
    val style: KeyStyle = KeyStyle.NORMAL,
    val shape: KeyShape = KeyShape.ROUNDED,
    val icon: KeyIcon = KeyIcon.NONE,
    /** Text to commit when it differs from the code point (multi-character keys). */
    val output: String? = null,
    /** Secondary label drawn beside the main one (phone pad letters). */
    val subLabel: String? = null,
    val labelSize: LabelSize = LabelSize.LETTER,
    /** Label (and output) in the shifted state; null = same as unshifted. */
    val shiftedLabel: String? = null,
    /** Whether this key repeats while held (delete). */
    val repeats: Boolean = false,
    /** Gap (in slots) to leave before this key. */
    val leftGap: Float = 0f,
    /** Absorbs the width of keys removed by layout conditions (the space bar). */
    val grows: Boolean = false,
    /** Horizontal label offset in reference pixels (phone pad digits sit left of centre). */
    val labelDx: Float = 0f,
)

/** A positioned key. [rect] is the visible key cap; [hit] is the touch area (key cap plus half the gaps). */
class Key(val spec: KeySpec, val rect: RectF, val hit: RectF, val row: Int, val index: Int) {
    val code get() = spec.code
    val isCharacter get() = spec.code > 0 && spec.code != Codes.SPACE
    val isLetter get() = spec.code > 0 && Character.isLetter(spec.code)

    /** Output for a (possibly shifted) press. */
    fun text(shifted: Boolean): String {
        if (shifted && spec.shiftedLabel != null) return spec.shiftedLabel
        spec.output?.let { return it }
        if (spec.code <= 0) return ""
        return String(Character.toChars(spec.code))
    }

    fun label(shifted: Boolean): String = if (shifted && spec.shiftedLabel != null) spec.shiftedLabel else spec.label

    /** Lower-case label used for the engine's geometry (e.g. "a", "'"). */
    val geometryLabel: String get() = spec.output ?: spec.label
}
