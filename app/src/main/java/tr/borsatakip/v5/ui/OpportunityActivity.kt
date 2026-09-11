package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
    private lateinit var emptyState: TextView
    private lateinit var filterLong: TextView
    private lateinit var filterShort: TextView
    private lateinit var filterHighPower: TextView
    private lateinit var filterStateText: TextView

    private var allOpportunities: List<Opportunity> = emptyList()
    private var filterState = OpportunityFilterState()
    private var baseTitle = "FIRSAT KONTROLÜ"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opportunity)
        setupBottomNav()

        favoriteRepository = FavoriteRepository.get(this)
        summary = findViewById(R.id.txtSummary)
        list = findViewById(R.id.list)
        emptyState = findViewById(R.id.txtEmpty)
        filterLong = findViewById(R.id.filterLong)
        filterShort = findViewById(R.id.filterShort)
        filterHighPower = findViewById(R.id.filterHighPower)
        filterStateText = findViewById(R.id.txtFilterState)
        val scanButton = findViewById<Button>(R.id.btnRealOpportunityScan)
        list.layoutManager = LinearLayoutManager(this)

        filterLong.setOnClickListener {
            filterState = filterState.copy(longEnabled = !filterState.longEnabled)
            renderFiltered()
        }
        filterShort.setOnClickListener {
            filterState = filterState.copy(shortEnabled = !filterState.shortEnabled)
            renderFiltered()
        }
        filterHighPower.setOnClickListener {
            filterState = filterState.copy(highPowerEnabled = !filterState.highPowerEnabled)
            renderFiltered()
        }
        filterStateText.setOnClickListener {
            filterState = OpportunityFilterState()
            renderFiltered()
        }

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
            emptyState.visibility = View.GONE
            scanButton.text = "DURDUR"
            scanJob = lifecycleScope.launch {
                try {
                    val settings = SettingsStore(this@OpportunityActivity)
                    summary.text = when {
                        settings.baseUrl.startsWith("https://") -> "BACKEND-FIRST FIRSAT TARAMASI başlatılıyor..."
                        settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> "Backend yok • Yahoo deneysel/gecikmeli yedek taraması başlatılıyor..."
                        else -> "Üretim backend yapılandırılmamış. TradingView veri kaynağı değildir."
                    }

                    val scanner = BistScanner(ProviderRouter(this@OpportunityActivity))
                    val finalState = scanner.scan { state ->
                        runOnUiThread {
                            summary.text = when (state.status) {
                                ScanStatus.IDLE -> "Hazır"
                                ScanStatus.RUNNING -> "BIST FIRSAT TARAMASI • ${state.processed}/${state.total} • %${state.progress} • Veri ${state.dataReceived} • Hata ${state.skipped}"
                                ScanStatus.COMPLETED -> "BIST fırsat taraması tamamlandı • ${state.results.size} fırsat"
                                ScanStatus.ERROR -> "BIST fırsat taraması başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                ScanStatus.CANCELLED -> "Fırsat taraması durduruldu"
                            }
                        }
                    }

                    if (finalState.status == ScanStatus.COMPLETED) {
                        allOpportunities = sort(finalState.results)
                        AppSession.lastOpportunities = allOpportunities
                        baseTitle = "FIRSAT KONTROLÜ • Kaynak: ${settings.lastProviderLabel} • Sıralama: Nihai Sinyal"
                        renderFiltered()
                    }
                } catch (ce: CancellationException) {
                    summary.text = "Fırsat taraması durduruldu"
                    throw ce
                } catch (t: Throwable) {
                    summary.text = "Fırsat taraması başarısız • ${t.message ?: "Beklenmeyen veri hatası"} • TradingView/demo verisine geçilmedi."
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
        allOpportunities = sort(AppSession.lastOpportunities)
        val settings = SettingsStore(this)
        baseTitle = when {
            allOpportunities.isNotEmpty() -> "FIRSAT KONTROLÜ • Sıralama: Nihai Sinyal"
            settings.baseUrl.startsWith("https://") -> "FIRSAT KONTROLÜ • ana kaynak HTTPS backend • TradingView veri kaynağı değil"
            settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> "FIRSAT KONTROLÜ • backend yok • Yahoo deneysel/gecikmeli yedek açık"
            else -> "FIRSAT KONTROLÜ • üretim backend yapılandırılmamış"
        }
        renderFiltered()
    }

    private fun renderFiltered() {
        updateFilterVisuals()
        val filtered = OpportunityFilter.apply(allOpportunities, filterState)
        summary.text = "$baseTitle • ${filtered.size} sonuç"
        emptyState.visibility = if (filtered.isEmpty() && allOpportunities.isNotEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (filtered.isEmpty() && allOpportunities.isNotEmpty()) View.GONE else View.VISIBLE
        bindAdapter(filtered)
    }

    private fun updateFilterVisuals() {
        fun apply(view: TextView, selected: Boolean) {
            view.isSelected = selected
            view.alpha = if (selected) 1f else 0.58f
        }
        apply(filterLong, filterState.longEnabled)
        apply(filterShort, filterState.shortEnabled)
        apply(filterHighPower, filterState.highPowerEnabled)
        filterStateText.text = "Filtre: ${filterState.label()}"
        filterStateText.alpha = if (filterState.isActive) 1f else 0.72f
    }

    private fun bindAdapter(items: List<Opportunity>) {
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
                    refreshFavoriteSymbols()
                    renderFiltered()
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
