package dev.pboard.prefs

import android.content.SharedPreferences

/** Minimal synchronous in-memory SharedPreferences for JVM tests. */
internal class FakeSharedPreferences : SharedPreferences {
    private val map = HashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = if (key in map) map[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = (map[key] as MutableSet<String>?) ?: defValues
    override fun getInt(key: String, defValue: Int) = if (key in map) map[key] as Int else defValue
    override fun getLong(key: String, defValue: Long) = if (key in map) map[key] as Long else defValue
    override fun getFloat(key: String, defValue: Float) = if (key in map) map[key] as Float else defValue
    override fun getBoolean(key: String, defValue: Boolean) = if (key in map) map[key] as Boolean else defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += l
    }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= l
    }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val puts = LinkedHashMap<String, Any?>()
        val removes = mutableSetOf<String>()
        var clear = false
        override fun putString(k: String, v: String?) = apply { puts[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { puts[k] = v }
        override fun putInt(k: String, v: Int) = apply { puts[k] = v }
        override fun putLong(k: String, v: Long) = apply { puts[k] = v }
        override fun putFloat(k: String, v: Float) = apply { puts[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v }
        override fun remove(k: String) = apply { removes += k }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply(); return true
        }
        override fun apply() {
            if (clear) map.clear()
            removes.forEach { map.remove(it) }
            map.putAll(puts)
            (removes + puts.keys).forEach { k -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, k) } }
        }
    }
}
