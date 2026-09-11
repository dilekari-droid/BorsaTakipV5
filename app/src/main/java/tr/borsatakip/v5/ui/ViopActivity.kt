package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract

class ViopActivity : BaseActivity() {
    private lateinit var repo: ViopRepository
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var providerStatus: TextView
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop)
        setupBottomNav()
        repo = ViopRepository(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        providerStatus = findViewById(R.id.providerStatus)
        scanButton = findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(this)

        refreshProviderLabel()
        showManualOnlyIfPresent()

        scanButton.setOnClickListener {
            val s = SettingsStore(this)
            if (!s.baseUrl.startsWith("https://")) {
                status.text = "BLOCKED • Production VİOP backend'i yapılandırılmamış. Gerçek tarama başlatılmadı.\nAyarlar → Veri Sağlayıcı bölümünde gerçek HTTPS backend tanımlayın."
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            refreshContracts()
        }
        findViewById<Button>(R.id.addContract).setOnClickListener { showAddDialog() }
    }

    private fun showManualOnlyIfPresent() {
        val manual = repo.loadManual()
        if (manual.isNotEmpty()) {
            render(manual)
            status.text = "MANUEL / DEMO • ${manual.size} kayıt • gerçek Production taraması değildir • fiyat/sinyal uydurulmaz"
        } else {
            val s = SettingsStore(this)
            status.text = if (s.baseUrl.startsWith("https://")) {
                "Hazır • VİOP taraması başlatılmadı"
            } else {
                "BLOCKED • Production VİOP backend'i yapılandırılmamış"
            }
        }
    }

    private fun refreshProviderLabel() {
        val s = SettingsStore(this)
        providerStatus.text = buildString {
            append("Ana VİOP kaynağı: HTTPS Production Backend\n")
            append("Backend: ${if (s.baseUrl.startsWith("https://")) "YAPILANDIRILMIŞ" else "YAPILANDIRILMAMIŞ"}\n")
            append("Kontrol: HTTPS → Contract Universe → Quote alanları → veri tazeliği\n")
            append("TradingView: yalnız harici görüntüleme • VİOP veri kaynağı değil")
        }
    }

    private fun refreshContracts() {
        status.text = "1/4 Backend kontrolü • 2/4 Aktif sözleşme evreni bekleniyor..."
        scanButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val r = repo.refreshDetailed()
                if (r.productionItems.isEmpty()) {
                    render(r.manualItems)
                    status.text = buildString {
                        append(r.message)
                        append("\nGerçek sözleşme verisi alınamadı; sahte kontrat/fiyat/hacim/açık pozisyon/sinyal üretilmedi.")
                        if (r.manualItems.isNotEmpty()) append("\nMANUEL / DEMO: ${r.manualItems.size} kayıt ayrı gösteriliyor.")
                    }
                    return@launch
                }

                render(r.productionItems)
                val usable = r.validCount + r.watchCount
                status.text = buildString {
                    append("4/4 Production VİOP taraması tamamlandı\n")
                    append("Sözleşme evreni: ${r.totalProduction} • Doğrulanmış: ${r.validCount} • İzleme: ${r.watchCount}\n")
                    append("Yetersiz: ${r.insufficientCount} • Reddedilen: ${r.rejectedCount} • Yayınlanabilir: $usable\n")
                    append("Not: Ayrı VİOP history/teknik-sinyal endpoint'i doğrulanmadıkça LONG/SHORT sinyali üretilmez.")
                }
            } finally {
                scanButton.isEnabled = true
                refreshProviderLabel()
            }
        }
    }

    private fun render(items: List<ViopContract>) {
        list.adapter = ViopAdapter(items) { contract -> confirmDelete(contract) }
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_viop_contract, null, false)
        val underlying = view.findViewById<EditText>(R.id.inputUnderlying)
        val expiry = view.findViewById<EditText>(R.id.inputExpiry)
        val symbol = view.findViewById<EditText>(R.id.inputSymbol)
        val provider = view.findViewById<Spinner>(R.id.inputProvider)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Manuel / Veri Yok"))
        provider.isEnabled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("VİOP Manuel / Demo Kayıt Ekle")
            .setView(view)
            .setNegativeButton("İPTAL", null)
            .setPositiveButton("EKLE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = underlying.text.toString().trim().uppercase()
                val e = expiry.text.toString().trim()
                val s = symbol.text.toString().trim().uppercase()
                when {
                    u.isBlank() -> underlying.error = "Dayanak zorunlu"
                    !e.matches(Regex("\\d{4}-\\d{2}")) -> expiry.error = "Vade YYYY-MM biçiminde olmalı"
                    s.isBlank() -> symbol.error = "Sözleşme kodu zorunlu"
                    else -> {
                        val result = repo.addManual(
                            ViopContract(
                                symbol = s,
                                underlying = u,
                                expiry = e,
                                providerId = "manual",
                                providerLabel = "MANUEL / DEMO",
                                isManual = true,
                                status = "Manuel kayıt • veri yok",
                                dataTimestamp = 0L,
                                isRealtime = false,
                                delaySeconds = null,
                                currentSessionIncluded = false,
                                receivedAt = System.currentTimeMillis(),
                                dataMode = DataMode.UNVERIFIED,
                                validity = SignalValidity.WATCH,
                                validityReason = "Manuel kayıt piyasa verisi değildir; fiyat, hacim, açık pozisyon ve sinyal üretilmez."
                            )
                        )
                        result.onSuccess {
                            showManualOnlyIfPresent()
                            dialog.dismiss()
                        }.onFailure {
                            symbol.error = it.message ?: "Sözleşme kaydedilemedi."
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(contract: ViopContract) {
        if (!contract.isManual) return
        AlertDialog.Builder(this)
            .setTitle("Manuel kaydı sil")
            .setMessage("${contract.symbol} MANUEL / DEMO kaydı silinsin mi?")
            .setNegativeButton("İPTAL", null)
            .setPositiveButton("SİL") { _, _ ->
                repo.removeManual(contract.symbol)
                showManualOnlyIfPresent()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized) refreshProviderLabel()
    }
}
