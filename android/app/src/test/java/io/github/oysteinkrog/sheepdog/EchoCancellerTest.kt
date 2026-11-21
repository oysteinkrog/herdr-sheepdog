package io.github.oysteinkrog.sheepdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.tanh

// Same cases as web/voice/echo.test.mjs: real speech from testdata/audio
// played through a simulated phone (delay, room echo, speaker distortion,
// noise). The mic must stay closed while only the assistant is heard, and
// open quickly when the user talks over it.
class EchoCancellerTest {
    private val rate = EchoCanceller.RATE

    private fun readWav(name: String): FloatArray {
        var dir = File("").absoluteFile
        while (!File(dir, "testdata/audio").isDirectory) dir = dir.parentFile ?: error("testdata not found")
        val b = ByteBuffer.wrap(File(dir, "testdata/audio/$name").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var off = 12
        var r = 0
        var data: ByteArray? = null
        while (off + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(off + it) }, Charsets.US_ASCII)
            val len = b.getInt(off + 4)
            if (id == "fmt ") r = b.getInt(off + 12)
            if (id == "data") data = ByteArray(len) { b.get(off + 8 + it) }
            off += 8 + len + (len and 1)
        }
        val pcm = Pcm.toFloat(data!!)
        return if (r == rate) pcm else Resampler(r, rate).process(pcm)
    }

    private fun rand(seed: Int): () -> Float {
        var s = seed.toLong() and 0xffffffffL
        return {
            s = (s * 1664525L + 1013904223L) and 0xffffffffL
            (s.toDouble() / 4294967296.0 * 2 - 1).toFloat()
        }
    }

    private fun phone(ref: FloatArray, delayMs: Int = 180, gain: Float = 0.8f, seed: Int = 1): FloatArray {
        val r = rand(seed)
        val d = (delayMs / 1000.0 * rate).roundToInt()
        val ir = FloatArray(400)
        ir[0] = 1f
        for (k in 1 until ir.size) ir[k] = r() * 0.3f * exp(-k / 80.0).toFloat()
        val out = FloatArray(ref.size)
        for (t in ref.indices) {
            var y = 0f
            for (k in ir.indices) {
                val i = t - d - k
                if (i >= 0) y += ir[k] * ref[i]
            }
            y = tanh(1.5f * gain * y) / 1.5f
            out[t] = y + r() * 3e-4f
        }
        return out
    }

    private class Run(val aec: EchoCanceller, val firstTalk: Double?, val loudFrames: Int)

    private fun run(ref: FloatArray, mic: FloatArray, block: Int = 43): Run {
        val aec = EchoCanceller()
        var firstTalk: Double? = null
        var loud = 0
        var i = 0
        while (i < ref.size) {
            val n = minOf(block, ref.size - i)
            aec.process(mic.copyOfRange(i, i + n), ref.copyOfRange(i, i + n), n) { f ->
                var e = 0f
                for (v in f) e += v * v
                if (e / f.size > 1e-5f) loud++
            }
            if (firstTalk == null && aec.stats.talking) firstTalk = i.toDouble() / rate
            i += block
        }
        return Run(aec, firstTalk, loud)
    }

    private val reply by lazy { readWav("reply-briefing.wav") }
    private val question by lazy { readWav("question.wav") }
    private val ref by lazy { reply + reply + FloatArray(rate) }

    @Test
    fun echoAloneNeverOpensTheMic() {
        val r = run(ref, phone(ref))
        assertEquals("false barge-in on echo only", 0, r.aec.stats.bargeIns)
        assertTrue("delay ${r.aec.stats.delayMs}", abs(r.aec.stats.delayMs - 180) <= 10)
        assertTrue("echo removed only ${r.aec.stats.erleDb} dB", r.aec.stats.erleDb > 6)
    }

    @Test
    fun talkingOverOpensTheMicWithin150ms() {
        val mic = phone(ref)
        val start = 4.5
        val at = (start * rate).roundToInt()
        for (i in question.indices) if (at + i < mic.size) mic[at + i] += question[i] * 0.7f
        val r = run(ref, mic)
        val first = r.firstTalk
        assertTrue("the user was never heard", first != null)
        assertTrue("opened at $first, before the user spoke", first!! >= start)
        assertTrue("opened late, at $first", first <= start + 0.15)
        assertEquals(1, r.aec.stats.bargeIns)
    }

    @Test
    fun otherDelayAndLouderSpeaker() {
        val r = run(ref, phone(ref, delayMs = 340, gain = 1.6f, seed = 7))
        assertTrue("delay ${r.aec.stats.delayMs}", abs(r.aec.stats.delayMs - 340) <= 10)
        assertEquals("false barge-in on echo only", 0, r.aec.stats.bargeIns)
    }

    @Test
    fun micPassesThroughWhileQuiet() {
        val silent = FloatArray(rate * 2)
        val mic = FloatArray(silent.size)
        question.copyInto(mic, 0, 0, minOf(question.size, mic.size))
        val r = run(silent, mic)
        assertTrue("only ${r.loudFrames} frames came through", r.loudFrames > 50)
    }
}
