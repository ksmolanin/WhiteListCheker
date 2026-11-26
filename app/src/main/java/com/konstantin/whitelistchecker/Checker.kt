package com.konstantin.whitelistchecker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL

// Окно ожидания для «обычных» доменов: если за это время ни один не ответил,
// соединения обрываются, а вердикт считается по белым сайтам
const val NON_WHITELIST_WINDOW_MS = 4_000L

// Таймаут одного соединения. Больше окна в 4 секунды — чтобы отличить
// «сайт молчит» от «сайт ответил, но медленно»
private const val CONNECT_TIMEOUT_MS = 9_000
private const val READ_TIMEOUT_MS = 9_000
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) WhiteListChecker/3.0"

// Результат одной проверки.
// OK      — получили HTTP-ответ
// RESET   — соединение установилось и было оборвано (подпись ТСПУ)
// TIMEOUT — ответа не было вообще
// ABORTED — проверку прервали сами (ранний вердикт или конец окна)
enum class CheckOutcome(val label: String) {
    OK("OK"),
    RESET("RESET"),
    TIMEOUT("--"),
    ABORTED("STOP")
}

// Итог проверки
enum class Verdict(val code: String) {
    ON("on"),
    OFF("off"),
    UNAVAILABLE("unavailable"),
    UNKNOWN("unknown");

    companion object {
        fun fromCode(code: String?): Verdict? = values().firstOrNull { it.code == code }
    }
}

data class CheckResult(val domain: String, val outcome: CheckOutcome) {
    val reachable: Boolean get() = outcome == CheckOutcome.OK
}

/**
 * Одно живое соединение, которое можно оборвать из другого потока.
 * Нужно, чтобы «сбросить» проверку, как только вердикт уже понятен:
 * висящий сокет закрывается через disconnect(), и поток проверки сразу
 * выходит из read/connect вместо того, чтобы ждать свой 9-секундный таймаут.
 */
class CheckSession {

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    var cancelled: Boolean = false
        private set

    @Synchronized
    fun attach(newConnection: HttpURLConnection) {
        if (cancelled) {
            runCatching { newConnection.disconnect() }
            return
        }
        connection = newConnection
    }

    @Synchronized
    fun release() {
        connection = null
    }

    fun cancel() {
        cancelled = true
        val toClose = synchronized(this) {
            val current = connection
            connection = null
            current
        }
        runCatching { toClose?.disconnect() }
    }
}

object Checker {

    // Проверяем, есть ли среди активных сетей хоть одна с транспортом VPN.
    // Если VPN включён с блокировкой «трафик только через VPN» — прямые
    // соединения в обход VPN будут рубиться системой, и проверка даст
    // ложный результат
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.forEach { network ->
            val capabilities = cm.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                return true
            }
        }
        return false
    }

    // Одна проверка: реальное TCP/TLS-соединение и HTTP-ответ, а не ICMP-пинг
    fun checkDomain(network: Network, domain: String, session: CheckSession): CheckResult {
        if (session.cancelled) return CheckResult(domain, CheckOutcome.ABORTED)

        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://$domain/")
            connection = network.openConnection(url) as HttpURLConnection
            session.attach(connection)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val code = connection.responseCode
            if (session.cancelled) {
                CheckResult(domain, CheckOutcome.ABORTED)
            } else if (code > 0) {
                CheckResult(domain, CheckOutcome.OK)
            } else {
                CheckResult(domain, CheckOutcome.TIMEOUT)
            }
        } catch (e: SocketException) {
            when {
                session.cancelled -> CheckResult(domain, CheckOutcome.ABORTED)
                e.message?.contains("reset", ignoreCase = true) == true -> CheckResult(domain, CheckOutcome.RESET)
                else -> CheckResult(domain, CheckOutcome.TIMEOUT)
            }
        } catch (e: SocketTimeoutException) {
            CheckResult(domain, if (session.cancelled) CheckOutcome.ABORTED else CheckOutcome.TIMEOUT)
        } catch (e: Exception) {
            CheckResult(domain, if (session.cancelled) CheckOutcome.ABORTED else CheckOutcome.TIMEOUT)
        } finally {
            session.release()
            runCatching { connection?.disconnect() }
        }
    }
}
