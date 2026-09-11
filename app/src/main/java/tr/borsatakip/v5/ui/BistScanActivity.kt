package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendPreflightClient
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus

class BistScanActivity : BaseActivity() {
    private var scanJob: Job? = null
    private lateinit var source: TextView
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bist_scan)
        setupBottomNav()

        val progress = findViewById<ProgressBar>(R.id.progress)
        val txt = findViewById<TextView>(R.id.txtProgress)
        val status = findViewById<TextView>(R.id.txtStatus)
        val btn = findViewById<Button>(R.id.btnStartScan)
        val configure = findViewById<Button>(R.id.btnConfigureProvider)
        source = findViewById(R.id.txtSource)
        val debug = findViewById<TextView>(R.id.txtDebugState)
        settings = SettingsStore(this)

        refreshSourceLabel()
        status.text = "Hazır • tarama başlatılmadı"
        txt.text = "TARAMA BAŞLAMADI"
        progress.progress = 0
        debug.text = "Tarama ilerlemesi ile başarılı analiz sayısı ayrı gösterilir."

        configure.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btn.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            progress.progress = 0
            btn.text = "DURDUR"
            status.text = "Veri sağlayıcısı doğrulanıyor..."
            txt.text = "TARAMA BAŞLAMADI"
            debug.text = "Kontrol: Backend → Health → Authentication → BIST Symbols → Quote → History"
            refreshSourceLabel()

            scanJob = lifecycleScope.launch {
                try {
                    var announcedTotal = 0

                    if (!experimentalOnly()) {
                        val preflight = BackendPreflightClient(this@BistScanActivity).check()
                        if (!preflight.ok) {
                            progress.progress = 0
                            txt.text = "PROVIDER HAZIR DEĞİL"
                            status.text = "Tarama başlatılamadı • ${preflight.message}"
                            debug.text = "${preflight.failureKind} • ScanRun oluşturulmadı • son başarılı tarama korunuyor.\nAyarlar → Veri Sağlayıcı bölümünden gerçek HTTPS backend adresini yapılandırın."
                            return@launch
                        }
                        announcedTotal = preflight.symbolCount
                        txt.text = "Tarama ilerlemesi: 0 / $announcedTotal • %0"
                        status.text = "Veri sağlayıcısı hazır • BIST taraması başlatılıyor"
                        debug.text = "Health ✓ • Authentication ✓ • Symbols ✓ (${preflight.symbolCount}) • Quote ✓ • History ✓"
                    } else {
                        status.text = "DENEYSEL sağlayıcı açık • üretim verisi olarak etiketlenmeyecek"
                        debug.text = "Yahoo fallback kullanıcı tarafından açıkça etkinleştirildi."
                    }

                    val scanner = BistScanner(ProviderRouter(this@BistScanActivity))
                    val finalState = scanner.scan { state ->
                        runOnUiThread {
                            progress.progress = state.progress
                            val visibleTotal = if (state.total > 0) state.total else announcedTotal
                            if (visibleTotal > 0) {
                                val visibleProcessed = state.processed.coerceIn(0, visibleTotal)
                                txt.text = "Tarama ilerlemesi: $visibleProcessed / $visibleTotal • %${state.progress}"
                            } else if (state.status == ScanStatus.RUNNING) {
                                txt.text = "SEMBOL LİSTESİ ALINIYOR"
                            }

                            when (state.status) {
                                ScanStatus.IDLE -> status.text = "Hazır"
                                ScanStatus.RUNNING -> {
                                    status.text = "BIST taraması çalışıyor"
                                    debug.text = "İşlenen ${state.processed}/${visibleTotal.coerceAtLeast(state.total)} • Analiz sonucu ${state.successful} • Hatalı/atlanan ${state.skipped}"
                                }
                                ScanStatus.COMPLETED -> {
                                    status.text = "Tarama döngüsü tamamlandı • Analiz sonucu ${state.successful} • ${state.scanRun?.status ?: "?"}"
                                    debug.text = "Başarılı analiz ${state.successful} • Hatalı/atlanan ${state.skipped} • Veri güvenilirliği uyarısı ${state.integrityRejected}"
                                }
                                ScanStatus.ERROR -> {
                                    status.text = "BIST taraması başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                    if (state.total <= 0) txt.text = "TARAMA BAŞLAMADI"
                                }
                                ScanStatus.CANCELLED -> status.text = "Tarama durduruldu"
                            }
                        }
                    }

                    val runStatus = finalState.scanRun?.status
                    val sortedResults = finalState.results.sortedWith(
                        compareByDescending<Opportunity> { it.finalSignalScore }.thenBy { it.symbol }
                    )

                    when {
                        finalState.status == ScanStatus.COMPLETED && runStatus == ScanRunStatus.COMPLETE -> {
                            AppSession.lastOpportunities = sortedResults
                            progress.progress = 100
                            txt.text = "Tarama ilerlemesi: ${finalState.processed}/${finalState.total} • %100"
                            status.text = "BIST taraması tamamlandı • Başarılı ${finalState.successful}/${finalState.total}"
                            debug.text = "ScanRun=COMPLETE • Hata/atlanan 0 • son başarılı tarama güncellendi."
                            startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                        }

                        finalState.status == ScanStatus.COMPLETED &&
                            runStatus == ScanRunStatus.PARTIAL &&
                            finalState.successful > 0 -> {
                            AppSession.lastOpportunities = sortedResults
                            progress.progress = 100
                            txt.text = "Tarama ilerlemesi: ${finalState.processed}/${finalState.total} • %100"
                            status.text = "Kısmi tarama • Başarılı ${finalState.successful} • Hatalı/atlanan ${finalState.skipped}"
                            debug.text = "ScanRun=PARTIAL • Veri güvenilirliği uyarısı ${finalState.integrityRejected} • sonuçlar gösteriliyor; son COMPLETE tarama kaydı ezilmedi."
                            startActivity(
                                Intent(this@BistScanActivity, OpportunityActivity::class.java)
                                    .putExtra(OpportunityActivity.EXTRA_SCAN_WARNING, "KISMİ TARAMA • Başarılı ${finalState.successful}/${finalState.total} • Hatalı/atlanan ${finalState.skipped}")
                            )
                        }

                        finalState.status == ScanStatus.COMPLETED && finalState.successful == 0 -> {
                            val pct = if (finalState.total > 0) finalState.progress else 0
                            txt.text = if (finalState.total > 0) "Tarama ilerlemesi: ${finalState.processed}/${finalState.total} • %$pct" else "TARAMA BAŞLAMADI"
                            status.text = "Tarama tamamlandı ancak başarılı analiz yok"
                            debug.text = "ScanRun=${runStatus ?: "?"} • Başarılı 0 • Hatalı/atlanan ${finalState.skipped} • Hisseler ekranına geçilmedi."
                        }

                        finalState.status == ScanStatus.COMPLETED -> {
                            status.text = "Tarama tamamlandı ancak yayınlanabilir sonuç oluşmadı"
                            debug.text = "ScanRun=${runStatus ?: "?"} • son başarılı tarama korunuyor."
                        }
                    }
                } catch (_: CancellationException) {
                    status.text = "Tarama durduruldu • son başarılı tarama korunuyor"
                } catch (t: Throwable) {
                    txt.text = "TARAMA BAŞLAMADI"
                    status.text = "BIST taraması başarısız • ${t.message ?: "Beklenmeyen hata"}"
                    debug.text = "Sahte/demo veriye geçilmedi • son başarılı tarama korunuyor."
                } finally {
                    btn.text = "BIST TARAMASINI BAŞLAT"
                    refreshSourceLabel()
                }
            }
        }
    }

    private fun experimentalOnly(): Boolean =
        !settings.baseUrl.startsWith("https://") &&
            settings.experimentalProvidersEnabled &&
            settings.yahooFallbackEnabled

    private fun refreshSourceLabel() {
        source.text = if (settings.baseUrl.startsWith("https://")) {
            "Kaynak: Production Backend • tarama öncesi Health/Symbols/Quote/History doğrulaması"
        } else if (experimentalOnly()) {
            "Kaynak: Yahoo Finance • DENEYSEL/YEDEK/GEÇİKMELİ"
        } else {
            "Kaynak: Production Backend yapılandırması eksik"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized && ::source.isInitialized) refreshSourceLabel()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        super.onDestroy()
    }
}
