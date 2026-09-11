package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.model.ViopContract

class ViopActivity : BaseActivity() {
    private lateinit var repo: ViopRepository
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var providerStatus: TextView
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop)
        setupBottomNav()

        repo = ViopRepository(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        providerStatus = findViewById(R.id.providerStatus)
        refreshButton = findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(this)

        refreshButton.setOnClickListener {
            val s = SettingsStore(this)
            if (!s.baseUrl.startsWith("https://")) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                refreshContracts()
            }
        }

        render(repo.loadLocal())
        refreshScreenState()
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized) {
            render(repo.loadLocal())
            refreshScreenState()
        }
    }

    private fun refreshScreenState() {
        val s = SettingsStore(this)
        val backendReady = s.baseUrl.startsWith("https://")

        providerStatus.text = buildString {
            append("Ana VİOP kaynağı: HTTPS backend\n")
            append("Backend: ${if (backendReady) "YAPILANDIRILMIŞ" else "YAPILANDIRILMAMIŞ"}\n")
            append("TradingView: yalnız harici görüntüleme • VİOP veri kaynağı değil")
        }

        if (backendReady) {
            refreshButton.text = "VİOP TARAMASINI BAŞLAT"
            refreshButton.contentDescription = "VİOP sözleşmelerini HTTPS backend üzerinden tara"
            status.text = if (repo.loadLocal().isEmpty()) {
                "VİOP veri kaynağı hazır. Gerçek sözleşme verisini almak için taramayı başlatın."
            } else {
                "Kayıtlı VİOP sözleşmeleri gösteriliyor. Yenilemek için taramayı başlatın."
            }
        } else {
            refreshButton.text = "VERİ KAYNAĞINI YAPILANDIR"
            refreshButton.contentDescription = "VİOP veri kaynağını Ayarlar ekranında yapılandır"
            status.text = "VİOP veri kaynağı hazır değil.\nGerçek sözleşme verisi alınamadığı için tarama başlatılamıyor. HTTPS backend yapılandırıldığında bu alan aktifleşecektir. Sahte veri üretilmez."
        }
    }

    private fun refreshContracts() {
        status.text = "Üretim VİOP backend sözleşmeleri alınıyor..."
        refreshButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val (items, message) = repo.refresh()
                render(items)
                status.text = if (items.isEmpty()) {
                    "$message\nGerçek sözleşme verisi alınamadı; sahte veri üretilmedi."
                } else message
            } finally {
                refreshButton.isEnabled = true
                refreshScreenState()
            }
        }
    }

    private fun render(items: List<ViopContract>) {
        list.adapter = ViopAdapter(items) { /* Üretim ekranında manuel silme aksiyonu yok. */ }
    }
}
