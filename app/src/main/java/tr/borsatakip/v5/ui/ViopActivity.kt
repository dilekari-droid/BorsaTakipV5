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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopScanner
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopScanProgress

class ViopActivity : BaseActivity() {
    private lateinit var repo: ViopRepository
    private lateinit var scanner: ViopScanner
    private lateinit var readiness: ProviderReadinessService
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var providerStatus: TextView
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop)
        setupBottomNav()
        repo = ViopRepository(this)
        scanner = ViopScanner(BackendProvider(this))
        readiness = ProviderReadinessService(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        providerStatus = findViewById(R.id.providerStatus)
        scanButton = findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(this)

        refreshProviderState()
        showManualOnlyIfPresent()

        scanButton.setOnClickListener {
            when (val snapshot = readiness.localConfigState()) {
                else -> when (snapshot.state) {
                    ProviderState.PROVIDER_NOT_CONFIGURED -> {
                        status.text = "BLOCKED • ${snapshot.message}"
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    ProviderState.PROVIDER_READY -> runOpportunityScan()
                    ProviderState.PROVIDER_TESTING -> status.text = "Provider bağlantı testi devam ediyor..."
                    ProviderState.PROVIDER_CONFIGURED,
                    ProviderState.PROVIDER_ERROR -> testThenScan()
                }
            }
        }
        findViewById<Button>(R.id.addContract).setOnClickListener { showAddDialog() }
    }

    private fun testThenScan() {
        scanButton.isEnabled = false
        status.text = "Provider doğrulanıyor: HTTPS → Health → Authentication → VİOP Contracts → Quote → History"
        lifecycleScope.launch {
            val result = readiness.test()
            scanButton.isEnabled = true
            refreshProviderState()
            if (result.state == ProviderState.PROVIDER_READY) {
                status.text = "Provider READY • gerçek VİOP taraması başlatılıyor"
                runOpportunityScan()
            } else {
                status.text = "${result.state} • ${result.failureCode} • ${result.message}"
            }
        }
    }

    private fun showManualOnlyIfPresent() {
        val manual = repo.loadManual()
        if (manual.isNotEmpty()) {
            renderContracts(manual)
            status.text = "MANUEL / DEMO • ${manual.size} kayıt • gerçek Production taraması değildir • fiyat/sinyal uydurulmaz"
        } else {
            val snapshot = readiness.localConfigState()
            status.text = when (snapshot.state) {
                ProviderState.PROVIDER_READY -> "Hazır • gerçek VİOP fırsat taraması başlatılmadı"
                ProviderState.PROVIDER_NOT_CONFIGURED -> "BLOCKED • ${snapshot.message}"
                ProviderState.PROVIDER_ERROR -> "PROVIDER ERROR • ${snapshot.failureCode} • ${snapshot.message}"
                ProviderState.PROVIDER_TESTING -> "Provider bağlantısı test ediliyor..."
                ProviderState.PROVIDER_CONFIGURED -> "Production Backend yapılandırıldı • bağlantı testi gerekli"
            }
        }
    }

    private fun refreshProviderState() {
        val snapshot = readiness.localConfigState()
        providerStatus.text = buildString {
            append("Ana VİOP kaynağı: HTTPS Production Backend\n")
            append("Provider state: ${snapshot.state}\n")
            append("Hata kodu: ${snapshot.failureCode}\n")
            append("Durum: ${snapshot.message}\n")
            append("Akış: Contract Universe → Quote → History → Teknik Analiz → LONG/SHORT → Nihai Sinyal\n")
            append("TradingView: yalnız harici görüntüleme • VİOP veri kaynağı değil")
        }
        scanButton.text = when (snapshot.state) {
            ProviderState.PROVIDER_NOT_CONFIGURED -> "VİOP VERİ SAĞLAYICIYI YAPILANDIR"
            ProviderState.PROVIDER_READY -> "VİOP TARAMASINI BAŞLAT"
            ProviderState.PROVIDER_ERROR -> "BAĞLANTIYI TEKRAR DENE"
            ProviderState.PROVIDER_TESTING -> "PROVIDER TEST EDİLİYOR"
            ProviderState.PROVIDER_CONFIGURED -> "VİOP PROVIDER'I TEST ET"
        }
        scanButton.isEnabled = snapshot.state != ProviderState.PROVIDER_TESTING
    }

    private fun runOpportunityScan() {
        if (readiness.localConfigState().state != ProviderState.PROVIDER_READY) {
            status.text = "BLOCKED • Provider READY değil; gerçek VİOP taraması başlatılmadı."
            refreshProviderState()
            return
        }
        scanButton.isEnabled = false
        status.text = "Provider READY • aktif sözleşme evreni alınıyor..."
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    scanner.scan { p -> runOnUiThread { status.text = progressText(p) } }
                }
                list.adapter = ViopOpportunityAdapter(result.opportunities)
                status.text = buildString {
                    append("${result.status.name} • Production VİOP taraması tamamlandı\n")
                    append(progressText(result.progress))
                    append("\nFırsat: ${result.opportunities.size} • LONG: ${result.progress.longCount} • SHORT: ${result.progress.shortCount}")
                    if (result.errors.isNotEmpty()) {
                        append("\nHata/elenen: ")
                        append(result.errors.take(5).joinToString(" | ") { "${it.symbol}:${it.code}" })
                        if (result.errors.size > 5) append(" +${result.errors.size - 5}")
                    }
                    if (result.opportunities.isEmpty()) append("\nGerçek veri/analiz koşullarını geçen fırsat bulunamadı; sahte sinyal üretilmedi.")
                }
            } catch (t: Throwable) {
                status.text = "VİOP taraması başarısız • ${t.message ?: "Beklenmeyen hata"}"
            } finally {
                scanButton.isEnabled = true
                refreshProviderState()
            }
        }
    }

    private fun progressText(p: ViopScanProgress): String =
        "Toplam ${p.total} • Quote ${p.quoteSuccess} • History ${p.historySuccess} • Analiz ${p.analyzed} • Yetersiz ${p.insufficient} • Elenen ${p.eliminated} • Hata ${p.failed}"

    private fun renderContracts(items: List<ViopContract>) {
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
                                symbol = s, underlying = u, expiry = e,
                                providerId = "manual", providerLabel = "MANUEL / DEMO", isManual = true,
                                status = "Manuel kayıt • veri yok", dataTimestamp = 0L, isRealtime = false,
                                delaySeconds = null, currentSessionIncluded = false, receivedAt = System.currentTimeMillis(),
                                dataMode = DataMode.UNVERIFIED, validity = SignalValidity.WATCH,
                                validityReason = "Manuel kayıt piyasa verisi değildir; fiyat, hacim, açık pozisyon ve sinyal üretilmez."
                            )
                        )
                        result.onSuccess { showManualOnlyIfPresent(); dialog.dismiss() }
                            .onFailure { symbol.error = it.message ?: "Sözleşme kaydedilemedi." }
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
            .setPositiveButton("SİL") { _, _ -> repo.removeManual(contract.symbol); showManualOnlyIfPresent() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::readiness.isInitialized) {
            refreshProviderState()
            showManualOnlyIfPresent()
        }
    }
}
