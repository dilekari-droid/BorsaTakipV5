package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.TradingViewScannerOpportunityProvider
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

        fun sort(items: List<tr.borsatakip.v5.model.Opportunity>) =
            items.sortedWith(compareByDescending<tr.borsatakip.v5.model.Opportunity> { it.finalSignalScore }.thenByDescending { it.score })

        fun showExisting() {
            val existing = sort(AppSession.lastOpportunities)
            if (existing.isEmpty()) {
                summary.text = "GERÇEK FIRSAT KONTROLÜ • BIST ana tarama kaynağı TradingView Scanner • backend isteğe bağlı • demo yok"
                list.adapter = OpportunityAdapter(emptyList()) { }
            } else {
                bind(existing, summary, list, "GERÇEK FIRSAT KONTROLÜ • ${existing.size} sonuç • sıralama: Nihai Sinyal")
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
                try {
                    summary.text = "TradingView BIST Scanner'a bağlanılıyor..."
                    val tv = TradingViewScannerOpportunityProvider()
                    val tvResult = tv.scan { done, total ->
                        runOnUiThread {
                            val pct = if (total <= 0) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)
                            summary.text = "GERÇEK FIRSAT TARAMASI • TradingView • $done/$total • %$pct"
                        }
                    }

                    if (tvResult.isSuccess) {
                        val output = tvResult.getOrThrow()
                        val results = sort(output.opportunities)
                        AppSession.lastOpportunities = results
                        bind(
                            results,
                            summary,
                            list,
                            "GERÇEK FIRSAT TARAMASI tamamlandı • ${results.size} sonuç • ${output.receivedRows} BIST kaydı • Atlanan ${output.skippedRows} • Kaynak: ${output.sourceLabel} • Sıralama: Nihai Sinyal"
                        )
                        return@launch
                    }

                    // TradingView Scanner erişilemezse backend yalnız kullanıcı gerçekten yapılandırdıysa denenir.
                    val settings = SettingsStore(this@OpportunityActivity)
                    if (settings.baseUrl.startsWith("https://")) {
                        summary.text = "TradingView Scanner erişilemedi • İsteğe bağlı backend deneniyor..."
                        val scanner = BistScanner(ProviderRouter(this@OpportunityActivity))
                        val finalState = scanner.scan { state ->
                            runOnUiThread {
                                summary.text = when (state.status) {
                                    ScanStatus.IDLE -> "Hazır"
                                    ScanStatus.RUNNING -> "BACKEND FIRSAT TARAMASI • ${state.processed}/${state.total} • %${state.progress} • Atlanan ${state.skipped}"
                                    ScanStatus.COMPLETED -> "BACKEND FIRSAT TARAMASI tamamlandı • ${state.results.size} sonuç"
                                    ScanStatus.ERROR -> "BACKEND FIRSAT TARAMASI başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                    ScanStatus.CANCELLED -> "Fırsat taraması durduruldu"
                                }
                            }
                        }
                        if (finalState.status == ScanStatus.COMPLETED) {
                            val results = sort(finalState.results)
                            AppSession.lastOpportunities = results
                            if (results.isEmpty()) {
                                summary.text = "Gerçek veri alındı ancak uygun fırsat sonucu üretilemedi."
                            } else {
                                bind(results, summary, list, "GERÇEK FIRSAT TARAMASI tamamlandı • ${results.size} sonuç • Kaynak: ${results.first().source}")
                            }
                        }
                    } else {
                        val error = tvResult.exceptionOrNull()
                        summary.text = "GERÇEK FIRSAT TARAMASI başarısız • TradingView BIST Scanner'a ulaşılamadı: ${error?.message ?: "veri alınamadı"}. Backend yapılandırılmamış; demo verisine geçilmedi."
                    }
                } catch (ce: CancellationException) {
                    summary.text = "GERÇEK FIRSAT TARAMASI durduruldu"
                    throw ce
                } catch (t: Throwable) {
                    summary.text = "GERÇEK FIRSAT TARAMASI başarısız • ${t.message ?: "Beklenmeyen veri hatası"}"
                } finally {
                    scanButton.text = "GERÇEK FIRSAT TARAMASINI BAŞLAT"
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
        scanJob = null
        super.onDestroy()
    }
}
