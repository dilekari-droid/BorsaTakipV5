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

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupBottomNav()

        val s = SettingsStore(this)
        val base = findViewById<EditText>(R.id.baseUrl)
        val key = findViewById<EditText>(R.id.apiKey)
        val refresh = findViewById<EditText>(R.id.refreshMinutes)
        val notifications = findViewById<Switch>(R.id.notifications)
        val dataStatus = findViewById<TextView>(R.id.dataStatus)

        base.setText(s.baseUrl)
        key.setText(s.apiKey)
        refresh.setText(s.refreshMinutes.toString())
        notifications.isChecked = s.notifications

        fun showStatus(extra: String? = null) {
            val provider = if (s.baseUrl.isBlank()) "tanımlı değil" else s.baseUrl
            dataStatus.text = buildString {
                append("BIST/VİOP mobil veri servisi: $provider\n")
                append("REST: /v1/bist/symbols, /v1/bist/history/{symbol}, /v1/viop/contracts\n")
                append("Canlı akış: wss://.../v1/live\n")
                append("Yahoo Finance ana veri kaynağı değildir.\n")
                append("Uygulama sürümü: ${BuildConfig.VERSION_NAME}")
                if (!extra.isNullOrBlank()) append("\n$extra")
            }
        }

        showStatus()

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Toast.makeText(this, "Mobil veri servisi için HTTPS adresi kullanın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            s.refreshMinutes = (refresh.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1)
            s.notifications = notifications.isChecked
            Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
            showStatus()
        }

        findViewById<Button>(R.id.testConnection).setOnClickListener {
            val url = base.text.toString().trim().removeSuffix("/")
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Toast.makeText(this, "Önce geçerli bir HTTPS adresi girin.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            showStatus("Bağlantı test ediliyor...")
            lifecycleScope.launch {
                val health = BackendHealthClient(this@SettingsActivity).check()
                val state = if (health.ok) "BAĞLANTI BAŞARILI" else "BAĞLANTI BAŞARISIZ"
                showStatus("$state • Sağlayıcı: ${health.provider} • Gecikme: ${health.latencyMs} ms • ${health.message}")
            }
        }
    }
}
