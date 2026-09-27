package app.obereg.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.obereg.core.CoreBridge

/**
 * Сервис, который держит сессию ядра и поднятый туннель.
 *
 * Важно: это **не** `VpnService`. Туннель живёт внутри процесса приложения
 * (локальный SOCKS5-прокси ядра) и обслуживает только трафик приложения —
 * системный VPN не требуется и не запрашивается.
 */
class TunnelService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        startForeground(NOTIFICATION_ID, buildNotification("Запуск туннеля…", null))

        if (!CoreBridge.isAvailable) {
            updateNotification("Ядро недоступно", CoreBridge.availabilityError)
            running = false
            stopForegroundCompat()
            stopSelf()
            return
        }

        val mode = CoreHolder.desiredMode(this)
        CoreHolder.ensureSession(this, mode)
        val raw = CoreBridge.startSession(CoreHolder.handle)
        val status = TunnelStatus.from(raw)
        running = status.state != "error"
        updateNotification(
            title = "Туннель: ${status.state} (${status.mode})",
            text = status.listenAddr?.let { "локальный прокси $it" } ?: status.lastError,
        )
    }

    private fun stopTunnel() {
        CoreHolder.release()
        running = false
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_tunnel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text ?: "")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()

    private fun updateNotification(title: String, text: String?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "app.obereg.player.action.START"
        const val ACTION_STOP = "app.obereg.player.action.STOP"
        const val CHANNEL_ID = "obereg-tunnel"
        const val NOTIFICATION_ID = 42

        @Volatile
        var running: Boolean = false
            private set

        /** Запустить сервис туннеля. */
        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Остановить сервис туннеля. */
        fun stop(context: Context) {
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
