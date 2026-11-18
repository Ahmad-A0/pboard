package dev.pboard.ime.engine

import android.content.Context
import dev.pboard.engine.KeyboardEngine
import dev.pboard.engine.LanguageSpec
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.decode.UserDataCodec
import dev.pboard.engine.lm.EnglishPack
import dev.pboard.engine.lm.LmPack
import dev.pboard.engine.lm.NeuralLm
import dev.pboard.engine.lm.UserNgramModel
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Builds the decoding engine ([PboardEngine]) over the production language packs: one shared English base
 * (`assets/lm/en.lm`, stored uncompressed and memory-mapped straight out of the APK) plus a small dialect
 * overlay per subtype (`en_GB.lmo`, `en_US.lmo`; en-AU/NZ/IE/IN use the British overlay, en-CA and plain "en"
 * the American one), plus the shared LSTM re-ranker (`assets/lm/en.nnlm`).
 *
 * The engine owns user learning: one [UserNgramModel] per process, shared by every dialect, fed by the engine's
 * commit hooks and persisted through [KeyboardEngine.exportUserData] in app-private no-backup storage. Learned
 * data never leaves the device.
 */
object Engines {
    @Volatile private var base: LmPack? = null
    private val overlays = HashMap<String, ByteArray>()

    /** The single user model (learned words and phrases), shared across dialect switches. */
    private val userModel = UserNgramModel()

