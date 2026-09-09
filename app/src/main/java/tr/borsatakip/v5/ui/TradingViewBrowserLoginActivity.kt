package tr.borsatakip.v5.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONTokener
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.TradingViewAuthClient

/**
 * TradingView web girişi için tanılama ve kurtarma ekranı.
 *
 * Güvenlik ilkeleri:
 * - CAPTCHA/2FA otomatik çözülmez.
 * - Kullanıcı adı/şifre okunmaz veya loglanmaz.
 * - Cookie değerleri tanı ekranına/loglara yazılmaz; yalnız var/yok bilgisi kullanılır.
 * - SSL hatalarında devam edilmez.
 * - Custom Tab / sistem tarayıcısı fallback'tir; tarayıcı oturumunun WebView'a aktarılacağı varsayılmaz.
 */
class TradingViewBrowserLoginActivity : BaseActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var importButton: Button
    private lateinit var retryButton: Button
    private lateinit var openBrowserButton: Button
    private lateinit var detailsButton: Button
    private lateinit var webContainer: FrameLayout
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var settings: SettingsStore

    private val handler = Handler(Looper.getMainLooper())
    private var pageFinished = false
    private var renderVerified = false
    private var fatalLoadError = false
    private var lastMainHttpStatus: Int? = null
    private var lastProgress = 0
    private var lastTitle: String? = null
    private var lastUrl: String? = null
    private var lastSslState = "hata yok"
    private var rendererState = "çalışıyor"
    private var jsState = "bekleniyor"
    private var domState = "bekleniyor"
    private var domTextLength = 0
    private var domHtmlLength = 0
    private var consoleErrorCount = 0
    private var consoleWarningCount = 0
    private val recentConsole = ArrayDeque<String>()

    private val loadTimeoutRunnable = Runnable {
        if (!pageFinished && !fatalLoadError) {
            Log.w(TAG, "load timeout; host=${safeHost(webView.url)}")
            showLoadError(
                "TradingView ana sayfa yüklemesi zamanında tamamlanmadı. Tekrar deneyin veya güvenli tarayıcıda açın."
            )
        }
    }

    private val renderTimeoutRunnable = Runnable {
        if (pageFinished && !renderVerified && !fatalLoadError) {
            runRenderHealthProbe(finalProbe = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(Color.rgb(1, 25, 45))
        }
        val title = TextView(this).apply {
            text = "TradingView Girişi"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        status = TextView(this).apply {
            text = "TradingView bağlantısı kontrol ediliyor..."
            textSize = 14f
            setTextColor(Color.rgb(167, 181, 200))
            setPadding(0, dp(8), 0, dp(6))
        }
        val steps = TextView(this).apply {
            text = "1  Giriş ekranını yükle\n2  CAPTCHA / 2FA varsa normal şekilde tamamla\n3  Oturum algılanınca uygulamaya aktar"
            textSize = 13f
            setTextColor(Color.rgb(230, 237, 245))
            setPadding(0, dp(4), 0, dp(10))
        }
        importButton = Button(this).apply {
            text = "OTURUMU UYGULAMAYA AKTAR"
            isEnabled = false
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        retryButton = Button(this).apply {
            text = "TEKRAR DENE"
            visibility = View.GONE
        }
        openBrowserButton = Button(this).apply { text = "GÜVENLİ TARAYICIDA AÇ" }
        actions.addView(retryButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        actions.addView(openBrowserButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })

        detailsButton = Button(this).apply {
            text = "TEKNİK AYRINTILAR"
        }
        diagnosticText = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            setTextColor(Color.rgb(210, 220, 232))
            setBackgroundColor(Color.rgb(8, 38, 59))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        webContainer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(6, 27, 45)) }
        loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(6, 27, 45))
            addView(ProgressBar(this@TradingViewBrowserLoginActivity))
            addView(TextView(this@TradingViewBrowserLoginActivity).apply {
                text = "TradingView giriş ekranı hazırlanıyor..."
                textSize = 14f
                setTextColor(Color.rgb(230, 237, 245))
                setPadding(0, dp(12), 0, 0)
            })
        }

        root.addView(title)
        root.addView(status)
        root.addView(steps)
        root.addView(importButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(detailsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(diagnosticText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(webContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)

        importButton.setOnClickListener { importSession() }
        retryButton.setOnClickListener { rebuildAndLoad() }
        openBrowserButton.setOnClickListener { openInCustomTab() }
        detailsButton.setOnClickListener {
            diagnosticText.visibility = if (diagnosticText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshDiagnosticText()
        }
        rebuildAndLoad()
    }

    private fun resetDiagnostics() {
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.removeCallbacks(renderTimeoutRunnable)
        pageFinished = false
        renderVerified = false
        fatalLoadError = false
        lastMainHttpStatus = null
        lastProgress = 0
        lastTitle = null
        lastUrl = null
        lastSslState = "hata yok"
        rendererState = "çalışıyor"
        jsState = "bekleniyor"
        domState = "bekleniyor"
        domTextLength = 0
        domHtmlLength = 0
        consoleErrorCount = 0
        consoleWarningCount = 0
        recentConsole.clear()
    }

    private fun rebuildAndLoad() {
        resetDiagnostics()
        importButton.isEnabled = false
        retryButton.visibility = View.GONE
        status.text = "TradingView bağlantısı kontrol ediliyor..."
        loadingOverlay.visibility = View.VISIBLE

        if (::webView.isInitialized) {
            runCatching {
                webView.stopLoading()
                webContainer.removeView(webView)
                webView.destroy()
            }
        }

        webView = WebView(this)
        configureWebView(webView)
        webContainer.removeAllViews()
        webContainer.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        webContainer.addView(loadingOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        refreshDiagnosticText()

        if (!isNetworkAvailable()) {
            showLoadError("İnternet bağlantısı algılanmadı. Bağlantıyı kontrol edip tekrar deneyin.")
            return
        }

        webView.loadUrl(LOGIN_URL)
        handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
    }

    private fun configureWebView(view: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.setBackgroundColor(Color.WHITE)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        view.settings.loadsImagesAutomatically = true
        view.settings.javaScriptCanOpenWindowsAutomatically = true

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                lastProgress = newProgress
                refreshDiagnosticText()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                lastTitle = title
                refreshDiagnosticText()
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val message = consoleMessage ?: return false
                when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> consoleErrorCount++
                    ConsoleMessage.MessageLevel.WARNING -> consoleWarningCount++
                    else -> Unit
                }
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    val safe = "${message.messageLevel()}: ${message.message().take(160)}"
                    if (recentConsole.size >= MAX_CONSOLE_LINES) recentConsole.removeFirst()
                    recentConsole.addLast(safe)
                    Log.w(TAG, "console ${message.messageLevel()} line=${message.lineNumber()} sourceHost=${safeHost(message.sourceId())}")
                    refreshDiagnosticText()
                }
                return false
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageFinished = false
                renderVerified = false
                fatalLoadError = false
                lastUrl = url
                loadingOverlay.visibility = View.VISIBLE
                status.text = "TradingView ana sayfası yükleniyor..."
                Log.d(TAG, "onPageStarted host=${safeHost(url)}")
                refreshDiagnosticText()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageFinished = true
                lastUrl = url
                handler.removeCallbacks(loadTimeoutRunnable)
                status.text = "Sayfa yükleme tamamlandı; görünür içerik doğrulanıyor..."
                Log.d(TAG, "onPageFinished host=${safeHost(url)}")
                refreshDiagnosticText()
                runRenderHealthProbe(finalProbe = false)
                handler.removeCallbacks(renderTimeoutRunnable)
                handler.postDelayed(renderTimeoutRunnable, RENDER_TIMEOUT_MS)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                Log.w(TAG, "WebView error main=${request?.isForMainFrame} host=${request?.url?.host} code=${error?.errorCode}")
                if (request?.isForMainFrame == true) {
                    showLoadError("TradingView bağlantısı kurulamadı. Ağ veya Android System WebView durumunu kontrol edin.")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) lastMainHttpStatus = errorResponse?.statusCode
                Log.w(TAG, "WebView HTTP main=${request?.isForMainFrame} host=${request?.url?.host} status=${errorResponse?.statusCode}")
                refreshDiagnosticText()
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    showLoadError("TradingView giriş servisi HTTP ${errorResponse?.statusCode ?: "hata"} döndürdü.")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                lastSslState = "SSL hata kodu ${error?.primaryError ?: "bilinmiyor"}"
                Log.e(TAG, "SSL error primary=${error?.primaryError} host=${safeHost(error?.url)}")
                handler?.cancel()
                showLoadError("TradingView SSL doğrulaması başarısız oldu. Güvenlik nedeniyle bağlantıya devam edilmedi.")
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                rendererState = if (detail?.didCrash() == true) "çöktü" else "sonlandırıldı"
                Log.e(TAG, "WebView renderer gone; didCrash=${detail?.didCrash()}")
                handler.removeCallbacks(loadTimeoutRunnable)
                handler.removeCallbacks(renderTimeoutRunnable)
                fatalLoadError = true
                runCatching { if (view != null) webContainer.removeView(view) }
                runCatching { view?.destroy() }
                showLoadError("Android WebView görüntüleme süreci durdu. Tekrar Dene yeni bir WebView oluşturacaktır.")
                return true
            }
        }
    }

    private fun runRenderHealthProbe(finalProbe: Boolean) {
        if (!::webView.isInitialized || fatalLoadError) return
        val script = """
            (function() {
              try {
                var b = document.body;
                var textLen = b ? (b.innerText || '').trim().length : 0;
                var htmlLen = b ? (b.innerHTML || '').length : 0;
                return [document.readyState || '', textLen, htmlLen, document.title || '', location.href || ''].join('|');
              } catch (e) {
                return ['JS_ERROR', 0, 0, '', location.href || ''].join('|');
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            val parts = decoded.split('|', limit = 5)
            val readyState = parts.getOrNull(0).orEmpty()
            domTextLength = parts.getOrNull(1)?.toIntOrNull() ?: 0
            domHtmlLength = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (parts.getOrNull(3).orEmpty().isNotBlank()) lastTitle = parts[3]
            if (parts.getOrNull(4).orEmpty().isNotBlank()) lastUrl = parts[4]

            jsState = if (readyState == "JS_ERROR" || decoded.isBlank()) "hata / sonuç yok" else "çalışıyor"
            val visibleEnough = domTextLength >= MIN_VISIBLE_TEXT || domHtmlLength >= MIN_HTML_SIZE
            domState = when {
                visibleEnough -> "içerik var"
                readyState == "complete" -> "boş / görünür içerik doğrulanamadı"
                else -> "hazırlanıyor ($readyState)"
            }

            if (visibleEnough) {
                renderVerified = true
                handler.removeCallbacks(renderTimeoutRunnable)
                loadingOverlay.visibility = View.GONE
                updateCookieState(renderReady = true)
            } else if (finalProbe) {
                renderVerified = false
                loadingOverlay.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
                status.text = "⚠ TradingView yüklemeyi tamamladı ancak görünür giriş içeriği doğrulanamadı. Teknik ayrıntıları açın, tekrar deneyin veya güvenli tarayıcıyı kullanın."
                updateCookieState(renderReady = false)
            } else {
                status.text = "Sayfa tamamlandı; JavaScript/DOM görünür içerik kontrolü sürüyor..."
                handler.postDelayed({ runRenderHealthProbe(finalProbe = false) }, 1_500L)
            }
            refreshDiagnosticText()
        }
    }

    private fun updateCookieState(renderReady: Boolean) {
        val cookies = CookieManager.getInstance().getCookie(TRADINGVIEW_ORIGIN).orEmpty()
        val sessionId = cookieValue(cookies, "sessionid")
        importButton.isEnabled = !sessionId.isNullOrBlank()
        if (!renderReady) return
        status.text = if (sessionId.isNullOrBlank()) {
            "TradingView görünür içeriği doğrulandı. Girişi tamamlayın; CAPTCHA/2FA varsa TradingView ekranında normal şekilde çözün."
        } else {
            "TradingView oturumu algılandı. Kimlik bilgileri okunmadı. Oturumu aktarmak için düğmeye basın."
        }
        refreshDiagnosticText()
    }

    private fun showLoadError(message: String) {
        fatalLoadError = true
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.removeCallbacks(renderTimeoutRunnable)
        loadingOverlay.visibility = View.GONE
        status.text = "⚠ $message"
        retryButton.visibility = View.VISIBLE
        importButton.isEnabled = false
        refreshDiagnosticText()
    }

    private fun refreshDiagnosticText() {
        if (!::diagnosticText.isInitialized) return
        val cookieHeader = if (::webView.isInitialized) CookieManager.getInstance().getCookie(TRADINGVIEW_ORIGIN).orEmpty() else ""
        val cookieNames = cookieHeader.split(';').mapNotNull { item ->
            item.trim().substringBefore('=', "").takeIf { it.isNotBlank() }
        }.toSet()
        val sessionPresent = "sessionid" in cookieNames
        val webViewPkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val defaultBrowser = defaultBrowserInfo()
        val chrome = packageInfo("com.android.chrome")
        val network = if (isNetworkAvailable()) "✓ bağlı" else "✗ bağlantı yok"
        val http = lastMainHttpStatus?.toString() ?: if (fatalLoadError) "bilinmiyor" else "hata yakalanmadı"
        val render = when {
            renderVerified -> "✓ görünür içerik doğrulandı"
            pageFinished -> "⚠ doğrulanmadı"
            else -> "bekleniyor"
        }
        val consoleSummary = "hata=$consoleErrorCount, uyarı=$consoleWarningCount"
        val consoleLines = if (recentConsole.isEmpty()) "yok" else recentConsole.joinToString("\n")

        diagnosticText.text = buildString {
            append("WEBVIEW TANILAMA\n")
            append("────────────────────────\n")
            append("URL host: ${safeHost(lastUrl) ?: "bekleniyor"}\n")
            append("HTTP: $http\n")
            append("Progress: $lastProgress%\n")
            append("Title: ${lastTitle?.takeIf { it.isNotBlank() } ?: "yok"}\n")
            append("JavaScript: $jsState\n")
            append("DOM: $domState • text=$domTextLength • html=$domHtmlLength\n")
            append("Render: $render\n")
            append("Cookie: ${if (cookieNames.isNotEmpty()) "✓ ${cookieNames.size} ad" else "yok"} • session=${if (sessionPresent) "var" else "yok"}\n")
            append("SSL: $lastSslState\n")
            append("Renderer: $rendererState\n")
            append("Console: $consoleSummary\n")
            append("Network: $network\n")
            append("WebView: ${webViewPkg?.packageName ?: "bilinmiyor"} ${webViewPkg?.versionName ?: ""}\n")
            append("Varsayılan tarayıcı: $defaultBrowser\n")
            append("Chrome: ${chrome ?: "yüklü değil / bulunamadı"}\n")
            append("Son console uyarıları:\n$consoleLines")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun defaultBrowserInfo(): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tradingview.com"))
        val info = packageManager.resolveActivity(intent, 0)?.activityInfo ?: return "bulunamadı"
        return packageInfo(info.packageName) ?: info.packageName
    }

    private fun packageInfo(packageName: String): String? = runCatching {
        val p = packageManager.getPackageInfo(packageName, 0)
        "$packageName ${p.versionName ?: ""}".trim()
    }.getOrNull()

    private fun importSession() {
        val cookies = CookieManager.getInstance().getCookie(TRADINGVIEW_ORIGIN).orEmpty()
        val sessionId = cookieValue(cookies, "sessionid")
        val sessionSign = cookieValue(cookies, "sessionid_sign")
        if (sessionId.isNullOrBlank()) {
            status.text = "Oturum çerezi bulunamadı. TradingView girişini tamamlayıp tekrar deneyin."
            importButton.isEnabled = false
            refreshDiagnosticText()
            return
        }
        settings.tradingViewSessionId = sessionId
        settings.tradingViewSessionSign = sessionSign.orEmpty()
        settings.tradingViewAuthToken = ""
        settings.tradingViewAuthenticatedAt = System.currentTimeMillis()
        status.text = "Oturum aktarıldı; TradingView tarafından doğrulanıyor..."
        importButton.isEnabled = false

        lifecycleScope.launch {
            val result = TradingViewAuthClient(this@TradingViewBrowserLoginActivity).validateStoredSession()
            if (result.ok) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                status.text = "Oturum çerezi alındı ancak doğrulama tamamlanamadı: ${result.message}"
                importButton.isEnabled = true
            }
            refreshDiagnosticText()
        }
    }

    private fun openInCustomTab() {
        val uri = Uri.parse(LOGIN_URL)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
            status.text = "TradingView güvenli tarayıcı sekmesinde açıldı. CAPTCHA/2FA işlemlerini orada tamamlayabilirsiniz. Tarayıcı oturumu WebView'a otomatik aktarılmaz."
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, uri)
            if (fallback.resolveActivity(packageManager) != null) {
                startActivity(fallback)
                status.text = "Custom Tab açılamadı; TradingView sistem tarayıcısında açıldı. Tarayıcı oturumu WebView'a otomatik aktarılmaz."
            } else {
                status.text = "Bu cihazda TradingView sayfasını açabilecek bir tarayıcı bulunamadı."
            }
        }
        refreshDiagnosticText()
    }

    private fun safeHost(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }

    private fun cookieValue(cookieHeader: String, name: String): String? {
        val prefix = "$name="
        return cookieHeader.split(';').asSequence().map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }?.substringAfter('=')?.takeIf { it.isNotBlank() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.removeCallbacks(renderTimeoutRunnable)
        if (::webView.isInitialized) {
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TV_LOGIN"
        private const val TRADINGVIEW_ORIGIN = "https://www.tradingview.com"
        private const val LOGIN_URL = "https://www.tradingview.com/accounts/signin/"
        private const val LOAD_TIMEOUT_MS = 12_000L
        private const val RENDER_TIMEOUT_MS = 8_000L
        private const val MIN_VISIBLE_TEXT = 24
        private const val MIN_HTML_SIZE = 800
        private const val MAX_CONSOLE_LINES = 5
    }
}
