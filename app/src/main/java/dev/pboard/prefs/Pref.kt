package dev.pboard.prefs

import android.content.SharedPreferences

/**
 * A typed preference entry: its storage [key], its [default] (Gboard's default unless documented otherwise) and a
 * codec. Entries are declared once in [Prefs] and read/written through [PboardPrefs].
 */
open class Pref<T : Any> internal constructor(
    val key: String,
    val default: T,
    internal val codec: Codec<T>,
) {
    /** Clamp/normalise a value before it is stored or after it is read. */
    internal open fun sanitize(value: T): T = value

    override fun toString(): String = "Pref($key)"
}

/** Integer preference with an inclusive range and slider step (used by slider dialogs). */
class IntPref internal constructor(key: String, default: Int, val min: Int, val max: Int, val step: Int = 1) :
    Pref<Int>(key, default, Codec.IntCodec) {
    override fun sanitize(value: Int): Int = value.coerceIn(min, max)
}

/** Float preference with an inclusive range. */
class FloatPref internal constructor(key: String, default: Float, val min: Float, val max: Float) :
    Pref<Float>(key, default, Codec.FloatCodec) {
    override fun sanitize(value: Float): Float = value.coerceIn(min, max)
}

/** Enum preference, persisted by the stable [StableId.id] of each constant (never by ordinal or name). */
class EnumPref<E> internal constructor(key: String, default: E, val values: List<E>) :
    Pref<E>(key, default, Codec.EnumCodec(values, default)) where E : Enum<E>, E : StableId

/** Implemented by enums stored in preferences so renaming a constant never breaks stored values. */
interface StableId {
    val id: String
}

internal sealed interface Codec<T : Any> {
    fun read(sp: SharedPreferences, key: String, default: T): T
    fun write(editor: SharedPreferences.Editor, key: String, value: T)

    object BoolCodec : Codec<Boolean> {
        override fun read(sp: SharedPreferences, key: String, default: Boolean) = sp.getBoolean(key, default)
        override fun write(editor: SharedPreferences.Editor, key: String, value: Boolean) {
            editor.putBoolean(key, value)
        }
    }

    object IntCodec : Codec<Int> {
        override fun read(sp: SharedPreferences, key: String, default: Int) = sp.getInt(key, default)
        override fun write(editor: SharedPreferences.Editor, key: String, value: Int) {
            editor.putInt(key, value)
        }
    }

    object LongCodec : Codec<Long> {
        override fun read(sp: SharedPreferences, key: String, default: Long) = sp.getLong(key, default)
        override fun write(editor: SharedPreferences.Editor, key: String, value: Long) {
            editor.putLong(key, value)
        }
    }

    object FloatCodec : Codec<Float> {
        override fun read(sp: SharedPreferences, key: String, default: Float) = sp.getFloat(key, default)
        override fun write(editor: SharedPreferences.Editor, key: String, value: Float) {
            editor.putFloat(key, value)
        }
    }

    object StringCodec : Codec<String> {
        override fun read(sp: SharedPreferences, key: String, default: String) = sp.getString(key, null) ?: default
        override fun write(editor: SharedPreferences.Editor, key: String, value: String) {
            editor.putString(key, value)
        }
    }

    /** Ordered list of strings. Stored as one string; items must not contain [SEP]. */
    object StringListCodec : Codec<List<String>> {
        const val SEP = '\u001F' // ASCII unit separator: never typed by users, keeps order (unlike StringSet)

        override fun read(sp: SharedPreferences, key: String, default: List<String>): List<String> {
            val raw = sp.getString(key, null) ?: return default
            return if (raw.isEmpty()) emptyList() else raw.split(SEP)
        }

        override fun write(editor: SharedPreferences.Editor, key: String, value: List<String>) {
            editor.putString(key, value.joinToString(SEP.toString()))
        }
    }

    class EnumCodec<E>(private val values: List<E>, private val fallback: E) : Codec<E>
        where E : Enum<E>, E : StableId {
        override fun read(sp: SharedPreferences, key: String, default: E): E {
            val id = sp.getString(key, null) ?: return default
            return values.firstOrNull { it.id == id } ?: fallback
        }

        override fun write(editor: SharedPreferences.Editor, key: String, value: E) {
            editor.putString(key, value.id)
        }
    }

    /** Values with their own string encoding (e.g. [KeyboardGeometry]). */
    class StringEncoded<T : Any>(private val encode: (T) -> String, private val decode: (String) -> T?) : Codec<T> {
        override fun read(sp: SharedPreferences, key: String, default: T): T =
            sp.getString(key, null)?.let(decode) ?: default

        override fun write(editor: SharedPreferences.Editor, key: String, value: T) {
            editor.putString(key, encode(value))
        }
    }
}
