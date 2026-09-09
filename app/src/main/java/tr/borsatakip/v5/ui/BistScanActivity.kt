package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanState
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
        val settings = SettingsStore(this)

        fun refreshSourceLabel() {
            val primary = if (settings.baseUrl.startsWith("https://")) settings.baseUrl else "tanımlı değil"
            val fallback = if (settings.yahooFallbackEnabled) "açık" else "kapalı"
            val cached = settings.cachedBistSymbols.size
            source.text = buildString {
                append("Ana kaynak: $primary\n")
                append("Yedek/gecikmeli kaynak: Yahoo Finance ($fallback)\n")
                append("Yüklenen dinamik BIST sembolü: $cached")
                if (cached == 0) append(" • Sabit 28 hisse listesi kullanılmaz")
            }
        }

        fun render(state: ScanState) {
            progress.progress = state.progress
            txt.text = "${state.processed} / ${state.total} • %${state.progress}"
            status.text = when (state.status) {
                ScanStatus.IDLE -> "Hazır"
                ScanStatus.RUNNING -> "Tarama başladı • Başarılı: ${state.successful} • Atlanan: ${state.skipped} • Toplam: ${state.total}"
                ScanStatus.COMPLETED -> "Tarama tamamlandı • Başarılı: ${state.successful} • Atlanan: ${state.skipped} • Toplam: ${state.total} • Fırsat: ${state.results.size}"
                ScanStatus.ERROR -> state.errorMessage ?: "Tarama başlatılamadı."
                ScanStatus.CANCELLED -> "Tarama durduruldu."
            }
            btn.text = if (state.status == ScanStatus.RUNNING) "DURDUR" else "TARAMAYI BAŞLAT"
        }

        refreshSourceLabel()
        render(ScanState())

        btn.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            AppSession.lastOpportunities = emptyList()
            val scanner = BistScanner(ProviderRouter(this))
            scanJob = lifecycleScope.launch {
                val finalState = scanner.scan { state ->
                    runOnUiThread { render(state) }
                }
                if (finalState.status == ScanStatus.COMPLETED) {
                    var ops = finalState.results
                    if (findViewById<Chip>(R.id.chipTechnical).isChecked) ops = ops.filter { it.score >= 65 }
                    if (findViewById<Chip>(R.id.chipVolume).isChecked) ops = ops.filter { (it.technical.volumeRatio ?: 0.0) >= 1.2 }
                    if (findViewById<Chip>(R.id.chipBreakout).isChecked) ops = ops.filter { x ->
                        if (x.direction == "LONG") x.resistance?.let { x.price >= it * 0.995 } == true
                        else x.support?.let { x.price <= it * 1.005 } == true
                    }
                    val longOn = findViewById<Chip>(R.id.chipLong).isChecked
                    val shortOn = findViewById<Chip>(R.id.chipShort).isChecked
                    ops = ops.filter { (it.direction == "LONG" && longOn) || (it.direction == "SHORT" && shortOn) }
                        .sortedByDescending { it.score }
                    AppSession.lastOpportunities = ops
                    render(finalState.copy(results = ops))
                    refreshSourceLabel()
                    startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                }
            }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }
}
