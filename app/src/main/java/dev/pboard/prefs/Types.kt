package dev.pboard.prefs

/** Preferences › Layout › One-handed mode. */
/**
 * Where Smart Compose shows inline completions. Gboard 18.6 limits it to an allow-list of messaging apps; pboard
 * scopes it by the field itself: [MESSAGES] = short/long message fields and multi-line sentence-capitalised fields
 * without an enter action (chat compose boxes, e-mail bodies).
 */
enum class SmartComposeScope(override val id: String) : StableId {
    MESSAGES("messages"),
    EVERYWHERE("everywhere"),
    OFF("off"),
}

enum class OneHandedMode(override val id: String) : StableId {
    OFF("off"),
    RIGHT("right"),
    LEFT("left"),
}

/**
 * Toolbar › Split (large screens). [AUTO] follows Gboard 18.6's default: split from 800 dp smallest width, unsplit on
 * 600-799 dp (foldables, small tablets); the toolbar item stores the user's explicit [ON] / [OFF].
 */
enum class SplitMode(override val id: String) : StableId {
    AUTO("auto"),
    ON("on"),
    OFF("off"),
}

/**
 * Preferences › Key press › Flick input sensitivity. [distanceMultiplier] scales the slide distance needed to trigger
 * a flick (higher = less sensitive). Values measured from Gboard.
 */
enum class FlickSensitivity(override val id: String, val distanceMultiplier: Float) : StableId {
    LOW("low", 3.0f),
    MID_LOW("mid_low", 1.5f),
    NORMAL("normal", 1.0f),
    MID_HIGH("mid_high", 0.8f),
    HIGH("high", 0.5f),
}

/** Write in text fields › Handwriting speed: pause (ms) after the last stroke before text is recognised. */
enum class HandwritingSpeed(override val id: String, val timeoutMs: Int) : StableId {
    VERY_SLOW("very_slow", 2000),
    SLOW("slow", 1500),
    MID_SLOW("mid_slow", 1200),
    NORMAL("normal", 1000),
    MID_FAST("mid_fast", 700),
    FAST("fast", 500),
    VERY_FAST("very_fast", 300),
}

/** Write in text fields › Handwriting stroke width multiplier. */
enum class StrokeWidth(override val id: String, val scale: Float) : StableId {
    EXTRA_THIN("extra_thin", 0.4f),
    THIN("thin", 0.6f),
    MID_THIN("mid_thin", 0.8f),
    NORMAL("normal", 1.0f),
    MID_THICK("mid_thick", 1.2f),
    THICK("thick", 1.5f),
    EXTRA_THICK("extra_thick", 2.0f),
}

/** Keyboard layout mode; each mode keeps its own [KeyboardGeometry] per [ScreenOrientation]. */
enum class KeyboardMode(override val id: String) : StableId {
    NORMAL("normal"),
    ONE_HANDED("one_handed"),
    FLOATING("floating"),
}

enum class ScreenOrientation(override val id: String) : StableId {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
}

/**
 * Size and position of the keyboard in one [KeyboardMode] × [ScreenOrientation], edited by the resize overlay.
 *
 * @property heightRatio multiplier on the default key-area height. Gboard allows roughly 0.7×–1.65× in normal mode
 *   (measured on a 1080×2400 phone); see [MIN_HEIGHT_RATIO]/[MAX_HEIGHT_RATIO].
 * @property widthRatio fraction of the available width (1 in normal mode, ≈0.8 one-handed, ≈0.83 floating).
 * @property horizontalBias 0 = flush left … 1 = flush right (one-handed side is still [Prefs.oneHandedMode]).
 * @property bottomOffsetDp lift above the bottom edge (normal: bottom resize handle; floating: window position).
 */
data class KeyboardGeometry(
    val heightRatio: Float = 1f,
    val widthRatio: Float = 1f,
    val horizontalBias: Float = 0.5f,
    val bottomOffsetDp: Float = 0f,
) {
    fun sanitized() = KeyboardGeometry(
        heightRatio.coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO),
        widthRatio.coerceIn(MIN_WIDTH_RATIO, 1f),
        horizontalBias.coerceIn(0f, 1f),
        bottomOffsetDp.coerceAtLeast(0f),
    )

    internal fun encode() = "$heightRatio,$widthRatio,$horizontalBias,$bottomOffsetDp"

    companion object {
        const val MIN_HEIGHT_RATIO = 0.7f
        const val MAX_HEIGHT_RATIO = 1.65f
        const val MIN_WIDTH_RATIO = 0.5f

        fun defaultFor(mode: KeyboardMode) = when (mode) {
            KeyboardMode.NORMAL -> KeyboardGeometry()
            KeyboardMode.ONE_HANDED -> KeyboardGeometry(widthRatio = 0.8f, horizontalBias = 1f)
            KeyboardMode.FLOATING -> KeyboardGeometry(widthRatio = 0.83f, horizontalBias = 0.5f, bottomOffsetDp = 96f)
        }

        internal fun decode(s: String): KeyboardGeometry? {
            val p = s.split(',').mapNotNull { it.toFloatOrNull() }
            if (p.size != 4) return null
            return KeyboardGeometry(p[0], p[1], p[2], p[3]).sanitized()
        }
    }
}