    /** Maps the base pack once per process; pages are pre-loaded so first keystrokes do not fault them in. */
    private fun base(context: Context): LmPack = base ?: synchronized(this) {
        base ?: context.assets.openFd(EnglishPack.BASE_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                val mapped: MappedByteBuffer = input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                mapped.load()
                LmPack.wrap(mapped)
            }
        }.also { base = it }
    }

    private fun overlay(context: Context, tag: String): ByteArray = synchronized(overlays) {
        val asset = EnglishPack.overlayAsset(tag)
        overlays.getOrPut(asset) { context.assets.open(asset).use { it.readBytes() } }
    }

    @Volatile private var neural: NeuralLm? = null

    /**
     * The LSTM language model (`assets/lm/en.nnlm`, ~5.6 MB), read once per process and shared by every dialect.
     * The engine uses it to re-rank each word's final candidates and the next-word predictions
     * ([dev.pboard.engine.decode.EngineConfig.neuralRerankTopN]); searches and live previews stay on the n-gram model.
     */
    private fun neural(context: Context): NeuralLm? = neural ?: synchronized(this) {
        neural ?: runCatching { context.assets.open(EnglishPack.NEURAL_ASSET).use { NeuralLm.read(it.readBytes()) } }
            .getOrNull()?.also { neural = it }
    }

    /** The language spec for a subtype locale (maps/loads the pack on first use: call off the main thread). */
    fun spec(context: Context, locale: Locale): LanguageSpec {
        val tag = locale.toLanguageTag()
        return EnglishPack.load(tag, base(context), overlay(context, tag), userModel, neural(context)).spec()
    }

    /** A new engine for the active subtype, with the user's learned data restored. */
    fun create(context: Context, locale: Locale): KeyboardEngine =
        PboardEngine.create(listOf(spec(context, locale))).also { loadUserData(context, it) }

    // --- learned data ---

    // Storage: noBackupFilesDir (credential-protected, app-private, excluded from backup/transfer). App updates keep it;
    // only an uninstall (or "Clear storage") removes it. The bytes are UserDataCodec's versioned format.
    private fun userDataFile(context: Context) = File(context.noBackupFilesDir, USER_DATA)

    private fun loadUserData(context: Context, engine: KeyboardEngine) {
        val f = userDataFile(context)
        if (!f.exists()) return
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return
        if (bytes.isEmpty() || runCatching { engine.importUserData(bytes) }.getOrDefault(false)) return
        // Not understood: corrupt, or written by a NEWER pboard (a downgrade). Move it aside instead of letting the
        // next save overwrite it, so a later (fixed or newer) version can still recover it.
        quarantine(context, f, UserDataCodec.versionOf(bytes))
    }

    /** Renames an unreadable user-data file to `user_data.v<N>.unreadable[.k]`, never replacing an earlier one. */
    private fun quarantine(context: Context, f: File, version: Int?) {
        val base = "user_data.v${version ?: "unknown"}.unreadable"
        var target = File(context.noBackupFilesDir, base)
        var k = 1
        while (target.exists()) target = File(context.noBackupFilesDir, "$base.${k++}")
        f.renameTo(target)
    }

    private const val USER_DATA = "user_data.bin"

    /** Writes the engine's learned data (atomically) to app-private, no-backup storage. */
    fun saveUserData(context: Context, engine: KeyboardEngine) {
        val data = engine.exportUserData()
        if (data.isEmpty()) return
        val f = userDataFile(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeBytes(data)
            tmp.renameTo(f)
        }
    }

    // --- backup and restore (Settings > Dictionary), see dev.pboard.prefs.LearnedDataBackup ---

    /**
     * The running keyboard's engine thread, while the IME service is alive (it shares this process with Settings).
     * Backup and restore go through it so they see and change what the keyboard has in memory, not only the file.
     */
    @Volatile var live: EngineRunner? = null

    /** Runs [work] on the live engine's thread and waits for it; null when no engine is live or it did not answer. */
    private fun <T : Any> onLiveEngine(work: (KeyboardEngine) -> T): T? {
        val runner = live?.takeIf { it.engine != null } ?: return null
        val done = java.util.concurrent.CountDownLatch(1)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        var result: T? = null
        runner.run("user-data") { e ->
            if (!abandoned.get()) { result = runCatching { work(e) }.getOrNull() }
            done.countDown()
        }
        if (!done.await(LIVE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)) {
            abandoned.set(true)
            // The work may be running right now: wait for it rather than report a change that then happens anyway.
            if (!done.await(LIVE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)) return null
        }
        return result
    }

    private const val LIVE_TIMEOUT_S = 10L

    /** Guards the stored file against a backup and a restore (or two restores) started at once from Settings. */
    private val fileLock = Any()

    /**
     * The learned data as it is now (UserDataCodec bytes), for a backup: from the running engine, else from the
     * stored file. Null when nothing has been learned yet (or the stored file is unreadable). Blocking: not for the
     * main thread.
     */
    fun currentUserData(context: Context): ByteArray? = synchronized(fileLock) {
        val bytes = onLiveEngine { it.exportUserData() } ?: runCatching { userDataFile(context).takeIf { it.exists() }?.readBytes() }.getOrNull()
        bytes?.takeIf { it.isNotEmpty() && UserDataCodec.decode(it) != null }
    }

    /**
     * Restores [incoming] (UserDataCodec bytes that passed [UserDataCodec.decodeStrict]): merged into what has been
     * learned ([merge]) or replacing it. All or nothing: on any failure the engine and the stored file are left as
     * they were and false is returned. Blocking: not for the main thread.
     */
    fun restoreUserData(context: Context, incoming: ByteArray, merge: Boolean): Boolean = synchronized(fileLock) {
        if (UserDataCodec.decodeStrict(incoming) == null) return false
        val app = context.applicationContext
        if (live?.engine != null) {
            return onLiveEngine { e ->
                val before = e.exportUserData()
                val target = (if (merge) UserDataCodec.mergeEncoded(before, incoming) else incoming) ?: return@onLiveEngine false
                if (!merge) e.clearUserData()
                val ok = runCatching { e.importUserData(target) }.getOrDefault(false)
                if (ok) writeUserData(app, target) else if (!merge) e.importUserData(before)
                ok
            } ?: false
        }
        // No keyboard running: change the stored file; the next engine loads it.
        val f = userDataFile(app)
        val stored = if (f.exists()) runCatching { f.readBytes() }.getOrNull() ?: return false else ByteArray(0)
        val readable = stored.isEmpty() || UserDataCodec.decode(stored) != null
        val target = when {
            merge -> if (readable) UserDataCodec.mergeEncoded(stored, incoming) else null // never merge into what we cannot read
            else -> incoming
        } ?: return false
        if (!readable) quarantine(app, f, UserDataCodec.versionOf(stored))
        return writeUserData(app, target)
    }

    private fun writeUserData(context: Context, data: ByteArray): Boolean {
        val f = userDataFile(context)
        val tmp = File(f.parentFile, f.name + ".restore.tmp")
        return runCatching { tmp.writeBytes(data); tmp.renameTo(f) }.getOrDefault(false).also { if (!it) tmp.delete() }
    }

    /** "Delete learned words and data": forgets everything the engine learned and deletes the stored copy. */
    fun clearUserData(context: Context, engine: KeyboardEngine?) {
        engine?.clearUserData()
        userDataFile(context).delete()
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("learned_") || f.name.startsWith("user_data.") }
            ?.forEach { it.delete() }
    }
}
