package dev.pboard.engine.eval

import dev.pboard.engine.KeyboardGeometry
import dev.pboard.engine.TouchPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generator calibration, fitted on the human TUNE splits (tools/data/human/typist_model.json, see
 * docs/eval-data.md §5). Loaded from that file when present; the defaults are its v1 values. Offsets are
 * in key widths (vertical row-pitch values are converted with the row/key pitch ratio).
 */
data class Calibration(
    val tapSigma: ClosedFloatingPointRange<Double> = 0.209..0.279,
    /** Mean vertical touch offset (negative = above the key centre on Gboard/Pixel). */
    val tapBiasY: Double = -0.055,
    val personBiasSd: Pair<Double, Double> = 0.059 to 0.078,
    /** Per-key mean offsets (dx, dy) in key widths. */
    val perKey: Map<Char, Pair<Double, Double>> = emptyMap(),
    val swipeSigma: ClosedFloatingPointRange<Double> = 0.185..0.288,
    val cornerCut: Double = 0.099,
    val startSd: Pair<Double, Double> = 0.28 to 0.24,
    val endSd: Pair<Double, Double> = 0.51 to 0.36,
    val endMeanY: Double = 0.13,
    /** Quantiles (p05, p25, p50, p75, p95) of the overshoot past the last key along the final segment. */
    val overshoot: DoubleArray = doubleArrayOf(-0.574, -0.127, 0.123, 0.393, 0.909),
    /** Swipe duration per segment and per key width of path (ms, FUTO fit). */
    val msPerSegment: Double = 183.0,
    val msPerKw: Double = 19.7,
) {
    companion object {
        /** Reads tools/data/human/typist_model.json if it exists. */
        @Suppress("UNCHECKED_CAST")
        fun load(root: File): Calibration {
            val f = File(root, "tools/data/human/typist_model.json")
            if (!f.exists()) return Calibration()
            val m = Json.parse(f.readText()) as Map<String, Any?>
            fun num(o: Any?) = (o as Number).toDouble()
            val keyPx = (m["targetKeyPx"] as List<Number>).map { it.toDouble() }
            val rowToKw = keyPx[1] / keyPx[0]
            val args = m["genTracesArgs"] as Map<String, Any?>
            val ext = args["extensions"] as Map<String, Any?>
            val sigma = (args["sigma"] as List<Number>).map { it.toDouble() }
            val swipeSigma = (args["swipe_sigma"] as List<Number>).map { it.toDouble() }
            val bias = (ext["perParticipantBias_sd"] as List<Number>).map { it.toDouble() }
            val tsi = ((m["tap"] as Map<String, Any?>)["tsi"] as Map<String, Any?>)
            val perKey = (tsi["perKey"] as Map<String, Map<String, Any?>>).filterKeys { it.length == 1 && it[0].isLetter() }
                .map { (k, v) -> val mean = (v["mean"] as List<Number>).map { it.toDouble() }; k[0] to (mean[0] to mean[1] * rowToKw) }.toMap()
            val futo = ((m["swipe"] as Map<String, Any?>)["futo"] as Map<String, Any?>)
            val start = futo["start"] as Map<String, Any?>
            val end = futo["end"] as Map<String, Any?>
            val sCov = start["offsetCov_kw_kh"] as List<List<Number>>
            val eCov = end["offsetCov_kw_kh"] as List<List<Number>>
            val ov = end["overshootAlongLastSegmentKw"] as Map<String, Number>
            val dm = futo["durationModel"] as? Map<String, Any?>
            return Calibration(
                tapSigma = sigma[0]..sigma[1],
                tapBiasY = num(args["bias_y"]),
                personBiasSd = bias[0] to bias[1] * rowToKw,
                perKey = perKey,
                swipeSigma = swipeSigma[0]..swipeSigma[1],
                cornerCut = num(args["corner_cut"]),
                startSd = sqrt(num(sCov[0][0])) to sqrt(num(sCov[1][1])) * rowToKw,
                endSd = sqrt(num(eCov[0][0])) to sqrt(num(eCov[1][1])) * rowToKw,
                endMeanY = num((end["offsetMean_kw_kh"] as List<Number>)[1]) * rowToKw,
                overshoot = doubleArrayOf(num(ov["p05"]), num(ov["p25"]), num(ov["p50"]), num(ov["p75"]), num(ov["p95"])),
                msPerSegment = (dm?.get("perSegmentMs") as? Number)?.toDouble() ?: 183.0,
                msPerKw = (dm?.get("perKwMs") as? Number)?.toDouble() ?: 19.7,
            )
        }
    }

    /** Samples the overshoot by interpolating the fitted quantiles (linear tails beyond p05/p95). */
    fun sampleOvershoot(rng: Random): Double {
        val q = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)
        val u = rng.nextDouble()
        if (u <= q[0]) return overshoot[0] - (q[0] - u) / q[0] * (overshoot[1] - overshoot[0])
        if (u >= q[4]) return overshoot[4] + (u - q[4]) / (1 - q[4]) * (overshoot[4] - overshoot[3])
        var k = 0
        while (u > q[k + 1]) k++
        return overshoot[k] + (u - q[k]) / (q[k + 1] - q[k]) * (overshoot[k + 1] - overshoot[k])
    }
}

