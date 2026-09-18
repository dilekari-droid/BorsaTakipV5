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
import tr.borsatakip.v5.scan.ScanStatus

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

        fun refreshSourceLabel() {
            source.text = if (settings.baseUrl.startsWith("https://")) {
                "● CANLI VERİ KAYNAĞI HAZIR"
            } else if (settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled) {
                "● YEDEK / GECİKMELİ VERİ MODU"
            } else {
                "● VERİ KAYNAĞI AYARLANMAMIŞ"
            }
        }

        technicalBtn.setOnClickListener {
            val show = debug.visibility != View.VISIBLE
            debug.visibility = if (show) View.VISIBLE else View.GONE
            technicalBtn.text = if (show) "TEKNİK VERİ DETAYINI GİZLE" else "TEKNİK VERİ DETAYI"
        }

        refreshSourceLabel()
        status.text = "Hazır • Tarama başlatılabilir"
        txt.text = "0 / 0 • %0"
        debug.text = "Motor: HAZIR\nProvider: ${settings.lastProviderLabel}\nTarama: 0/0\nHata: 0"

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
                            txt.text = "${state.processed} / ${state.total} • %${state.progress}"
                            when (state.status) {
                                ScanStatus.IDLE -> status.text = "Hazır"
                                ScanStatus.RUNNING -> {
                                    status.text = "Tarama çalışıyor • Başarılı ${state.results.size} • Atlanan ${state.skipped}"
                                    debug.text = "Provider: ${settings.lastProviderLabel}\nİşlenen: ${state.processed}/${state.total}\nBaşarılı: ${state.results.size}\nAtlanan: ${state.skipped}"
                                }
                                ScanStatus.COMPLETED -> status.text = "Tarama tamamlandı • ${state.results.size} fırsat"
                                ScanStatus.ERROR -> status.text = "Tarama tamamlanamadı • ${state.errorMessage ?: "Veri alınamadı"}"
                                ScanStatus.CANCELLED -> status.text = "Tarama durduruldu"
                            }
                        }
                    }

                    if (finalState.status == ScanStatus.COMPLETED) {
                        AppSession.lastOpportunities = finalState.results.sortedByDescending { it.finalSignalScore }
                        progress.progress = 100
                        txt.text = "${finalState.processed} / ${finalState.total} • %100"
                        status.text = "Tarama tamamlandı • ${finalState.results.size} fırsat • Atlanan ${finalState.skipped}"
                        debug.text = "Aktif kaynak: ${settings.lastProviderLabel}\nİşlenen: ${finalState.processed}/${finalState.total}\nBaşarılı fırsat: ${finalState.results.size}\nAtlanan: ${finalState.skipped}"
                        startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                    }
                } catch (ce: CancellationException) {
                    status.text = "Tarama durduruldu"
                    throw ce
                } catch (t: Throwable) {
                    status.text = "CANLI VERİ DOĞRULANAMADI\nBu taramada fırsat üretilmedi. Veri kaynağını Ayarlar bölümünden kontrol edin."
                    debug.text = "Hata: ${t.message ?: "Beklenmeyen hata"}\nSahte/demo verisine geçilmedi."
                } finally {
                    btn.text = "BIST TARAMASINI BAŞLAT"
                    refreshSourceLabel()
                }
            }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        super.onDestroy()
    }
}
