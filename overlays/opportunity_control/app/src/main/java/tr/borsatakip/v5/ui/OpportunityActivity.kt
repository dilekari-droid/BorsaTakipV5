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
import tr.borsatakip.v5.analysis.OpportunityDiscoveryPresentation
import tr.borsatakip.v5.analysis.OpportunityFilter
import tr.borsatakip.v5.analysis.OpportunityFilterPolicy
import tr.borsatakip.v5.data.LastSuccessfulScanStore
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.SignalHistoryRecorder
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiscoveryFilter { ALL, STRONG_BUY, WATCH, CATALYST, LOW_RISK, HIGH_VOLUME }
enum class DiscoverySort { SCORE, DATA_QUALITY, LOW_RISK, HIGH_VOLUME }

class OpportunityActivity : BaseActivity() {
    private var scanJob: Job? = null
    private var selectedFilter = DiscoveryFilter.ALL
    private var selectedSort = DiscoverySort.SCORE
    private var lastSuccessfulRun: ScanRun? = null
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var historyStore: LastSuccessfulScanStore
    private var favoriteSymbols: Set<String> = emptySet()
    private lateinit var summary: TextView
    private lateinit var list: RecyclerView
    private lateinit var marketRegime: TextView
    private lateinit var watchedCount: TextView
    private lateinit var foundCount: TextView
    private lateinit var dataQuality: TextView
    private val filterButtons = linkedMapOf<DiscoveryFilter, Button>()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opportunity)
        setupBottomNav()

        favoriteRepository = FavoriteRepository.get(this)
        historyStore = LastSuccessfulScanStore(this)
        summary = findViewById(R.id.txtSummary)
        list = findViewById(R.id.list)
        marketRegime = findViewById(R.id.txtMarketRegime)
        watchedCount = findViewById(R.id.txtWatchedCount)
        foundCount = findViewById(R.id.txtFoundCount)
        dataQuality = findViewById(R.id.txtDataQuality)
        list.layoutManager = LinearLayoutManager(this)

        val ids = listOf(
            DiscoveryFilter.ALL to R.id.btnFilterAll,
            DiscoveryFilter.STRONG_BUY to R.id.btnFilterStrongBuy,
            DiscoveryFilter.WATCH to R.id.btnFilterWatch,
            DiscoveryFilter.CATALYST to R.id.btnFilterCatalyst,
            DiscoveryFilter.LOW_RISK to R.id.btnFilterLowRisk,
            DiscoveryFilter.HIGH_VOLUME to R.id.btnFilterHighVolume
        )
        ids.forEach { (filter, id) ->
            val button = findViewById<Button>(id)
            filterButtons[filter] = button
            button.setOnClickListener {
                selectedFilter = filter
                updateFilterVisuals()
                lifecycleScope.launch { applyDiscoveryFilter() }
            }
        }
        updateFilterVisuals()

        findViewById<TextView>(R.id.btnSortOpportunity).setOnClickListener {
            selectedSort = when (selectedSort) {
                DiscoverySort.SCORE -> DiscoverySort.DATA_QUALITY
                DiscoverySort.DATA_QUALITY -> DiscoverySort.LOW_RISK
                DiscoverySort.LOW_RISK -> DiscoverySort.HIGH_VOLUME
                DiscoverySort.HIGH_VOLUME -> DiscoverySort.SCORE
            }
            Toast.makeText(this, "Sıralama: ${sortLabel(selectedSort)}", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { applyDiscoveryFilter() }
        }

        findViewById<Button>(R.id.btnSignalHistory).setOnClickListener {
            startActivity(Intent(this, SignalHistoryActivity::class.java))
        }

        lifecycleScope.launch {
            favoriteRepository.migrateLegacyIfNeeded()
            refreshFavoriteSymbols()
            if (AppSession.lastOpportunities.isEmpty()) {
                historyStore.load()?.let { (run, items) ->
                    lastSuccessfulRun = run
                    AppSession.lastOpportunities = OpportunityFilterPolicy.apply(items, OpportunityFilter.ALL)
                }
            }
            showExisting()
        }

        val scanButton = findViewById<Button>(R.id.btnRealOpportunityScan)
        scanButton.setOnClickListener {
            if (scanJob?.isActive == true) {
                scanJob?.cancel()
                return@setOnClickListener
            }
            scanButton.text = "DURDUR"
            scanJob = lifecycleScope.launch {
                try {
                    val settings = SettingsStore(this@OpportunityActivity)
                    summary.text = when {
                        settings.baseUrl.startsWith("https://") -> "Fırsat verileri toplanıyor • gerçek provider verisi kullanılıyor"
                        settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> "Fırsat verileri toplanıyor • deneysel/gecikmeli yedek aktif"
                        else -> "Üretim backend yapılandırılmamış • veri yoksa fırsat üretilmez"
                    }
                    val scanner = BistScanner(ProviderRouter(this@OpportunityActivity), SignalHistoryRecorder(this@OpportunityActivity))
                    val finalState = scanner.scan { state ->
                        runOnUiThread {
                            summary.text = when (state.status) {
                                ScanStatus.IDLE -> "Hazır"
                                ScanStatus.RUNNING -> "Piyasa verisi toplanıyor • ${state.processed}/${state.total} • %${state.progress}"
                                ScanStatus.COMPLETED -> "Veri toplama tamamlandı • ${state.successful}/${state.total} analiz edildi"
                                ScanStatus.ERROR -> "Fırsat analizi başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                ScanStatus.CANCELLED -> "Fırsat analizi durduruldu"
                            }
                        }
                    }
                    val run = finalState.scanRun
                    val historySuffix = finalState.historyError?.let { " • Geçmiş hatası: $it" }
                        ?: " • ${finalState.historyPersisted} yeni sinyal geçmişe yazıldı"
                    when {
                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE -> {
                            val results = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)
                            AppSession.lastOpportunities = results
                            lastSuccessfulRun = run
                            historyStore.save(run, results)
                            applyDiscoveryFilter("SON GÜNCELLEME • ${formatRunTime(run)} • ${results.size} aday$historySuffix")
                        }
                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.PARTIAL && finalState.successful > 0 -> {
                            val partial = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)
                            AppSession.lastOpportunities = partial
                            bindFiltered(partial, "KISMİ VERİ • Başarılı ${finalState.successful}/${finalState.total} • Hatalı/atlanan ${finalState.skipped}$historySuffix")
                        }
                        finalState.status == ScanStatus.COMPLETED -> bindFiltered(emptyList(), "Kalite eşiğini geçen fırsat bulunamadı.")
                    }
                } catch (_: CancellationException) {
                    summary.text = "Fırsat analizi durduruldu • son başarılı sonuç korunuyor"
                } catch (t: Throwable) {
                    summary.text = "Fırsat analizi başarısız • ${t.message ?: "Beklenmeyen veri hatası"}"
                } finally {
                    scanButton.text = "FIRSATLARI YENİLE"
                }
            }
        }
    }

    private suspend fun applyDiscoveryFilter(prefix: String? = null) {
        val base = OpportunityFilterPolicy.apply(AppSession.lastOpportunities, OpportunityFilter.ALL)
        val filtered = base.filter { x ->
            val p = OpportunityDiscoveryPresentation.from(x)
            when (selectedFilter) {
                DiscoveryFilter.ALL -> true
                DiscoveryFilter.STRONG_BUY -> p.classification == "GÜÇLÜ AL" || p.classification == "AL"
                DiscoveryFilter.WATCH -> p.classification == "İZLE"
                DiscoveryFilter.CATALYST -> p.catalystAvailable
                DiscoveryFilter.LOW_RISK -> p.lowRisk
                DiscoveryFilter.HIGH_VOLUME -> p.highVolume
            }
        }
        val sorted = when (selectedSort) {
            DiscoverySort.SCORE -> filtered.sortedByDescending { OpportunityDiscoveryPresentation.from(it).opportunityScore }
            DiscoverySort.DATA_QUALITY -> filtered.sortedByDescending { it.dataConfidenceScore }
            DiscoverySort.LOW_RISK -> filtered.sortedBy { it.riskScore }
            DiscoverySort.HIGH_VOLUME -> filtered.sortedByDescending { it.technical.volumeRatio ?: -1.0 }
        }
        val baseText = prefix ?: lastSuccessfulRun?.let { "SON GÜNCELLEME • ${formatRunTime(it)}" } ?: "FIRSAT KONTROLÜ"
        val emptySuffix = if (sorted.isEmpty()) " • Bu filtrede aday yok" else ""
        bindFiltered(sorted, "$baseText • ${filterLabel(selectedFilter)} • ${sorted.size}/${base.size}$emptySuffix")
    }

    private suspend fun bindFiltered(items: List<Opportunity>, title: String) {
        refreshFavoriteSymbols()
        summary.text = title
        updateTopMetrics(AppSession.lastOpportunities)
        list.adapter = OpportunityAdapter(
            items,
            favoriteSymbols,
            click = {
                AppSession.selected = it
                startActivity(Intent(this, StockDetailActivity::class.java).putExtra("opportunity", it))
            },
            toggleFavorite = { opportunity ->
                lifecycleScope.launch {
                    val added = favoriteRepository.toggle(opportunity.symbol, opportunity.companyName)
                    Toast.makeText(
                        this@OpportunityActivity,
                        if (added) "${opportunity.symbol} favorilere eklendi" else "${opportunity.symbol} favorilerden çıkarıldı",
                        Toast.LENGTH_SHORT
                    ).show()
                    applyDiscoveryFilter()
                }
            }
        )
    }

    private fun updateTopMetrics(items: List<Opportunity>) {
        marketRegime.text = "Benchmark verisi yok"
        val watched = lastSuccessfulRun?.count?.takeIf { it > 0 } ?: items.size
        watchedCount.text = "İzlenen\n$watched"
        foundCount.text = "Fırsat\n${items.size}"
        val avgQuality = if (items.isEmpty()) null else items.map { it.dataConfidenceScore }.average().toInt()
        dataQuality.text = "Veri Kalitesi\n${avgQuality?.let { "%$it" } ?: "—"}"
    }

    private fun updateFilterVisuals() {
        filterButtons.forEach { (filter, button) ->
            val active = filter == selectedFilter
            button.alpha = if (active) 1f else 0.62f
            val clean = button.text.toString().removePrefix("✓ ")
            button.text = if (active) "✓ $clean" else clean
        }
    }

    private suspend fun refreshFavoriteSymbols() {
        favoriteSymbols = favoriteRepository.symbols()
    }

    private suspend fun showExisting() {
        val settings = SettingsStore(this)
        if (AppSession.lastOpportunities.isEmpty()) {
            val text = when {
                settings.baseUrl.startsWith("https://") -> "Kayıtlı fırsat yok • FIRSATLARI YENİLE ile gerçek veriyi değerlendir"
                settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> "Kayıtlı fırsat yok • deneysel/gecikmeli yedek açık"
                else -> "Üretim backend yapılandırılmamış • fırsat üretilmez"
            }
            bindFiltered(emptyList(), text)
        } else {
            applyDiscoveryFilter()
        }
    }

    private fun filterLabel(filter: DiscoveryFilter) = when (filter) {
        DiscoveryFilter.ALL -> "Tümü"
        DiscoveryFilter.STRONG_BUY -> "Güçlü Al"
        DiscoveryFilter.WATCH -> "İzleme"
        DiscoveryFilter.CATALYST -> "Katalizörlü"
        DiscoveryFilter.LOW_RISK -> "Düşük Risk"
        DiscoveryFilter.HIGH_VOLUME -> "Yüksek Hacim"
    }

    private fun sortLabel(sort: DiscoverySort) = when (sort) {
        DiscoverySort.SCORE -> "Fırsat Skoru"
        DiscoverySort.DATA_QUALITY -> "Veri Kalitesi"
        DiscoverySort.LOW_RISK -> "Düşük Risk"
        DiscoverySort.HIGH_VOLUME -> "Yüksek Hacim"
    }

    private fun formatRunTime(run: ScanRun): String {
        val ts = run.scanCompletedAt ?: run.scanStartedAt
        return if (ts > 0) dateFormat.format(Date(ts)) else "zaman bilinmiyor"
    }

    override fun onResume() {
        super.onResume()
        if (::favoriteRepository.isInitialized && ::list.isInitialized) {
            lifecycleScope.launch { showExisting() }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        super.onDestroy()
    }

    companion object { const val EXTRA_SCAN_WARNING = "scan_warning" }
}
