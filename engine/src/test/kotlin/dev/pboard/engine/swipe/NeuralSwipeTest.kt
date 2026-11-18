package dev.pboard.engine.swipe

import dev.pboard.engine.TouchPoint
import dev.pboard.engine.eval.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parity of the Kotlin feature/model code with the Python reference (tools/swipe/swipefeat.py, export.py). */
class NeuralSwipeTest {
    @Suppress("UNCHECKED_CAST")
    private fun json(name: String): Map<String, Any?> =
        Json.parse(javaClass.getResourceAsStream("/swipe/$name")!!.bufferedReader().readText()) as Map<String, Any?>

    private fun floats(v: Any?) = (v as List<Number>).map { it.toFloat() }.toFloatArray()

    @Suppress("UNCHECKED_CAST")
    @Test
    fun featuresMatchPythonReference() {
        val f = SwipeFeatures()
        for (c in json("feature_vectors.json")["cases"] as List<Map<String, Any?>>) {
            val u = floats(c["u"]); val v = floats(c["v"]); val t = floats(c["t"])
            val frames = SwipeFeatures.Frames(8)
            f.resample(u, v, t, u.size, frames)
            val fu = floats(c["fu"])
            assertEquals(fu.size, frames.size, "frame count")
            val ft = floats(c["ft"])
            for (j in fu.indices) {
                assertTrue(abs(fu[j] - frames.u[j]) < 1e-3f, "u[$j]")
                assertTrue(abs(ft[j] - frames.t[j]) < 1e-2f, "t[$j]")
            }
            val x = f.features(frames, FloatArray(0))
            val ref = floats(c["x"])
            for (j in ref.indices) assertTrue(abs(ref[j] - x[j]) < 2e-3f, "feature $j: ${ref[j]} vs ${x[j]}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun v2FeaturesMatchPythonReference() {
        for (c in json("feature_vectors2.json")["cases"] as List<Map<String, Any?>>) {
            val x = floats(c["x"]); val y = floats(c["y"]); val t = floats(c["t"])
            val f = SwipeFeatures2(floats(c["kx"]), floats(c["ky"]))
            val keys = floats(c["keys"])
            for (k in 0 until f.keyCount) {
                assertTrue(abs(keys[2 * k] - f.keyX[k]) < 1e-4f && abs(keys[2 * k + 1] - f.keyY[k]) < 1e-4f, "key $k")
            }
            val out = f.features(x.indices.map { TouchPoint(x[it], y[it], t[it].toLong()) }, FloatArray(0))
            val ref = floats(c["f"])
            assertEquals(ref.size, f.frames * SwipeFeatures2.N_FEAT, "frame count")
            for (j in ref.indices) assertTrue(abs(ref[j] - out[j]) < 2e-3f, "feature $j: ${ref[j]} vs ${out[j]}")
        }
    }

    @Test
    fun halfFloatDecoding() {
        assertEquals(1f, SwipeModel.halfToFloat(0x3c00))
        assertEquals(-2f, SwipeModel.halfToFloat(0xc000))
        assertEquals(0.5f, SwipeModel.halfToFloat(0x3800))
        assertEquals(5.9604645e-8f, SwipeModel.halfToFloat(0x0001))
        assertEquals(65504f, SwipeModel.halfToFloat(0x7bff))
    }

    @Test
    fun fastMathIsAccurate() {
        var x = -10f
        while (x < 10f) {
            assertTrue(abs(SwipeModel.tanh(x) - kotlin.math.tanh(x)) < 5e-5f, "tanh($x)")
            val e = kotlin.math.ln(kotlin.math.exp(x.toDouble()) + kotlin.math.exp(0.3)).toFloat()
            assertTrue(abs(NeuralSwipeDecoder.lse(x, 0.3f) - e) < 2e-4f, "lse($x)")
            x += 0.013f
        }
    }

    /** The bundled model reproduces the exported reference log-posteriors (fp16 weights on both sides). */
    @Test
    fun bundledModelMatchesPythonReference() {
        val model = SwipeModel.bundled() ?: return
        checkParity(model, "model_vectors.json")
    }

    /** A tiny (random-weight) version 2 model with an embedded auxiliary network: format, features, spatial head. */
    @Test
    fun v2ModelMatchesPythonReference() {
        val model = SwipeModel.read(javaClass.getResourceAsStream("/swipe/v2_tiny.pbsw")!!.readBytes())
        assertEquals(2, model.version)
        val aux = model.aux
        assertTrue(aux != null && aux.version == 2 && aux.nBasis == 3 && model.nBasis == 4, "auxiliary network")
        checkParity(model, "v2_tiny_vectors.json")
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkParity(model: SwipeModel, vectors: String) {
        val root = json(vectors)
        val cases = root["cases"] as List<Map<String, Any?>>
        val enc = model.newEncoder()
        var feat2: SwipeFeatures2? = null
        if (model.version == 2) {
            feat2 = SwipeFeatures2(floats(root["kx"]), floats(root["ky"]), model.stepS, model.stepT, model.maxFrames)
            enc.setKeys(feat2.keyX, feat2.keyY)
        }
        for (c in cases) {
            val x = floats(c["x"])
            if (feat2 != null) {
                // The engine's own features from the recorded stroke match the reference features.
                val pts = (c["points"] as List<List<Number>>).map { TouchPoint(it[0].toFloat(), it[1].toFloat(), it[2].toLong()) }
                val mine = feat2.features(pts, FloatArray(0))
                assertEquals(x.size, feat2.frames * model.nFeat, "frames for ${c["word"]}")
                for (j in x.indices) assertTrue(abs(x[j] - mine[j]) < 2e-3f, "feature $j of ${c["word"]}")
            }
            val t = enc.encode(x, x.size / model.nFeat)
            val ref = floats(c["lp"])
            assertEquals(ref.size, t * enc.classes)
            var worst = 0f
            for (j in ref.indices) worst = maxOf(worst, abs(ref[j] - enc.lastOutput[j]) / maxOf(1f, abs(ref[j])))
            assertTrue(worst < 2e-3f, "log-posterior mismatch $worst for ${c["word"]}")
        }
    }
}
