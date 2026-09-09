package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.scan.SafeBistScanner
import tr.borsatakip.v5.scan.SafeScanState
import tr.borsatakip.v5.scan.SafeScanStatus

/**
 * V5.1.5 DEBUG: BIST Tara önce provider'dan tamamen ayrılmış yerel motor testi çalıştırır.
 * Bu ekran gerçek provider'a dokunmaz. Amaç cihaz üzerindeki crash'in UI/scanner tarafında olup
 * olmadığını izole ederek kanıtlamaktır.
 */
class BistScanActivity : BaseActivity() {
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bist_scan)
        setupBottomNav()

        val progress = requireNotNull(findViewById<ProgressBar>(R.id.progress)) { "progress view missing" }
        val txt = requireNotNull(findViewById<TextView>(R.id.txtProgress)) { "txtProgress view missing" }
        val status = requireNotNull(findViewById<TextView>(R.id.txtStatus)) { "txtStatus view missing" }
        val btn = requireNotNull(findViewById<Button>(R.id.btnStartScan)) { "btnStartScan view missing" }
        val source = requireNotNull(findViewById<TextView>(R.id.txtSource)) { "txtSource view missing" }
        val debug = requireNotNull(findViewById<TextView>(R.id.txtDebugState)) { "txtDebugState view missing" }

        source.text = "V5.1.5 TEST MODU\nProvider: YEREL TEST • İnternet/WebSocket/Backend kullanılmaz"

        fun render(state: SafeScanState) {
            val safeProgress = state.progress.coerceIn(0, 100)
            progress.progress = safeProgress
            txt.text = "${state.processed.coerceAtLeast(0)} / ${state.total.coerceAtLeast(0)} • %$safeProgress"
            status.text = "${state.message}\nBaşarılı: ${state.successful} • Atlanan: ${state.skipped} • Toplam: ${state.total}"
            debug.text = buildString {
                append("Motor: ")
                append(if (state.status == SafeScanStatus.RUNNING) "ÇALIŞIYOR" else "HAZIR")
                append("\nProvider: TEST")
                append("\nTarama: ${state.processed}/${state.total}")
                append("\nSon işlenen: ${state.lastSymbol}")
                append("\nHata: ${state.errors}")
            }
            btn.text = if (state.status == SafeScanStatus.RUNNING) "DURDUR" else "TEST TARAMASINI BAŞLAT"
            btn.isEnabled = true
        }

        render(SafeScanState())

        btn.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            AppSession.lastOpportunities = emptyList()
            val scanner = SafeBistScanner()
            scanJob = lifecycleScope.launch {
                val finalState = scanner.scan(::render)
                if (finalState.status == SafeScanStatus.COMPLETED) {
                    AppSession.lastOpportunities = finalState.results.toList()
                    render(finalState)
                    startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                } else {
                    render(finalState)
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