/**
 * Synthetic thumb typist, one profile per sentence (people differ), calibrated on human data: isotropic
 * touch noise of 0.21–0.28 key widths with mildly heavy tails, an upward bias, the fitted per-key mean
 * offsets plus a per-person bias, rare slips (omission/doubling/transposition/stray at human per-word
 * rates), and occasional genuine misspellings.
 */
data class TypistProfile(
    val sigma: Double, val biasX: Double, val biasY: Double,
    val pOmit: Double, val pOmitDouble: Double, val pDouble: Double, val pTranspose: Double, val pStray: Double,
    /** Probability that a word of 5+ letters is misspelt (cognitive error, see [Synth.misspell]). */
    val pMisspell: Double,
    /** Fraction of touches drawn from a 2.5× wider Gaussian (heavy tails; human excess kurtosis ≈ 1.5). */
    val pWide: Double,
    val msPerKey: Double,
) {
    companion object {
        fun random(rng: Random, c: Calibration = Calibration()) = TypistProfile(
            sigma = rng.uniform(c.tapSigma.start, c.tapSigma.endInclusive),
            biasX = rng.gauss() * c.personBiasSd.first,
            biasY = c.tapBiasY + rng.gauss() * c.personBiasSd.second,
            pOmit = rng.uniform(0.001, 0.004),
            pOmitDouble = rng.uniform(0.01, 0.04),
            pDouble = rng.uniform(0.0005, 0.003),
            pTranspose = rng.uniform(0.0005, 0.002),
            pStray = rng.uniform(0.001, 0.004),
            pMisspell = rng.uniform(0.01, 0.05),
            pWide = 0.04,
            msPerKey = rng.uniform(150.0, 230.0),
        )
    }
}

/** Swipe style per sentence: aim noise, corner cutting, speed, start/end scatter (incl. overshoot). */
data class SwiperProfile(val aimSigma: Double, val cornerCut: Double, val speed: Double, val cornerSlowdown: Double, val jitter: Double, val hz: Double) {
    companion object {
        fun random(rng: Random, c: Calibration = Calibration()) = SwiperProfile(
            aimSigma = rng.uniform(c.swipeSigma.start, c.swipeSigma.endInclusive),
            cornerCut = c.cornerCut * rng.uniform(0.3, 1.7),
            speed = rng.uniform(0.6, 1.6),
            cornerSlowdown = rng.uniform(0.35, 0.9),
            jitter = rng.uniform(0.005, 0.02),
            hz = listOf(60.0, 90.0, 120.0)[rng.nextInt(3)],
        )
    }
}

fun Random.uniform(a: Double, b: Double) = a + nextDouble() * (b - a)
fun Random.gauss(): Double {
    var u = 0.0
    while (u == 0.0) u = nextDouble()
    val v = nextDouble()
    return sqrt(-2.0 * ln(u)) * kotlin.math.cos(2 * Math.PI * v)
}

/**
 * Generates synthetic touches on any layout: keys are looked up by their single-character labels in the
 * geometry, so the same generator works for QWERTY, AZERTY, Cyrillic, … ([keys] strings use those labels).
 */
class Synth(private val g: KeyboardGeometry, private val cal: Calibration = Calibration()) {
    private val kw = g.keyWidth.toDouble()
    private val labels: List<Char> = g.keys.filter { it.label.length == 1 && it.label[0].isLetter() }.map { it.label[0] }
    private val index: Map<Char, Int> = labels.withIndex().associate { it.value to it.index }
    private val cx = DoubleArray(labels.size); private val cy = DoubleArray(labels.size)
    private val letterBottom: Double = g.keys.filter { it.label.length == 1 && it.label[0].isLetter() }.maxOf { it.cy + it.height / 2 }.toDouble()

