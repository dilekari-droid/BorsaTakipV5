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
                    val settings = SettingsStore(this@OpportunityActivity)
                    val hasBackend = settings.baseUrl.startsWith("https://")

                    if (hasBackend) {
                        summary.text = "ÜRETİM BACKEND FIRSAT TARAMASI başlatılıyor..."
                        val scanner = BistScanner(ProviderRouter(this@OpportunityActivity))
                        val finalState = scanner.scan { state ->
                            runOnUiThread {
                                summary.text = when (state.status) {
                                    ScanStatus.IDLE -> "Hazır"
                                    ScanStatus.RUNNING -> "ÜRETİM BACKEND • ${state.processed}/${state.total} • %${state.progress} • Atlanan ${state.skipped}"
                                    ScanStatus.COMPLETED -> "ÜRETİM BACKEND taraması tamamlandı • ${state.results.size} sonuç"
                                    ScanStatus.ERROR -> "ÜRETİM BACKEND başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                    ScanStatus.CANCELLED -> "Fırsat taraması durduruldu"
                                }
                            }
                        }

                        if (finalState.status == ScanStatus.COMPLETED && finalState.results.isNotEmpty()) {
                            val results = sort(finalState.results)
                            AppSession.lastOpportunities = results
                            bind(
                                results,
                                "ÜRETİM FIRSAT TARAMASI tamamlandı • ${results.size} sonuç • Kaynak: ${results.first().source} • Sıralama: Nihai Sinyal"
                            )
                            return@launch
                        }

                        if (!settings.experimentalProvidersEnabled) {
                            summary.text = "ÜRETİM BACKEND veri üretmedi. Deneysel sağlayıcı modu kapalı; TradingView/Yahoo'ya geçilmedi."
                            return@launch
                        }
                    } else if (!settings.experimentalProvidersEnabled) {
                        summary.text = "ÜRETİM VERİ SAĞLAYICISI YAPILANDIRILMAMIŞ • Ayarlar'da gerçek HTTPS backend tanımlayın. Deneysel TradingView/Yahoo modu kapalı."
                        return@launch
                    }

                    summary.text = "DENEYSEL TradingView BIST Scanner'a bağlanılıyor..."
                    val tv = TradingViewScannerOpportunityProvider()
                    val tvResult = tv.scan { done, total ->
                        runOnUiThread {
                            val pct = if (total <= 0) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)
                            summary.text = "DENEYSEL FIRSAT TARAMASI • TradingView • $done/$total • %$pct"
                        }
                    }

                    if (tvResult.isSuccess) {
                        val output = tvResult.getOrThrow()
                        val results = sort(output.opportunities)
                        AppSession.lastOpportunities = results
                        bind(
                            results,
                            "DENEYSEL FIRSAT TARAMASI tamamlandı • ${results.size} sonuç • ${output.receivedRows} BIST kaydı • Atlanan ${output.skippedRows} • Kaynak: ${output.sourceLabel} • ÜRETİM VERİSİ DEĞİL"
                        )
                    } else {
                        val error = tvResult.exceptionOrNull()
                        summary.text = "DENEYSEL TradingView taraması başarısız • ${error?.message ?: "veri alınamadı"}. Sahte/demo verisine geçilmedi."
                    }
                } catch (ce: CancellationException) {
                    summary.text = "Fırsat taraması durduruldu"
                    throw ce
                } catch (t: Throwable) {
                    summary.text = "Fırsat taraması başarısız • ${t.message ?: "Beklenmeyen veri hatası"}"
                } finally {
                    scanButton.text = "FIRSAT TARAMASINI BAŞLAT"
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
        val settings = SettingsStore(this)
        if (existing.isEmpty()) {
            summary.text = if (settings.baseUrl.startsWith("https://")) {
                "ÜRETİM FIRSAT KONTROLÜ • ana kaynak HTTPS backend • deneysel kaynaklar ${if (settings.experimentalProvidersEnabled) "AÇIK" else "KAPALI"}"
            } else {
                "ÜRETİM FIRSAT KONTROLÜ • backend yapılandırılmamış • deneysel kaynaklar ${if (settings.experimentalProvidersEnabled) "AÇIK" else "KAPALI"}"
            }
            bind(emptyList(), summary.text.toString())
        } else {
            bind(existing, "FIRSAT KONTROLÜ • ${existing.size} sonuç • sıralama: Nihai Sinyal")
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
