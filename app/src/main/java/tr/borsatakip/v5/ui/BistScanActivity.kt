package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanState
import tr.borsatakip.v5.scan.ScanStatus
import tr.borsatakip.v5.scan.SymbolTerminalStatus

class BistScanActivity : BaseActivity() {
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bist_scan)
        setupBottomNav()

        val progress = findViewById<ProgressBar>(R.id.progress)
        val txt = findViewById<TextView>(R.id.txtProgress)
        val status = findViewById<TextView>(R.id.txtStatus)
        val btn = findViewById<Button>(R.id.btnStartScan)
        val technicalBtn = findViewById<Button>(R.id.btnTechnical)
        val source = findViewById<TextView>(R.id.txtSource)
        val debug = findViewById<TextView>(R.id.txtDebugState)
        val settings = SettingsStore(this)

        fun delayedMode(): Boolean =
            !settings.baseUrl.startsWith("https://") &&
                settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled

        fun refreshSourceLabel() {
            source.text = when {
                settings.baseUrl.startsWith("https://") -> "● CANLI VERİ KAYNAĞI HAZIR"
                delayedMode() -> "● YEDEK / GECİKMELİ VERİ MODU • CANLI DEĞİL"
                else -> "● VERİ KAYNAĞI AYARLANMAMIŞ"
            }
        }

        technicalBtn.isEnabled = true
        technicalBtn.alpha = 1f
        technicalBtn.contentDescription = "Tarama hata ve terminal durum ayrıntılarını göster veya gizle"
        technicalBtn.setOnClickListener {
            val show = debug.visibility != View.VISIBLE
            debug.visibility = if (show) View.VISIBLE else View.GONE
            technicalBtn.text = if (show) "TEKNİK VERİ DETAYINI GİZLE" else "TEKNİK VERİ DETAYI"
        }

        refreshSourceLabel()
        status.text = if (delayedMode()) {
            "Hazır • Gecikmeli/yedek veri taranabilir; sonuçlar CANLI olarak etiketlenmez."
        } else {
            "Hazır • Tarama başlatılabilir"
        }
        txt.text = "İşlenen: 0 / 0"
        debug.text = "Motor: HAZIR\nProvider: ${settings.lastProviderLabel}\nTarama: 0/0\nTerminal sonuç: 0"

        btn.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            progress.progress = 0
            btn.text = "DURDUR"
            status.text = "Veri kaynağına bağlanılıyor..."
            AppSession.lastOpportunities = emptyList()
            refreshSourceLabel()

            scanJob = lifecycleScope.launch {
                try {
                    val scanner = BistScanner(ProviderRouter(this@BistScanActivity))
                    val finalState = scanner.scan { state ->
                        runOnUiThread {
                            progress.progress = state.progress
                            txt.text = if (state.status == ScanStatus.COMPLETED) {
                                "İşlenen: ${state.processed} / ${state.total} • Tarama tamamlandı"
                            } else {
                                "İşlenen: ${state.processed} / ${state.total} • İlerleme %${state.progress}"
                            }
                            status.text = renderUserState(state, delayedMode())
                            debug.text = renderTechnicalState(state, settings.lastProviderLabel)
                        }
                    }

                    if (finalState.status == ScanStatus.COMPLETED) {
                        AppSession.lastOpportunities = finalState.results.sortedByDescending { it.finalSignalScore }
                        progress.progress = 100
                        txt.text = "İşlenen: ${finalState.processed} / ${finalState.total} • Tarama tamamlandı"
                        status.text = renderUserState(finalState, delayedMode())
                        debug.text = renderTechnicalState(finalState, settings.lastProviderLabel)

                        if (finalState.signalCount > 0) {
                            startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                        }
                    }
                } catch (ce: CancellationException) {
                    status.text = "Tarama durduruldu"
                    throw ce
                } catch (t: Throwable) {
                    status.text = "Tarama başlatılamadı.\nVeri kaynağını Ayarlar bölümünden kontrol edin."
                    debug.text = "Hata: ${t.message ?: "Beklenmeyen hata"}\nSahte/demo verisine geçilmedi."
                } finally {
                    btn.text = "BIST TARAMASINI BAŞLAT"
                    refreshSourceLabel()
                }
            }
        }
    }

    private fun renderUserState(state: ScanState, delayedMode: Boolean): String {
        val failures = hardFailureCount(state)
        return when (state.status) {
            ScanStatus.IDLE -> "Hazır"
            ScanStatus.RUNNING -> buildString {
                append("Tarama çalışıyor\n")
                append("Veri alındı: ${state.dataReceived} • Hata: $failures • Fırsat: ${state.signalCount}")
                if (delayedMode) append("\nYedek/gecikmeli veri kullanılıyor; CANLI değildir.")
            }
            ScanStatus.COMPLETED -> buildString {
                append("Tarama: Tamamlandı\n")
                append("İşlenen: ${state.processed}/${state.total} • Veri alındı: ${state.dataReceived}\n")
                append("Başarılı analiz: ${state.successful} • Hata: $failures\n")
                append("Fırsat: ${state.signalCount} • Sinyal yok: ${state.noSignal}")
                if (failures >= state.total && state.total > 0) {
                    append("\nVERİ İŞLENEMEDİ: tüm semboller hata ile sonuçlandı. Teknik veri detayını açın.")
                } else if (delayedMode) {
                    append("\nUYARI: Sonuçlar gecikmeli/yedek veriye dayanır; canlı veri değildir.")
                }
            }
            ScanStatus.ERROR -> "Tarama tamamlanamadı\n${state.errorMessage ?: "Veri alınamadı"}"
            ScanStatus.CANCELLED -> "Tarama durduruldu"
        }
    }

    private fun hardFailureCount(state: ScanState): Int =
        state.timeout + state.rateLimited + state.httpErrors + state.networkErrors +
            state.parseErrors + state.dataInsufficient + state.integrityRejected + state.analysisErrors

    private fun renderTechnicalState(state: ScanState, providerLabel: String): String {
        val failures = state.terminalResults
            .filter { it.status != SymbolTerminalStatus.SIGNAL && it.status != SymbolTerminalStatus.NO_SIGNAL }
            .take(20)
            .joinToString("\n") {
                buildString {
                    append("${it.symbol}: ${it.status}")
                    if (it.attempt > 1) append(" • deneme ${it.attempt}")
                    it.httpCode?.let { code -> append(" • HTTP $code") }
                    if (!it.errorMessage.isNullOrBlank()) append(" • ${it.errorMessage}")
                }
            }
            .ifBlank { "Sembol bazlı hata yok." }

        return buildString {
            append("Provider: $providerLabel\n")
            append("Toplam: ${state.total} • İşlenen: ${state.processed} • Veri alındı: ${state.dataReceived}\n")
            append("Sinyal: ${state.signalCount} • Sinyal yok: ${state.noSignal}\n")
            append("Timeout: ${state.timeout} • Rate limit: ${state.rateLimited} • HTTP: ${state.httpErrors}\n")
            append("Ağ: ${state.networkErrors} • Parse: ${state.parseErrors} • Yetersiz veri: ${state.dataInsufficient}\n")
            append("Doğrulama reddi: ${state.integrityRejected} • Analiz hatası: ${state.analysisErrors}\n")
            append("Terminal sonuç: ${state.terminalResults.size}/${state.total}\n\n")
            append("SEMBOL BAZLI RAPOR (ilk 20):\n$failures")
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        super.onDestroy()
    }
}
