package tr.borsatakip.v5.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.TradingViewAuthClient

/**
 * TradingView web girişi için dayanıklı yardımcı ekran.
 * CAPTCHA/2FA asla otomatik çözülmez; kullanıcı TradingView arayüzünde normal şekilde tamamlar.
 * WebView kimlik bilgilerini okumaz. Yalnız WebView'ın oluşturduğu TradingView session çerezleri,
 * kullanıcı açıkça isterse uygulamaya aktarılır.
 * Custom Tab güvenli fallback'tir; tarayıcı oturumunun WebView'a aktarılacağı varsayılmaz.
 */
class TradingViewBrowserLoginActivity : BaseActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var retryButton: Button
    private lateinit var openBrowserButton: Button
    private lateinit var webContainer: FrameLayout
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var settings: SettingsStore

    private val handler = Handler(Looper.getMainLooper())
    private var pageFinished = false
    private var fatalLoadError = false

    private val timeoutRunnable = Runnable {
        if (!pageFinished && !fatalLoadError) {
            Log.w(TAG, "TradingView WebView timeout; host=${runCatching { Uri.parse(webView.url).host }.getOrNull()}")
            showLoadError("TradingView giriş ekranı zamanında yüklenemedi. Tekrar deneyin veya güvenli tarayıcıda açın.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)

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
            text = webViewDiagnostic("TradingView giriş ekranı hazırlanıyor...")
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

        webContainer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(6, 27, 45)) }
        loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(6, 27, 45))
            addView(ProgressBar(this@TradingViewBrowserLoginActivity))
            addView(TextView(this@TradingViewBrowserLoginActivity).apply {
                text = "TradingView giriş ekranı yükleniyor..."
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
        root.addView(webContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)

        importButton.setOnClickListener { importSession() }
        retryButton.setOnClickListener { rebuildAndLoad() }
        openBrowserButton.setOnClickListener { openInCustomTab() }
        rebuildAndLoad()
    }

    private fun webViewDiagnostic(prefix: String): String {
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val version = pkg?.versionName ?: "bilinmiyor"
        return "$prefix\nWebView: $version"
    }

    private fun rebuildAndLoad() {
        handler.removeCallbacks(timeoutRunnable)
        pageFinished = false
        fatalLoadError = false
        importButton.isEnabled = false
        retryButton.visibility = View.GONE
        status.text = webViewDiagnostic("TradingView giriş ekranı yükleniyor...")
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
        webView.loadUrl(LOGIN_URL)
        handler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS)
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
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageFinished = false
                fatalLoadError = false
                loadingOverlay.visibility = View.VISIBLE
                status.text = webViewDiagnostic("TradingView sayfasına bağlanılıyor...")
                Log.d(TAG, "onPageStarted host=${safeHost(url)}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageFinished = true
                handler.removeCallbacks(timeoutRunnable)
                loadingOverlay.visibility = View.GONE
                Log.d(TAG, "onPageFinished host=${safeHost(url)}")
                updateCookieState()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                Log.w(TAG, "WebView error main=${request?.isForMainFrame} host=${request?.url?.host} code=${error?.errorCode}")
                if (request?.isForMainFrame == true) {
                    showLoadError("TradingView bağlantısı kurulamadı. İnternet veya Android System WebView durumunu kontrol edin.")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.w(TAG, "WebView HTTP main=${request?.isForMainFrame} host=${request?.url?.host} status=${errorResponse?.statusCode}")
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    showLoadError("TradingView giriş servisi HTTP ${errorResponse?.statusCode ?: "hata"} döndürdü.")
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.e(TAG, "WebView renderer gone; didCrash=${detail?.didCrash()}")
                handler.removeCallbacks(timeoutRunnable)
                fatalLoadError = true
                runCatching { if (view != null) webContainer.removeView(view) }
                runCatching { view?.destroy() }
                showLoadError("Android WebView görüntüleme süreci durdu. Tekrar deneyin veya güvenli tarayıcıda açın.")
                return true
            }
        }
    }

    private fun safeHost(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }

    private fun showLoadError(message: String) {
        fatalLoadError = true
        handler.removeCallbacks(timeoutRunnable)
        loadingOverlay.visibility = View.GONE
        status.text = webViewDiagnostic(message)
        retryButton.visibility = View.VISIBLE
        importButton.isEnabled = false
    }

    private fun updateCookieState() {
        val cookies = CookieManager.getInstance().getCookie(TRADINGVIEW_ORIGIN).orEmpty()
        val sessionId = cookieValue(cookies, "sessionid")
        importButton.isEnabled = !sessionId.isNullOrBlank()
        status.text = webViewDiagnostic(if (sessionId.isNullOrBlank()) {
            "TradingView sayfası yüklendi. Girişi tamamlayın; CAPTCHA/2FA varsa normal şekilde çözün. Oturum algılanınca aktar düğmesi etkinleşir."
        } else {
            "TradingView oturumu algılandı. Kimlik bilgileri okunmadı. Oturumu aktarmak için düğmeye basın."
        })
    }

    private fun importSession() {
        val cookies = CookieManager.getInstance().getCookie(TRADINGVIEW_ORIGIN).orEmpty()
        val sessionId = cookieValue(cookies, "sessionid")
        val sessionSign = cookieValue(cookies, "sessionid_sign")
        if (sessionId.isNullOrBlank()) {
            status.text = webViewDiagnostic("Oturum çerezi bulunamadı. TradingView girişini tamamlayıp tekrar deneyin.")
            importButton.isEnabled = false
            return
        }
        settings.tradingViewSessionId = sessionId
        settings.tradingViewSessionSign = sessionSign.orEmpty()
        settings.tradingViewAuthToken = ""
        settings.tradingViewAuthenticatedAt = System.currentTimeMillis()
        status.text = webViewDiagnostic("Oturum aktarıldı; TradingView tarafından doğrulanıyor...")
        importButton.isEnabled = false

        lifecycleScope.launch {
            val result = TradingViewAuthClient(this@TradingViewBrowserLoginActivity).validateStoredSession()
            if (result.ok) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                status.text = webViewDiagnostic("Oturum çerezi alındı ancak doğrulama tamamlanamadı: ${result.message}")
                importButton.isEnabled = true
            }
        }
    }

    private fun openInCustomTab() {
        val uri = Uri.parse(LOGIN_URL)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
            status.text = webViewDiagnostic("TradingView güvenli tarayıcı sekmesinde açıldı. CAPTCHA/2FA işlemlerini orada tamamlayabilirsiniz. Tarayıcı oturumu WebView'a otomatik aktarılmaz.")
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, uri)
            if (fallback.resolveActivity(packageManager) != null) {
                startActivity(fallback)
                status.text = webViewDiagnostic("Custom Tab açılamadı; TradingView sistem tarayıcısında açıldı. Tarayıcı oturumu WebView'a otomatik aktarılmaz.")
            } else {
                status.text = webViewDiagnostic("Bu cihazda TradingView sayfasını açabilecek bir tarayıcı bulunamadı.")
            }
        }
    }

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
        handler.removeCallbacks(timeoutRunnable)
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
    }
}
