package com.konstantin.whitelistchecker

import android.net.Network
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Один цикл проверки. Списки сайтов приходят снаружи (SitesStore), поэтому
 * движок не знает про конкретные домены.
 *
 * Правила:
 *  1. Как только ответил хотя бы один сайт НЕ из белого списка — проверка сразу
 *     прекращается, соединения обрываются, вердикт Verdict.OFF.
 *  2. Если ни один «обычный» сайт не ответил за NON_WHITELIST_WINDOW_MS (4 сек),
 *     их соединения сбрасываются, и если к этому моменту ответили все белые
 *     сайты — Verdict.ON.
 *  3. Никто не ответил вообще — Verdict.UNAVAILABLE.
 *  4. Ответила только часть белых — Verdict.UNKNOWN (честно: непонятно).
 */
class CheckEngine(
    private val network: Network,
    private val whiteDomains: List<String>,
    private val blackDomains: List<String>,
    private val listener: Listener
) {

    enum class Group { WHITELIST, NON_WHITELIST }

    data class Stats(
        val whitelistReachable: Int,
        val nonWhitelistReachable: Int,
        val durationMs: Long
    )

    interface Listener {
        fun onDomainResult(domain: String, group: Group, result: CheckResult)
        fun onFinished(verdict: Verdict, stats: Stats)
    }

    private val whiteSessions = ConcurrentHashMap<String, CheckSession>()
    private val blackSessions = ConcurrentHashMap<String, CheckSession>()

    private val whiteResults = ConcurrentHashMap<String, CheckResult>()
    private val blackResults = ConcurrentHashMap<String, CheckResult>()

    private val whitePending = AtomicInteger(whiteDomains.size)
    private val blackPending = AtomicInteger(blackDomains.size)

    // Окно ожидания «обычных» доменов закрыто: новые их ответы на вердикт не влияют
    private val windowClosed = AtomicBoolean(false)

    // Вердикт уже выдан — дальше ничего не решаем
    private val finished = AtomicBoolean(false)

    // Успели получить ответ вне белого списка до закрытия окна — вердикт OFF
    private val earlyBlackSuccess = AtomicBoolean(false)

    private val workers: ExecutorService =
        Executors.newFixedThreadPool((whiteDomains.size + blackDomains.size).coerceAtLeast(1))
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val startedAt = SystemClock.elapsedRealtime()

    fun start() {
        // Если списки пустые, проверять нечего
        if (whiteDomains.isEmpty() && blackDomains.isEmpty()) {
            finish(Verdict.UNKNOWN)
            return
        }
        // При пустом белом списке вердикт ON невозможен по определению
        if (blackDomains.isEmpty()) {
            finish(Verdict.UNKNOWN)
            return
        }

        whiteDomains.forEach { launch(it, Group.WHITELIST) }
        blackDomains.forEach { launch(it, Group.NON_WHITELIST) }

        timer.schedule({ closeBlackWindow() }, NON_WHITELIST_WINDOW_MS, TimeUnit.MILLISECONDS)
    }

    /** Прервать цикл (например, экран закрыли) — без выдачи вердикта. */
    fun cancel() {
        finished.set(true)
        cancelSessions()
        timer.shutdownNow()
        workers.shutdownNow()
    }

    private fun launch(domain: String, group: Group) {
        val session = CheckSession()
        when (group) {
            Group.WHITELIST -> whiteSessions[domain] = session
            Group.NON_WHITELIST -> blackSessions[domain] = session
        }

        workers.execute {
            val result = Checker.checkDomain(network, domain, session)

            when (group) {
                Group.WHITELIST -> {
                    whiteResults[domain] = result
                    whitePending.decrementAndGet()
                }

                Group.NON_WHITELIST -> {
                    blackResults[domain] = result
                    if (!result.reachable) blackPending.decrementAndGet()
                }
            }

            listener.onDomainResult(domain, group, result)

            when (group) {
                Group.WHITELIST -> maybeFinish()

                Group.NON_WHITELIST -> {
                    if (result.reachable) {
                        // Пункт 1: первый же ответ вне белого списка — сразу вердикт OFF
                        if (windowClosed.compareAndSet(false, true)) {
                            earlyBlackSuccess.set(true)
                            finish(Verdict.OFF)
                        }
                    } else {
                        // Все «обычные» уже отвалились — держать окно 4 секунды незачем
                        if (blackPending.get() <= 0) closeBlackWindow()
                        maybeFinish()
                    }
                }
            }
        }
    }

    /** Пункт 2: окно ожидания закрылось — обрываем всё висящее на «обычных» доменах. */
    private fun closeBlackWindow() {
        if (!windowClosed.compareAndSet(false, true)) return
        blackSessions.values.forEach { it.cancel() }
        // По истечении окна: если уже ответил хотя бы один белый сайт — сразу «включены»,
        // не дожидаясь остальных белых (они могут висеть до 9 секунд).
        if (whiteResults.values.any { it.reachable }) {
            finish(Verdict.ON)
            return
        }
        maybeFinish()
    }

    private fun maybeFinish() {
        if (finished.get()) return
        if (!windowClosed.get()) return
        if (whitePending.get() != 0) return
        finish(decide())
    }

    private fun decide(): Verdict {
        val whiteReachable = whiteResults.values.count { it.reachable }
        return when {
            earlyBlackSuccess.get() -> Verdict.OFF
            whiteDomains.isEmpty() -> Verdict.UNKNOWN
            // Достаточно хотя бы одного ответившего белого сайта: «небелые»
            // за 4 секунды промолчали, значит фильтрация включена
            whiteReachable > 0 -> Verdict.ON
            else -> Verdict.UNAVAILABLE
        }
    }

    private fun finish(verdict: Verdict) {
        if (!finished.compareAndSet(false, true)) return

        // Обрываем все ещё висящие соединения, чтобы не занимать сеть и батарею
        cancelSessions()
        workers.shutdown()
        timer.shutdown()

        val stats = Stats(
            whitelistReachable = whiteResults.values.count { it.reachable },
            nonWhitelistReachable = blackResults.values.count { it.reachable },
            durationMs = SystemClock.elapsedRealtime() - startedAt
        )
        listener.onFinished(verdict, stats)
    }

    private fun cancelSessions() {
        whiteSessions.values.forEach { it.cancel() }
        blackSessions.values.forEach { it.cancel() }
    }
}