    init {
        for (k in g.keys) index[k.label.singleOrNull() ?: continue]?.let { cx[it] = k.cx.toDouble(); cy[it] = k.cy.toDouble() }
    }

    private fun idx(c: Char): Int = index[c] ?: error("no key labelled '$c'")

    /** Neighbouring keys (centres within 1.3 key pitches) used for stray taps. */
    private fun neighbours(l: Int): List<Int> = labels.indices.filter { it != l && dist(cx[it], cy[it], cx[l], cy[l]) < 1.3 * pitch }
    private val pitch: Double = labels.indices.minOf { a -> labels.indices.filter { it != a }.minOf { dist(cx[a], cy[a], cx[it], cy[it]) } }

    /** A typist's systematic per-key offsets (key widths): the fitted population means plus a little individual variation. */
    fun keyOffsets(p: TypistProfile, rng: Random): Array<DoubleArray> = Array(labels.size) { i ->
        val pop = cal.perKey[labels[i]] ?: (0.0 to 0.0)
        doubleArrayOf(pop.first + rng.gauss() * 0.03, pop.second + rng.gauss() * 0.03)
    }

    private fun tapPoint(l: Int, p: TypistProfile, offsets: Array<DoubleArray>?, rng: Random): Pair<Float, Float> {
        val ox = offsets?.get(l)?.get(0) ?: 0.0; val oy = offsets?.get(l)?.get(1) ?: 0.0
        repeat(50) {
            val s = if (rng.nextDouble() < p.pWide) p.sigma * 2.5 else p.sigma
            val x = cx[l] + (p.biasX + ox) * kw + rng.gauss() * s * kw
            val y = cy[l] + (p.biasY + oy) * kw + rng.gauss() * s * kw
            // Touches that would land on a non-letter key (shift, delete, space…) are redrawn: the replay
            // on another keyboard must produce letters only.
            if (landsOnLetter(x, y)) return x.toFloat() to y.toFloat()
        }
        return cx[l].toFloat() to cy[l].toFloat()
    }

    /** Whether the key under (x, y) (nearest centre among keys whose row band contains y) is a letter. */
    private fun landsOnLetter(x: Double, y: Double): Boolean {
        if (x < 0 || x >= g.width) return false
        var best: dev.pboard.engine.KeyGeom? = null; var bestD = Double.MAX_VALUE
        for (k in g.keys) {
            if (y < k.cy - k.height / 2 || y >= k.cy + k.height / 2) continue
            val d = abs(k.cx - x)
            if (d < bestD) { bestD = d; best = k }
        }
        return best != null && best.label.length == 1 && index.containsKey(best.label[0])
    }

    /**
     * A spelling mistake of the kind people make knowing the keys perfectly well (as opposed to touch
     * errors): vowel confusion ("definately", "seperate"), ie/ei swaps ("recieve"), a doubled consonant
     * dropped ("ocasion") or added ("untill"). Returns the misspelt key string, or [keys] if none applies.
     */
    fun misspell(keys: String, rng: Random): String {
        val vowels = "aeiou"
        repeat(10) {
            when (rng.nextInt(4)) {
                0 -> {
                    val pos = keys.indices.filter { it > 0 && keys[it] in vowels }
                    if (pos.isNotEmpty()) {
                        val i = pos[rng.nextInt(pos.size)]
                        val alt = vowels.filter { it != keys[i] }
                        return keys.substring(0, i) + alt[rng.nextInt(alt.length)] + keys.substring(i + 1)
                    }
                }
                1 -> {
                    val i = keys.indexOf("ie").takeIf { it >= 0 } ?: keys.indexOf("ei")
                    if (i >= 0) return keys.substring(0, i) + keys[i + 1] + keys[i] + keys.substring(i + 2)
                }
                2 -> {
                    val pos = (1 until keys.length).filter { keys[it] == keys[it - 1] && keys[it] !in vowels }
                    if (pos.isNotEmpty()) { val i = pos[rng.nextInt(pos.size)]; return keys.removeRange(i, i + 1) }
                }
                else -> {
                    val pos = (1 until keys.length - 1).filter { keys[it] !in vowels && keys[it - 1] in vowels && keys[it] != keys[it + 1] }
                    if (pos.isNotEmpty()) { val i = pos[rng.nextInt(pos.size)]; return keys.substring(0, i + 1) + keys[i] + keys.substring(i + 1) }
                }
            }
        }
        return keys
    }

