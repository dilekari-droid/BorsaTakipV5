package tr.borsatakip.v5.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.data.ViopRunMode
import tr.borsatakip.v5.model.ViopContract

class ViopActivity : BaseActivity() {
    private lateinit var repo: ViopRepository
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var providerStatus: TextView
    private lateinit var refreshButton: Button
    private lateinit var addExperimentalButton: Button
    private var lastMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop)
        setupBottomNav()

        repo = ViopRepository(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        providerStatus = findViewById(R.id.providerStatus)
        refreshButton = findViewById(R.id.refresh)
        addExperimentalButton = findViewById(R.id.addExperimental)
        list.layoutManager = LinearLayoutManager(this)

        refreshButton.setOnClickListener { refreshContracts() }
        addExperimentalButton.setOnClickListener { showExperimentalContractDialog() }

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
        val mode = repo.mode()
        val backendReady = s.baseUrl.startsWith("https://")

        providerStatus.text = buildString {
            append("Ana VİOP kaynağı: HTTPS backend\n")
            append("Backend: ${if (backendReady) "YAPILANDIRILMIŞ" else "YAPILANDIRILMAMIŞ"}\n")
            append("Deneysel mod: ${if (mode == ViopRunMode.EXPERIMENTAL_LOCAL) "AKTİF" else "PASİF"}\n")
            append("TradingView: yalnız harici görüntüleme • VİOP veri kaynağı değil")
        }

        addExperimentalButton.visibility = if (mode == ViopRunMode.EXPERIMENTAL_LOCAL) View.VISIBLE else View.GONE

        when (mode) {
            ViopRunMode.PRODUCTION_BACKEND -> {
                refreshButton.text = "VİOP TARAMASINI BAŞLAT"
                refreshButton.contentDescription = "VİOP sözleşmelerini HTTPS backend üzerinden tara"
                if (lastMessage == null) {
                    status.text = if (repo.loadLocal().isEmpty()) {
                        "Production VİOP kaynağı hazır. Gerçek sözleşme verisini almak için taramayı başlatın."
                    } else "Kayıtlı VİOP sözleşmeleri gösteriliyor."
                }
            }
            ViopRunMode.EXPERIMENTAL_LOCAL -> {
                refreshButton.text = "DENEYSEL VİOP AKIŞINI ÇALIŞTIR"
                refreshButton.contentDescription = "Yerel deneysel VİOP geliştirme akışını çalıştır"
                if (lastMessage == null) {
                    status.text = if (repo.loadLocal().isEmpty()) {
                        "DENEYSEL VİOP MODU hazır. Sözleşme metadata'sı ekleyerek kart, liste, filtre ve navigasyon yapısını geliştirebilirsiniz. Piyasa verisi uydurulmaz."
                    } else {
                        "DENEYSEL VİOP MODU • ${repo.loadLocal().size} geliştirme kaydı. Piyasa fiyatı değildir."
                    }
                }
            }
            ViopRunMode.UNAVAILABLE -> {
                refreshButton.text = "DENEYSEL SAĞLAYICILARI AYARLARDAN AÇ"
                refreshButton.contentDescription = "Ayarlar ekranında deneysel sağlayıcıları etkinleştir"
                status.text = "VİOP backend yok ve deneysel sağlayıcılar kapalı."
            }
        }

        lastMessage?.let { status.text = it }
    }

    private fun refreshContracts() {
        lastMessage = null
        status.text = when (repo.mode()) {
            ViopRunMode.PRODUCTION_BACKEND -> "Üretim VİOP backend sözleşmeleri alınıyor..."
            ViopRunMode.EXPERIMENTAL_LOCAL -> "Deneysel VİOP geliştirme akışı çalıştırılıyor..."
            ViopRunMode.UNAVAILABLE -> "VİOP veri kaynağı kullanılamıyor."
        }
        refreshButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = repo.refresh()
                render(result.items)
                lastMessage = result.message
            } catch (t: Throwable) {
                lastMessage = "VİOP akışı başarısız: ${t.message ?: "Beklenmeyen hata"}. Sahte veri üretilmedi."
            } finally {
                refreshButton.isEnabled = true
                refreshScreenState()
            }
        }
    }

    private fun showExperimentalContractDialog() {
        if (repo.mode() != ViopRunMode.EXPERIMENTAL_LOCAL) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_viop_contract, null)
        val underlying = view.findViewById<EditText>(R.id.inputUnderlying)
        val expiry = view.findViewById<EditText>(R.id.inputExpiry)
        val symbol = view.findViewById<EditText>(R.id.inputSymbol)

        AlertDialog.Builder(this)
            .setTitle("Deneysel VİOP metadata kaydı")
            .setMessage("Yalnız bildiğiniz sözleşme kodu/dayanak/vade bilgisini girin. Fiyat, hacim ve açık pozisyon üretilmez.")
            .setView(view)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Ekle") { _, _ ->
                val result = repo.addExperimentalContract(
                    symbol.text.toString(),
                    underlying.text.toString(),
                    expiry.text.toString()
                )
                if (result.isSuccess) {
                    lastMessage = "Deneysel sözleşme metadata'sı eklendi. Piyasa verisi değildir."
                    render(repo.loadLocal())
                    refreshScreenState()
                } else {
                    Toast.makeText(this, result.exceptionOrNull()?.message ?: "Kayıt eklenemedi", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun render(items: List<ViopContract>) {
        list.adapter = ViopAdapter(items) { contract ->
            if (repo.mode() == ViopRunMode.EXPERIMENTAL_LOCAL && contract.isManual) {
                AlertDialog.Builder(this)
                    .setTitle("Deneysel kaydı sil")
                    .setMessage("${contract.symbol} yerel geliştirme kaydı silinsin mi?")
                    .setNegativeButton("İptal", null)
                    .setPositiveButton("Sil") { _, _ ->
                        repo.removeExperimentalContract(contract.symbol)
                        lastMessage = "${contract.symbol} deneysel kaydı silindi."
                        render(repo.loadLocal())
                        refreshScreenState()
                    }
                    .show()
            }
        }
    }
}
