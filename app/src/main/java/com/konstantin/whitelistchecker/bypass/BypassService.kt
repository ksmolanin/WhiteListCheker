package com.konstantin.whitelistchecker.bypass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.konstantin.whitelistchecker.MainActivity
import com.konstantin.whitelistchecker.R
import io.openflux.bridge.mobile.Mobile
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * VPN-сервис раздела «Обход»: поднимает ядро OpenFlux (транспорт Телемост),
 * создаёт TUN-интерфейс и гоняет через него трафик.
 *
 * Режимы:
 *  - MODE_ALL      — весь трафик через туннель (маршрут по умолчанию);
 *  - MODE_SELECTED — только выбранные сайты (маршруты /32 на их IP) и,
 *                    если задан список, только выбранные приложения.
 */
class BypassService : VpnService() {

    private var tunnel: PacketTunnel? = null
    private var worker: Thread? = null

    companion object {
        const val ACTION_START = "com.konstantin.whitelistchecker.BYPASS_START"
        const val ACTION_STOP = "com.konstantin.whitelistchecker.BYPASS_STOP"
        const val EXTRA_LINK = "link"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SITES = "sites"
        const val EXTRA_APPS = "apps"

        private const val CHANNEL_ID = "bypass_status"
        private const val NOTIFICATION_ID = 11
        private const val MTU = 1400
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val FALLBACK_DNS = "1.1.1.1"

        @Volatile
        var running = false
            private set

        @Volatile
        var connected = false
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(stopSelf = true)
            return START_NOT_STICKY
        }

        startInForeground(getString(R.string.bypass_notif_connecting))

        val link = intent?.getStringExtra(EXTRA_LINK).orEmpty()
        val secret = intent?.getStringExtra(EXTRA_SECRET).orEmpty()
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: BypassPrefs.MODE_ALL
        val sites = intent?.getStringArrayListExtra(EXTRA_SITES) ?: arrayListOf()
        val apps = intent?.getStringArrayListExtra(EXTRA_APPS) ?: arrayListOf()

        if (link.isBlank()) {
            fail(getString(R.string.bypass_error_no_link))
            return START_NOT_STICKY
        }

        lastError = null
        resetStats()
        running = true

        worker = thread(name = "bypass-start", isDaemon = true) {
            startTunnel(link, secret, mode, sites, apps)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(
        link: String,
        secret: String,
        mode: String,
        sites: List<String>,
        apps: List<String>,
    ) {
        try {
            Mobile.setDebugLevel(0)
            val error = Mobile.start("telemost", link, secret, "batched", "", "")
            if (!error.isNullOrEmpty()) {
                fail(error)
                return
            }

            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (running && !Mobile.isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
            }
            if (!running) return
            if (!Mobile.isConnected()) {
                Mobile.stop()
                fail(getString(R.string.bypass_error_timeout))
                return
            }

            connected = true
            updateNotification(getString(R.string.bypass_notif_connected))

            val dns = networkDns()
            val tun = establishTunnel(mode, sites, apps, dns)
            if (tun == null) {
                Mobile.stop()
                fail(getString(R.string.bypass_error_tun))
                return
            }

            tunnel = PacketTunnel(
                tun, dns, sentBytes, receivedBytes,
                onProblem = { /* DNS-ошибки не рвут туннель */ },
                onFailure = { message -> fail(message) },
            ).also { it.start() }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun establishTunnel(
        mode: String,
        sites: List<String>,
        apps: List<String>,
        dns: String,
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("WLchek bypass")
            .setMtu(MTU)
            .addAddress("10.10.10.2", 24)
            .addDnsServer(dns)
            .setConfigureIntent(openAppIntent(this))

        if (Build.VERSION.SDK_INT >= 29) builder.setBlocking(true)

        // Какие приложения пускать в туннель.
        val allow = apps.filter { it != packageName && it.isNotBlank() }
        if (allow.isEmpty()) {
            builder.addDisallowedApplication(packageName)
        } else {
            allow.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
            }
        }

        if (mode == BypassPrefs.MODE_SELECTED) {
            // Только выбранные сайты: маршруты /32 на их адреса + DNS-сервер,
            // чтобы запросы имен тоже попадали в туннель.
            resolveIpv4(dns)?.let { runCatching { builder.addRoute(it, 32) } }
            sites.forEach { domain ->
                resolveAllIpv4(domain).forEach { ip ->
                    runCatching { builder.addRoute(ip, 32) }
                }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        return runCatching { builder.establish() }.getOrNull()
    }

    private fun resolveAllIpv4(domain: String): List<String> =
        runCatching {
            InetAddress.getAllByName(domain)
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    private fun resolveIpv4(host: String): String? =
        runCatching { InetAddress.getByName(host) as? Inet4Address }
            .getOrNull()?.hostAddress

    private fun networkDns(): String = runCatching {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props: LinkProperties? = manager.getLinkProperties(manager.activeNetwork)
        props?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull() ?: FALLBACK_DNS

    private fun fail(message: String) {
        lastError = message
        shutdown(stopSelf = true)
    }

    private fun shutdown(stopSelf: Boolean) {
        running = false
        connected = false
        runCatching { Mobile.stop() }
        tunnel?.close()
        tunnel = null
        if (stopSelf) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    override fun onRevoke() {
        fail(getString(R.string.bypass_error_revoked))
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown(stopSelf = false)
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
                getString(R.string.bypass_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, BypassService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bypass_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(this))
            .addAction(0, getString(R.string.bypass_notif_stop), stop)
            .build()
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
