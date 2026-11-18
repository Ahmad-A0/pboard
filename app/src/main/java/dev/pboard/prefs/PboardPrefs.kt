package dev.pboard.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

/**
 * Typed, reactive access to pboard's settings.
 *
 * Usage from the IME:
 * ```
 * val prefs = PboardPrefs.get(context)
 * val delay = prefs[Prefs.longPressDelayMs]                 // synchronous, cheap (in-memory map)
 * scope.launch { prefs.flow(Prefs.numberRow).collect { relayout(it) } } // current value, then every change
 * val handle = prefs.observe(Prefs.theme) { applyTheme(it) } // callback style for View code; handle.close()
 * ```
 *
 * Storage: one private SharedPreferences file in **device-protected** storage, so the keyboard can read its
 * settings before the first unlock (direct boot). It contains no personal data (the personal dictionary lives in
 * credential-protected storage, see [PersonalDictionary]). The app disables backup and device transfer for all
 * data (`allowBackup=false`, `data_extraction_rules.xml`).
 */
class PboardPrefs private constructor(private val sp: SharedPreferences) {

    private val changedKeys = MutableSharedFlow<String?>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Strong reference: SharedPreferences only keeps listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        changedKeys.tryEmit(key) // null key = clear()
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Keys as they change (`null` when everything was reset). */
    val changes: SharedFlow<String?> = changedKeys.asSharedFlow()

    operator fun <T : Any> get(pref: Pref<T>): T =
        try {
            pref.sanitize(pref.codec.read(sp, pref.key, pref.default))
        } catch (_: ClassCastException) {
            pref.default // stored with another type by an older build
        }

    operator fun <T : Any> set(pref: Pref<T>, value: T) {
        sp.edit().also { pref.codec.write(it, pref.key, pref.sanitize(value)) }.apply()
    }

    /** Applies several writes atomically (one change notification per key). */
    fun edit(block: Editor.() -> Unit) {
        val editor = sp.edit()
        Editor(editor).block()
        editor.apply()
    }

    inner class Editor internal constructor(private val e: SharedPreferences.Editor) {
        operator fun <T : Any> set(pref: Pref<T>, value: T) = pref.codec.write(e, pref.key, pref.sanitize(value))
        fun reset(pref: Pref<*>) {
            e.remove(pref.key)
        }
    }

    fun <T : Any> update(pref: Pref<T>, transform: (T) -> T) = set(pref, transform(get(pref)))

    /** Back to the default value. */
    fun reset(pref: Pref<*>) = sp.edit().remove(pref.key).apply()

    /** True if the user (or code) has stored a value, i.e. it is not following the default. */
    fun isSet(pref: Pref<*>): Boolean = sp.contains(pref.key)

    /** Current value followed by every subsequent change. Conflating, distinct, never completes. */
    fun <T : Any> flow(pref: Pref<T>): Flow<T> =
        changedKeys
            .onSubscription { emit(pref.key) } // after subscribing, so no change can slip in between
            .filter { it == null || it == pref.key }
            .map { get(pref) }
            .distinctUntilChanged()

    /** Callback-style observation for non-coroutine code. Called on the main thread; close the handle to stop. */
    fun <T : Any> observe(pref: Pref<T>, callback: (T) -> Unit): AutoCloseable {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == pref.key) callback(get(pref))
        }
        sp.registerOnSharedPreferenceChangeListener(l)
        return AutoCloseable { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }

    companion object {
        private const val FILE = "pboard_settings"

        @Volatile
        private var instance: PboardPrefs? = null

        fun get(context: Context): PboardPrefs =
            instance ?: synchronized(this) {
                instance ?: run {
                    val dp = context.applicationContext.createDeviceProtectedStorageContext()
                    val sp = dp.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    PrefsSchema.migrate(sp)
                    PboardPrefs(sp).also { instance = it }
                }
            }

        /** For tests: wrap an arbitrary SharedPreferences. */
        fun forTesting(sp: SharedPreferences) = PboardPrefs(sp)
    }
}

/**
 * Schema version of the settings store, and the migrations between versions. Updates keep the store, so a change
 * that renames or removes a key, or changes a key's stored type or encoding, must bump [CURRENT] and add a migration
 * (the next entry of [migrations]) that rewrites old values. Adding a key needs neither (absent = default).
 * app/src/test/resources/prefs/keys_v<CURRENT>.txt freezes each version's keys and types (PrefsSchemaTest).
 *
 * History: 0 = written before the store was versioned (identical to 1); 1 = baseline (2026-11).
 */
internal object PrefsSchema {
    const val KEY = "prefs_schema_version"
    const val CURRENT = 1

    /** migrations[i] upgrades a store at version i to i + 1. */
    private val migrations: List<(SharedPreferences, SharedPreferences.Editor) -> Unit> = listOf(
        { _, _ -> }, // 0 -> 1: no key changes, the version is just recorded
    )

    init {
        check(migrations.size == CURRENT)
    }

    /** Brings [sp] up to [CURRENT]; a store from a NEWER version (after a downgrade) is left untouched. */
    fun migrate(sp: SharedPreferences): Int {
        val from = runCatching { sp.getInt(KEY, 0) }.getOrDefault(0)
        if (from >= CURRENT) return from
        val e = sp.edit()
        for (v in from until CURRENT) migrations[v](sp, e)
        e.putInt(KEY, CURRENT)
        e.commit()
        return CURRENT
    }
}