    /** Taps for a word's key string with typist errors; t in ms from the first tap. */
    fun taps(keys: String, p: TypistProfile, rng: Random, offsets: Array<DoubleArray>? = null): List<TouchPoint> {
        val letters = ArrayList<Int>()
        var i = 0
        while (i < keys.length) {
            val l = idx(keys[i])
            val r = rng.nextDouble()
            if (i + 1 < keys.length && keys[i + 1] != keys[i] && r < p.pTranspose) {
                letters.add(idx(keys[i + 1])); letters.add(l); i += 2; continue
            }
            val pOmit = when {
                i > 0 && keys[i - 1] == keys[i] -> p.pOmitDouble
                i == 0 -> p.pOmit / 4
                else -> p.pOmit
            }
            if (keys.length > 1 && r < p.pTranspose + pOmit) { i++; continue }
            letters.add(l)
            if (rng.nextDouble() < p.pDouble) letters.add(l)
            if (rng.nextDouble() < p.pStray) neighbours(l).takeIf { it.isNotEmpty() }?.let { letters.add(it[rng.nextInt(it.size)]) }
            i++
        }
        if (letters.isEmpty()) letters.add(idx(keys[0]))
        val out = ArrayList<TouchPoint>()
        var t = 0.0
        var prev = -1
        for (l in letters) {
            if (prev >= 0) {
                // Two-thumb typing: no distance effect; crossing to the other half is faster (log-normal IKIs).
                val cross = (cx[prev] < g.width / 2) != (cx[l] < g.width / 2)
                val median = if (prev == l) 172.0 else if (cross) 141.0 else 214.0
                t += (median * p.msPerKey / 185.0 * kotlin.math.exp(rng.gauss() * 0.45)).coerceIn(60.0, 900.0)
            }
            val (x, y) = tapPoint(l, p, offsets, rng)
            out.add(TouchPoint(r1(x), r1(y), round(t).toLong()))
            prev = l
        }
        return out
    }

