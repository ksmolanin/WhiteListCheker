package com.konstantin.whitelistchecker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        const val CHANNEL_ID = "whitelist_status_channel"
        const val NOTIFICATION_ID = 1

        private const val CHECK_TIMEOUT_SECONDS = 30L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.description = context.getString(R.string.notif_channel_description)
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun doWork(): Result {
        val context = applicationContext

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = requestCellularNetworkBlocking(connectivityManager) ?: run {
            HistoryStore.get(context).record(0, 0, Verdict.UNAVAILABLE.code)
            handleResult(context, Verdict.UNAVAILABLE)
            return Result.success()
        }

        val whiteDomains = SitesStore.whiteSites(context)
        val blackDomains = SitesStore.blockSites(context)

        val latch = CountDownLatch(1)
        var verdict: Verdict = Verdict.UNKNOWN
        var stats = CheckEngine.Stats(0, 0, 0)

        val engine = CheckEngine(network, whiteDomains, blackDomains, object : CheckEngine.Listener {
            override fun onDomainResult(domain: String, group: CheckEngine.Group, result: CheckResult) = Unit

            override fun onFinished(resultVerdict: Verdict, resultStats: CheckEngine.Stats) {
                verdict = resultVerdict
                stats = resultStats
                latch.countDown()
            }
        })
        engine.start()

        if (!latch.await(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            engine.cancel()
            return Result.success()
        }

        HistoryStore.get(context).record(stats.whitelistReachable, stats.nonWhitelistReachable, verdict.code)
        handleResult(context, verdict)

        return Result.success()
    }

    // Блокирующий запрос мобильной сети — Worker выполняется в фоновом потоке,
    // так что блокировка здесь не подвешивает интерфейс приложения
    private fun requestCellularNetworkBlocking(connectivityManager: ConnectivityManager): Network? {
        val latch = CountDownLatch(1)
        var result: Network? = null

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                result = network
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }

        runCatching { connectivityManager.requestNetwork(request, callback) }
        latch.await(8, TimeUnit.SECONDS)
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        return result
    }

    private fun handleResult(context: Context, verdict: Verdict) {
        val previousStatus = SettingsPrefs.getLastStatus(context)
        SettingsPrefs.setLastStatus(context, verdict.code)

        // Уведомляем только при реальном изменении статуса, не каждый раз
        if (previousStatus != null && previousStatus == verdict.code) return

        val text = context.getString(
            when (verdict) {
                Verdict.ON -> R.string.verdict_on
                Verdict.OFF -> R.string.verdict_off
                Verdict.UNAVAILABLE -> R.string.verdict_unavailable
                Verdict.UNKNOWN -> R.string.verdict_unknown
            }
        )

        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
