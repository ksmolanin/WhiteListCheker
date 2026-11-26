package com.konstantin.whitelistchecker.bypass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.konstantin.whitelistchecker.MainActivity
import com.konstantin.whitelistchecker.R
import io.openflux.bridge.mobile.Mobile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Режим «Выходная нода»: устройство само становится нодой OpenFlux
 * (классическая нода на транспорте Телемост). Клиенты подключаются к той же
 * конференции и ходят в интернет через телефон.
 *
 * В отличие от [BypassService] (VPN-клиент), здесь VPN не нужен: ядро
 * OpenFlux раздаёт трафик через userspace-стек, поэтому это обычный
 * foreground-сервис, а не VpnService.
 */
class BypassNodeService : Service() {

    companion object {
        const val ACTION_START = "com.konstantin.whitelistchecker.BYPASS_NODE_START"
        const val ACTION_STOP = "com.konstantin.whitelistchecker.BYPASS_NODE_STOP"
        const val EXTRA_LINK = "link"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_NAME = "name"

        private const val CHANNEL_ID = "bypass_node_status"
        private const val NOTIFICATION_ID = 12
        private const val CONNECT_TIMEOUT_MS = 60_000L

        @Volatile
        var running = false
            private set

        @Volatile
        var connected = false
            private set

        @Volatile
        var shareLink: String? = null
            private set

        @Volatile
        var lastError: String? = null
            private set

        val sentBytes = AtomicLong()
        val receivedBytes = AtomicLong()

        fun resetStats() {
            sentBytes.set(0)
            receivedBytes.set(0)
        }
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        startInForeground(getString(R.string.bypass_node_notif_connecting))

        val link = intent?.getStringExtra(EXTRA_LINK).orEmpty()
        val secret = intent?.getStringExtra(EXTRA_SECRET).orEmpty()
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()

        if (link.isBlank()) {
            fail(getString(R.string.bypass_node_error_no_link))
            return START_NOT_STICKY
        }

        lastError = null
        resetStats()
        running = true

        worker = thread(name = "bypass-node-start", isDaemon = true) {
            startNode(link, secret, name)
        }
        return START_NOT_STICKY
    }

    private fun startNode(link: String, secret: String, name: String) {
        try {
            Mobile.setDebugLevel(0)
            val error = Mobile.startExit("telemost", link, secret, "batched", "", "")
            if (error.isNotEmpty()) {
                fail(error)
                return
            }

            // Ссылку для клиентов ядро готовит сразу после запуска ноды.
            shareLink = runCatching {
                Mobile.exitShareLink(localAddress().orEmpty(), name.ifBlank { "Профиль" })
            }.getOrNull()

            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (running && !Mobile.exitIsConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
            }
            if (!running) return

            connected = Mobile.exitIsConnected()
            updateNotification(getString(R.string.bypass_node_notif_running))

            // Держим сервис живым и обновляем счётчики, пока нода работает.
            while (running) {
                sentBytes.set(Mobile.exitBytesSent())
                receivedBytes.set(Mobile.exitBytesReceived())
                if (!Mobile.exitIsRunning()) {
                    fail(getString(R.string.bypass_node_error_stopped))
                    return
                }
                Thread.sleep(1000)
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(message: String) {
        lastError = message
        shutdown()
    }

    private fun shutdown() {
        running = false
        connected = false
        runCatching { Mobile.stopExit() }
        shareLink = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** Адрес телефона в локальной сети — для прямого транспорта (backup). */
    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    override fun onDestroy() {
        running = false
        connected = false
        runCatching { Mobile.stopExit() }
        shareLink = null
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        createChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bypass_node_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, BypassNodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bypass_node_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(this))
            .addAction(0, getString(R.string.bypass_node_notif_stop), stop)
            .build()
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
