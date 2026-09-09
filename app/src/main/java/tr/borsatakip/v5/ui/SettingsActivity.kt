package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendHealthClient
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.TradingViewAuthClient

class SettingsActivity : BaseActivity() {
    private val tradingViewBrowserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupBottomNav()

        val s = SettingsStore(this)
        val base = findViewById<EditText>(R.id.baseUrl)
        val key = findViewById<EditText>(R.id.apiKey)
        val refresh = findViewById<EditText>(R.id.refreshMinutes)
        val experimentalProviders = findViewById<Switch>(R.id.experimentalProviders)
        val yahooFallback = findViewById<Switch>(R.id.yahooFallback)
        val notifications = findViewById<Switch>(R.id.notifications)
        val dataStatus = findViewById<TextView>(R.id.dataStatus)
        val tvUser = findViewById<EditText>(R.id.tradingViewUsername)
        val tvPass = findViewById<EditText>(R.id.tradingViewPassword)
        val tvStatus = findViewById<TextView>(R.id.tradingViewStatus)
        val tvLoginButton = findViewById<Button>(R.id.testTradingViewLogin)

        base.setText(s.baseUrl)
        key.setText(s.apiKey)
        refresh.setText(s.refreshMinutes.toString())
        experimentalProviders.isChecked = s.experimentalProvidersEnabled
        yahooFallback.isChecked = s.yahooFallbackEnabled
        yahooFallback.isEnabled = s.experimentalProvidersEnabled
        notifications.isChecked = s.notifications

        val injectedUser = BuildConfig.TV_TEST_USERNAME.trim()
        val injectedPass = BuildConfig.TV_TEST_PASSWORD
        tvUser.setText(s.tradingViewUsername.ifBlank { injectedUser })
        tvPass.setText("")
        tvPass.hint = "Tarayıcı girişinde şifreyi TradingView ekranına yazın"
        tvLoginButton.text = "TRADINGVIEW TARAYICI GİRİŞİ"

        experimentalProviders.setOnCheckedChangeListener { _, enabled ->
            yahooFallback.isEnabled = enabled
            if (!enabled) yahooFallback.isChecked = false
        }

        fun showTvStatus(extra: String? = null) {
            val hasSession = s.tradingViewSessionId.isNotBlank()
            val hasToken = s.tradingViewAuthToken.isNotBlank()
            tvStatus.text = buildString {
                append("TradingView: DENEYSEL kaynak\n")
                append("Deneysel mod: ${if (s.experimentalProvidersEnabled) "AÇIK" else "KAPALI"}\n")
                append("Üyelik oturumu: ${if (hasSession) "KAYITLI" else "YOK"}\n")
                append("Giriş yöntemi: TradingView web sayfasında kullanıcı tarafından doğrulama\n")
                append("WebSocket auth token: ${if (hasToken) "MEVCUT" else "YOK"}")
                if (BuildConfig.DEBUG) {
                    append("\nDEBUG test hesabı: ")
                    append(if (injectedUser.isNotBlank() && injectedPass.isNotBlank()) "CI'da tanımlı" else "CI secret tanımlı değil")
                }
                if (s.tradingViewAuthenticatedAt > 0L) append("\nSon doğrulama: ${s.tradingViewAuthenticatedAt}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        fun showStatus(extra: String? = null) {
            val backend = if (s.baseUrl.isBlank()) "YAPILANDIRILMAMIŞ" else s.baseUrl
            val exp = if (s.experimentalProvidersEnabled) "AÇIK" else "KAPALI"
            val fallback = if (s.experimentalProvidersEnabled && s.yahooFallbackEnabled) {
                "Yahoo Finance • deneysel/yedek • açık"
            } else {
                "kapalı"
            }
            val universe = s.cachedBistSymbols.size
            val last = if (s.lastProviderTimestamp > 0L) s.lastProviderLabel else "henüz veri alınmadı"
            dataStatus.text = buildString {
                append("VERİ MODU: ${if (s.experimentalProvidersEnabled) "ÜRETİM + DENEYSEL FALLBACK" else "ÜRETİM"}\n")
                append("Ana BIST/VİOP kaynağı: HTTPS backend\n")
                append("Üretim backend: $backend\n")
                append("Deneysel sağlayıcılar: $exp\n")
                append("TradingView: yalnız deneysel modda\n")
                append("Yahoo: $fallback\n")
                append("Son aktif kaynak: $last\n")
                append("Dinamik BIST evren önbelleği: $universe sembol\n")
                append("Backend sözleşmesi: /v1/health, /v1/bist/symbols, /v1/bist/history/{symbol}, /v1/viop/contracts\n")
                append("Uygulama sürümü: ${BuildConfig.VERSION_NAME}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        showTvStatus()
        showStatus()

        tvLoginButton.setOnClickListener {
            if (!experimentalProviders.isChecked) {
                Toast.makeText(
                    this,
                    "TradingView yalnız deneysel modda kullanılabilir. Önce DENEYSEL sağlayıcıları etkinleştirin.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val username = tvUser.text.toString().trim().ifBlank { injectedUser }
            if (username.isNotBlank()) s.tradingViewUsername = username
            tvPass.setText("")
            Toast.makeText(
                this,
                "TradingView sayfası açılıyor. Girişi ve varsa CAPTCHA/2FA doğrulamasını TradingView ekranında tamamlayın.",
                Toast.LENGTH_LONG
            ).show()
            tradingViewBrowserLauncher.launch(Intent(this, TradingViewBrowserLoginActivity::class.java))
        }

        findViewById<Button>(R.id.clearTradingViewSession).setOnClickListener {
            TradingViewAuthClient(this).logoutLocal()
            tvUser.setText(injectedUser)
            tvPass.setText("")
            showTvStatus("Yerel TradingView oturumu temizlendi.")
        }

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
            if (tvUser.text.toString().isNotBlank()) s.tradingViewUsername = tvUser.text.toString()
            Toast.makeText(
                this,
                if (url.isBlank() && !s.experimentalProvidersEnabled) {
                    "Ayarlar kaydedildi. Üretim backend yapılandırılana kadar piyasa taraması başlamaz."
                } else {
                    "Ayarlar kaydedildi"
                },
                Toast.LENGTH_LONG
            ).show()
            showStatus()
            showTvStatus()
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
}
