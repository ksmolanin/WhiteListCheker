package com.konstantin.whitelistchecker

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.konstantin.whitelistchecker.bypass.BypassNodeService
import com.konstantin.whitelistchecker.bypass.BypassPrefs
import com.konstantin.whitelistchecker.bypass.BypassService
import io.openflux.bridge.mobile.Mobile
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Экран — WebView с интерфейсом из assets/index.html.
 * Мост в обе стороны:
 *   JS → Kotlin: window.Bridge.* (см. WebBridge)
 *   Kotlin → JS: window.onNativeState / onCheckStarted / onSiteResult /
 *                onCheckFinished / onVpnDetected
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var connectivityManager: ConnectivityManager

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var engine: CheckEngine? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var autoTimer: Runnable? = null

    @Volatile
    private var isChecking = false

    @Volatile
    private var pageReady = false

    private var checkStartedAt = 0L
    private var rxBefore = -1L
    private var txBefore = -1L

    private var pendingBypassLink = ""
    private var pendingBypassSecret = ""

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (pendingBypassLink.isNotBlank()) startBypassService()
        }

    private val qrScannerLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val content = result.contents
            if (!content.isNullOrBlank()) {
                val parsed = JSONObject(WebBridge().bypassParseLink(content))
                val link = parsed.optString("link", "")
                if (link.isNotBlank()) {
                    BypassPrefs.addProfile(
                        applicationContext,
                        BypassPrefs.Profile(
                            name = parsed.optString("name", "Профиль"),
                            link = link,
                            secret = parsed.optString("secret", ""),
                        )
                    )
                    pushBypassSettings()
                }
            }
        }

    private companion object {
        const val CELLULAR_WAIT_MS = 5_000L
        const val MIN_AUTO_INTERVAL_SEC = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        applyEdgeToEdge()
        setupWebView()
        setupBackNavigation()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        CheckWorker.ensureChannel(this)
    }

    /* ---------- WebView ---------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(0xFF1A1A1A.toInt())
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
            setSupportZoom(false)
        }

        webView.addJavascriptInterface(WebBridge(), "Bridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pushState()
                pushBypassSettings()
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun runJs(code: String) {
        if (!pageReady) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(code, null)
            }
        }
    }

    private fun pushState() {
        val json = buildStateJson()
        runJs("window.onNativeState(" + JSONObject.quote(json) + ")")
    }

    private fun buildStateJson(): String {
        val context = applicationContext
        val json = JSONObject()
        json.put("status", SettingsPrefs.getLastStatus(context) ?: "idle")
        json.put("checking", isChecking)
        json.put("whiteSites", JSONArray(SitesStore.whiteSites(context)))
        json.put("blockSites", JSONArray(SitesStore.blockSites(context)))
        json.put("autoCheck", SettingsPrefs.isAutoCheckEnabled(context))
        json.put("intervalSec", SettingsPrefs.getIntervalSeconds(context))
        return json.toString()
    }

    /** Объект, доступный из JS как window.Bridge */
    inner class WebBridge {

        @JavascriptInterface
        fun getStateJson(): String = buildStateJson()

        @JavascriptInterface
        fun startCheck() {
            runOnUiThread { startCheckInternal() }
        }

        /** Проверка «всё равно», даже когда включён VPN (кнопка в модальном окне). */
        @JavascriptInterface
        fun startCheckForced() {
            runOnUiThread { startCheckInternal(force = true) }
        }

        @JavascriptInterface
        fun addSite(type: String, domain: String) {
            SitesStore.add(applicationContext, type, domain)
            pushState()
        }

        @JavascriptInterface
        fun removeSite(type: String, domain: String) {
            SitesStore.remove(applicationContext, type, domain)
            pushState()
        }

        @JavascriptInterface
        fun setAutoCheck(enabled: Boolean) {
            SettingsPrefs.setAutoCheckEnabled(applicationContext, enabled)
            runOnUiThread { scheduleAutoCheck() }
        }

        @JavascriptInterface
        fun setInterval(seconds: Int) {
            SettingsPrefs.setIntervalSeconds(applicationContext, seconds)
            runOnUiThread { scheduleAutoCheck() }
        }

        /* ---------- раздел «Обход» ---------- */

        @JavascriptInterface
        fun bypassConnect(link: String, secret: String) {
            runOnUiThread {
                stopBypassNodeService()
                pendingBypassLink = link
                pendingBypassSecret = secret
                val prepare = VpnService.prepare(this@MainActivity)
                if (prepare != null) {
                    vpnPermissionLauncher.launch(prepare)
                } else {
                    startBypassService()
                }
            }
        }

        @JavascriptInterface
        fun bypassDisconnect() {
            runOnUiThread { stopBypassService() }
        }

        @JavascriptInterface
        fun bypassStateJson(): String {
            val json = JSONObject()
            json.put("running", BypassService.running)
            json.put("connected", BypassService.connected)
            json.put("sent", BypassService.sentBytes.get())
            json.put("received", BypassService.receivedBytes.get())
            BypassService.lastError?.let { json.put("error", it) }
            return json.toString()
        }

        @JavascriptInterface
        fun bypassSettingsJson(): String {
            val context = applicationContext
            val json = JSONObject()
            json.put("mode", BypassPrefs.mode(context))
            json.put("sites", JSONArray(BypassPrefs.sites(context)))
            json.put("apps", JSONArray(BypassPrefs.apps(context)))
            val profiles = JSONArray()
            BypassPrefs.profiles(context).forEach { profile ->
                profiles.put(
                    JSONObject()
                        .put("name", profile.name)
                        .put("link", profile.link)
                        .put("secret", profile.secret)
                )
            }
            json.put("profiles", profiles)
            json.put("selected", BypassPrefs.selectedIndex(context))
            return json.toString()
        }

        @JavascriptInterface
        fun bypassSetMode(mode: String) {
            BypassPrefs.setMode(applicationContext, mode)
            pushBypassSettings()
        }

        @JavascriptInterface
        fun bypassSetSites(values: String) {
            BypassPrefs.setSites(applicationContext, parseStringArray(values))
            pushBypassSettings()
        }

        @JavascriptInterface
        fun bypassSetApps(values: String) {
            BypassPrefs.setApps(applicationContext, parseStringArray(values))
            pushBypassSettings()
        }

        @JavascriptInterface
        fun bypassAddProfile(name: String, link: String, secret: String) {
            BypassPrefs.addProfile(
                applicationContext,
                BypassPrefs.Profile(name.ifBlank { "Профиль" }, link.trim(), secret.trim())
            )
            pushBypassSettings()
        }

        @JavascriptInterface
        fun bypassScanQr() {
            runOnUiThread {
                runCatching {
                    qrScannerLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Наведите камеру на QR-код")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }
            }
        }

        @JavascriptInterface
        fun bypassSelectProfile(index: Int) {
            BypassPrefs.setSelectedIndex(applicationContext, index)
            pushBypassSettings()
        }

        @JavascriptInterface
        fun bypassRemoveProfile(index: Int) {
            BypassPrefs.removeProfile(applicationContext, index)
            pushBypassSettings()
        }

        @JavascriptInterface
        fun bypassAppsJson(): String {
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = JSONArray()
            runCatching {
                pm.queryIntentActivities(launcherIntent, 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName }
                    .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                    .forEach { info ->
                        apps.put(
                            JSONObject()
                                .put("pkg", info.packageName)
                                .put("label", pm.getApplicationLabel(info).toString())
                        )
                    }
            }
            return apps.toString()
        }

        @JavascriptInterface
        fun bypassExternalIp() {
            Thread {
                val ip = runCatching {
                    val connection = URL("https://api.ipify.org").openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.inputStream.bufferedReader().use { it.readText().trim() }
                }.getOrNull().orEmpty()
                runJs("window.onExternalIp(" + JSONObject.quote(ip) + ")")
            }.start()
        }

        @JavascriptInterface
        fun bypassParseLink(raw: String): String {
            val result = JSONObject()
            val link = raw.trim().removePrefix("openflux://").removePrefix("openflux:")
            if (link.startsWith("v1/")) {
                runCatching {
                    val payload = link.removePrefix("v1/").trim()
                    val base64 = payload.replace('-', '+').replace('_', '/')
                    val padded = base64 + "=".repeat((4 - base64.length % 4) % 4)
                    val bytes = android.util.Base64.decode(padded, android.util.Base64.NO_WRAP)
                    val inflater = java.util.zip.Inflater(true)
                    inflater.setInput(bytes)
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buf)
                        if (n == 0) break
                        out.write(buf, 0, n)
                    }
                    inflater.end()
                    val json = JSONObject(out.toString("UTF-8"))
                    val name = json.optString("name", "")
                    val secret = json.optString("secret", "")
                    val transports = json.optJSONArray("transports")
                    val t0 = transports?.optJSONObject(0)
                    val docUrl = t0?.optString("url", "") ?: ""
                    result.put("name", name)
                    result.put("secret", secret)
                    result.put("link", docUrl)
                }
            } else if (link.contains("telemost.yandex.ru")) {
                // пользователь вставил голую ссылку на конференцию
                result.put("link", "https://" + link.removePrefix("https://").removePrefix("http://"))
            }
            return result.toString()
        }

        /* ---------- режим «Выходная нода» ---------- */

        @JavascriptInterface
        fun bypassNodeStart(link: String, secret: String, name: String) {
            runOnUiThread {
                stopBypassService()
                val intent = Intent(this@MainActivity, BypassNodeService::class.java).apply {
                    action = BypassNodeService.ACTION_START
                    putExtra(BypassNodeService.EXTRA_LINK, link.trim())
                    putExtra(BypassNodeService.EXTRA_SECRET, secret.trim())
                    putExtra(BypassNodeService.EXTRA_NAME, name.trim())
                }
                runCatching { ContextCompat.startForegroundService(this@MainActivity, intent) }
            }
        }

        @JavascriptInterface
        fun bypassNodeStop() {
            runOnUiThread { stopBypassNodeService() }
        }

        @JavascriptInterface
        fun bypassNodeStateJson(): String {
            val json = JSONObject()
            json.put("running", BypassNodeService.running)
            json.put("connected", BypassNodeService.connected)
            json.put("sent", BypassNodeService.sentBytes.get())
            json.put("received", BypassNodeService.receivedBytes.get())
            BypassNodeService.shareLink?.let { json.put("shareLink", it) }
            BypassNodeService.lastError?.let { json.put("error", it) }
            return json.toString()
        }

        @JavascriptInterface
        fun bypassNodeQrPng(): String {
            val link = BypassNodeService.shareLink ?: return ""
            return runCatching {
                val png = Mobile.shareQRPNG(link, 600)
                "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
            }.getOrDefault("")
        }

        @JavascriptInterface
        fun bypassNodeShare() {
            val link = BypassNodeService.shareLink ?: return
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                }
                runCatching { startActivity(Intent.createChooser(intent, "Поделиться ссылкой")) }
            }
        }

        @JavascriptInterface
        fun bypassNodeCopy() {
            val link = BypassNodeService.shareLink ?: return
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlux", link))
            }
        }

        @JavascriptInterface
        fun bypassNodeSettingsJson(): String {
            val context = applicationContext
            val json = JSONObject()
            json.put("link", BypassPrefs.nodeLink(context))
            json.put("secret", BypassPrefs.nodeSecret(context))
            return json.toString()
        }

        @JavascriptInterface
        fun bypassNodeSaveSettings(link: String, secret: String) {
            val context = applicationContext
            BypassPrefs.setNodeLink(context, link.trim())
            BypassPrefs.setNodeSecret(context, secret.trim())
        }
    }

    private fun parseStringArray(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { i -> array.getString(i) }
    }.getOrDefault(emptyList())

    private fun pushBypassSettings() {
        val json = WebBridge().bypassSettingsJson()
        runJs("window.onBypassSettings(" + JSONObject.quote(json) + ")")
    }

    private fun startBypassService() {
        val context = applicationContext
        val intent = Intent(this, BypassService::class.java).apply {
            action = BypassService.ACTION_START
            putExtra(BypassService.EXTRA_LINK, pendingBypassLink)
            putExtra(BypassService.EXTRA_SECRET, pendingBypassSecret)
            putExtra(BypassService.EXTRA_MODE, BypassPrefs.mode(context))
            putStringArrayListExtra(BypassService.EXTRA_SITES, ArrayList(BypassPrefs.sites(context)))
            putStringArrayListExtra(BypassService.EXTRA_APPS, ArrayList(BypassPrefs.apps(context)))
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun stopBypassService() {
        val intent = Intent(this, BypassService::class.java).apply {
            action = BypassService.ACTION_STOP
        }
        runCatching { startService(intent) }
        runCatching { stopService(intent) }
    }

    private fun stopBypassNodeService() {
        val intent = Intent(this, BypassNodeService::class.java).apply {
            action = BypassNodeService.ACTION_STOP
        }
        runCatching { startService(intent) }
        runCatching { stopService(intent) }
    }

    /* ---------- проверка ---------- */

    private fun startCheckInternal(force: Boolean = false) {
        if (isChecking) return

        // При включённом VPN замер бессмыслен: трафик идёт через туннель,
        // и тестер соврёт, что белые списки выключены.
        if (!force && vpnActive()) {
            runJs("window.onVpnDetected(" + JSONObject.quote(vpnKind()) + ")")
            return
        }

        val uid = Process.myUid()
        rxBefore = TrafficStats.getUidRxBytes(uid)
        txBefore = TrafficStats.getUidTxBytes(uid)
        checkStartedAt = SystemClock.elapsedRealtime()
        isChecking = true

        runJs("window.onCheckStarted()")
        requestCellularNetwork()
    }

    /** Есть ли сейчас активный VPN (наш обход или сторонний). */
    private fun vpnActive(): Boolean = runCatching {
        connectivityManager.allNetworks.any { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(false)

    /** "own" — наш обход, "external" — сторонний VPN. */
    private fun vpnKind(): String =
        if (BypassService.running) "own" else "external"

    private fun requestCellularNetwork() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (isChecking && engine == null) launchEngine(network)
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    if (isChecking && engine == null) failNoNetwork()
                }
            }
        }

        networkCallback = callback
        runCatching { connectivityManager.requestNetwork(request, callback) }

        uiHandler.postDelayed({
            if (isChecking && engine == null) failNoNetwork()
        }, CELLULAR_WAIT_MS)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun launchEngine(network: Network) {
        unregisterNetworkCallback()

        val whiteDomains = SitesStore.whiteSites(this)
        val blackDomains = SitesStore.blockSites(this)

        val newEngine = CheckEngine(network, whiteDomains, blackDomains, object : CheckEngine.Listener {
            override fun onDomainResult(domain: String, group: CheckEngine.Group, result: CheckResult) {
                val type = if (group == CheckEngine.Group.WHITELIST) SitesStore.TYPE_WHITE else SitesStore.TYPE_BLOCK
                runJs(
                    "window.onSiteResult(" +
                        JSONObject.quote(type) + "," +
                        JSONObject.quote(domain) + "," +
                        JSONObject.quote(result.outcome.label) + ")"
                )
            }

            override fun onFinished(verdict: Verdict, stats: CheckEngine.Stats) {
                runOnUiThread { handleFinished(verdict, stats) }
            }
        })

        engine = newEngine
        newEngine.start()
    }

    private fun handleFinished(verdict: Verdict, stats: CheckEngine.Stats) {
        if (isDestroyed) return

        isChecking = false
        engine = null
        unregisterNetworkCallback()

        SettingsPrefs.setLastStatus(this, verdict.code)
        val traffic = trafficKb()

        runJs(
            "window.onCheckFinished(" +
                JSONObject.quote(verdict.code) + "," +
                traffic + "," +
                stats.durationMs + ")"
        )

        ioExecutor.execute {
            runCatching {
                HistoryStore.get(applicationContext)
                    .record(stats.whitelistReachable, stats.nonWhitelistReachable, verdict.code)
            }
        }

        // Счётчики трафика в системе обновляются с задержкой: дочитываем
        // реальное значение через полсекунды, если не началась новая проверка
        val token = checkStartedAt
        uiHandler.postDelayed({
            if (!isChecking && checkStartedAt == token) {
                runJs("window.onTrafficUpdated(" + trafficKb() + ")")
            }
        }, 600)

        scheduleAutoCheck()
    }

    private fun failNoNetwork() {
        if (isDestroyed) return

        isChecking = false
        engine = null
        unregisterNetworkCallback()

        SettingsPrefs.setLastStatus(this, Verdict.UNAVAILABLE.code)
        val elapsed = if (checkStartedAt > 0) SystemClock.elapsedRealtime() - checkStartedAt else 0

        runJs("window.onCheckFinished(" + JSONObject.quote(Verdict.UNAVAILABLE.code) + ",-1," + elapsed + ")")

        ioExecutor.execute {
            runCatching {
                HistoryStore.get(applicationContext).record(0, 0, Verdict.UNAVAILABLE.code)
            }
        }

        scheduleAutoCheck()
    }

    private fun trafficKb(): Long {
        val uid = Process.myUid()
        val rxAfter = TrafficStats.getUidRxBytes(uid)
        val txAfter = TrafficStats.getUidTxBytes(uid)
        if (rxBefore < 0 || txBefore < 0 || rxAfter < 0 || txAfter < 0) return -1
        val bytes = (rxAfter - rxBefore) + (txAfter - txBefore)
        return if (bytes < 0) -1 else bytes / 1024
    }

    /* ---------- автопроверка ---------- */

    private fun scheduleAutoCheck() {
        autoTimer?.let { uiHandler.removeCallbacks(it) }
        autoTimer = null

        val enabled = SettingsPrefs.isAutoCheckEnabled(this)
        val interval = SettingsPrefs.getIntervalSeconds(this)

        // 15 минут и больше — отдаём WorkManager-у, он умеет работать в фоне
        Scheduler.apply(this, enabled, interval)

        if (!enabled || interval < MIN_AUTO_INTERVAL_SEC) return
        // Короткие интервалы живут, только пока приложение открыто
        if (interval >= Scheduler.MIN_BACKGROUND_INTERVAL_SEC) return

        val runnable = Runnable {
            if (!isChecking) startCheckInternal()
        }
        autoTimer = runnable
        uiHandler.postDelayed(runnable, interval * 1000L)
    }

    /* ---------- системное ---------- */

    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!pageReady) {
                    finish()
                    return
                }
                // Если открыта не главная вкладка — JS вернёт true и сам переключит её
                webView.evaluateJavascript("(window.androidBack && window.androidBack()) === true") { result ->
                    if (result != "true") finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        scheduleAutoCheck()
        pushState()
    }

    override fun onPause() {
        super.onPause()
        autoTimer?.let { uiHandler.removeCallbacks(it) }
        autoTimer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.cancel()
        engine = null
        unregisterNetworkCallback()
        uiHandler.removeCallbacksAndMessages(null)
        ioExecutor.shutdown()
        runCatching {
            webView.removeJavascriptInterface("Bridge")
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
}
