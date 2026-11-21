package io.github.oysteinkrog.sheepdog

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Echo canceller for the loud speaker mode, a port of web/voice/echo.js.
 *
 * The app plays the assistant at media volume with the phone's own echo
 * cancelling off, so the voice is loud. This class removes the assistant
 * from the mic using the exact samples the app played (the reference), so
 * the user can still talk over the assistant to interrupt it.
 *
 * Three parts, all at 16 kHz on the capture thread:
 *  1. Delay search: every 0.5 s the mic's 4 ms envelope is correlated with
 *     the reference's to find the speaker-to-mic delay (up to 800 ms).
 *  2. A 512-tap NLMS adaptive filter at that delay subtracts the echo.
 *  3. A talk detector compares what is left with the echo it expects. While
 *     the assistant speaks the mic stays silent unless the user is clearly
 *     talking; then it opens (with the 40 ms before) for at least a second.
 *
 * Keep the numbers in step with echo.js; EchoCancellerTest runs the same
 * cases as echo.test.mjs.
 */
class EchoCanceller(
    private val taps: Int = 512,
    private val lead: Int = 96,
    private val maxDelay: Int = 12800,
    private val mu: Float = 0.5f,
    private val talkRatio: Float = 8f,
    private val micRatio: Float = 2f,
    private val holdFrames: Int = 100,
    private val tailFrames: Int = 30,
) {
    companion object {
        const val RATE = 16000
        const val FRAME = 160 // 10 ms decision frame
        private const val ENV_BLOCK = 64 // 4 ms envelope block
        private const val RING = 32768 // reference history, 2 s
        private const val MASK = RING - 1
        private const val ENV_RING = 1024
        private const val ENV_MASK = ENV_RING - 1
        private const val PREROLL_FRAMES = 4
    }

    class Stats {
        var bargeIns = 0
        var erleDb = 0f
        var delayMs = -1
        var talking = false
        var refActive = false
        var resets = 0
    }

    private val refFloor = 1e-6
    private val absFloor = 3e-6

    private val ref = FloatArray(2 * RING)
    private val w = FloatArray(taps)
    private var t = 0L
    private var lastRef = -1_000_000_000L
    private var delay = -1
    private var xe = 0.0

    private val envM = FloatArray(ENV_RING)
    private val envR = FloatArray(ENV_RING)
    private var envN = 0
    private var accM = 0f
    private var accR = 0f
    private var accN = 0
    private var candidate = -1

    private val frame = FloatArray(FRAME)
    private var fill = 0
    private var pm = 0.0
    private var pe = 0.0
    private var pr = 0.0
    private var lc = 0.0
    private var lm = 0.0
    private var prDecay = 0.0
    private var learned = 0
    private var nf = 1e-7
    private var open = 0
    private var talkHist = 0
    private val preroll = ArrayDeque<FloatArray>()
    private var adapt = true

    val stats = Stats()

    /**
     * Feeds one block. [mic] and [refIn] are 16 kHz, the first [n] samples
     * are used. Output frames (10 ms each) go to [out]: silence while the
     * assistant speaks and the user does not.
     */
    fun process(mic: FloatArray, refIn: FloatArray, n: Int, out: (FloatArray) -> Unit) {
        for (i in 0 until n) {
            val x = refIn[i]
            val p = (t and MASK.toLong()).toInt()
            ref[p] = x
            ref[p + RING] = x
            if (x > 1e-3f || x < -1e-3f) lastRef = t
            val m = mic[i]
            envelope(m, x)

            var e = m
            var ra = 0f
            if (delay >= 0) {
                ra = ref[((t - delay) and MASK.toLong()).toInt() + RING]
                val off = max(0, delay - lead)
                val base = ((t - off) and MASK.toLong()).toInt() + RING
                val xNew = ref[base].toDouble()
                val xOld = ref[base - taps].toDouble()
                xe += xNew * xNew - xOld * xOld
                if (xe < 0.0) xe = 0.0
                var y = 0f
                for (k in 0 until taps) y += w[k] * ref[base - k]
                e = m - y
                if (adapt && xe > taps * refFloor) {
                    val g = ((mu * e) / (xe + taps * 1e-6)).toFloat()
                    for (k in 0 until taps) w[k] += g * ref[base - k]
                }
            }
            pm += (m * m).toDouble()
            pe += (e * e).toDouble()
            pr += (ra * ra).toDouble()
            frame[fill++] = e
            if (fill == FRAME) endFrame(out)
            t++
        }
    }

    private fun envelope(m: Float, x: Float) {
        accM += abs(m)
        accR += abs(x)
        if (++accN < ENV_BLOCK) return
        val j = envN and ENV_MASK
        envM[j] = accM / ENV_BLOCK
        envR[j] = accR / ENV_BLOCK
        envN++
        accM = 0f; accR = 0f; accN = 0
        if (envN % 125 == 0) searchDelay()
    }

    private fun searchDelay() {
        val l = maxDelay / ENV_BLOCK
        val wn = minOf(500, envN - l - 1)
        if (wn < 150) return
        val end = envN - 1
        var active = 0
        for (i in 0 until wn + l) if (envR[(end - i) and ENV_MASK] > 1e-3f) active++
        if (active < 40) return
        var sm = 0.0
        var smm = 0.0
        for (i in 0 until wn) {
            val v = envM[(end - i) and ENV_MASK].toDouble()
            sm += v; smm += v * v
        }
        val varM = smm - sm * sm / wn
        if (varM <= 0) return
        var best = -1
        var bestC = 0.0
        for (lag in 0..l) {
            var sr = 0.0
            var srr = 0.0
            var smr = 0.0
            for (i in 0 until wn) {
                val mv = envM[(end - i) and ENV_MASK].toDouble()
                val rv = envR[(end - i - lag) and ENV_MASK].toDouble()
                sr += rv; srr += rv * rv; smr += mv * rv
            }
            val varR = srr - sr * sr / wn
            if (varR <= 0) continue
            val c = (smr - sm * sr / wn) / sqrt(varM * varR)
            if (c > bestC) { bestC = c; best = lag }
        }
        if (best < 0 || bestC < 0.4) return
        val d = best * ENV_BLOCK
        if (delay >= 0 && abs(d - delay) <= 2 * ENV_BLOCK) { candidate = -1; return }
        if (delay >= 0 && (candidate < 0 || abs(d - candidate) > 2 * ENV_BLOCK)) { candidate = d; return }
        setDelay(d)
    }

    private fun setDelay(d: Int) {
        delay = d
        candidate = -1
        w.fill(0f)
        stats.resets++
        val off = max(0, d - lead)
        val base = ((t - off) and MASK.toLong()).toInt() + RING
        var s = 0.0
        for (k in 0 until taps) s += (ref[base - k] * ref[base - k]).toDouble()
        xe = s
        stats.delayMs = (d.toFloat() / RATE * 1000).roundToInt()
    }

    private fun endFrame(out: (FloatArray) -> Unit) {
        val pmF = pm / FRAME
        val peF = pe / FRAME
        val prF = pr / FRAME
        pm = 0.0; pe = 0.0; pr = 0.0
        fill = 0
        prDecay = max(prF, prDecay * 0.7)
        val prWin = prDecay
        val reach = (if (delay >= 0) delay else maxDelay) + tailFrames * FRAME
        val refActive = t - lastRef < reach

        var talk = false
        if (refActive && delay >= 0) {
            val expected = exp(lc) * prWin
            val expectedMic = exp(lm) * prWin
            talk = learned >= 30 && peF > talkRatio * expected + 4 * nf && peF > absFloor &&
                pmF > micRatio * expectedMic
            if (!talk && open == 0 && prF > refFloor && prF >= 0.5 * prWin) {
                val ratio = ln((peF + 1e-12) / (prWin + 1e-12))
                val a = if (learned < 30) 0.3 else if (ratio > lc) 0.2 else 0.01
                lc += a * (ratio - lc)
                val mratio = ln((pmF + 1e-12) / (prWin + 1e-12))
                val b = if (learned < 30) 0.3 else if (mratio > lm) 0.2 else 0.01
                lm += b * (mratio - lm)
                learned++
                val erle = 10.0 * log10((pmF + 1e-12) / (peF + 1e-12))
                stats.erleDb += (0.05 * (erle - stats.erleDb)).toFloat()
                if (peF > 4 * pmF && pmF > absFloor) {
                    w.fill(0f)
                    stats.resets++
                }
            }
        } else if (!refActive) {
            nf = if (peF < nf) peF else nf * 1.002 + peF * 0.0005
        }
        talkHist = ((talkHist shl 1) or (if (talk) 1 else 0)) and 0xf
        val recentTalk = Integer.bitCount(talkHist)
        val wasOpen = open > 0
        if (refActive && recentTalk >= 3) {
            if (!wasOpen) stats.bargeIns++
            open = holdFrames
        } else if (open > 0) {
            open--
        }
        adapt = !talk && open == 0

        val f = frame.copyOf()
        stats.refActive = refActive
        stats.talking = refActive && open > 0
        if (!refActive || open > 0) {
            if (refActive && !wasOpen) for (pf in preroll) out(pf)
            preroll.clear()
            out(f)
        } else {
            preroll.addLast(f)
            if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
            out(FloatArray(FRAME))
        }
    }
}
