package io.github.oysteinkrog.sheepdog

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/** How the phone plays the voice and keeps it out of the mic. */
enum class SpeakerMode(val label: String, val help: String) {
    TALK(
        "Loud, talk over it",
        "Media volume. The app removes its own voice from the mic, so you can interrupt by talking.",
    ),
    PAUSE(
        "Loud, mic pauses while it talks",
        "Media volume. The mic is off while the assistant speaks; tap Stop to interrupt.",
    ),
    CALL(
        "Call audio",
        "The phone's own call echo cancelling on the speaker. Quieter, uses the call volume.",
    ),
}

/** The audio the echo canceller needs: what was played, at 16 kHz, in play order. */
private class RefRing(private val cap: Int) {
    private val buf = FloatArray(cap)
    private var head = 0
    private var size = 0

    @Synchronized fun put(x: FloatArray) {
        for (v in x) {
            buf[(head + size) % cap] = v
            if (size < cap) size++ else head = (head + 1) % cap
        }
    }

    @Synchronized fun take(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            if (size > 0) {
                out[i] = buf[head]; head = (head + 1) % cap; size--
            } else out[i] = 0f
        }
    }

    @Synchronized fun clear() { head = 0; size = 0 }
}

/**
 * Mic capture at 16 kHz and playback at 24 kHz (the daemon's formats), each
 * on its own thread. In TALK mode the capture runs the echo canceller with
 * the playback as its reference.
 */
