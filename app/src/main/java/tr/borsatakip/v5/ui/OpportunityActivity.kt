package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus

class OpportunityActivity : BaseActivity() {
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opportunity)
        setupBottomNav()

        val summary = findViewById<TextView>(R.id.txtSummary)
        val list = findViewById<RecyclerView>(R.id.list)
        val scanButton = findViewById<Button>(R.id.btnRealOpportunityScan)
        list.layoutManager = LinearLayoutManager(this)

        fun showExisting() {
            val existing = AppSession.lastOpportunities.sortedByDescending { it.score }
            if (existing.isEmpty()) {
                summary.text = "GERÇEK FIRSAT KONTROLÜ • Henüz tarama yapılmadı. Ana kaynak mobil backend; Yahoo yalnız yedek/gecikmeli kaynaktır."
                list.adapter = OpportunityAdapter(emptyList()) { }
            } else {
                bind(existing, summary, list, "GERÇEK FIRSAT KONTROLÜ • ${existing.size} sonuç • Skor bileşenleri aşağıda açıklanır")
            }
        }

        showExisting()

        scanButton.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            list.adapter = OpportunityAdapter(emptyList()) { }
            scanButton.text = "DURDUR"
            scanJob = lifecycleScope.launch {
                val scanner = BistScanner(ProviderRouter(this@OpportunityActivity))
                val finalState = scanner.scan { state ->
                    runOnUiThread {
                        summary.text = when (state.status) {
                            ScanStatus.IDLE -> "Hazır"
                            ScanStatus.RUNNING -> "GERÇEK FIRSAT TARAMASI • ${state.processed}/${state.total} • %${state.progress} • Atlanan ${state.skipped}"
                            ScanStatus.COMPLETED -> "GERÇEK FIRSAT TARAMASI tamamlandı • ${state.results.size} sonuç"
                            ScanStatus.ERROR -> "GERÇEK FIRSAT TARAMASI başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                            ScanStatus.CANCELLED -> "GERÇEK FIRSAT TARAMASI durduruldu"
                        }
                    }
                }

                scanButton.text = "GERÇEK FIRSAT TARAMASINI BAŞLAT"
                if (finalState.status == ScanStatus.COMPLETED) {
                    val results = finalState.results.sortedByDescending { it.score }
                    AppSession.lastOpportunities = results
                    if (results.isEmpty()) {
                        summary.text = "GERÇEK FIRSAT TARAMASI tamamlandı • Uygun sonuç bulunamadı"
                        list.adapter = OpportunityAdapter(emptyList()) { }
                    } else {
                        val source = results.firstOrNull()?.source ?: "Bilinmeyen kaynak"
                        bind(results, summary, list, "GERÇEK FIRSAT TARAMASI tamamlandı • ${results.size} sonuç • Kaynak: $source")
                    }
                }
            }
        }
    }

    private fun bind(
        items: List<tr.borsatakip.v5.model.Opportunity>,
        summary: TextView,
        list: RecyclerView,
        title: String
    ) {
        summary.text = title
        list.adapter = OpportunityAdapter(items) {
            AppSession.selected = it
            startActivity(Intent(this@OpportunityActivity, StockDetailActivity::class.java))
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }
}
