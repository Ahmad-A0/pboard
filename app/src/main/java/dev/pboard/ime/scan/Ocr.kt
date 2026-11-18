package dev.pboard.ime.scan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * On-device text recognition for "Scan text": Tesseract 5 with the `tessdata_fast` English LSTM model, both living in
 * the separately installed scan pack (dev.pboard.pack.scan, module :scanpack), so pboard itself carries no OCR code
 * (~13 MB of native libraries per ABI).
 *
 * The pack is used only when it is signed with pboard's own key; its engine is loaded with a class loader of its own
 * (parent: the framework, so the pack's Kotlin and JavaCPP classes never mix with pboard's) and called by reflection
 * with plain arrays and strings. The model is copied out of the pack once per pack install. Nothing leaves the device.
 *
 * Engine chosen by measurement (tools/scan/README.md).
 */
object Ocr {
    const val PACK = "dev.pboard.pack.scan"
    private const val ENGINE = "dev.pboard.pack.scan.ScanEngine"
    private const val API = 1
    private const val MODEL_ASSET = "ocr/eng.traineddata"

    @Volatile private var engine: Pair<Long, Method>? = null

    /** The installed pack, if it is pboard's (same signing key) and has code for this device's ABI. */
    fun pack(context: Context): ApplicationInfo? {
        val pm = context.packageManager
        val ai = runCatching { pm.getApplicationInfo(PACK, 0) }.getOrNull() ?: return null
        if (pm.checkSignatures(context.packageName, PACK) != PackageManager.SIGNATURE_MATCH) return null
        val libs = ai.nativeLibraryDir?.let { File(it) } ?: return null
        return ai.takeIf { File(libs, "libjnitesseract.so").isFile }
    }

    fun available(context: Context): Boolean = pack(context) != null

    /** Recognises the text in [image] (any size; scaled down to at most [MAX_SIDE] px), lines separated by '\n'. */
    fun recognize(context: Context, image: Bitmap): String {
        val ai = pack(context) ?: error("scan pack not installed")
        val stamp = runCatching { context.packageManager.getPackageInfo(PACK, 0).lastUpdateTime }.getOrDefault(0L)
        val dir = modelDir(context, stamp)
        val bmp = scaled(image)
        val w = bmp.width
        val h = bmp.height
        val gray = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                gray[y * w + x] = ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000).toByte()
            }
        }
        val m = engine(ai, stamp)
        return tidy(m.invoke(null, gray, w, h, dir.absolutePath, "eng") as String)
    }

    @Synchronized
    private fun engine(ai: ApplicationInfo, stamp: Long): Method {
        engine?.let { (s, m) -> if (s == stamp) return m }
        val loader = PathClassLoader(ai.sourceDir, ai.nativeLibraryDir, ClassLoader.getSystemClassLoader())
        val cls = loader.loadClass(ENGINE)
        val api = cls.getField("API").getInt(null)
        check(api == API) { "scan pack API $api, expected $API" }
        val m = cls.getMethod("recognize", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java)
        engine = stamp to m
        return m
    }

    /** Collapses runs of blank lines and trailing spaces; keeps line breaks (the user reviews before inserting). */
    fun tidy(raw: String): String = raw.replace('\u000c', '\n')
        .lines().map { it.trimEnd() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun scaled(b: Bitmap): Bitmap {
        val s = MAX_SIDE.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    /** Tesseract reads its model from a file: copied out of the pack's assets once per pack install. */
    @Synchronized
    private fun modelDir(context: Context, stamp: Long): File {
        val dir = File(context.noBackupFilesDir, "ocr/tessdata").apply { mkdirs() }
        val f = File(dir, "eng.traineddata")
        val version = File(dir, "version")
        if (!f.isFile || runCatching { version.readText() }.getOrNull() != stamp.toString()) {
            val packAssets = context.createPackageContext(PACK, 0).assets
            val tmp = File(dir, "eng.traineddata.tmp")
            packAssets.open(MODEL_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(f)
            version.writeText(stamp.toString())
        }
        return dir
    }

    const val MAX_SIDE = 2400
}
