package io.github.oysteinkrog.sheepdog

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** Linear-interpolation resampler that keeps its phase across blocks (web/voice/audio-util.js). */
class Resampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var prev = 0f
    private var primed = false

    fun process(input: FloatArray, n: Int = input.size): FloatArray {
        if (n == 0) return FloatArray(0)
        if (!primed) {
            prev = input[0]
            primed = true
            pos = 1.0
        }
        val out = FloatArray((n / step).toInt() + 2)
        var count = 0
        var p = pos
        while (p < n) {
            val i = p.toInt()
            val frac = (p - i).toFloat()
            val a = if (i == 0) prev else input[i - 1]
            val b = input[i]
            out[count++] = a + (b - a) * frac
            p += step
        }
        pos = p - n
        prev = input[n - 1]
        return out.copyOf(count)
    }
}

/** Windowed-sinc low-pass FIR filter, streaming. */
class Lowpass(rate: Int, cutoff: Double, taps: Int = 31) {
    private val h = FloatArray(taps)
    private val hist = FloatArray(taps)
    private var idx = 0

    init {
        val fc = cutoff / rate
        val mid = (taps - 1) / 2.0
        var sum = 0.0
        for (k in 0 until taps) {
            val x = k - mid
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val win = 0.54 - 0.46 * cos(2 * PI * k / (taps - 1))
            h[k] = (sinc * win).toFloat()
            sum += h[k]
        }
        for (k in 0 until taps) h[k] = (h[k] / sum).toFloat()
    }

    fun process(input: FloatArray, n: Int = input.size): FloatArray {
        val out = FloatArray(n)
        val taps = h.size
        for (i in 0 until n) {
            hist[idx] = input[i]
            var y = 0f
            var j = idx
            for (k in 0 until taps) {
                y += h[k] * hist[j]
                j = if (j == 0) taps - 1 else j - 1
            }
            out[i] = y
            idx = if (idx == taps - 1) 0 else idx + 1
        }
        return out
    }
}

/** 24 kHz playback to the 16 kHz echo reference: low-pass, then resample. */
class Downsampler(inRate: Int, outRate: Int) {
    private val lp = Lowpass(inRate, outRate * 0.45)
    private val rs = Resampler(inRate, outRate)
    fun process(input: FloatArray, n: Int = input.size): FloatArray = rs.process(lp.process(input, n))
}

object Pcm {
    fun toFloat(bytes: ByteArray, len: Int = bytes.size): FloatArray {
        val n = len / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val v = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
            out[i] = v.toShort() / 32768f
        }
        return out
    }

    fun toBytes(samples: FloatArray, n: Int = samples.size): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    fun shortsToFloat(s: ShortArray, n: Int): FloatArray = FloatArray(n) { s[it] / 32768f }

    fun floatToShorts(f: FloatArray, n: Int = f.size): ShortArray =
        ShortArray(n) { (f[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}

/**
 * Volume boost with a soft limiter, so a quiet voice gets louder without
 * clipping. Below the knee the gain is linear; above it tanh rounds the peaks.
 */
class Limiter(var gain: Float) {
    var peak = 0f // loudest input sample since the last read, before the boost
    fun process(x: FloatArray) {
        val knee = 0.7f
        for (i in x.indices) {
            val a = kotlin.math.abs(x[i])
            if (a > peak) peak = a
            val y = x[i] * gain
            val ay = kotlin.math.abs(y)
            x[i] = if (ay <= knee) y else {
                val s = if (y < 0) -1f else 1f
                s * (knee + (1 - knee) * tanh((ay - knee) / (1 - knee)))
            }
        }
    }
}
