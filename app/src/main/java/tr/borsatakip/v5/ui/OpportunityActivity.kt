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
import tr.borsatakip.v5.analysis.OpportunityFilter
import tr.borsatakip.v5.analysis.OpportunityFilterPolicy
import tr.borsatakip.v5.data.LastSuccessfulScanStore
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.SignalHistoryStore
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OpportunityActivity : BaseActivity() {
    private var scanJob: Job? = null
    private var selectedFilter = OpportunityFilter.ALL
    private var lastSuccessfulRun: ScanRun? = null
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var historyStore: LastSuccessfulScanStore
    private lateinit var signalHistoryStore: SignalHistoryStore
    private var favoriteSymbols: Set<String> = emptySet()
    private lateinit var summary: TextView
    private lateinit var list: RecyclerView
    private lateinit var btnLong: Button
    private lateinit var btnShort: Button
    private lateinit var btnHigh: Button
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr","TR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opportunity)
        setupBottomNav()
        favoriteRepository = FavoriteRepository.get(this)
        historyStore = LastSuccessfulScanStore(this)
        signalHistoryStore = SignalHistoryStore(this)
        summary = findViewById(R.id.txtSummary)
        list = findViewById(R.id.list)
        val scanButton = findViewById<Button>(R.id.btnRealOpportunityScan)
        btnLong = findViewById(R.id.btnFilterLong)
        btnShort = findViewById(R.id.btnFilterShort)
        btnHigh = findViewById(R.id.btnFilterHighPower)
        list.layoutManager = LinearLayoutManager(this)

        btnLong.setOnClickListener { toggleFilter(OpportunityFilter.LONG) }
        btnShort.setOnClickListener { toggleFilter(OpportunityFilter.SHORT) }
        btnHigh.setOnClickListener { toggleFilter(OpportunityFilter.HIGH_POWER) }
        findViewById<Button>(R.id.btnSignalHistory).setOnClickListener {
            startActivity(Intent(this, SignalHistoryActivity::class.java))
        }
        updateFilterVisuals()

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

        scanButton.setOnClickListener {
            if (scanJob?.isActive == true) { scanJob?.cancel(); return@setOnClickListener }
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
                                ScanStatus.RUNNING -> "BIST FIRSAT TARAMASI • ${state.processed}/${state.total} • %${state.progress} • Atlanan ${state.skipped}"
                                ScanStatus.COMPLETED -> "BIST taraması bitti • ${state.results.size} kayıt • ${state.scanRun?.status ?: "?"}"
                                ScanStatus.ERROR -> "BIST fırsat taraması başarısız • ${state.errorMessage ?: "Veri alınamadı"}"
                                ScanStatus.CANCELLED -> "Fırsat taraması durduruldu"
                            }
                        }
                    }
                    val run = finalState.scanRun
                    when {
                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE -> {
                            val results = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)
                            AppSession.lastOpportunities = results
                            lastSuccessfulRun = run
                            historyStore.save(run, results)
                            signalHistoryStore.recordCompleteScan(run, results)
                            applyFilter("SON BAŞARILI TARAMA • ${formatRunTime(run)} • ${results.size} kayıt • Kaynak: ${settings.lastProviderLabel}")
                        }
                        finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.PARTIAL -> {
                            val partial = OpportunityFilterPolicy.apply(finalState.results, OpportunityFilter.ALL)
                            bindFiltered(partial, "KISMİ TARAMA • ${formatRunTime(run)} • ${partial.size} kayıt • Son başarılı tarama değiştirilmedi")
                        }
                        finalState.status == ScanStatus.COMPLETED -> bindFiltered(emptyList(), "Tarama tamamlandı ancak yayınlanabilir sonuç oluşmadı")
                    }
                } catch (_: CancellationException) {
                    summary.text = "Fırsat taraması durduruldu • son başarılı tarama korunuyor"
                } catch (t: Throwable) {
                    summary.text = "Fırsat taraması başarısız • ${t.message ?: "Beklenmeyen veri hatası"} • son başarılı tarama korunuyor"
                } finally { scanButton.text = "FIRSAT TARAMASINI BAŞLAT" }
            }
        }
    }

    private fun toggleFilter(filter: OpportunityFilter) {
        selectedFilter = if (selectedFilter == filter) OpportunityFilter.ALL else filter
        updateFilterVisuals()
        lifecycleScope.launch { applyFilter() }
    }

    private suspend fun applyFilter(prefix: String? = null) {
        val all = OpportunityFilterPolicy.apply(AppSession.lastOpportunities, OpportunityFilter.ALL)
        val filtered = OpportunityFilterPolicy.apply(all, selectedFilter)
        val label = when (selectedFilter) {
            OpportunityFilter.ALL -> "TÜMÜ"
            OpportunityFilter.LONG -> "LONG"
            OpportunityFilter.SHORT -> "SHORT"
            OpportunityFilter.HIGH_POWER -> "85+"
        }
        val base = prefix ?: lastSuccessfulRun?.let { "SON BAŞARILI TARAMA • ${formatRunTime(it)}" } ?: "FIRSAT KONTROLÜ"
        bindFiltered(filtered, "$base • Filtre: $label • ${filtered.size}/${all.size} sonuç • Nihai Sinyal ↓")
    }

    private suspend fun bindFiltered(items: List<Opportunity>, title: String) {
        refreshFavoriteSymbols()
        summary.text = title
        list.adapter = OpportunityAdapter(items, favoriteSymbols,
            click = { AppSession.selected = it; startActivity(Intent(this@OpportunityActivity, StockDetailActivity::class.java)) },
            toggleFavorite = { opportunity -> lifecycleScope.launch {
                val added = favoriteRepository.toggle(opportunity.symbol, opportunity.companyName)
                Toast.makeText(this@OpportunityActivity, if (added) "${opportunity.symbol} favorilere eklendi" else "${opportunity.symbol} favorilerden çıkarıldı", Toast.LENGTH_SHORT).show()
                applyFilter()
            }}
        )
    }

    private fun updateFilterVisuals() {
        fun Button.state(active: Boolean, normal: String) { alpha = if (active) 1f else 0.58f; text = if (active) "✓ $normal" else normal }
        btnLong.state(selectedFilter == OpportunityFilter.LONG, "LONG")
        btnShort.state(selectedFilter == OpportunityFilter.SHORT, "SHORT")
        btnHigh.state(selectedFilter == OpportunityFilter.HIGH_POWER, "85+")
    }

    private suspend fun refreshFavoriteSymbols() { favoriteSymbols = favoriteRepository.symbols() }

    private suspend fun showExisting() {
        val settings = SettingsStore(this)
        if (AppSession.lastOpportunities.isEmpty()) {
            val text = when {
                settings.baseUrl.startsWith("https://") -> "FIRSAT KONTROLÜ • ana kaynak HTTPS backend • kayıtlı başarılı tarama yok"
                settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> "FIRSAT KONTROLÜ • Yahoo yalnız deneysel/gecikmeli yedek"
                else -> "FIRSAT KONTROLÜ • üretim backend yapılandırılmamış"
            }
            bindFiltered(emptyList(), text)
        } else applyFilter()
    }

    private fun formatRunTime(run: ScanRun):String {
        val ts=run.scanCompletedAt ?: run.scanStartedAt
        return if(ts>0) dateFormat.format(Date(ts)) else "zaman bilinmiyor"
    }

    override fun onResume() { super.onResume(); if (::favoriteRepository.isInitialized && ::list.isInitialized) lifecycleScope.launch { showExisting() } }
    override fun onDestroy() { scanJob?.cancel(); scanJob=null; super.onDestroy() }
}
