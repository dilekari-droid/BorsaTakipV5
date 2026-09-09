package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendHealthClient
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.TradingViewAuthClient

class SettingsActivity : BaseActivity() {
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

        if (s.baseUrl.equals("https://api.ornek.com", ignoreCase = true)) s.baseUrl = ""

        base.setText(s.baseUrl)
        key.setText(s.apiKey)
        refresh.setText(s.refreshMinutes.toString())
        yahooFallback.isChecked = s.yahooFallbackEnabled
        notifications.isChecked = s.notifications

        val injectedUser = BuildConfig.TV_TEST_USERNAME.trim()
        val injectedPass = BuildConfig.TV_TEST_PASSWORD
        tvUser.setText(s.tradingViewUsername.ifBlank { injectedUser })
        tvPass.setText(if (BuildConfig.DEBUG && injectedPass.isNotBlank()) injectedPass else "")

        fun showTvStatus(extra: String? = null) {
            val hasSession = s.tradingViewSessionId.isNotBlank()
            val hasToken = s.tradingViewAuthToken.isNotBlank()
            tvStatus.text = buildString {
                append("TradingView: ")
                append(if (hasSession) "OTURUM KAYITLI" else "OTURUM YOK")
                append("\nWebSocket auth token: ")
                append(if (hasToken) "MEVCUT" else "YOK")
                if (BuildConfig.DEBUG) {
                    append("\nDEBUG test hesabı: ")
                    append(if (injectedUser.isNotBlank() && injectedPass.isNotBlank()) "YÜKLÜ" else "CI secret tanımlı değil")
                }
                if (s.tradingViewAuthenticatedAt > 0L) append("\nSon doğrulama: ${s.tradingViewAuthenticatedAt}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        fun showStatus(extra: String? = null) {
            val provider = if (s.baseUrl.isBlank()) "tanımlı değil" else s.baseUrl
            val fallback = if (s.yahooFallbackEnabled) "Yahoo Finance • yedek/gecikmeli • açık" else "kapalı"
            val universe = s.cachedBistSymbols.size
            val last = if (s.lastProviderTimestamp > 0L) s.lastProviderLabel else "henüz veri alınmadı"
            dataStatus.text = buildString {
                append("Ana BIST/VİOP sağlayıcısı: $provider\n")
                append("TradingView Scanner: doğrudan snapshot tarama\n")
                append("TradingView üyelik oturumu: ${if (s.tradingViewSessionId.isNotBlank()) "kayıtlı" else "yok"}\n")
                append("Yedek/gecikmeli kaynak: $fallback\n")
                append("Son aktif kaynak: $last\n")
                append("Dinamik BIST evren önbelleği: $universe sembol\n")
                append("REST: /v1/bist/symbols, /v1/bist/history/{symbol}, /v1/viop/contracts\n")
                append("Uygulama sürümü: ${BuildConfig.VERSION_NAME}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        showTvStatus()
        showStatus()

        findViewById<Button>(R.id.testTradingViewLogin).setOnClickListener {
            val username = tvUser.text.toString().trim().ifBlank { injectedUser }
            val enteredPassword = tvPass.text.toString()
            val password = when {
                enteredPassword.isNotBlank() -> enteredPassword
                s.tradingViewPassword.isNotBlank() -> s.tradingViewPassword
                BuildConfig.DEBUG && injectedPass.isNotBlank() -> injectedPass
                else -> ""
            }
            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "TradingView test hesabı CI secret olarak tanımlı değil veya alanlar boş.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            tvStatus.text = "TradingView: giriş deneniyor..."
            lifecycleScope.launch {
                val result = TradingViewAuthClient(this@SettingsActivity).login(username, password)
                if (result.ok) {
                    s.tradingViewUsername = username
                    s.tradingViewPassword = password
                    if (!BuildConfig.DEBUG) tvPass.setText("")
                }
                showTvStatus(result.message)
                Toast.makeText(
                    this@SettingsActivity,
                    if (result.ok) "TradingView oturumu doğrulandı" else "TradingView girişi başarısız",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        findViewById<Button>(R.id.clearTradingViewSession).setOnClickListener {
            TradingViewAuthClient(this).logoutLocal()
            tvUser.setText(injectedUser)
            tvPass.setText(if (BuildConfig.DEBUG) injectedPass else "")
            showTvStatus("Yerel TradingView oturumu ve kayıtlı şifre temizlendi.")
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Toast.makeText(this, "Ana mobil veri servisi için HTTPS adresi kullanın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = if (url.equals("https://api.ornek.com", ignoreCase = true)) "" else url
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
            if (url.isBlank() || !url.startsWith("https://") || url.equals("https://api.ornek.com", ignoreCase = true)) {
                Toast.makeText(this, "Gerçek bir ana HTTPS sağlayıcı adresi girin.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            showStatus("Ana bağlantı test ediliyor...")
            lifecycleScope.launch {
                val health = BackendHealthClient(this@SettingsActivity).check()
                val state = if (health.ok) "ANA BAĞLANTI BAŞARILI" else "ANA BAĞLANTI BAŞARISIZ"
                showStatus("$state • Sağlayıcı: ${health.provider} • Gecikme: ${health.latencyMs} ms • ${health.message}")
            }
        }
    }
}
