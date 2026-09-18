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
import androidx.core.content.ContextCompat
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

        val settings = SettingsStore(this)
        settings.purgeLegacyTradingViewState()

        val baseUrl = findViewById<EditText>(R.id.baseUrl)
        val apiKey = findViewById<EditText>(R.id.apiKey)
        val refreshMinutes = findViewById<EditText>(R.id.refreshMinutes)
        val experimentalProviders = findViewById<Switch>(R.id.experimentalProviders)
        val yahooFallback = findViewById<Switch>(R.id.yahooFallback)
        val notifications = findViewById<Switch>(R.id.notifications)
        val dataStatus = findViewById<TextView>(R.id.dataStatus)
        val tradingViewStatus = findViewById<TextView>(R.id.tradingViewStatus)
        val systemStatus = findViewById<TextView>(R.id.systemStatus)
        val workingModeStatus = findViewById<TextView>(R.id.workingModeStatus)
        val versionBadge = findViewById<TextView>(R.id.versionBadge)

        versionBadge.text = "v${BuildConfig.VERSION_NAME} • B${BuildConfig.VERSION_CODE}"

        baseUrl.setText(settings.baseUrl)
        apiKey.setText(settings.apiKey)
        refreshMinutes.setText(settings.refreshMinutes.toString())
        experimentalProviders.isChecked = settings.experimentalProvidersEnabled
        yahooFallback.isChecked = settings.yahooFallbackEnabled
        yahooFallback.isEnabled = settings.experimentalProvidersEnabled
        notifications.isChecked = settings.notifications

        experimentalProviders.setOnCheckedChangeListener { _, enabled ->
            yahooFallback.isEnabled = enabled
            if (!enabled) yahooFallback.isChecked = false
        }

        tradingViewStatus.text = buildString {
            append("Yalnız güvenli tarayıcı / Custom Tab üzerinden görüntüleme\n")
            append("BIST/VİOP veri kaynağı: HAYIR\n")
            append("Uygulama içi TradingView kimlik doğrulaması: KULLANILMIYOR\n")
            append("Tarayıcı cookie/oturum aktarımı: YAPILMIYOR")
        }

        fun refreshDashboard(extra: String? = null) {
            val backendConfigured = settings.baseUrl.isNotBlank()
            val backend = if (backendConfigured) settings.baseUrl else "YAPILANDIRILMAMIŞ"
            val yahooActive = settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled
            val yahoo = if (yahooActive) {
                "Yahoo Finance • deneysel/yedek/gecikmeli • AÇIK"
            } else {
                "KAPALI"
            }
            val universe = settings.cachedBistSymbols.size
            val lastProvider = if (settings.lastProviderTimestamp > 0L) {
                settings.lastProviderLabel
            } else {
                "henüz veri alınmadı"
            }

            systemStatus.text = if (backendConfigured) {
                "Üretim veri bağlantısı yapılandırıldı"
            } else {
                "Üretim veri bağlantısı yapılandırılmayı bekliyor"
            }
            systemStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (backendConfigured) R.color.green else R.color.yellow
                )
            )

            workingModeStatus.text = buildString {
                append("Çalışma modu: Canlı • Backend-first")
                if (yahooActive) append(" • Deneysel BIST yedeği açık")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }

            dataStatus.text = buildString {
                append("VERİ MİMARİSİ: BACKEND-FIRST\n")
                append("Ana BIST/VİOP kaynağı: HTTPS backend\n")
                append("Üretim backend: $backend\n")
                append("Yahoo BIST yedeği: $yahoo\n")
                append("TradingView: yalnız harici görüntüleme; veri sağlayıcısı değil\n")
                append("Son aktif veri kaynağı: $lastProvider\n")
                append("Dinamik BIST evren önbelleği: $universe sembol\n")
                append("Backend sözleşmesi: /v1/health, /v1/bist/symbols, /v1/bist/history/{symbol}, /v1/viop/contracts")
            }
        }

        refreshDashboard()

        findViewById<Button>(R.id.testTradingViewLogin).setOnClickListener {
            openTradingView()
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = baseUrl.text.toString().trim().removeSuffix("/")
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Toast.makeText(
                    this,
                    "Üretim backend için HTTPS adresi zorunludur.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            settings.baseUrl = url
            settings.apiKey = apiKey.text.toString()
            settings.refreshMinutes = (refreshMinutes.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1)
            settings.experimentalProvidersEnabled = experimentalProviders.isChecked
            settings.yahooFallbackEnabled = experimentalProviders.isChecked && yahooFallback.isChecked
            settings.notifications = notifications.isChecked

            Toast.makeText(
                this,
                if (url.isBlank() && !settings.yahooFallbackEnabled) {
                    "Ayarlar kaydedildi. Üretim backend yapılandırılana kadar piyasa taraması başlamaz."
                } else {
                    "Ayarlar kaydedildi"
                },
                Toast.LENGTH_LONG
            ).show()
            refreshDashboard("Ayarlar kaydedildi")
        }

        findViewById<Button>(R.id.testConnection).setOnClickListener {
            val url = baseUrl.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) {
                settings.baseUrl = ""
                refreshDashboard("Üretim veri sunucusu yapılandırılmamış. Gerçek HTTPS backend adresi girin.")
                Toast.makeText(this, "Üretim backend yapılandırılmamış.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!url.startsWith("https://")) {
                Toast.makeText(this, "Backend testi için HTTPS adresi kullanın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            settings.baseUrl = url
            settings.apiKey = apiKey.text.toString()
            refreshDashboard("Üretim backend bağlantısı test ediliyor...")

            lifecycleScope.launch {
                val health = BackendHealthClient(this@SettingsActivity).check()
                val state = if (health.ok) {
                    "ÜRETİM BACKEND BAĞLANTISI BAŞARILI"
                } else {
                    "ÜRETİM BACKEND BAĞLANTISI BAŞARISIZ"
                }
                refreshDashboard(
                    "$state • Sağlayıcı: ${health.provider} • Gecikme: ${health.latencyMs} ms • ${health.message}"
                )
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
                Toast.makeText(
                    this,
                    "Bu cihazda web sayfasını açabilecek bir tarayıcı bulunamadı.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val TRADINGVIEW_URL = "https://www.tradingview.com/"
    }
}