class AudioEngine(
    private val ctx: Context,
    val mode: SpeakerMode,
    gain: Float,
    private val onMic: (ByteArray) -> Unit,
    private val onPlayback: (state: String, playedMs: Long) -> Unit,
) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val ref = RefRing(EchoCanceller.RATE)
    private val aec = EchoCanceller()
    private val limiter = Limiter(gain)
    @Volatile private var running = false
    @Volatile var muted = false
    private var capture: Thread? = null
    private var playback: Thread? = null
    private var focus: AudioFocusRequest? = null

    // Playback state, guarded by lock.
    private val lock = Object()
    private val queue = ArrayDeque<FloatArray>()
    private var queuedSamples = 0
    @Volatile private var flushRequested = false
    @Volatile private var playing = false
    @Volatile private var lastPlayedAt = 0L
    private var track: AudioTrack? = null
    private var runStartHead = 0L
    private var written = 0L

    // Stats for the ping.
    @Volatile var micPeak = 0
    @Volatile var drains = 0
    val echo: EchoCanceller.Stats get() = aec.stats
    val gain: Float get() = limiter.gain
    fun setGain(g: Float) { limiter.gain = g }

    fun bufferMs(): Int {
        val t = track ?: return 0
        val inTrack = max(0L, written - t.playbackHeadPosition.toLong())
        return ((queuedSamples + inTrack) / 24).toInt()
    }

    fun underruns(): Int = track?.underrunCount ?: 0

    fun takeVoicePeak(): Int {
        val p = limiter.peak
        limiter.peak = 0f
        return (p * 32767).toInt()
    }

    fun start() {
        if (running) return
        running = true
        routeAudio()
        playback = Thread(::playLoop, "sheepdog-play").apply { start() }
        capture = Thread(::captureLoop, "sheepdog-mic").apply { start() }
    }

    fun stop() {
        running = false
        capture?.join(1000)
        playback?.join(1000)
        capture = null
        playback = null
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        if (mode == SpeakerMode.CALL) {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    /** Audio from the daemon, PCM16 at 24 kHz. */
    fun enqueue(pcm: ByteArray) {
        val f = Pcm.toFloat(pcm)
        synchronized(lock) {
            queue.addLast(f)
            queuedSamples += f.size
            lock.notifyAll()
        }
    }

    /** Drop everything not yet played (the daemon's flush, or Stop). */
    fun flush() {
        synchronized(lock) {
            queue.clear()
            queuedSamples = 0
            flushRequested = true
            lock.notifyAll()
        }
    }

    private fun routeAudio() {
        val attrs = AudioAttributes.Builder()
            .setUsage(if (mode == SpeakerMode.CALL) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build().also {
            am.requestAudioFocus(it)
        }
        if (mode == SpeakerMode.CALL) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } else {
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = EchoCanceller.RATE
        val source = when (mode) {
            SpeakerMode.CALL -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION // no echo cancelling, no gain control
        }
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(min, rate * 2 / 5))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "mic open failed", e); return
        }
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        if (mode == SpeakerMode.CALL) {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
        }
        rec.startRecording()
        val n = 320 // 20 ms
        val buf = ShortArray(n)
        val refBuf = FloatArray(n)
        val out = FloatArray(n * 4)
        try {
            while (running) {
                val got = rec.read(buf, 0, n)
                if (got <= 0) continue
                var peak = 0
                for (i in 0 until got) peak = max(peak, abs(buf[i].toInt()))
                micPeak = peak
                val mic = Pcm.shortsToFloat(buf, got)
                when (mode) {
                    SpeakerMode.TALK -> {
                        ref.take(refBuf, got)
                        var o = 0
                        aec.process(mic, refBuf, got) { f -> f.copyInto(out, o); o += f.size }
                        if (o > 0 && !muted) onMic(Pcm.toBytes(out, o))
                    }
                    SpeakerMode.PAUSE -> {
                        val quiet = !playing && System.currentTimeMillis() - lastPlayedAt > 300
                        if (!muted) onMic(Pcm.toBytes(if (quiet) mic else FloatArray(got)))
                    }
                    SpeakerMode.CALL -> if (!muted) onMic(Pcm.toBytes(mic))
                }
            }
        } finally {
            rec.stop()
            effects.forEach { it.release() }
            rec.release()
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = 24000
        val usage = if (mode == SpeakerMode.CALL) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(min * 2, rate * 2 / 10)) // at least 100 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        val down = Downsampler(rate, EchoCanceller.RATE)
        val slice = 240 // 10 ms
        var idleSince = 0L
        try {
            while (running) {
                if (flushRequested) {
                    flushRequested = false
                    val played = playedMs(t)
                    t.pause(); t.flush(); t.play()
                    written = 0; runStartHead = 0
                    ref.clear()
                    if (playing) { playing = false; lastPlayedAt = System.currentTimeMillis() }
                    onPlayback("flushed", played)
                    continue
                }
                val chunk: FloatArray? = synchronized(lock) {
                    if (queue.isEmpty()) lock.wait(10)
                    queue.removeFirstOrNull()?.also { queuedSamples -= it.size }
                }
                if (chunk == null) {
                    if (playing) {
                        val now = System.currentTimeMillis()
                        if (idleSince == 0L) idleSince = now
                        val done = t.playbackHeadPosition.toLong() >= written
                        if (done && now - idleSince >= 300) {
                            playing = false
                            lastPlayedAt = now
                            drains++
                            onPlayback("drained", playedMs(t))
                        }
                    }
                    continue
                }
                idleSince = 0L
                if (!playing) {
                    playing = true
                    runStartHead = t.playbackHeadPosition.toLong()
                    written = max(written, runStartHead)
                    onPlayback("started", 0)
                }
                var i = 0
                while (i < chunk.size && running && !flushRequested) {
                    val m = minOf(slice, chunk.size - i)
                    val s = chunk.copyOfRange(i, i + m)
                    limiter.process(s)
                    t.write(Pcm.floatToShorts(s), 0, m, AudioTrack.WRITE_BLOCKING)
                    written += m
                    if (mode == SpeakerMode.TALK) ref.put(down.process(s))
                    i += m
                }
                lastPlayedAt = System.currentTimeMillis()
            }
        } finally {
            t.stop()
            t.release()
            track = null
        }
    }

    private fun playedMs(t: AudioTrack): Long = max(0L, t.playbackHeadPosition.toLong() - runStartHead) / 24

    companion object { const val TAG = "SheepdogAudio" }
}
