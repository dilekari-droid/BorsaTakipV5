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
                delayedMode() -> "⚠ VERİ MODU • YEDEK / GECİKMELİ • CANLI VERİ DEĞİL"
                else -> "● VERİ KAYNAĞI AYARLANMAMIŞ"
            }
        }

        technicalBtn.isEnabled = true
        technicalBtn.alpha = 1f
        technicalBtn.contentDescription = "Tarama veri, analiz, hata ve LRC ayrıntılarını göster veya gizle"
        technicalBtn.setOnClickListener {
            val show = debug.visibility != View.VISIBLE
            debug.visibility = if (show) View.VISIBLE else View.GONE
            technicalBtn.text = if (show) "TEKNİK VERİ DETAYINI GİZLE" else "TEKNİK VERİ DETAYI"
        }

        refreshSourceLabel()
        status.text = if (delayedMode()) {
            "Hazır • Gecikmeli/yedek veri yalnız teknik araştırma adayı üretir; gerçek zamanlı AL/SAT sinyali üretmez."
        } else {
            "Hazır • Tarama başlatılabilir"
        }
        txt.text = "ANALİZ İLERLEMESİ\n0 / 0 • %0 tamamlandı"
        debug.text = "Motor: HAZIR\nProvider: ${settings.lastProviderLabel}\nVeri toplama ve teknik analiz ayrı izlenir."

        btn.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            progress.progress = 0
            btn.text = "DURDUR"
            status.text = "Veri toplama başlatılıyor..."
            AppSession.lastOpportunities = emptyList()
            refreshSourceLabel()

            scanJob = lifecycleScope.launch {
                try {
                    val scanner = BistScanner(ProviderRouter(this@BistScanActivity))
                    val finalState = scanner.scan { state ->
                        runOnUiThread {
                            val analyzed = analyzedCount(state)
                            val analysisProgress = analysisProgress(analyzed, state.total)
                            progress.progress = analysisProgress
                            txt.text = "ANALİZ İLERLEMESİ\n$analyzed / ${state.total} • %$analysisProgress tamamlandı"
                            status.text = renderUserState(state, delayedMode())
                            debug.text = renderTechnicalState(state, settings.lastProviderLabel)
                            AppSession.lastOpportunities = state.results.sortedByDescending { it.finalSignalScore }
                        }
                    }

                    if (finalState.status == ScanStatus.COMPLETED) {
                        AppSession.lastOpportunities = finalState.results.sortedByDescending { it.finalSignalScore }
                        val analyzed = analyzedCount(finalState)
                        val analysisProgress = analysisProgress(analyzed, finalState.total)
                        progress.progress = analysisProgress
                        txt.text = "ANALİZ İLERLEMESİ\n$analyzed / ${finalState.total} • %$analysisProgress tamamlandı"
                        status.text = renderUserState(finalState, delayedMode())
                        debug.text = renderTechnicalState(finalState, settings.lastProviderLabel)

                        if (finalState.signalCount + finalState.researchCandidateCount > 0) {
                            startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                        }
                    }
                } catch (ce: CancellationException) {
                    status.text = "Tarama durduruldu • kısmi sonuçlar korunuyor"
                    throw ce
                } catch (t: Throwable) {
                    status.text = "Tarama başlatılamadı.\nVeri kaynağını Ayarlar bölümünden kontrol edin."
                    debug.text = "Hata: ${t.message ?: "Beklenmeyen hata"}\nSahte/demo verisine geçilmedi."
                } finally {
                    btn.text = "YENİDEN TARA"
                    refreshSourceLabel()
                }
            }
        }
    }

    private fun analyzedCount(state: ScanState): Int =
        (state.successful + state.analysisErrors + state.integrityRejected).coerceAtMost(state.total.coerceAtLeast(0))

    private fun analysisProgress(analyzed: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((analyzed.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
    }

    private fun renderUserState(state: ScanState, delayedMode: Boolean): String {
        val hardFailures = hardFailureCount(state)
        val analyzed = analyzedCount(state)
        return when (state.status) {
            ScanStatus.IDLE -> "Hazır"
            ScanStatus.RUNNING -> buildString {
                append("VERİ DURUMU\n")
                append("Başarılı veri: ${state.dataReceived} • Yetersiz veri: ${state.dataInsufficient} • Veri yok: ${state.dataUnavailable} • Hata: $hardFailures\n\n")
                append("TARAMA DURUMU\n● Tarama çalışıyor\n")
                append("Analiz: $analyzed / ${state.total}\n")
                append("Canlı sinyal: ${state.signalCount} • Araştırma adayı: ${state.researchCandidateCount} • Net sinyal yok: ${state.noSignal}")
                if (delayedMode) append("\n⚠ GECİKMELİ VERİ • GERÇEK ZAMANLI AL/SAT SİNYALİ DEĞİLDİR.")
            }
            ScanStatus.COMPLETED -> buildString {
                append("VERİ DURUMU\n")
                append("Başarılı veri: ${state.dataReceived} • Yetersiz veri: ${state.dataInsufficient} • Veri yok: ${state.dataUnavailable} • Hata: $hardFailures\n\n")
                append("TARAMA DURUMU\n● Tarama tamamlandı\n")
                append("Analiz: $analyzed / ${state.total}\n")
                append("Canlı sinyal: ${state.signalCount} • Araştırma adayı: ${state.researchCandidateCount} • Net sinyal yok: ${state.noSignal}")
                if (state.terminalResults.size != state.total) {
                    append("\nUYARI: Terminal sonuç ${state.terminalResults.size}/${state.total}")
                } else if (delayedMode) {
                    append("\n⚠ GECİKMELİ VERİ • sonuçlar araştırma amaçlıdır; canlı sinyal değildir.")
                }
            }
            ScanStatus.ERROR -> "Tarama tamamlanamadı\n${state.errorMessage ?: "Veri alınamadı"}"
            ScanStatus.CANCELLED -> "Tarama durduruldu • kısmi sonuçlar korunuyor"
        }
    }

    private fun hardFailureCount(state: ScanState): Int =
        state.timeout + state.rateLimited + state.httpErrors + state.networkErrors +
            state.parseErrors + state.integrityRejected + state.analysisErrors

    private fun renderTechnicalState(state: ScanState, providerLabel: String): String {
        val failures = state.terminalResults
            .filter {
                it.status != SymbolTerminalStatus.SIGNAL &&
                    it.status != SymbolTerminalStatus.RESEARCH_CANDIDATE &&
                    it.status != SymbolTerminalStatus.NO_SIGNAL
            }
            .take(20)
            .joinToString("\n") {
                buildString {
                    append("${it.symbol}: ${it.status}")
                    if (it.attempt > 0) append(" • deneme ${it.attempt}")
                    it.httpCode?.let { code -> append(" • HTTP $code") }
                    if (!it.errorMessage.isNullOrBlank()) append(" • ${it.errorMessage}")
                }
            }
            .ifBlank { "Sembol bazlı hata yok." }

        val lrcDetails = state.results.take(12).joinToString("\n") { opportunity ->
            val lrc = opportunity.lrc
            if (lrc == null) {
                "${opportunity.symbol}: LRC unavailable"
            } else {
                val normalized = lrc.normalizedSlopePct?.let { " • norm ${"%.4f".format(it)}%/bar" } ?: ""
                "${opportunity.symbol}: LRC${lrc.period} ${lrc.trend} • eğim ${"%.6f".format(lrc.slope)}$normalized • R ${"%.2f".format(lrc.pearsonR)} • ${lrc.channelPosition}"
            }
        }.ifBlank { "Henüz LRC analiz sonucu yok." }

        return buildString {
            append("Provider: $providerLabel\n")
            append("VERİ TOPLAMA → Toplam ${state.total} • Veri alınan ${state.dataReceived} • Yetersiz ${state.dataInsufficient} • Veri yok ${state.dataUnavailable}\n")
            append("TEKNİK ANALİZ → Analiz ${analyzedCount(state)} • Canlı sinyal ${state.signalCount} • Araştırma adayı ${state.researchCandidateCount} • Net sinyal yok ${state.noSignal}\n")
            append("Timeout: ${state.timeout} • Rate limit: ${state.rateLimited} • HTTP: ${state.httpErrors}\n")
            append("Ağ: ${state.networkErrors} • Parse: ${state.parseErrors}\n")
            append("Doğrulama reddi: ${state.integrityRejected} • Analiz/diğer: ${state.analysisErrors}\n")
            append("Terminal sonuç: ${state.terminalResults.size}/${state.total}\n\n")
            append("LRC TEKNİK DETAY (ilk 12 aday):\n$lrcDetails\n\n")
            append("SEMBOL | DURUM | DENEME | HTTP | AÇIKLAMA (ilk 20):\n$failures")
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        super.onDestroy()
    }
}
