package io.github.oysteinkrog.sheepdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a call running while the screen is off or another app is in front:
 * a foreground service with the microphone, a wake lock and a Wi-Fi lock.
 * The notification has Mute and Hang up.
 */
class VoiceService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANG_UP -> { Session.hangUp(); return START_NOT_STICKY }
            ACTION_MUTE -> { Session.toggleMute(); return START_NOT_STICKY }
        }
        ServiceCompat.startForeground(this, ID, notification(Session.ui.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sheepdog:call").apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sheepdog:call").apply { acquire() }
        }
        if (watch == null) {
            watch = scope.launch {
                Session.ui.map { Triple(it.muted, it.socket, it.voice) }.distinctUntilChanged().collect {
                    getSystemService(NotificationManager::class.java).notify(ID, notification(Session.ui.value))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(s: UiState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Call", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, VoiceService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            s.socket != "open" -> "Reconnecting…"
            s.muted -> "Muted"
            s.voice == "standby" -> "Listening"
            else -> "On a call with ${s.computer.ifBlank { "your computer" }}"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sheepdog")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, if (s.muted) "Unmute" else "Mute", action(ACTION_MUTE, 1))
            .addAction(0, "Hang up", action(ACTION_HANG_UP, 2))
            .build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "call"
        const val ACTION_HANG_UP = "hang_up"
        const val ACTION_MUTE = "mute"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, VoiceService::class.java)) }
    }
}
