package io.github.oysteinkrog.sheepdog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) Session.connect()
    }

    private var scanned: ((String) -> Unit)? = null
    private val scan = registerForActivityResult(ScanContract()) { r -> r.contents?.let { scanned?.invoke(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        enableEdgeToEdge()
        intent?.data?.let { handleLink(it) }
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFD79921)) else lightColorScheme(primary = Color(0xFF9A6A00))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s by Session.ui.collectAsState()
                    LaunchedEffect(s.keepScreenOn, s.connected) {
                        if (s.keepScreenOn && s.connected) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    if (!s.paired) PairScreen(onScan = { cb -> scanned = cb; scan.launch(ScanOptions().setPrompt("Scan the sheepdog pairing code").setBeepEnabled(false).setOrientationLocked(false)) })
                    else MainScreen(s, onConnect = ::connect)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { handleLink(it) }
    }

    private var linkError by mutableStateOf<String?>(null)

    private fun handleLink(u: Uri) {
        val link = PairLink.parse(u.toString().replaceFirst("sheepdog://pair", "https://pair/voice")) ?: return
        Session.pair(link) { err -> runOnUiThread { linkError = err } }
    }

    private fun connect() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) Session.connect() else askMic.launch(missing.toTypedArray())
    }

    @Composable
    private fun PairScreen(onScan: ((String) -> Unit) -> Unit) {
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val shownError = error ?: linkError
        fun tryPair(t: String) {
            val link = PairLink.parse(t)
            if (link == null) { error = "That is not a sheepdog pairing link."; return }
            busy = true; error = null
            Session.pair(link) { err -> runOnUiThread { busy = false; error = err } }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sheepdog", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Pair this phone with your computer. On the computer, run sheepdog-pair-https (or open the setup page), then scan the code it shows.")
            Button(onClick = { onScan { tryPair(it) } }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Scan the pairing code") }
            Text("Or paste the pairing link:")
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("https://…/voice#pair=1&…") }, minLines = 2)
            OutlinedButton(onClick = { tryPair(text) }, enabled = !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Pair") }
            if (busy) Text("Pairing…")
            shownError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun MainScreen(s: UiState, onConnect: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s.lines.size, s.lines.lastOrNull()?.text?.length) {
        if (s.lines.isNotEmpty()) list.animateScrollToItem(s.lines.size - 1)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        // Top: where the call is.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = statusOf(s)
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.computer, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { settings = true }) { Text("Settings") }
        }
        if (s.notice.isNotBlank()) {
            Text(s.notice, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        // Questions first: they block an agent.
        if (s.questions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (q in s.questions) QuestionCard(s, q)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(s.lines, key = { it.id }) { l -> Bubble(l, s.textScale) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text("Type to the assistant") }, singleLine = true)
            TextButton(onClick = { Session.sendText(typed); typed = "" }, enabled = typed.isNotBlank()) { Text("Send") }
        }
        // One row of buttons, big enough to hit without looking.
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tall = Modifier.height(64.dp)
            if (!s.connected) {
                Button(onClick = onConnect, modifier = tall.weight(1f)) { Text("Connect", fontSize = 18.sp) }
            } else {
                Button(onClick = { Session.toggleMute() }, modifier = tall.weight(1.6f),
                    colors = if (s.muted) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) {
                    Text(if (s.muted) "Unmute" else "Mute", fontSize = 18.sp)
                }
                OutlinedButton(onClick = { Session.stopTalking() }, modifier = tall.weight(1f)) { Text("Stop") }
            }
            OutlinedButton(onClick = { Session.newConversation() }, modifier = tall.weight(1f)) { Text("New") }
            if (s.connected) OutlinedButton(onClick = { Session.hangUp() }, modifier = tall.weight(1f)) { Text("Hang up") }
        }
    }
    if (settings) SettingsDialog(s) { settings = false }
}

private fun statusOf(s: UiState): Pair<Color, String> = when {
    !s.connected -> Color.Gray to "Not on a call"
    s.socket != "open" -> Color(0xFFD79921) to "Connecting…"
    s.muted -> Color(0xFFCC241D) to "Muted"
    s.talking -> Color(0xFF458588) to "Hearing you over the voice"
    s.playing -> Color(0xFF98971A) to "Speaking"
    s.voice == "standby" || s.voice == "connected" -> Color(0xFF98971A) to "Listening"
    else -> Color(0xFFD79921) to (s.voice.ifBlank { "Connected" })
}

@Composable
private fun Bubble(l: Line, scale: Float) {
    val mine = l.dir == "in"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            l.text,
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = (17 * scale).sp,
            lineHeight = (24 * scale).sp,
        )
    }
}

@Composable
private fun QuestionCard(s: UiState, q: Question) {
    val busy = q.id in s.busy
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(q.agent, style = MaterialTheme.typography.labelMedium)
            Text(q.summary, fontWeight = FontWeight.Bold)
            if (q.multi) {
                val ticks = Session.ticked(s, q)
                Text("Tick one or more, then Submit.", style = MaterialTheme.typography.bodySmall)
                for (o in q.options) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(o.index in ticks, { Session.toggleTick(q, o.index) }, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text(o.label)
                            if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Session.submit(q) }, enabled = !busy && ticks.isNotEmpty()) { Text("Submit (${ticks.size})") }
                    OutlinedButton(onClick = { Session.dismiss(q) }, enabled = !busy) { Text("Dismiss") }
                }
            } else {
                for (o in q.options) {
                    OutlinedButton(onClick = { Session.answer(q, o.index) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("${o.index}. ${o.label}")
                            if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedButton(onClick = { Session.dismiss(q) }, enabled = !busy) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(s: UiState, close: () -> Unit) {
    var confirmUnpair by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("Done") } }, title = { Text("Settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Speaker", fontWeight = FontWeight.Bold)
            for (m in SpeakerMode.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.mode == m, { Session.setMode(m) })
                    Column {
                        Text(m.label)
                        Text(m.help, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Volume boost: ${"%.1f".format(s.gain)}×", fontWeight = FontWeight.Bold)
            Slider(s.gain, { Session.setGain((it * 2).roundToInt() / 2f) }, valueRange = 1f..4f, steps = 5)
            Text("Text size: ${(s.textScale * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            Slider(s.textScale, { Session.setTextScale((it * 20).roundToInt() / 20f) }, valueRange = 0.8f..1.8f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep the screen on during a call", Modifier.weight(1f))
                Switch(s.keepScreenOn, { Session.setKeepScreenOn(it) })
            }
            Spacer(Modifier.height(8.dp))
            Text("App ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmUnpair = true }) { Text("Unpair this phone") }
        }
    })
    if (confirmUnpair) AlertDialog(onDismissRequest = { confirmUnpair = false },
        title = { Text("Unpair?") }, text = { Text("The computer forgets this phone. You need a new pairing code to use it again.") },
        confirmButton = { TextButton(onClick = { confirmUnpair = false; close(); Session.unpair() }) { Text("Unpair") } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } })
}
