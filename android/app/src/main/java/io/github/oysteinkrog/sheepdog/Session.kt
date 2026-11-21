package io.github.oysteinkrog.sheepdog

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

data class Line(val id: Long, val dir: String, val text: String, val final: Boolean)

data class Option(val index: Int, val label: String, val description: String, val checked: Boolean)

data class Question(
    val id: String,
    val agent: String,
    val summary: String,
    val multi: Boolean,
    val options: List<Option>,
)

data class UiState(
    val paired: Boolean = false,
    val computer: String = "",
    val connected: Boolean = false, // the user wants a call
    val socket: String = "off", // off, connecting, open, retrying
    val voice: String = "", // the daemon's voice status
    val muted: Boolean = false,
    val playing: Boolean = false,
    val talking: Boolean = false,
    val lines: List<Line> = emptyList(),
    val questions: List<Question> = emptyList(),
    val ticks: Map<String, Set<Int>> = emptyMap(),
    val busy: Set<String> = emptySet(),
    val notice: String = "",
    val mode: SpeakerMode = SpeakerMode.TALK,
    val gain: Float = 2f,
    val textScale: Float = 1f,
    val keepScreenOn: Boolean = false,
)

/** The one voice session of the app: the /ws socket, the audio and the questions. */
object Session {
    private const val TAG = "Sheepdog"
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    lateinit var daemon: Daemon
        private set
    private lateinit var app: Context
    private val worker = HandlerThread("sheepdog-net").apply { start() }
    private val h = Handler(worker.looper)
    private var ws: WebSocket? = null
    private var audio: AudioEngine? = null
    private var events: Call? = null
    private var retry = 0
    private var lineId = 0L
    private var lastPong = 0L
    private var stopRetrying = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        daemon = Daemon(app)
        val p = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _ui.update {
            it.copy(
                paired = daemon.pairing != null,
                computer = daemon.pairing?.name ?: "",
                mode = runCatching { SpeakerMode.valueOf(p.getString("mode", "TALK")!!) }.getOrDefault(SpeakerMode.TALK),
                gain = p.getFloat("gain", 2f),
                textScale = p.getFloat("text_scale", 1f),
                keepScreenOn = p.getBoolean("keep_screen_on", false),
            )
        }
        if (daemon.pairing != null) startEvents()
    }

    private fun saveSetting(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().apply(edit).apply()
    }

    // ------------------------------------------------------------ pairing

    fun pair(link: PairLink, done: (String?) -> Unit) = h.post {
        val err = try {
            daemon.pair(link); null
        } catch (e: DaemonError) {
            e.message
        } catch (e: Exception) {
            "Could not reach the computer: ${e.message}"
        }
        if (err == null) {
            _ui.update { it.copy(paired = true, computer = link.name) }
            startEvents()
        }
        done(err)
    }

    fun unpair() = h.post {
        hangUpNow()
        events?.cancel()
        daemon.unpair()
        _ui.update { UiState(mode = it.mode, gain = it.gain, textScale = it.textScale) }
    }

    // ------------------------------------------------------------ settings

    fun setMode(m: SpeakerMode) {
        saveSetting { putString("mode", m.name) }
        _ui.update { it.copy(mode = m) }
        h.post { audio?.let { if (it.mode != m) { restartAudio() } } }
    }

    fun setGain(g: Float) {
        saveSetting { putFloat("gain", g) }
        _ui.update { it.copy(gain = g) }
        audio?.setGain(g)
    }

    fun setTextScale(s: Float) {
        saveSetting { putFloat("text_scale", s) }
        _ui.update { it.copy(textScale = s) }
    }

    fun setKeepScreenOn(on: Boolean) {
        saveSetting { putBoolean("keep_screen_on", on) }
        _ui.update { it.copy(keepScreenOn = on) }
    }

    // ------------------------------------------------------------ call

    /** Start a call: the service keeps the app running with the screen off. */
    fun connect() {
        if (_ui.value.connected) return
        _ui.update { it.copy(connected = true, notice = "") }
        VoiceService.start(app)
        stopRetrying = false
        h.post { openSocket() }
    }

    fun hangUp() = h.post { hangUpNow() }

    private fun hangUpNow() {
        _ui.update { it.copy(connected = false, socket = "off", voice = "", playing = false, talking = false) }
        h.removeCallbacksAndMessages(RECONNECT)
        h.removeCallbacks(pinger)
        ws?.close(1000, "hang up")
        ws = null
        audio?.stop()
        audio = null
        VoiceService.stop(app)
    }

    fun toggleMute() {
        val m = !_ui.value.muted
        _ui.update { it.copy(muted = m) }
        audio?.muted = m
        send(JSONObject().put("type", "mute").put("on", m))
    }

    /** Stop: drop what is playing and run the stop procedure. */
    fun stopTalking() {
        audio?.flush()
        send(JSONObject().put("type", "cancel"))
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        if (ws == null) {
            _ui.update { it.copy(notice = "Connect first to type to the assistant.") }
            return
        }
        send(JSONObject().put("type", "text").put("text", text.trim()))
    }

    /** New conversation (POST /api/conversation/reset). */
    fun newConversation() = h.post {
        try {
            daemon.authed("POST", "/api/conversation/reset", JSONObject().put("via", "android_app"))
            _ui.update { it.copy(lines = emptyList(), notice = "Started a new conversation.") }
        } catch (e: Exception) {
            _ui.update { it.copy(notice = "Could not start a new conversation: ${e.message}") }
        }
    }

    private fun restartAudio() {
        audio?.stop()
        audio = null
        if (ws != null) startAudio()
    }

    private fun startAudio() {
        val s = _ui.value
        val a = AudioEngine(app, s.mode, s.gain,
            onMic = { bytes -> ws?.send(bytes.toByteString()) },
            onPlayback = { state, played ->
                _ui.update { it.copy(playing = state == "started") }
                val m = JSONObject().put("type", "playback").put("state", state)
                if (state != "started") m.put("played_ms", played)
                send(m)
            })
        a.muted = s.muted
        a.start()
        audio = a
    }

    private fun openSocket() {
        if (!_ui.value.connected) return
        _ui.update { it.copy(socket = if (retry == 0) "connecting" else "retrying") }
        val req = try {
            daemon.request("/ws").build()
        } catch (e: Exception) {
            scheduleReconnect("Cannot sign in: ${e.message}")
            return
        }
        ws = daemon.wsHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = h.post {
                if (ws !== webSocket) return@post
                retry = 0
                lastPong = System.currentTimeMillis()
                _ui.update { it.copy(socket = "open", notice = "") }
                webSocket.send(JSONObject().put("type", "client-ready").put("protocol", 1).put("client",
                    JSONObject().put("name", "sheepdog-android").put("version", BuildConfig.VERSION_NAME).put("platform", "android")).toString())
                webSocket.send(JSONObject().put("type", "mute").put("on", _ui.value.muted).toString())
                if (audio == null) startAudio()
                h.removeCallbacks(pinger)
                h.postDelayed(pinger, 5000)
                refreshState()
            }.let {}

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (ws === webSocket) audio?.enqueue(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = h.post {
                if (ws === webSocket) handle(JSONObject(text))
            }.let {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = h.post {
                if (ws !== webSocket) return@post
                ws = null
                when {
                    code == 4001 -> { stopRetrying = true; note("This app is too old for the computer's sheepdog. Update the app.") }
                    code == 4003 -> { stopRetrying = true; note("This phone was removed on the computer. Pair it again.") }
                }
                scheduleReconnect(null)
            }.let {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = h.post {
                if (ws !== webSocket) return@post
                ws = null
                if (response?.code == 401) daemon.dropTicket()
                scheduleReconnect(t.message)
            }.let {}
        })
    }

    private val RECONNECT = Any()

    private fun scheduleReconnect(why: String?) {
        h.removeCallbacks(pinger)
        if (!_ui.value.connected) return
        if (stopRetrying) { hangUpNow(); return }
        val delay = minOf(10_000L, 500L * (1 shl minOf(retry, 5)))
        retry++
        _ui.update { it.copy(socket = "retrying", voice = "", notice = why?.let { w -> "Reconnecting: $w" } ?: it.notice) }
        h.postAtTime({ openSocket() }, RECONNECT, android.os.SystemClock.uptimeMillis() + delay)
    }

    private val pinger = object : Runnable {
        override fun run() {
            val w = ws ?: return
            if (System.currentTimeMillis() - lastPong > 15_000) {
                w.cancel() // onFailure reconnects
                return
            }
            val a = audio
            val m = JSONObject().put("type", "ping")
            if (a != null) {
                m.put("peak", a.micPeak).put("underruns", a.underruns()).put("drains", a.drains).put("buffer_ms", a.bufferMs())
                    .put("audio_state", "running").put("aec", if (a.mode == SpeakerMode.CALL) "true" else "false")
                    .put("voice_peak", a.takeVoicePeak()).put("volume_gain", a.gain.toDouble())
                if (a.mode == SpeakerMode.TALK) {
                    m.put("echo_delay_ms", a.echo.delayMs).put("echo_erle_db", a.echo.erleDb.toDouble()).put("barge_ins", a.echo.bargeIns)
                }
                _ui.update { it.copy(talking = a.echo.talking) }
            }
            w.send(m.toString())
            h.postDelayed(this, 5000)
        }
    }

    private fun send(m: JSONObject) {
        ws?.send(m.toString())
    }

    private fun note(text: String) = _ui.update { it.copy(notice = text) }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "pong" -> lastPong = System.currentTimeMillis()
            "flush" -> {
                audio?.flush()
                closeOpenOut()
            }
            "status" -> {
                val v = m.optString("voice")
                val reason = m.optString("reason", "")
                _ui.update { it.copy(voice = if (reason == "standby") "standby" else v) }
                if (v == "closed" && reason == "replaced") {
                    stopRetrying = true
                    note("Another phone or browser took over the call.")
                }
                if (reason == "protocol_too_old") stopRetrying = true
            }
            "transcript" -> transcript(m.optString("dir"), m.optString("text", ""), m.optBoolean("final"))
            "error" -> note(m.optString("message"))
            "voice-command" -> when (m.optString("action")) {
                "stop" -> stopTalking()
                "mute" -> if (m.optBoolean("on") != _ui.value.muted) toggleMute()
            }
        }
    }

    private fun transcript(dir: String, text: String, final: Boolean) {
        _ui.update { s ->
            val lines = s.lines.toMutableList()
            val last = lines.lastOrNull()
            if (dir == "in") closeOut(lines)
            if (last != null && last.dir == dir && !last.final) {
                lines[lines.lastIndex] = last.copy(text = last.text + text, final = final)
            } else if (text.isNotEmpty()) {
                lines += Line(++lineId, dir, text.trimStart(), final)
            }
            s.copy(lines = lines.takeLast(300))
        }
    }

    private fun closeOut(lines: MutableList<Line>) {
        for (i in lines.indices.reversed()) {
            val l = lines[i]
            if (l.dir == "out" && !l.final) { lines[i] = l.copy(final = true); break }
        }
    }

    private fun closeOpenOut() = _ui.update { s -> s.copy(lines = s.lines.toMutableList().also { closeOut(it) }) }

    // ------------------------------------------------------------ questions

    private fun startEvents() {
        h.post {
            events?.cancel()
            refreshState()
            streamEvents()
        }
    }

    private fun streamEvents() {
        val call = try {
            daemon.streamHttp.newCall(daemon.request("/events?kinds=pending,daemon").build())
        } catch (e: Exception) {
            h.postDelayed({ streamEvents() }, 5000); return
        }
        events = call
        Thread {
            try {
                call.execute().use { r ->
                    if (r.code == 401) daemon.dropTicket()
                    if (r.isSuccessful) {
                        h.post { refreshState() }
                        val src = r.body!!.source()
                        while (!call.isCanceled()) {
                            val line = src.readUtf8Line() ?: break
                            if (line.isEmpty()) h.post { refreshState() } // one event ended
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "events: ${e.message}")
            }
            if (!call.isCanceled() && daemon.pairing != null) h.postDelayed({ streamEvents() }, 3000)
        }.apply { isDaemon = true; name = "sheepdog-events" }.start()
    }

    private var refreshPending = false

    fun refreshState() {
        if (refreshPending) return
        refreshPending = true
        h.postDelayed({
            refreshPending = false
            try {
                val st = daemon.authed("GET", "/api/state", null)
                _ui.update { s ->
                    val qs = parseQuestions(st)
                    s.copy(questions = qs, ticks = s.ticks.filterKeys { k -> qs.any { it.id == k } })
                }
            } catch (e: Exception) {
                Log.i(TAG, "state: ${e.message}")
            }
        }, 150)
    }

    private fun parseQuestions(st: JSONObject): List<Question> {
        val arr = st.optJSONArray("questions")?.takeIf { it.length() > 0 }
            ?: st.optJSONObject("pending_action")?.let { JSONArray().put(it) } ?: JSONArray()
        val out = mutableListOf<Question>()
        for (i in 0 until arr.length()) {
            val q = arr.getJSONObject(i)
            if (q.optString("kind") != "answer_prompt") continue
            val opts = q.optJSONArray("options") ?: continue
            if (opts.length() == 0) continue
            val options = List(opts.length()) { j ->
                val o = opts.getJSONObject(j)
                Option(o.optInt("index"), o.optString("title").ifBlank { o.optString("label") }, o.optString("description"), o.optBoolean("checked"))
            }
            val summary = listOf("summary", "spoken_summary", "question").map { q.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
                ?: "An agent is waiting for your answer."
            out += Question(q.getString("id"), q.optString("agent").ifBlank { "An agent" }, summary, q.optBoolean("multi_select"), options)
        }
        return out
    }

    fun toggleTick(q: Question, index: Int) = _ui.update { s ->
        val cur = s.ticks[q.id] ?: q.options.filter { it.checked }.map { it.index }.toSet()
        s.copy(ticks = s.ticks + (q.id to if (index in cur) cur - index else cur + index))
    }

    fun ticked(s: UiState, q: Question): Set<Int> = s.ticks[q.id] ?: q.options.filter { it.checked }.map { it.index }.toSet()

    fun answer(q: Question, option: Int) = post(q, JSONObject().put("id", q.id).put("option", option))
    fun submit(q: Question) = post(q, JSONObject().put("id", q.id).put("options", JSONArray(ticked(_ui.value, q).sorted())))
    fun dismiss(q: Question) = post(q, JSONObject().put("id", q.id).put("dismiss", true))

    private fun post(q: Question, body: JSONObject) {
        _ui.update { it.copy(busy = it.busy + q.id) }
        h.post {
            val err = try {
                daemon.authed("POST", "/api/pending/answer", body, mapOf("Idempotency-Key" to Daemon.idempotencyKey()))
                null
            } catch (e: DaemonError) {
                if (e.status == 404 || e.status == 409 || e.status == 410) "That question was already answered or has gone." else e.message
            } catch (e: Exception) {
                e.message
            }
            _ui.update { it.copy(busy = it.busy - q.id, notice = err ?: it.notice) }
            refreshState()
        }
    }
}
