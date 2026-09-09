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
        val source = findViewById<TextView>(R.id.txtSource)
        val debug = findViewById<TextView>(R.id.txtDebugState)
        val settings = SettingsStore(this)

        fun refreshSourceLabel() {
            source.text = if (settings.baseUrl.startsWith("https://")) {
                "Kaynak: HTTPS BorsaTakip backend • Yahoo yalnız açıkça etkinse yedek"
            } else if (settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled) {
                "Kaynak: Backend yapılandırılmamış • Yahoo deneysel/gecikmeli yedek"
            } else {
                "Kaynak: Üretim backend yapılandırılmamış"
            }
        }

        refreshSourceLabel()
        status.text = "Hazır"
        txt.text = "0 / 0 • %0"
        debug.text = "TradingView BIST veri sağlayıcısı değildir. Tarama ProviderRouter üzerinden yürütülür."

        btn.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            progress.progress = 0
            btn.text = "DURDUR"
            status.text = "BIST veri kaynağına bağlanılıyor..."
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
                                    status.text = "BIST taraması çalışıyor"
                                    debug.text = "ProviderRouter • İşlenen ${state.processed}/${state.total} • Atlanan ${state.skipped}"
                                }
                                ScanStatus.COMPLETED -> status.text = "BIST taraması tamamlandı • ${state.results.size} sonuç"
                                ScanStatus.ERROR -> status.text = "BIST taraması başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                ScanStatus.CANCELLED -> status.text = "Tarama durduruldu"
                            }
                        }
                    }

                    if (finalState.status == ScanStatus.COMPLETED) {
                        AppSession.lastOpportunities = finalState.results.sortedByDescending { it.finalSignalScore }
                        progress.progress = 100
                        txt.text = "${finalState.processed} / ${finalState.total} • %100"
                        status.text = "BIST taraması tamamlandı • ${finalState.results.size} sonuç • Atlanan ${finalState.skipped}"
                        debug.text = "Aktif kaynak: ${settings.lastProviderLabel}\nTradingView veri kaynağı kullanılmadı."
                        startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                    }
                } catch (ce: CancellationException) {
                    status.text = "Tarama durduruldu"
                    throw ce
                } catch (t: Throwable) {
                    status.text = "BIST taraması başarısız • ${t.message ?: "Beklenmeyen hata"}"
                    debug.text = "Sahte/demo/TradingView verisine geçilmedi."
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
