package dev.pboard.prefs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings survive app updates: stores written by older builds keep their values, and a key is never renamed,
 * removed or given another stored type without a schema bump and a migration ([PrefsSchema]).
 */
class PrefsSchemaTest {
    private fun kind(p: Pref<*>): String = when (val c = p.codec) {
        Codec.BoolCodec -> "bool"
        Codec.IntCodec -> "int"
        Codec.LongCodec -> "long"
        Codec.FloatCodec -> "float"
        Codec.StringCodec -> "string"
        Codec.StringListCodec -> "list"
        is Codec.EnumCodec<*> -> "enum"
        is Codec.StringEncoded<*> -> "encoded"
        else -> error("unknown codec $c")
    }

    private fun current(): Map<String, String> = Prefs.entries.associate { it.key to kind(it) }

    /** keys_v<N>.txt: `key<TAB>kind` per line, the keys a store at schema N may contain. */
    private fun frozen(version: Int): Map<String, String>? =
        javaClass.getResourceAsStream("/prefs/keys_v$version.txt")?.bufferedReader()?.readLines()
            ?.filter { it.isNotBlank() && !it.startsWith("#") }
            ?.associate { line -> line.split('\t').let { it[0] to it[1] } }

    @Test
    fun keysOfTheCurrentSchemaKeepTheirNameAndType() {
        val frozen = frozen(PrefsSchema.CURRENT)
        assertNotNull("app/src/test/resources/prefs/keys_v${PrefsSchema.CURRENT}.txt is missing (see writeKeys)", frozen)
        val now = current()
        val broken = frozen!!.filter { (k, kind) -> now[k] != kind }
        assertTrue(
            "keys renamed, removed or retyped without a PrefsSchema bump + migration: " +
                broken.map { (k, kind) -> "$k ($kind -> ${now[k] ?: "gone"})" },
            broken.isEmpty(),
        )
    }

    @Test
    fun aStoreFromBeforeVersioningMigratesWithItsValues() {
        // As a build from before PrefsSchema existed (schema 0) left it: no version key, values in their encodings.
        val sp = FakeSharedPreferences()
        sp.edit()
            .putString("enabled_languages", "en-GB:qwerty\u001Fde-DE:qwertz")
            .putBoolean("number_row", true)
            .putString("one_handed_mode", "left")
            .putInt("long_press_delay_ms", 450)
            .putString("theme", "custom_1730000000000")
            .putString("custom_themes", "custom_1730000000000")
            .putString("geometry_normal_portrait", "1.2,1.0,0.5,8.0")
            .putLong("learned_data_clear_request", 1_700_000_000_000L)
            .putBoolean("rounded_keys", true)
            .commit()
        assertEquals(PrefsSchema.CURRENT, PrefsSchema.migrate(sp))
        assertEquals(PrefsSchema.CURRENT, sp.getInt(PrefsSchema.KEY, -1))
        val prefs = PboardPrefs.forTesting(sp)
        assertEquals(listOf("en-GB:qwerty", "de-DE:qwertz"), prefs[Prefs.enabledLanguages])
        assertTrue(prefs[Prefs.numberRow])
        assertEquals(OneHandedMode.LEFT, prefs[Prefs.oneHandedMode])
        assertEquals(450, prefs[Prefs.longPressDelayMs])
        assertEquals("custom_1730000000000", prefs[Prefs.theme])
        assertEquals(listOf("custom_1730000000000"), prefs[Prefs.customThemes])
        assertEquals(KeyboardGeometry(1.2f, 1f, 0.5f, 8f), prefs[Prefs.geometry(KeyboardMode.NORMAL, ScreenOrientation.PORTRAIT)])
        assertEquals(1_700_000_000_000L, prefs[Prefs.learnedDataClearRequest])
        assertTrue(prefs[Prefs.roundedKeys])
        // Idempotent.
        assertEquals(PrefsSchema.CURRENT, PrefsSchema.migrate(sp))
    }

    @Test
    fun aStoreFromANewerVersionIsLeftUntouched() {
        val sp = FakeSharedPreferences()
        sp.edit().putInt(PrefsSchema.KEY, PrefsSchema.CURRENT + 5).putString("some_future_key", "x").putBoolean("number_row", true).commit()
        val before = sp.all
        assertEquals(PrefsSchema.CURRENT + 5, PrefsSchema.migrate(sp))
        assertEquals(before, sp.all)
        assertTrue(PboardPrefs.forTesting(sp)[Prefs.numberRow])
    }

    /** Writes keys_v<CURRENT>.txt when it does not exist yet: PBOARD_WRITE_PREFS_KEYS=1 ./gradlew :app:testDebugUnitTest */
    @Test
    fun writeKeys() {
        if (System.getenv("PBOARD_WRITE_PREFS_KEYS") == null) return
        val f = File("src/test/resources/prefs/keys_v${PrefsSchema.CURRENT}.txt")
        if (f.exists()) return
        f.parentFile.mkdirs()
        f.writeText(
            "# Keys and stored types of settings schema ${PrefsSchema.CURRENT} (dev.pboard.prefs.PrefsSchema). Frozen: never edit.\n" +
                current().toSortedMap().entries.joinToString("") { "${it.key}\t${it.value}\n" },
        )
    }
}
