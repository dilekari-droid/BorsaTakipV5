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
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupBottomNav()

        val s = SettingsStore(this)
        val readiness = ProviderReadinessService(this)
        s.purgeLegacyTradingViewState()

        val base = findViewById<EditText>(R.id.baseUrl)
        val key = findViewById<EditText>(R.id.apiKey)
        val refresh = findViewById<EditText>(R.id.refreshMinutes)
        val experimentalProviders = findViewById<Switch>(R.id.experimentalProviders)
        val yahooFallback = findViewById<Switch>(R.id.yahooFallback)
        val notifications = findViewById<Switch>(R.id.notifications)
        val dataStatus = findViewById<TextView>(R.id.dataStatus)
        val tradingViewStatus = findViewById<TextView>(R.id.tradingViewStatus)
        val testConnection = findViewById<Button>(R.id.testConnection)

        base.setText(s.baseUrl)
        key.setText("")
        key.hint = if (s.apiKey.isNotBlank()) "Yapılandırıldı • değiştirmek için yeni anahtar girin" else "API erişim anahtarı"
        refresh.setText(s.refreshMinutes.toString())
        experimentalProviders.isChecked = s.experimentalProvidersEnabled
        yahooFallback.isChecked = s.yahooFallbackEnabled
        yahooFallback.isEnabled = s.experimentalProvidersEnabled
        notifications.isChecked = s.notifications

        experimentalProviders.setOnCheckedChangeListener { _, enabled ->
            yahooFallback.isEnabled = enabled
            if (!enabled) yahooFallback.isChecked = false
        }

        tradingViewStatus.text = buildString {
            append("TradingView: yalnız güvenli tarayıcı/Custom Tab üzerinden görüntüleme\n")
            append("BIST/VİOP veri kaynağı: HAYIR\n")
            append("Uygulama içi TradingView kimlik doğrulaması: KULLANILMIYOR\n")
            append("Tarayıcı cookie/oturum aktarımı: YAPILMIYOR")
        }

        fun formatTime(epoch: Long): String = if (epoch > 0L) SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(epoch)) else "yok"

        fun showStatus(extra: String? = null) {
            val snapshot = readiness.localConfigState()
            val backend = if (s.baseUrl.isBlank()) "YAPILANDIRILMAMIŞ" else s.baseUrl
            dataStatus.text = buildString {
                append("VERİ SAĞLAYICI • PRODUCTION BACKEND\n")
                append("Provider state: ${snapshot.state}\n")
                append("Backend URL: $backend\n")
                append("API erişimi: ${if (s.apiKey.isBlank()) "YAPILANDIRILMAMIŞ" else "YAPILANDIRILDI"}\n")
                append("Son test: ${formatTime(s.lastBackendHealthAt)}\n")
                append("Hata kodu: ${snapshot.failureCode}\n")
                append("Durum: ${snapshot.message}\n")
                append("BIST sembol sayısı: ${s.cachedBistSymbolCount}\n")
                append("Zorunlu zincir: HTTPS → Health → Authentication → BIST Symbols → BIST Quote → BIST History → VİOP Contracts → VİOP Quote → VİOP History\n")
                append("Uygulama sürümü: ${BuildConfig.VERSION_NAME}")
                if (!extra.isNullOrBlank()) append("\n\n$extra")
            }
            testConnection.text = when (snapshot.state) {
                ProviderState.PROVIDER_READY -> "ÜRETİM BACKEND BAĞLANTISINI YENİDEN TEST ET"
                ProviderState.PROVIDER_ERROR -> "BAĞLANTIYI TEKRAR DENE"
                ProviderState.PROVIDER_TESTING -> "TEST EDİLİYOR..."
                else -> "ÜRETİM BACKEND BAĞLANTISINI TEST ET"
            }
        }

        showStatus()
        findViewById<Button>(R.id.testTradingViewLogin).setOnClickListener { openTradingView() }

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            val newKey = key.text.toString().trim()
            if (url.isNotBlank() && !ProviderReadinessService.isValidHttps(url)) {
                Toast.makeText(this, "Geçersiz Production Backend adresi. Yalnız geçerli HTTPS URL kabul edilir.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            if (newKey.isNotBlank()) {
                s.apiKey = newKey
                key.setText("")
                key.hint = "Yapılandırıldı • değiştirmek için yeni anahtar girin"
            }
            s.refreshMinutes = (refresh.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1)
            s.experimentalProvidersEnabled = experimentalProviders.isChecked
            s.yahooFallbackEnabled = experimentalProviders.isChecked && yahooFallback.isChecked
            s.notifications = notifications.isChecked
            s.lastProviderState = if (s.baseUrl.isNotBlank() && s.apiKey.isNotBlank()) ProviderState.PROVIDER_CONFIGURED.name else ProviderState.PROVIDER_NOT_CONFIGURED.name
            s.lastProviderFailureCode = ""
            s.lastProviderMessage = ""
            Toast.makeText(this, if (s.baseUrl.isBlank() || s.apiKey.isBlank()) "Production Backend yapılandırılmamış. HTTPS adresi ve API erişim anahtarı girin." else "Ayarlar kaydedildi • bağlantı testi gerekli", Toast.LENGTH_LONG).show()
            showStatus()
        }

        testConnection.setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            val newKey = key.text.toString().trim()
            s.baseUrl = url
            if (newKey.isNotBlank()) s.apiKey = newKey
            val local = readiness.localConfigState()
            if (local.state == ProviderState.PROVIDER_NOT_CONFIGURED || !ProviderReadinessService.isValidHttps(url)) {
                showStatus("BAĞLANTI BAŞARISIZ\n${local.failureCode}\n${local.message}")
                Toast.makeText(this, local.message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testConnection.isEnabled = false
            testConnection.text = "TEST EDİLİYOR..."
            showStatus("Bağlantı testi: HTTPS → Health → Authentication → BIST → VİOP")
            lifecycleScope.launch {
                val result = readiness.test()
                showStatus(
                    if (result.state == ProviderState.PROVIDER_READY) {
                        "BAĞLANTI BAŞARILI\nHTTPS ✓ • Health ✓ • Authentication ✓ • BIST Symbols ✓ • BIST Quote ✓ • BIST History ✓ • VİOP Contracts ✓ (${result.viopContractCount}) • VİOP Quote ✓ • VİOP History ✓"
                    } else {
                        "BAĞLANTI BAŞARISIZ\nHata: ${result.failureCode}\n${result.message}"
                    }
                )
                testConnection.isEnabled = true
            }
        }
    }

    private fun openTradingView() {
        val uri = Uri.parse(TRADINGVIEW_URL)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, uri)
            if (fallback.resolveActivity(packageManager) != null) startActivity(fallback)
            else Toast.makeText(this, "Bu cihazda web sayfasını açabilecek bir tarayıcı bulunamadı.", Toast.LENGTH_LONG).show()
        }
    }

    companion object { private const val TRADINGVIEW_URL = "https://www.tradingview.com/" }
}
