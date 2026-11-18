package dev.pboard.ime.voice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * Voice typing's speech recogniser lives in the separately installed voice pack (dev.pboard.pack.voice.en, module
 * :voicepack): the engine chosen in docs/voice-eval.md (X-ASR 480 ms streaming transducer on sherpa-onnx), ~200 MB, so
 * pboard itself carries no speech code or model and needs no network to get one.
 *
 * As with the scan pack (dev.pboard.ime.scan.Ocr), the pack is used only when it is signed with pboard's own key; its
 * engine is loaded with a class loader of its own (parent: the framework) and called by reflection with arrays, strings
 * and the pack's AssetManager. Audio never leaves the process.
 */
object VoicePack {
    const val PACK = "dev.pboard.pack.voice.en"
    /**
     * Optional data-only pack (module :voicepackaccurate): NVIDIA parakeet-tdt_ctc-110m (CC-BY-4.0) for a final
     * re-decode of each utterance on the voice pack's engine (API 2). Used when installed, off otherwise.
     */
    const val ACCURATE_PACK = "dev.pboard.pack.voice.en.accurate"
    private const val ENGINE = "dev.pboard.pack.voice.VoiceEngine"
    /** Oldest engine API this pboard works with; the final pass needs [FINAL_API]. */
    private const val MIN_API = 1
    private const val FINAL_API = 2

    /** The installed pack, if it is pboard's (same signing key) and has code for this device's ABI. */
    fun pack(context: Context): ApplicationInfo? {
        val pm = context.packageManager
        val ai = runCatching { pm.getApplicationInfo(PACK, 0) }.getOrNull() ?: return null
        if (pm.checkSignatures(context.packageName, PACK) != PackageManager.SIGNATURE_MATCH) return null
        val libs = ai.nativeLibraryDir?.let { File(it) } ?: return null
        return ai.takeIf { File(libs, "libsherpa-onnx-jni.so").isFile }
    }

    fun available(context: Context): Boolean = pack(context) != null

    /** The accurate pack, if installed and signed with pboard's key. */
    fun accurate(context: Context): Boolean {
        val pm = context.packageManager
        if (runCatching { pm.getApplicationInfo(ACCURATE_PACK, 0) }.getOrNull() == null) return false
        return pm.checkSignatures(context.packageName, ACCURATE_PACK) == PackageManager.SIGNATURE_MATCH
    }

    /** The pack's engine (reflection handles; the native model is loaded by [Engine.load]). */
    class Engine internal constructor(cls: Class<*>, private val assets: AssetManager, api: Int,
                                      private val finalAssets: AssetManager?) {
        private val load: Method = cls.getMethod("load", AssetManager::class.java)
        private val begin: Method = cls.getMethod("begin")
        private val accept: Method = cls.getMethod("accept", FloatArray::class.java, Int::class.javaPrimitiveType)
        private val endpoint: Method = cls.getMethod("endpoint")
        private val finish: Method = cls.getMethod("finish")
        private val release: Method = cls.getMethod("release")
        private val loadFinal: Method? =
            if (api >= FINAL_API) cls.getMethod("loadFinal", AssetManager::class.java) else null
        private val decodeFinal: Method? =
            if (api >= FINAL_API) cls.getMethod("decodeFinal", FloatArray::class.java, Int::class.javaPrimitiveType) else null
        /** Whether a final pass will run (accurate pack installed and the engine supports it). */
        val hasFinal: Boolean = finalAssets != null && loadFinal != null

        /** Loads the model (seconds; worker thread only). */
        fun load() {
            load.invoke(null, assets)
            if (hasFinal) loadFinal!!.invoke(null, finalAssets)
        }
        fun begin() { begin.invoke(null) }
        /** Feeds audio and returns the running text of the current utterance. */
        fun accept(samples: FloatArray, n: Int): String = accept.invoke(null, samples, n) as String
        /** The final text of an utterance the recogniser just closed (trailing silence), else null. */
        fun endpoint(): String? = endpoint.invoke(null) as String?
        /** Flushes after the user stopped; the last utterance's final text. */
        fun finish(): String = finish.invoke(null) as String
        fun release() { release.invoke(null) }
        /** The accurate pack's text for one whole utterance ("" without a final pass). */
        fun decodeFinal(samples: FloatArray, n: Int): String =
            if (hasFinal) decodeFinal!!.invoke(null, samples, n) as String else ""
    }

    @Volatile private var engine: Pair<Long, Engine>? = null
    /** The engine class per voice-pack install: its native library can be opened by one class loader only. */
    private var engineClass: Pair<Long, Class<*>>? = null

    /** The engine of the installed pack (cached per install of the voice pack and of the accurate pack). */
    @Synchronized
    fun engine(context: Context): Engine {
        val ai = pack(context) ?: error("voice pack not installed")
        val pm = context.packageManager
        val acc = accurate(context)
        val packStamp = runCatching { pm.getPackageInfo(PACK, 0).lastUpdateTime }.getOrDefault(0L)
        val stamp = packStamp * 31 +
            (if (acc) runCatching { pm.getPackageInfo(ACCURATE_PACK, 0).lastUpdateTime }.getOrDefault(1L) else 0L)
        engine?.let { (s, e) -> if (s == stamp) return e }
        val cls = engineClass?.takeIf { it.first == packStamp }?.second ?: run {
            val loader = PathClassLoader(ai.sourceDir, ai.nativeLibraryDir, ClassLoader.getSystemClassLoader())
            loader.loadClass(ENGINE).also { engineClass = packStamp to it }
        }
        val api = cls.getField("API").getInt(null)
        check(api >= MIN_API) { "voice pack API $api, expected $MIN_API or later" }
        val finalAssets = if (acc) context.createPackageContext(ACCURATE_PACK, 0).assets else null
        val e = Engine(cls, context.createPackageContext(PACK, 0).assets, api, finalAssets)
        engine = stamp to e
        return e
    }
}
