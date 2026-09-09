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
import tr.borsatakip.v5.data.TradingViewScannerOpportunityProvider

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

        source.text = "Kaynak: TradingView BIST Scanner • Gerçek veri • Demo/test provider yok"
        status.text = "Hazır"
        txt.text = "0 / 0 • %0"
        debug.text = "Tarama gerçek TradingView Scanner üzerinden yapılır."

        btn.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            progress.progress = 0
            btn.text = "DURDUR"
            status.text = "TradingView BIST Scanner'a bağlanılıyor..."
            AppSession.lastOpportunities = emptyList()

            scanJob = lifecycleScope.launch {
                try {
                    val result = TradingViewScannerOpportunityProvider().scan { done, total ->
                        runOnUiThread {
                            val pct = if (total <= 0) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)
                            progress.progress = pct
                            txt.text = "$done / $total • %$pct"
                            status.text = "GERÇEK BIST TARAMASI çalışıyor"
                            debug.text = "Kaynak: TradingView Scanner\nİşlenen: $done/$total"
                        }
                    }

                    result.onSuccess { output ->
                        AppSession.lastOpportunities = output.opportunities
                        progress.progress = 100
                        txt.text = "${output.receivedRows} / ${output.receivedRows} • %100"
                        status.text = "GERÇEK BIST TARAMASI tamamlandı • ${output.opportunities.size} sonuç • Atlanan ${output.skippedRows}"
                        debug.text = "Kaynak: ${output.sourceLabel}\nDemo/test provider kullanılmadı."
                        startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                    }.onFailure { error ->
                        status.text = "GERÇEK BIST TARAMASI başarısız • ${error.message ?: "Veri alınamadı"}"
                        debug.text = "Demo/test verisine geçilmedi."
                    }
                } catch (ce: CancellationException) {
                    status.text = "Tarama durduruldu"
                    throw ce
                } catch (t: Throwable) {
                    status.text = "GERÇEK BIST TARAMASI başarısız • ${t.message ?: "Beklenmeyen hata"}"
                    debug.text = "Demo/test verisine geçilmedi."
                } finally {
                    btn.text = "GERÇEK BIST TARAMASINI BAŞLAT"
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
