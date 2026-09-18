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
import tr.borsatakip.v5.data.BackendPreflightClient
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
        s.purgeLegacyTradingViewState()

        val base = findViewById<EditText>(R.id.baseUrl)
        val key = findViewById<EditText>(R.id.apiKey)
        val refresh = findViewById<EditText>(R.id.refreshMinutes)
        val experimentalProviders = findViewById<Switch>(R.id.experimentalProviders)
        val yahooFallback = findViewById<Switch>(R.id.yahooFallback)
        val notifications = findViewById<Switch>(R.id.notifications)
        val dataStatus = findViewById<TextView>(R.id.dataStatus)
        val tradingViewStatus = findViewById<TextView>(R.id.tradingViewStatus)

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
            append("Tarayıcı cookie/oturum aktarımı: YAPILMIYOR\n")
            append("TradingView hesabı yalnız açılan güvenli tarayıcı sekmesinde kullanıcı tarafından yönetilir.")
        }

        fun formatTime(epoch: Long): String = if (epoch > 0L) {
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(epoch))
        } else "yok"

        fun showStatus(extra: String? = null) {
            val backend = if (s.baseUrl.isBlank()) "YAPILANDIRILMAMIŞ" else s.baseUrl
            val yahoo = if (s.experimentalProvidersEnabled && s.yahooFallbackEnabled) {
                "Yahoo Finance • deneysel/yedek/gecikmeli • AÇIK"
            } else {
                "KAPALI"
            }
            val health = when {
                s.lastBackendHealthAt <= 0L -> "TEST EDİLMEDİ"
                s.lastBackendHealthOk -> "HAZIR"
                else -> "BAŞARISIZ"
            }
            val api = if (s.apiKey.isBlank()) "YAPILANDIRILMAMIŞ / backend anahtarsız olabilir" else "YAPILANDIRILDI"
            dataStatus.text = buildString {
                append("VERİ SAĞLAYICI • PRODUCTION BACKEND\n")
                append("Backend URL: $backend\n")
                append("Bağlantı durumu: $health\n")
                append("Son bağlantı testi: ${formatTime(s.lastBackendHealthAt)}\n")
                append("API erişimi: $api\n")
                append("BIST sembol sayısı: ${s.cachedBistSymbolCount}\n")
                append("Son sembol güncellemesi: ${formatTime(s.cachedBistSymbolsFetchedAt)}\n")
                append("Sembol kaynağı: ${s.cachedBistSymbolsProviderId.ifBlank { "yok" }}\n")
                append("Yahoo BIST yedeği: $yahoo\n")
                append("Backend sözleşmesi: /v1/health → /v1/bist/symbols → /v1/bist/history/{symbol}\n")
                append("Uygulama sürümü: ${BuildConfig.VERSION_NAME}")
                if (!extra.isNullOrBlank()) append("\n\n$extra")
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
            val newKey = key.text.toString().trim()
            if (newKey.isNotBlank()) {
                s.apiKey = newKey
                key.setText("")
                key.hint = "Yapılandırıldı • değiştirmek için yeni anahtar girin"
            }
            s.refreshMinutes = (refresh.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1)
            s.experimentalProvidersEnabled = experimentalProviders.isChecked
            s.yahooFallbackEnabled = experimentalProviders.isChecked && yahooFallback.isChecked
            s.notifications = notifications.isChecked
            Toast.makeText(
                this,
                if (url.isBlank() && !s.yahooFallbackEnabled) "Ayarlar kaydedildi. Production Backend yapılandırması eksik." else "Ayarlar kaydedildi",
                Toast.LENGTH_LONG
            ).show()
            showStatus()
        }

        findViewById<Button>(R.id.testConnection).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) {
                s.baseUrl = ""
                showStatus("BAĞLANTI BAŞARISIZ\nÜretim veri sağlayıcısı için backend adresi tanımlanmamış.")
                Toast.makeText(this, "Production Backend yapılandırması eksik.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!url.startsWith("https://")) {
                Toast.makeText(this, "Backend testi için HTTPS adresi kullanın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            val newKey = key.text.toString().trim()
            if (newKey.isNotBlank()) s.apiKey = newKey
            showStatus("Bağlantı testi çalışıyor: Health → Authentication → Symbols → History")
            lifecycleScope.launch {
                val result = BackendPreflightClient(this@SettingsActivity).check()
                if (result.ok) {
                    showStatus(
                        "BAĞLANTI BAŞARILI\n" +
                            "Health ✓ • Authentication ✓ • Symbols ✓ • History ✓\n" +
                            "BIST hisseleri: ${result.symbolCount}\n" +
                            "Sağlayıcı: ${result.provider ?: "Production Backend"}\n" +
                            "Süre: ${result.elapsedMs} ms"
                    )
                } else {
                    showStatus(
                        "BAĞLANTI BAŞARISIZ\n" +
                            "Hata: ${result.failureKind}\n" +
                            result.message
                    )
                }
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

    companion object {
        private const val TRADINGVIEW_URL = "https://www.tradingview.com/"
    }
}
