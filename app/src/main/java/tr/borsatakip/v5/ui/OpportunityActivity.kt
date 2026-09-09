package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.data.DemoMarketDataProvider

class OpportunityActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opportunity)
        setupBottomNav()

        val summary = findViewById<TextView>(R.id.txtSummary)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)

        val existing = AppSession.lastOpportunities.sortedByDescending { it.score }
        if (existing.isNotEmpty()) {
            bind(existing, summary, list, "${existing.size} sonuç • Skor yüksekten düşüğe • Risk ayrı hesaplanır")
            return
        }

        summary.text = "DEMO TARAMA hazırlanıyor • %0"
        lifecycleScope.launch {
            try {
                val demoStocks = DemoMarketDataProvider().scan { done, total ->
                    val pct = if (total == 0) 0 else done * 100 / total
                    runOnUiThread { summary.text = "DEMO TARAMA • %$pct • $done/$total" }
                }
                val results = demoStocks.mapNotNull { runCatching { OpportunityEngine.score(it) }.getOrNull() }
                    .sortedByDescending { it.score }
                AppSession.lastOpportunities = results
                if (results.isEmpty()) {
                    summary.text = "DEMO TARAMA tamamlandı • %100 • Sonuç üretilemedi"
                    list.adapter = OpportunityAdapter(emptyList()) { }
                } else {
                    bind(results, summary, list, "DEMO TARAMA tamamlandı • %100 • ${results.size} sonuç • Yerel test verisi")
                }
            } catch (e: Exception) {
                summary.text = "DEMO TARAMA çalıştırılamadı • ${e.message ?: "Bilinmeyen hata"}"
                list.adapter = OpportunityAdapter(emptyList()) { }
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
}
