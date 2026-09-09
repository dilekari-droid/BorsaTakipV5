package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
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
        base.setText(s.baseUrl)
        key.setText(s.apiKey)
        refresh.setText(s.refreshMinutes.toString())
        notifications.isChecked = s.notifications
        fun status() {
            val provider = if (s.baseUrl.isBlank()) "tanımlı değil" else s.baseUrl
            findViewById<TextView>(R.id.dataStatus).text = "BIST/VİOP mobil veri servisi: $provider\nYahoo Finance ana veri kaynağı değildir.\nTema: koyu lacivert referans tema\nUygulama sürümü: ${BuildConfig.VERSION_NAME}"
        }
        status()
        findViewById<Button>(R.id.save).setOnClickListener {
            val url = base.text.toString().trim()
            if (url.isNotBlank() && !url.startsWith("https://")) {
                Toast.makeText(this, "Mobil veri servisi için HTTPS adresi kullanın.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.baseUrl = url
            s.apiKey = key.text.toString()
            s.refreshMinutes = (refresh.text.toString().toIntOrNull() ?: 15).coerceAtLeast(1)
            s.notifications = notifications.isChecked
            Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
            status()
        }
    }
}
