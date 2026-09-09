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
        yahooFallback.isChecked = s.yahooFallbackEnabled
        notifications.isChecked = s.notifications

        val injectedUser = BuildConfig.TV_TEST_USERNAME.trim()
        val injectedPass = BuildConfig.TV_TEST_PASSWORD
        tvUser.setText(s.tradingViewUsername.ifBlank { injectedUser })
        tvPass.setText("")
        tvPass.hint = "Tarayıcı girişinde şifreyi TradingView ekranına yazın"
        tvLoginButton.text = "TRADINGVIEW TARAYICI GİRİŞİ"

        fun showTvStatus(extra: String? = null) {
            val hasSession = s.tradingViewSessionId.isNotBlank()
            val hasToken = s.tradingViewAuthToken.isNotBlank()
            tvStatus.text = buildString {
                append("TradingView üyelik oturumu: ")
                append(if (hasSession) "KAYITLI" else "YOK (Fırsat Kontrol için zorunlu değil)")
                append("\nGiriş yöntemi: TradingView web sayfasında kullanıcı tarafından doğrulama")
                append("\nWebSocket auth token: ")
                append(if (hasToken) "MEVCUT" else "YOK")
                append("\nBIST Fırsat ana kaynağı: TradingView Scanner")
                if (BuildConfig.DEBUG) {
                    append("\nDEBUG test hesabı: ")
                    append(if (injectedUser.isNotBlank() && injectedPass.isNotBlank()) "CI'da tanımlı" else "CI secret tanımlı değil")
                }
                if (s.tradingViewAuthenticatedAt > 0L) append("\nSon doğrulama: ${s.tradingViewAuthenticatedAt}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        fun showStatus(extra: String? = null) {
            val backend = if (s.baseUrl.isBlank()) "YAPILANDIRILMAMIŞ (isteğe bağlı)" else s.baseUrl
            val fallback = if (s.yahooFallbackEnabled) "Yahoo Finance • yedek/gecikmeli • açık" else "kapalı"
            val universe = s.cachedBistSymbols.size
            val last = if (s.lastProviderTimestamp > 0L) s.lastProviderLabel else "henüz veri alınmadı"
            dataStatus.text = buildString {
                append("BIST Fırsat ana kaynağı: TradingView Scanner\n")
                append("İsteğe bağlı BIST/VİOP backend: $backend\n")
                append("TradingView üyelik girişi: ${if (s.tradingViewSessionId.isNotBlank()) "kayıtlı" else "isteğe bağlı / oturum yok"}\n")
                append("Yedek/gecikmeli kaynak: $fallback\n")
                append("Son aktif kaynak: $last\n")
                append("Dinamik BIST evren önbelleği: $universe sembol\n")
                append("Backend tanımlanırsa: /v1/health, /v1/bist/symbols, /v1/bist/history/{symbol}, /v1/viop/contracts\n")
                append("Uygulama sürümü: ${BuildConfig.VERSION_NAME}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        showTvStatus()
        showStatus()

        tvLoginButton.setOnClickListener {
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
            showTvStatus("Yerel TradingView oturumu ve kayıtlı şifre temizlendi.")
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Toast.makeText(this, "İsteğe bağlı backend kullanacaksanız HTTPS adresi girin; boş bırakabilirsiniz.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            s.refreshMinutes = (refresh.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1)
            s.yahooFallbackEnabled = yahooFallback.isChecked
            s.notifications = notifications.isChecked
            if (tvUser.text.toString().isNotBlank()) s.tradingViewUsername = tvUser.text.toString()
            Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
            showStatus()
            showTvStatus()
        }

        findViewById<Button>(R.id.testConnection).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) {
                s.baseUrl = ""
                showStatus("Ana veri sunucusu yapılandırılmamış. Bu alan isteğe bağlıdır; Fırsat Kontrol TradingView Scanner ile çalışabilir.")
                Toast.makeText(this, "Backend isteğe bağlıdır; TradingView Scanner için URL gerekmez.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!url.startsWith("https://")) {
                Toast.makeText(this, "Backend testi için HTTPS adresi kullanın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            showStatus("İsteğe bağlı backend bağlantısı test ediliyor...")
            lifecycleScope.launch {
                val health = BackendHealthClient(this@SettingsActivity).check()
                val state = if (health.ok) "BACKEND BAĞLANTISI BAŞARILI" else "BACKEND BAĞLANTISI BAŞARISIZ"
                showStatus("$state • Sağlayıcı: ${health.provider} • Gecikme: ${health.latencyMs} ms • ${health.message}")
            }
        }
    }
}
