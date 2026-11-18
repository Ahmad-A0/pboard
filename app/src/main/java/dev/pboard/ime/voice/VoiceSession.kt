package dev.pboard.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/** 16 kHz mono float audio, read in blocking pieces. */
interface AudioSource {
    fun start()
    /** Fills [buf]; returns the number of samples read, or a negative value when the source has ended. */
    fun read(buf: FloatArray): Int
    fun stop()
}

/** The microphone, through the platform's speech-tuned input (AGC/noise suppression as for any recogniser). */
class MicSource : AudioSource {
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission") // checked by the caller (PboardIme.openVoice) before every session
    override fun start() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // BUFFER_SECONDS of float audio: the microphone opens before the model has loaded (the user can start speaking
        // at once), and the recogniser catches up from this buffer.
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, RATE * BUFFER_SECONDS * 4))
        check(r.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        r.startRecording()
        rec = r
    }

    override fun read(buf: FloatArray): Int = rec?.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) ?: -1

    override fun stop() {
        rec?.let { runCatching { it.stop() }; it.release() }
        rec = null
    }

    companion object {
        const val RATE = 16000
        const val BUFFER_SECONDS = 8
    }
}

/**
 * Debug builds only (dev.pboard.DEBUG voiceWav): a 16 kHz mono 16-bit WAV file played in real time instead of the
 * microphone, so voice typing can be checked on devices and emulators without speaking to them.
 */
class WavSource(private val file: File) : AudioSource {
    private var raf: RandomAccessFile? = null
    private var t0 = 0L
    private var sent = 0L

    override fun start() {
        val f = RandomAccessFile(file, "r")
        f.seek(44)
        raf = f
        t0 = SystemClock.uptimeMillis()
        sent = 0
    }

    override fun read(buf: FloatArray): Int {
        val f = raf ?: return -1
        val bytes = ByteArray(buf.size * 2)
        val n = f.read(bytes)
        if (n <= 0) return -1
        val samples = n / 2
        for (i in 0 until samples) {
            val s = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
            buf[i] = s.toShort() / 32768f
        }
        sent += samples
        val due = t0 + sent * 1000 / MicSource.RATE
        val wait = due - SystemClock.uptimeMillis()
        if (wait > 0) Thread.sleep(wait)
        return samples
    }

    override fun stop() {
        runCatching { raf?.close() }
        raf = null
    }
}

/**
 * The audio of the utterance being spoken, kept for the accurate pack's final re-decode: appended piece by piece,
 * taken (and cleared) at each endpoint. Utterances longer than [maxSamples] skip the final pass.
 */
class UtteranceAudio(private val maxSamples: Int = 30 * MicSource.RATE) {
    private var buf = FloatArray(4 * MicSource.RATE)
    private var n = 0
    private var overflow = false

    fun append(x: FloatArray, len: Int) {
        if (overflow) return
        if (n + len > maxSamples) { overflow = true; return }
        if (n + len > buf.size) buf = buf.copyOf(maxOf(n + len, minOf(buf.size * 2, maxSamples)))
        System.arraycopy(x, 0, buf, n, len)
        n += len
    }

    /** The utterance so far (a copy), or null when empty or too long; clears for the next utterance. */
    fun take(): FloatArray? {
        val r = if (overflow || n == 0) null else buf.copyOf(n)
        n = 0
        overflow = false
        return r
    }

    companion object {
        /**
         * The text committed for an utterance: the accurate pack's when it produced one, else the streaming model's.
         * Silence the streaming model heard nothing in stays empty (no final pass on noise).
         */
        fun pick(streaming: String, accurate: String?): String =
            if (streaming.isBlank()) streaming else accurate?.takeIf { it.isNotBlank() } ?: streaming
    }
}

/**
 * One voice-typing session: audio in 100 ms pieces from [source] into the pack's streaming recogniser on a worker
 * thread, results posted to [main]. Utterances end at the recogniser's endpoints (about 1 s of silence after speech);
 * the session stops itself after [IDLE_STOP_MS] without new speech, or when [stop] is called.
 */
class VoiceSession(
    private val engine: VoicePack.Engine,
    private val source: AudioSource,
    private val main: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** The model is loaded and audio is flowing. */
        fun onListening()
        /** Running text of the current utterance (replaces the previous partial). */
        fun onPartial(text: String)
        /** Final text of an utterance; the next partial starts a new utterance. */
        fun onFinal(text: String)
        /** Input level, 0..1, about ten times a second. */
        fun onLevel(level: Float)
        /** The session ended (stopped, idle, or failed with [error]). */
        fun onStopped(error: String?)
    }

    @Volatile private var running = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null
    /** How long the model took to load in this session (0 when it was still warm). */
    @Volatile var loadMs = 0L
        private set

    fun start() {
        running = true
        thread = Thread({ run() }, "pboard-voice").also { it.start() }
    }

    /** Stops listening; the last utterance is flushed and delivered as [Listener.onFinal] before [Listener.onStopped]. */
    fun stop() { running = false }

    /** Stops without delivering anything more (the keyboard went away). */
    fun cancel() {
        cancelled = true
        running = false
    }

    /** Waits up to [ms] for the worker to end (the pack's engine serves one session at a time). */
    fun join(ms: Long) { runCatching { thread?.join(ms) } }

    private fun post(block: () -> Unit) { if (!cancelled) main.post { if (!cancelled) block() } }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var error: String? = null
        var started = false
        try {
            // The microphone first: what is said while the model loads (first use, or after it was released) waits in
            // the audio buffer and is recognised once the model is ready.
            source.start()
            started = true
            val t0 = SystemClock.uptimeMillis()
            engine.load()
            loadMs = SystemClock.uptimeMillis() - t0
            if (!running) return
            engine.begin()
            post { listener.onListening() }
            val utterance = if (engine.hasFinal) UtteranceAudio() else null
            fun finalText(streaming: String): String {
                val audio = utterance?.take() ?: return streaming
                if (streaming.isBlank()) return streaming
                return UtteranceAudio.pick(streaming, runCatching { engine.decodeFinal(audio, audio.size) }.getOrNull())
            }
            val buf = FloatArray(MicSource.RATE / 10)
            var last = ""
            var lastSpeech = SystemClock.uptimeMillis()
            while (running) {
                val n = source.read(buf)
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i]
                val level = (sqrt(sum / n) * 8).toFloat().coerceIn(0f, 1f)
                post { listener.onLevel(level) }
                utterance?.append(buf, n)
                val partial = engine.accept(buf, n)
                if (partial != last) {
                    last = partial
                    lastSpeech = SystemClock.uptimeMillis()
                    post { listener.onPartial(partial) }
                }
                engine.endpoint()?.let { streaming ->
                    last = ""
                    val text = finalText(streaming)
                    post { listener.onFinal(text) }
                }
                if (SystemClock.uptimeMillis() - lastSpeech > IDLE_STOP_MS && last.isEmpty()) break
            }
            if (!cancelled) {
                val text = finalText(engine.finish())
                post { listener.onFinal(text) }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            if (started) runCatching { source.stop() }
            val e = error
            post { listener.onStopped(e) }
        }
    }

    companion object {
        /** Gboard closes the microphone after a few seconds of silence. */
        const val IDLE_STOP_MS = 8000L
    }
}
