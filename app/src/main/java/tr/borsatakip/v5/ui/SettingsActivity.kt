package tr.borsatakip.v5.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendHealthClient
import tr.borsatakip.v5.data.SettingsStore

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupBottomNav()

        val s = SettingsStore(this)
        s.purgeLegacyTradingViewState()

        val base = findViewById<EditText>(R.id.baseUrl)
        val key = findViewById<EditText>(R.id.apiKey)
        val refresh = findViewById<EditText>(R.id.refreshMinutes)
        val experimentalProviders = findViewById<Switch>(R.id.experimentalProviders)
        val yahooFallback = findViewById<Switch>(R.id.yahooFallback)
        val notifications = findViewById<Switch>(R.id.notifications)
        val lrcEnabled = findViewById<Switch>(R.id.lrcEnabled)
        val lrcLength = findViewById<EditText>(R.id.lrcLength)
        val lrcSigma1 = findViewById<Switch>(R.id.lrcSigma1)
        val lrcSigma2 = findViewById<Switch>(R.id.lrcSigma2)
        val lrcSigma3 = findViewById<Switch>(R.id.lrcSigma3)
        val lrcTrendColor = findViewById<Switch>(R.id.lrcTrendColor)
        val lrcPearson = findViewById<Switch>(R.id.lrcPearson)
        val lrcBreakoutWarning = findViewById<Switch>(R.id.lrcBreakoutWarning)
        val lrcFill = findViewById<Switch>(R.id.lrcFill)
        val dataStatus = findViewById<TextView>(R.id.dataStatus)
        val tradingViewStatus = findViewById<TextView>(R.id.tradingViewStatus)

        base.setText(s.baseUrl)
        key.setText(s.apiKey)
        refresh.setText(s.refreshMinutes.toString())
        experimentalProviders.isChecked = s.experimentalProvidersEnabled
        yahooFallback.isChecked = s.yahooFallbackEnabled
        yahooFallback.isEnabled = s.experimentalProvidersEnabled
        notifications.isChecked = s.notifications
        lrcEnabled.isChecked = s.lrcEnabled
        lrcLength.setText(s.lrcLength.toString())
        lrcSigma1.isChecked = s.lrcSigma1Enabled
        lrcSigma2.isChecked = s.lrcSigma2Enabled
        lrcSigma3.isChecked = s.lrcSigma3Enabled
        lrcTrendColor.isChecked = s.lrcTrendColorEnabled
        lrcPearson.isChecked = s.lrcPearsonEnabled
        lrcBreakoutWarning.isChecked = s.lrcBreakoutWarningEnabled
        lrcFill.isChecked = s.lrcFillEnabled

        fun setLrcChildrenEnabled(enabled: Boolean) {
            lrcLength.isEnabled = enabled
            lrcSigma1.isEnabled = enabled
            lrcSigma2.isEnabled = enabled
            lrcSigma3.isEnabled = enabled
            lrcTrendColor.isEnabled = enabled
            lrcPearson.isEnabled = enabled
            lrcBreakoutWarning.isEnabled = enabled
            lrcFill.isEnabled = enabled
        }
        setLrcChildrenEnabled(lrcEnabled.isChecked)
        lrcEnabled.setOnCheckedChangeListener { _, enabled -> setLrcChildrenEnabled(enabled) }

        experimentalProviders.setOnCheckedChangeListener { _, enabled ->
            yahooFallback.isEnabled = enabled
            if (!enabled) yahooFallback.isChecked = false
        }

        tradingViewStatus.text = buildString {
            append("TradingView: yalnız güvenli tarayıcı/Custom Tab üzerinden görüntüleme\n")
            append("BIST/VİOP veri kaynağı: HAYIR\n")
            append("Uygulama içi TradingView kimlik doğrulaması: KULLANILMIYOR\n")
            append("Tarayıcı cookie/oturum aktarımı: YAPILMIYOR\n")
            append("TradingView hesabı yalnız açılan güvenli tarayıcı sekmesinde kullanıcı tarafından yönetilir.")
        }

        fun showStatus(extra: String? = null) {
            val backend = if (s.baseUrl.isBlank()) "YAPILANDIRILMAMIŞ" else s.baseUrl
            val yahoo = if (s.experimentalProvidersEnabled && s.yahooFallbackEnabled) {
                "Yahoo Finance • deneysel/yedek/gecikmeli • AÇIK"
            } else {
                "KAPALI"
            }
            val universe = s.cachedBistSymbols.size
            val last = if (s.lastProviderTimestamp > 0L) s.lastProviderLabel else "henüz veri alınmadı"
            dataStatus.text = buildString {
                append("VERİ MİMARİSİ: BACKEND-FIRST\n")
                append("Ana BIST/VİOP kaynağı: HTTPS backend\n")
                append("Üretim backend: $backend\n")
                append("Yahoo BIST yedeği: $yahoo\n")
                append("TradingView: yalnız harici görüntüleme; veri sağlayıcısı değil\n")
                append("Son aktif veri kaynağı: $last\n")
                append("Dinamik BIST evren önbelleği: $universe sembol\n")
                append("Backend sözleşmesi: /v1/health, /v1/bist/symbols, /v1/bist/history/{symbol}, /v1/viop/contracts\n")
                append("LRC: ${if (s.lrcEnabled) "AÇIK" else "KAPALI"} • Length ${s.lrcLength} • ±1σ ${if (s.lrcSigma1Enabled) "Açık" else "Kapalı"} • ±2σ ${if (s.lrcSigma2Enabled) "Açık" else "Kapalı"} • ±3σ ${if (s.lrcSigma3Enabled) "Açık" else "Kapalı"}\n")
                append("Uygulama sürümü: ${BuildConfig.VERSION_NAME}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        showStatus()

        findViewById<Button>(R.id.testTradingViewLogin).setOnClickListener { openTradingView() }

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Toast.makeText(this, "Üretim backend için HTTPS adresi zorunludur.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            s.refreshMinutes = (refresh.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1)
            s.experimentalProvidersEnabled = experimentalProviders.isChecked
            s.yahooFallbackEnabled = experimentalProviders.isChecked && yahooFallback.isChecked
            s.notifications = notifications.isChecked
            s.lrcEnabled = lrcEnabled.isChecked
            s.lrcLength = (lrcLength.text.toString().toIntOrNull() ?: 100).coerceIn(20, 500)
            lrcLength.setText(s.lrcLength.toString())
            s.lrcSigma1Enabled = lrcSigma1.isChecked
            s.lrcSigma2Enabled = lrcSigma2.isChecked
            s.lrcSigma3Enabled = lrcSigma3.isChecked
            s.lrcTrendColorEnabled = lrcTrendColor.isChecked
            s.lrcPearsonEnabled = lrcPearson.isChecked
            s.lrcBreakoutWarningEnabled = lrcBreakoutWarning.isChecked
            s.lrcFillEnabled = lrcFill.isChecked
            Toast.makeText(
                this,
                if (url.isBlank() && !s.yahooFallbackEnabled) {
                    "Ayarlar kaydedildi. Üretim backend yapılandırılana kadar piyasa taraması başlamaz."
                } else {
                    "Ayarlar kaydedildi"
                },
                Toast.LENGTH_LONG
            ).show()
            showStatus()
        }

        findViewById<Button>(R.id.testConnection).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) {
                s.baseUrl = ""
                showStatus("Üretim veri sunucusu yapılandırılmamış. Gerçek HTTPS backend adresi girin.")
                Toast.makeText(this, "Üretim backend yapılandırılmamış.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!url.startsWith("https://")) {
                Toast.makeText(this, "Backend testi için HTTPS adresi kullanın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            showStatus("Üretim backend bağlantısı test ediliyor...")
            lifecycleScope.launch {
                val health = BackendHealthClient(this@SettingsActivity).check()
                val state = if (health.ok) "ÜRETİM BACKEND BAĞLANTISI BAŞARILI" else "ÜRETİM BACKEND BAĞLANTISI BAŞARISIZ"
                showStatus("$state • Sağlayıcı: ${health.provider} • Gecikme: ${health.latencyMs} ms • ${health.message}")
            }
        }
    }

    private fun openTradingView() {
        val uri = Uri.parse(TRADINGVIEW_URL)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, uri)
            if (fallback.resolveActivity(packageManager) != null) {
                startActivity(fallback)
            } else {
                Toast.makeText(this, "Bu cihazda web sayfasını açabilecek bir tarayıcı bulunamadı.", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TRADINGVIEW_URL = "https://www.tradingview.com/"
    }
}