    /**
     * A gesture for [keys]: via points near the key centres (doubled letters visited once) with a mild,
     * angle-dependent corner cut, a scattered start, a wider end with overshoot along the last segment
     * (fitted quantiles), joined by minimum-jerk quintic segments with continuous velocity; per-segment
     * duration from the human fit, sampled at 60–120 Hz with sensor jitter.
     */
    fun swipe(keys: String, s: SwiperProfile, rng: Random): List<TouchPoint> {
        val seq = ArrayList<Int>()
        for (c in keys) { val l = idx(c); if (seq.isEmpty() || seq.last() != l) seq.add(l) }
        val m = seq.size
        val vx = DoubleArray(m); val vy = DoubleArray(m)
        for (j in 0 until m) {
            vx[j] = cx[seq[j]] + rng.gauss() * s.aimSigma * kw
            vy[j] = cy[seq[j]] + rng.gauss() * s.aimSigma * kw * 0.7
        }
        if (m >= 3) {
            val ox = vx.copyOf(); val oy = vy.copyOf()
            for (j in 1 until m - 1) {
                // People round off gentle turns but still reach the key at sharp reversals ("did", "suicide").
                val ax = ox[j] - ox[j - 1]; val ay = oy[j] - oy[j - 1]
                val bx = ox[j + 1] - ox[j]; val by = oy[j + 1] - oy[j]
                val la = sqrt(ax * ax + ay * ay).coerceAtLeast(1e-6); val lb = sqrt(bx * bx + by * by).coerceAtLeast(1e-6)
                val straightness = (1 + (ax * bx + ay * by) / (la * lb)) / 2 // 1 = straight on, 0 = reversal
                val cut = (s.cornerCut * straightness * rng.uniform(0.5, 1.5)).coerceIn(0.0, 0.5)
                vx[j] = ox[j] + cut * ((ox[j - 1] + ox[j + 1]) / 2 - ox[j])
                vy[j] = oy[j] + cut * ((oy[j - 1] + oy[j + 1]) / 2 - oy[j])
            }
        }
        // Start and end scatter around the first/last key (the end twice as wide, a little low) + overshoot.
        vx[0] = cx[seq[0]] + rng.gauss() * cal.startSd.first * kw
        vy[0] = cy[seq[0]] + rng.gauss() * cal.startSd.second * kw
        if (m >= 2) {
            val lx = cx[seq[m - 1]]; val ly = cy[seq[m - 1]]
            val dx = lx - vx[m - 2]; val dy = ly - vy[m - 2]
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
            val along = cal.sampleOvershoot(rng)
            val px = -dy / d; val py = dx / d
            val perp = rng.gauss() * 0.6 * cal.endSd.first
            vx[m - 1] = lx + (dx / d * along + px * perp) * kw
            vy[m - 1] = ly + (dy / d * along + py * perp) * kw * 0.7 + (cal.endMeanY + rng.gauss() * cal.endSd.second * 0.6) * kw
        }
        val dur = DoubleArray(max(0, m - 1))
        for (j in 0 until m - 1) {
            val d = dist(vx[j], vy[j], vx[j + 1], vy[j + 1]) / kw
            dur[j] = max(40.0, (cal.msPerSegment + cal.msPerKw * d) / s.speed * rng.uniform(0.8, 1.25))
        }
        val velX = DoubleArray(m); val velY = DoubleArray(m)
        for (j in 1 until m - 1) {
            val ax = vx[j] - vx[j - 1]; val ay = vy[j] - vy[j - 1]
            val bx = vx[j + 1] - vx[j]; val by = vy[j + 1] - vy[j]
            val la = sqrt(ax * ax + ay * ay).coerceAtLeast(1e-6); val lb = sqrt(bx * bx + by * by).coerceAtLeast(1e-6)
            val cos = (ax * bx + ay * by) / (la * lb)
            val keep = s.cornerSlowdown + (1 - s.cornerSlowdown) * ((cos + 1) / 2)
            val tt = dur[j - 1] + dur[j]
            velX[j] = keep * (vx[j + 1] - vx[j - 1]) / tt
            velY[j] = keep * (vy[j + 1] - vy[j - 1]) / tt
        }
        val out = ArrayList<TouchPoint>()
        val dt = 1000.0 / s.hz
        out.add(TouchPoint(r1(vx[0]), r1(vy[0]), 0))
        val dwell = rng.uniform(5.0, 20.0)
        out.add(TouchPoint(r1(vx[0] + rng.gauss() * 0.3), r1(vy[0] + rng.gauss() * 0.3), round(dwell).toLong()))
        var tNext = dwell + dt
        var segStartT = dwell
        for (j in 0 until m - 1) {
            val T = dur[j]
            while (tNext <= segStartT + T + 1e-9) {
                val u = (tNext - segStartT) / T
                val (x, y) = hermite5(vx[j], vy[j], velX[j] * T, velY[j] * T, vx[j + 1], vy[j + 1], velX[j + 1] * T, velY[j + 1] * T, u)
                out.add(TouchPoint(r1(x + rng.gauss() * s.jitter * kw), r1(y + rng.gauss() * s.jitter * kw), round(tNext).toLong()))
                tNext += dt
            }
            segStartT += T
        }
        val last = out.last()
        if (abs(last.x - vx[m - 1]) > 0.5 || abs(last.y - vy[m - 1]) > 0.5) out.add(TouchPoint(r1(vx[m - 1]), r1(vy[m - 1]), round(segStartT).toLong()))
        // Keep inside the letter area.
        return out.map { TouchPoint(it.x.coerceIn(0f, g.width - 1), it.y.coerceIn(0f, letterBottom.toFloat() - 1), it.t) }
    }

    /** Quintic Hermite with zero end accelerations: the minimum-jerk segment for given end positions/velocities. */
    private fun hermite5(x0: Double, y0: Double, v0x: Double, v0y: Double, x1: Double, y1: Double, v1x: Double, v1y: Double, u: Double): Pair<Double, Double> {
        val u2 = u * u; val u3 = u2 * u; val u4 = u3 * u; val u5 = u4 * u
        val h0 = 1 - 10 * u3 + 15 * u4 - 6 * u5
        val h1 = u - 6 * u3 + 8 * u4 - 3 * u5
        val h4 = -4 * u3 + 7 * u4 - 3 * u5
        val h5 = 10 * u3 - 15 * u4 + 6 * u5
        return (h0 * x0 + h1 * v0x + h4 * v1x + h5 * x1) to (h0 * y0 + h1 * v0y + h4 * v1y + h5 * y1)
    }

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    private fun r1(v: Double) = (round(v * 10) / 10).toFloat()
    private fun r1(v: Float) = (round(v * 10) / 10)
}
