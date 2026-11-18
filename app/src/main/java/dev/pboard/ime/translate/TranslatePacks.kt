package dev.pboard.ime.translate

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.net.Uri
import dev.pboard.translate.ModelSpec
import dev.pboard.translate.PackManifest
import dev.pboard.translate.PackStorage
import dev.pboard.translate.TranslateFormatException
import dev.pboard.translate.Translators
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Installed translation packs. pboard has no network access, so models arrive in one of two ways (docs/translate.md):
 *
 *  1. **Pack APK** - a code-free APK (`android:hasCode="false"`) named `dev.pboard.pack.translate.<id>`, signed with the
 *     same key as pboard, holding the pack under `assets/translate/`. Read in place through [Context.createPackageContext];
 *     nothing is copied. Only the package names in [PACK_IDS] are visible to pboard (they are listed in `<queries>`).
 *  2. **Pack file** - a zip with `manifest.json` at its root, picked by the user with the system file picker
 *     (Settings › Translate › Import). It is checked, then extracted into app-private, no-backup storage.
 *
 * Packs only ever contain data. Nothing from a pack is executed, and every file is parsed defensively.
 */
class TranslatePacks private constructor(private val ctx: Context) {
    /** Where a pack came from. */
    enum class Source { APK, IMPORTED }

    class Installed(val manifest: PackManifest, val source: Source, val location: String, val storage: PackStorage) {
        val id: String get() = manifest.id
    }

    private val importDir = File(ctx.noBackupFilesDir, "translate")

    /** All usable packs; an imported pack overrides a pack APK with the same id only if its version is newer. */
    fun installed(): List<Installed> {
        val byId = LinkedHashMap<String, Installed>()
        for (p in apkPacks() + importedPacks()) {
            val old = byId[p.id]
            if (old == null || p.manifest.version > old.manifest.version) byId[p.id] = p
        }
        return byId.values.sortedBy { it.manifest.name }
    }

    /** The pack and model for one direction, preferring the newest pack that has it. */
    fun find(from: String, to: String): Pair<Installed, ModelSpec>? =
        installed().sortedByDescending { it.manifest.version }
            .firstNotNullOfOrNull { p -> p.manifest.model(from, to)?.let { p to it } }

    /** Every direction available, as (from, to). */
    fun directions(): Set<Pair<String, String>> = installed().flatMap { p -> p.manifest.models.map { it.from to it.to } }.toSet()

    private fun apkPacks(): List<Installed> {
        val pm = ctx.packageManager
        return PACK_IDS.mapNotNull { id ->
            val pkg = PACKAGE_PREFIX + id
            try {
                pm.getPackageInfo(pkg, 0)
                if (pm.checkSignatures(ctx.packageName, pkg) != PackageManager.SIGNATURE_MATCH) return@mapNotNull null
                val assets = ctx.createPackageContext(pkg, 0).assets
                val manifest = PackManifest.parse(assets.open("$ASSET_ROOT/${PackManifest.FILE}").use { it.readBytes().decodeToString() })
                Installed(manifest, Source.APK, pkg, AssetStorage(assets))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun importedPacks(): List<Installed> =
        importDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.mapNotNull { dir ->
            try {
                val manifest = PackManifest.parse(File(dir, PackManifest.FILE).readText())
                if (manifest.id != dir.name) null else Installed(manifest, Source.IMPORTED, dir.path, PackStorage.Directory(dir))
            } catch (e: IOException) {
                null
            }
        }.orEmpty()

    /**
     * Imports a pack file (zip) from [uri]: extracts it into a temporary folder with size and path limits, parses the
     * manifest, loads every model once to prove it works, then replaces any earlier import of the same pack.
     * Call off the main thread (it reads and checks ~30-45 MB per model).
     */
    fun import(uri: Uri): PackManifest = ctx.contentResolver.openInputStream(uri)?.use { import(it) }
        ?: throw IOException("cannot open $uri")

    fun import(input: InputStream): PackManifest {
        importDir.mkdirs()
        val tmp = File(importDir, ".import-" + System.nanoTime())
        try {
            unzip(input, tmp)
            // accept a single top-level folder holding the pack as well as files at the root
            val root = if (File(tmp, PackManifest.FILE).isFile) tmp
                else tmp.listFiles()?.singleOrNull { it.isDirectory && File(it, PackManifest.FILE).isFile }
                    ?: throw TranslateFormatException("no ${PackManifest.FILE} in the pack")
            val manifest = PackManifest.parse(File(root, PackManifest.FILE).readText())
            val storage = PackStorage.Directory(root)
            for (m in manifest.models) {
                for (f in m.files) if (!File(root, f).isFile) throw TranslateFormatException("pack lacks $f")
                Translators.load(storage, m).close()
            }
            val dest = File(importDir, manifest.id)
            val old = File(importDir, ".old-" + System.nanoTime())
            if (dest.exists() && !dest.renameTo(old)) throw IOException("cannot replace ${dest.name}")
            if (!root.renameTo(dest)) { old.renameTo(dest); throw IOException("cannot install ${dest.name}") }
            old.deleteRecursively()
            return manifest
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Removes an imported pack (pack APKs are uninstalled through the system). */
    fun delete(id: String): Boolean {
        val dir = File(importDir, id)
        return dir.parentFile == importDir && dir.deleteRecursively()
    }

    private fun unzip(input: InputStream, into: File) {
        into.mkdirs()
        var total = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw TranslateFormatException("too many files in the pack")
                val name = e.name.trimEnd('/')
                if (name.isEmpty()) continue
                if (!PackManifest.isSafePath(name)) throw TranslateFormatException("unsafe path in pack: ${e.name}")
                val f = File(into, name)
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                f.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PACK_BYTES) throw TranslateFormatException("pack too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** A pack APK's `assets/translate/` folder. */
    private class AssetStorage(private val assets: AssetManager) : PackStorage {
        override fun open(path: String): InputStream {
            require(PackManifest.isSafePath(path)) { "unsafe pack path: $path" }
            return assets.open("$ASSET_ROOT/$path", AssetManager.ACCESS_STREAMING)
        }
    }

    companion object {
        const val PACKAGE_PREFIX = "dev.pboard.pack.translate."
        const val ASSET_ROOT = "translate"
        private const val MAX_ENTRIES = 256
        private const val MAX_PACK_BYTES = 1L shl 30

        /**
         * Pack APK ids pboard looks for. Must match the `<queries>` list in AndroidManifest.xml (Android 11+ package
         * visibility); language codes of the Firefox Translations models that pair with English.
         */
        val PACK_IDS = listOf(
            "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "es", "et", "fa", "fi", "fr", "gu", "he",
            "hi", "hr", "hu", "id", "is", "it", "ja", "kn", "ko", "lt", "lv", "ml", "ms", "mt", "nb", "nl", "nn", "pl",
            "pt", "ro", "ru", "sk", "sl", "sq", "sr", "sv", "ta", "te", "th", "tr", "uk", "vi", "zh",
        )

        @Volatile private var instance: TranslatePacks? = null
        fun get(ctx: Context): TranslatePacks =
            instance ?: synchronized(this) { instance ?: TranslatePacks(ctx.applicationContext).also { instance = it } }
    }
}
