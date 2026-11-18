package dev.pboard.pack.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * The speech-recognition entry point pboard calls (by reflection, through its own class loader for this APK, so only
 * framework types cross the boundary): a streaming transducer (X-ASR 480 ms, punctuated and cased, int8) on
 * sherpa-onnx, fed 16 kHz mono float audio in small pieces, returning the running text of the current utterance.
 * One recogniser and one stream at a time; calls are serialised.
 *
 * Measured on the dev phone (docs/voice-eval.md): real-time factor 0.17 on one thread, first words about 1.0-1.2 s
 * after speech starts, the last word about 0.7 s after it ends.
 *
 * API 2 adds an optional final pass: an offline model from the separately installed accurate pack
 * (dev.pboard.pack.voice.en.accurate, NVIDIA parakeet-tdt_ctc-110m, CC-BY-4.0) re-decodes each finished utterance;
 * pboard passes that pack's AssetManager to [loadFinal].
 */
object VoiceEngine {
    /** Bumped when the contract below changes; pboard checks it. */
    const val API = 2

    private const val DIR = "voice"
    private const val SAMPLE_RATE = 16000
    /** Trailing silence fed when the user stops: flushes the last 480 ms chunk and its right context (eval: 1-2 s). */
    private const val TAIL_SECONDS = 2.0f

    private var recognizer: OnlineRecognizer? = null
    private var finalRecognizer: OfflineRecognizer? = null
    private var stream: OnlineStream? = null

    /**
     * Loads the recogniser from this pack's own assets ([assets] = the pack's AssetManager). The models are in ORT format,
     * already optimised for ARM (tools/voice/to_ort.sh), so loading does no graph optimisation (the same model as .onnx
     * took 10 s on the dev phone). One decoding thread: on the target phone more threads were not faster and burnt
     * 3-4x the CPU. Call it off the main thread.
     */
    @JvmStatic
    @Synchronized
    fun load(assets: AssetManager): Boolean {
        if (recognizer != null) return true
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$DIR/encoder.int8.ort",
                    decoder = "$DIR/decoder.ort",
                    joiner = "$DIR/joiner.int8.ort",
                ),
                tokens = "$DIR/tokens.txt",
                numThreads = 1,
                provider = "cpu",
            ),
            // rule 1: nothing said for 2.4 s; rule 2: 1.0 s of silence after speech ends the utterance;
            // rule 3: utterances are cut at 20 s
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.0f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        recognizer = OnlineRecognizer(assets, config)
        return true
    }

    /** Starts listening afresh: a new stream, dropping any previous one. */
    @JvmStatic
    @Synchronized
    fun begin() {
        val r = recognizer ?: error("voice engine not loaded")
        stream?.release()
        stream = r.createStream()
    }

    /** Feeds [n] samples (16 kHz mono, -1..1), decodes what is ready and returns the current utterance's text. */
    @JvmStatic
    @Synchronized
    fun accept(samples: FloatArray, n: Int): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        s.acceptWaveform(if (n == samples.size) samples else samples.copyOf(n), SAMPLE_RATE)
        while (r.isReady(s)) r.decode(s)
        return r.getResult(s).text
    }

    /**
     * When the recogniser has seen the end of an utterance (trailing silence after speech): the utterance's final text,
     * and the stream continues with the next utterance. Otherwise null.
     */
    @JvmStatic
    @Synchronized
    fun endpoint(): String? {
        val r = recognizer ?: return null
        val s = stream ?: return null
        if (!r.isEndpoint(s)) return null
        val text = r.getResult(s).text
        r.reset(s)
        return text
    }

    /** The user stopped: flushes the stream and returns the last utterance's final text. The stream is released. */
    @JvmStatic
    @Synchronized
    fun finish(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        s.acceptWaveform(FloatArray((TAIL_SECONDS * SAMPLE_RATE).toInt()), SAMPLE_RATE)
        s.inputFinished()
        while (r.isReady(s)) r.decode(s)
        val text = r.getResult(s).text
        s.release()
        stream = null
        return text
    }

    /**
     * Loads the final-pass model from the accurate pack's assets ([assets] = that pack's AssetManager, models under
     * voice/accurate/, ORT format). One thread, as for the streaming model.
     */
    @JvmStatic
    @Synchronized
    fun loadFinal(assets: AssetManager): Boolean {
        if (finalRecognizer != null) return true
        val dir = "$DIR/accurate"
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.ort",
                    decoder = "$dir/decoder.int8.ort",
                    joiner = "$dir/joiner.int8.ort",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 1,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        )
        finalRecognizer = OfflineRecognizer(assets, config)
        return true
    }

    /** The final-pass text of one whole utterance ([n] samples, 16 kHz mono); "" when no final model is loaded. */
    @JvmStatic
    @Synchronized
    fun decodeFinal(samples: FloatArray, n: Int): String {
        val r = finalRecognizer ?: return ""
        val s = r.createStream()
        try {
            s.acceptWaveform(if (n == samples.size) samples else samples.copyOf(n), SAMPLE_RATE)
            r.decode(s)
            return r.getResult(s).text
        } finally {
            s.release()
        }
    }

    /** Frees the models (hundreds of MB); [load] (and [loadFinal]) again before the next use. */
    @JvmStatic
    @Synchronized
    fun release() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
        finalRecognizer?.release()
        finalRecognizer = null
    }
}
