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
        debug.text = "Tarama ancak veri sağlayıcısı hazır olduğunda başlar. 0/0 başarılı tarama olarak gösterilmez."

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
            debug.text = "Kontrol: Backend → Health → Authentication → BIST Symbols → History"
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
                        txt.text = "0 / $announcedTotal • %0"
                        status.text = "Veri sağlayıcısı hazır • BIST taraması başlatılıyor"
                        debug.text = "Health ✓ • Authentication ✓ • Symbols ✓ (${preflight.symbolCount}) • History ✓"
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
                                txt.text = "$visibleProcessed / $visibleTotal • %${state.progress}"
                            } else if (state.status == ScanStatus.RUNNING) {
                                txt.text = "SEMBOL LİSTESİ ALINIYOR"
                            }

                            when (state.status) {
                                ScanStatus.IDLE -> status.text = "Hazır"
                                ScanStatus.RUNNING -> {
                                    status.text = "BIST taraması çalışıyor"
                                    debug.text = "ProviderRouter • İşlenen ${state.processed}/${visibleTotal.coerceAtLeast(state.total)} • Atlanan ${state.skipped}"
                                }
                                ScanStatus.COMPLETED -> status.text = "BIST taraması tamamlandı • ${state.results.size} sonuç • ${state.scanRun?.status ?: "?"}"
                                ScanStatus.ERROR -> {
                                    status.text = "BIST taraması başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                    if (state.total <= 0) txt.text = "TARAMA BAŞLAMADI"
                                }
                                ScanStatus.CANCELLED -> status.text = "Tarama durduruldu"
                            }
                        }
                    }

                    if (finalState.status == ScanStatus.COMPLETED && finalState.scanRun?.status == ScanRunStatus.COMPLETE) {
                        AppSession.lastOpportunities = finalState.results.sortedWith(
                            compareByDescending<tr.borsatakip.v5.model.Opportunity> { it.finalSignalScore }.thenBy { it.symbol }
                        )
                        progress.progress = 100
                        txt.text = "${finalState.processed} / ${finalState.total} • %100"
                        status.text = "BIST taraması tamamlandı • ${finalState.results.size} sonuç • Atlanan ${finalState.skipped}"
                        debug.text = "Aktif kaynak: ${settings.lastProviderLabel}\nScanRun=COMPLETE • son başarılı tarama güncellendi."
                        startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                    } else if (finalState.status == ScanStatus.COMPLETED) {
                        val pct = if (finalState.total > 0) finalState.progress else 0
                        txt.text = if (finalState.total > 0) "${finalState.processed} / ${finalState.total} • %$pct" else "TARAMA BAŞLAMADI"
                        status.text = "BIST taraması kısmi/eksik tamamlandı • son başarılı tarama korunuyor"
                        debug.text = "ScanRun=${finalState.scanRun?.status ?: "?"} • hata/atlanan=${finalState.skipped + finalState.integrityRejected}"
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
            "Kaynak: Production Backend • tarama öncesi Health/Symbols/History doğrulaması"
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
