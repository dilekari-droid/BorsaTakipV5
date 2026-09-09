package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus

class OpportunityActivity : BaseActivity() {
    private var scanJob: Job? = null
    private lateinit var favoriteRepository: FavoriteRepository
    private var favoriteSymbols: Set<String> = emptySet()
    private lateinit var summary: TextView
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opportunity)
        setupBottomNav()

        favoriteRepository = FavoriteRepository.get(this)
        summary = findViewById(R.id.txtSummary)
        list = findViewById(R.id.list)
        val scanButton = findViewById<Button>(R.id.btnRealOpportunityScan)
        list.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            favoriteRepository.migrateLegacyIfNeeded()
            refreshFavoriteSymbols()
            showExisting()
        }

        scanButton.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }

            list.adapter = null
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
                            "GERÇEK FIRSAT TARAMASI tamamlandı • ${results.size} sonuç • ${output.receivedRows} BIST kaydı • Atlanan ${output.skippedRows} • Kaynak: ${output.sourceLabel} • Sıralama: Nihai Sinyal"
                        )
                        return@launch
                    }

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
                                bind(results, "GERÇEK FIRSAT TARAMASI tamamlandı • ${results.size} sonuç • Kaynak: ${results.first().source}")
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

    private fun sort(items: List<Opportunity>) =
        items.sortedWith(compareByDescending<Opportunity> { it.finalSignalScore }.thenByDescending { it.score })

    private suspend fun refreshFavoriteSymbols() {
        favoriteSymbols = favoriteRepository.symbols()
    }

    private suspend fun showExisting() {
        val existing = sort(AppSession.lastOpportunities)
        if (existing.isEmpty()) {
            summary.text = "GERÇEK FIRSAT KONTROLÜ • BIST ana tarama kaynağı TradingView Scanner • backend isteğe bağlı • demo yok"
            bind(emptyList(), summary.text.toString())
        } else {
            bind(existing, "GERÇEK FIRSAT KONTROLÜ • ${existing.size} sonuç • sıralama: Nihai Sinyal")
        }
    }

    private suspend fun bind(items: List<Opportunity>, title: String) {
        refreshFavoriteSymbols()
        summary.text = title
        list.adapter = OpportunityAdapter(
            items = items,
            favoriteSymbols = favoriteSymbols,
            click = {
                AppSession.selected = it
                startActivity(Intent(this@OpportunityActivity, StockDetailActivity::class.java))
            },
            toggleFavorite = { opportunity ->
                lifecycleScope.launch {
                    val added = favoriteRepository.toggle(opportunity.symbol, opportunity.companyName)
                    Toast.makeText(
                        this@OpportunityActivity,
                        if (added) "${opportunity.symbol} favorilere eklendi" else "${opportunity.symbol} favorilerden çıkarıldı",
                        Toast.LENGTH_SHORT
                    ).show()
                    bind(items, summary.text.toString())
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::favoriteRepository.isInitialized && ::list.isInitialized) {
            lifecycleScope.launch {
                refreshFavoriteSymbols()
                showExisting()
            }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        super.onDestroy()
    }
}
