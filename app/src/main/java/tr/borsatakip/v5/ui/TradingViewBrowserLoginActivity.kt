package tr.borsatakip.v5.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.TradingViewAuthClient

/**
 * Kullanıcının TradingView'in kendi web giriş ekranında normal şekilde oturum açmasını sağlar.
 * CAPTCHA/2FA varsa kullanıcı TradingView arayüzünde tamamlar. Uygulama kimlik bilgilerini
 * WebView'den okumaz; yalnızca başarılı web oturumunun TradingView session çerezlerini alır.
 */
class TradingViewBrowserLoginActivity : BaseActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply {
            text = "TradingView'in kendi giriş ekranında oturum açın. CAPTCHA/2FA çıkarsa burada normal şekilde tamamlayın. Ardından OTURUMU UYGULAMAYA AKTAR'a basın."
            textSize = 14f
        }
        importButton = Button(this).apply {
            text = "OTURUMU UYGULAMAYA AKTAR"
            isEnabled = false
        }
        webView = WebView(this)

        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(importButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = webView.settings.userAgentString + " BorsaTakip/${tr.borsatakip.v5.BuildConfig.VERSION_NAME}"
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateCookieState()
            }
        }

        importButton.setOnClickListener { importSession() }
        webView.loadUrl(LOGIN_URL)
    }

    private fun updateCookieState() {
        val cookies = CookieManager.getInstance().getCookie(TRADINGVIEW_ORIGIN).orEmpty()
        val sessionId = cookieValue(cookies, "sessionid")
        importButton.isEnabled = !sessionId.isNullOrBlank()
        status.text = if (sessionId.isNullOrBlank()) {
            "TradingView oturumu henüz algılanmadı. Girişi tamamlayın; CAPTCHA/2FA varsa TradingView ekranında çözün."
        } else {
            "TradingView oturum çerezi algılandı. Kimlik bilgileri okunmadı. Oturumu uygulamaya aktarmak için düğmeye basın."
        }
    }

    private fun importSession() {
        val cookies = CookieManager.getInstance().getCookie(TRADINGVIEW_ORIGIN).orEmpty()
        val sessionId = cookieValue(cookies, "sessionid")
        val sessionSign = cookieValue(cookies, "sessionid_sign")
        if (sessionId.isNullOrBlank()) {
            status.text = "Oturum çerezi bulunamadı. TradingView girişini tamamlayıp tekrar deneyin."
            importButton.isEnabled = false
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
                status.text = "TradingView web oturumu doğrulandı. Uygulamaya dönülüyor."
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                status.text = "Oturum çerezi alındı ancak uygulama doğrulaması tamamlanamadı: ${result.message}"
                importButton.isEnabled = true
            }
        }
    }

    private fun cookieValue(cookieHeader: String, name: String): String? {
        val prefix = "$name="
        return cookieHeader.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TRADINGVIEW_ORIGIN = "https://www.tradingview.com"
        private const val LOGIN_URL = "https://www.tradingview.com/accounts/signin/"
    }
}
