from pathlib import Path

ROOT = Path.cwd()

def p(rel): return ROOT / rel

def replace_once(rel, old, new):
    path = p(rel)
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'REPLACE_MISS:{rel}:{old[:120]!r}')
    if text.count(old) != 1:
        raise SystemExit(f'REPLACE_COUNT:{rel}:{text.count(old)}:{old[:120]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

def write(rel, content):
    path = p(rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')

replace_once('app/build.gradle.kts', 'versionCode = 123', 'versionCode = 124')

write('app/src/main/java/tr/borsatakip/v5/analysis/ViopUnderlyingScanner.kt', r'''package tr.borsatakip.v5.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.data.ViopBuiltinContractCatalog
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ViopContract

/**
 * Production VİOP quote/history bulunmadığında kullanılan DAYANAK İZLEME taraması.
 * Bu sınıf VİOP fiyatı, hacmi veya açık pozisyon üretmez. Yalnız ilgili BIST dayanağının
 * mevcut teknik motor çıktısını en yakın yerel kontrat etiketiyle birlikte sunar.
 */
class ViopUnderlyingScanner(private val provider: MarketDataProvider) {
    data class Candidate(
        val contract: ViopContract,
        val underlying: Opportunity
    )

    data class Result(
        val items: List<Candidate>,
        val attempted: Int,
        val resolved: Int,
        val failed: Int
    )

    suspend fun scan(): Result = coroutineScope {
        val nearestContracts = ViopBuiltinContractCatalog.current()
            .filter { it.underlying.matches(Regex("[A-Z0-9_]{3,12}")) }
            .filterNot { it.underlying == "XU030" }
            .groupBy { it.underlying }
            .mapNotNull { (_, items) -> items.minByOrNull { it.expiryAt ?: Long.MAX_VALUE } }
            .sortedBy { it.underlying }

        val semaphore = Semaphore(CONCURRENCY)
        val rows = nearestContracts.map { contract ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val stock = runCatching { provider.fetchOne(contract.underlying) }.getOrNull()
                    val opportunity = stock?.let { OpportunityEngine.score(it) }
                    if (opportunity == null) null else Candidate(contract, opportunity)
                }
            }
        }.awaitAll()

        val items = rows.filterNotNull().sortedWith(
            compareByDescending<Candidate> { it.underlying.rankingScore }
                .thenByDescending { it.underlying.finalSignalScore }
                .thenBy { it.contract.underlying }
        )
        Result(items, nearestContracts.size, items.size, nearestContracts.size - items.size)
    }

    companion object {
        const val CONCURRENCY = 4
    }
}
''')

rel='app/src/main/java/tr/borsatakip/v5/ui/ViopActivity.kt'
replace_once(rel, 'import tr.borsatakip.v5.analysis.ViopScanner\n', 'import tr.borsatakip.v5.analysis.ViopScanner\nimport tr.borsatakip.v5.analysis.ViopUnderlyingScanner\n')
replace_once(rel,
'''    private lateinit var backend: BackendProvider
    private lateinit var scanner: ViopScanner
    private lateinit var readiness: ProviderReadinessService
''',
'''    private lateinit var backend: BackendProvider
    private lateinit var scanner: ViopScanner
    private lateinit var underlyingScanner: ViopUnderlyingScanner
    private lateinit var readiness: ProviderReadinessService
''')
replace_once(rel,
'''    private var providerAutoTestInFlight = false
    private var lastProviderAutoTestAt = 0L
''',
'''    private var providerAutoTestInFlight = false
    private var lastProviderAutoTestAt = 0L
    private var underlyingScanInFlight = false
    private var underlyingScanCompleted = false
''')
replace_once(rel,
'''        backend = BackendProvider(this)
        scanner = ViopScanner(backend, ProviderRouter(this), ViopStrategyDecisionService(this, backend))
        readiness = ProviderReadinessService(this)
''',
'''        backend = BackendProvider(this)
        scanner = ViopScanner(backend, ProviderRouter(this), ViopStrategyDecisionService(this, backend))
        underlyingScanner = ViopUnderlyingScanner(ProviderRouter(this, experimentalFallbackOverride = true))
        readiness = ProviderReadinessService(this)
''')
replace_once(rel,
'''            ProviderState.PROVIDER_NOT_CONFIGURED -> {
                clearPreview()
                status.text = "VİOP için Production Backend eksik. Ayarlar simgesinden HTTPS backend adresi ve API erişim anahtarı girin; kaydedince bağlantı otomatik test edilir."
            }
''',
'''            ProviderState.PROVIDER_NOT_CONFIGURED -> {
                clearPreview()
                if (!underlyingScanInFlight && !underlyingScanCompleted) {
                    runUnderlyingScan("Production VİOP backend bağlı değil")
                }
            }
''')
replace_once(rel,
'''                ProviderState.PROVIDER_NOT_CONFIGURED -> {
                    status.text = "VİOP VERİSİ BAĞLI DEĞİL • ${s.failureCode} • ${s.message}"
                    openViopSettings()
                }
''',
'''                ProviderState.PROVIDER_NOT_CONFIGURED -> runUnderlyingScan("Production VİOP backend bağlı değil")
''')
replace_once(rel,
'''            if (r.state == ProviderState.PROVIDER_READY) {
                loadDashboardPreview()
                runOpportunityScan()
            } else status.text = "${r.state} • ${r.failureCode} • ${r.message}"
''',
'''            if (r.state == ProviderState.PROVIDER_READY) {
                loadDashboardPreview()
                runOpportunityScan()
            } else runUnderlyingScan("VİOP provider doğrulanamadı: ${r.failureCode}")
''')
replace_once(rel, 'ProviderState.PROVIDER_NOT_CONFIGURED -> "VİOP VERİSİ BAĞLI DEĞİL"', 'ProviderState.PROVIDER_NOT_CONFIGURED -> "DAYANAK TARAMASI HAZIR"')
replace_once(rel,
'''            ProviderState.PROVIDER_NOT_CONFIGURED -> "VERİ KAYNAĞINI YAPILANDIR"
            ProviderState.PROVIDER_READY -> "VİOP TARAMASINI BAŞLAT"
            ProviderState.PROVIDER_STALE_READY -> "YENİDEN DOĞRULA"
            ProviderState.PROVIDER_ERROR -> "BAĞLANTIYI TEKRAR DENE"
''',
'''            ProviderState.PROVIDER_NOT_CONFIGURED -> if (underlyingScanCompleted) "DAYANAK TARAMASINI YENİLE" else "DAYANAK TARAMASINI BAŞLAT"
            ProviderState.PROVIDER_READY -> "VİOP TARAMASINI BAŞLAT"
            ProviderState.PROVIDER_STALE_READY -> "YENİDEN DOĞRULA"
            ProviderState.PROVIDER_ERROR -> "DAYANAK TARAMASI / TEKRAR DENE"
''')
marker='''    private fun renderOpportunities(items: List<ViopOpportunity>) {\n'''
method=r'''    private fun runUnderlyingScan(reason: String) {
        if (underlyingScanInFlight) return
        underlyingScanInFlight = true
        scanButton.isEnabled = false
        status.text = "$reason • BIST dayanakları teknik olarak taranıyor..."
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { underlyingScanner.scan() }
                list.adapter = ViopUnderlyingOpportunityAdapter(result.items)
                findViewById<TextView>(R.id.emptySignals).visibility = if (result.items.isEmpty()) View.VISIBLE else View.GONE
                val longs = result.items.count { it.underlying.direction.equals("LONG", true) }
                val shorts = result.items.count { it.underlying.direction.equals("SHORT", true) }
                findViewById<TextView>(R.id.signalSummary).text = if (result.items.isEmpty()) "Dayanak verisi alınamadı" else "${result.items.size} dayanak • $longs LONG • $shorts SHORT"
                findViewById<TextView>(R.id.marketSignals).text = "Dayanak\n${result.items.size}"
                findViewById<TextView>(R.id.marketTrend).text = when {
                    longs > shorts -> "Eğilim\nLONG"
                    shorts > longs -> "Eğilim\nSHORT"
                    else -> "Eğilim\nDENGELİ"
                }
                status.text = buildString {
                    append("DAYANAK TARAMASI TAMAMLANDI • VİOP fiyatı üretilmedi\n")
                    append("Denenen ${result.attempted} • Analiz ${result.resolved} • Veri yok ${result.failed}\n")
                    append("Gerçek VİOP fırsatı için production kontrat quote/history gerekir.")
                }
            } catch (t: Throwable) {
                status.text = "Dayanak taraması başarısız • ${t.message ?: "Beklenmeyen hata"}"
            } finally {
                underlyingScanInFlight = false
                underlyingScanCompleted = true
                scanButton.isEnabled = true
                refreshProviderState()
            }
        }
    }

'''
replace_once(rel, marker, method + marker)

rel='app/src/main/java/tr/borsatakip/v5/ui/ViopContractsActivity.kt'
replace_once(rel, 'import tr.borsatakip.v5.data.ViopWatchlistStore\n', 'import tr.borsatakip.v5.data.ViopWatchlistStore\nimport tr.borsatakip.v5.data.WatchlistDataResolver\n')
replace_once(rel, 'import tr.borsatakip.v5.model.ViopContract\n', 'import tr.borsatakip.v5.model.Stock\nimport tr.borsatakip.v5.model.ViopContract\n')
replace_once(rel, '    private var providerWarning: String? = null\n', '    private var providerWarning: String? = null\n    private val underlyingStocks = mutableMapOf<String, Stock>()\n')
text=p(rel).read_text(encoding='utf-8')
needle='''                renderCategoryUi()
                bindWatchlist()
                if (addMode) updateSuggestions()
                return@launch
'''
if text.count(needle)!=1: raise SystemExit(f'COUNT1 {text.count(needle)}')
text=text.replace(needle, '''                renderCategoryUi()
                bindWatchlist()
                refreshUnderlyingReferences()
                if (addMode) updateSuggestions()
                return@launch
''',1)
needle2='''            renderCategoryUi()
            bindWatchlist()
            if (addMode) updateSuggestions()
'''
if text.count(needle2)!=1: raise SystemExit(f'COUNT2 {text.count(needle2)}')
text=text.replace(needle2, '''            renderCategoryUi()
            bindWatchlist()
            refreshUnderlyingReferences()
            if (addMode) updateSuggestions()
''',1)
p(rel).write_text(text,encoding='utf-8')
replace_once(rel,
'''        val rows = symbols.map { symbol ->
            ViopWatchlistAdapter.Row(symbol, bySymbol[symbol.uppercase(Locale.ROOT)])
        }
''',
'''        val rows = symbols.map { symbol ->
            val contract = bySymbol[symbol.uppercase(Locale.ROOT)]
            ViopWatchlistAdapter.Row(symbol, contract, contract?.underlying?.let { underlyingStocks[it.uppercase(Locale.ROOT)] })
        }
''')
insert_marker='''    private fun openContract(contract: ViopContract) {\n'''
insert_method=r'''    private fun refreshUnderlyingReferences() {
        val contracts = watchlistStore.symbols(category)
            .mapNotNull { symbol -> all.firstOrNull { it.symbol.equals(symbol, true) } }
        val underlyings = contracts.map { it.underlying.uppercase(Locale.ROOT) }
            .filter { it.matches(Regex("[A-Z0-9_]{3,12}")) && it != "XU030" }
            .distinct()
        if (underlyings.isEmpty()) return
        lifecycleScope.launch {
            val resolver = WatchlistDataResolver(this@ViopContractsActivity)
            underlyings.forEach { underlying ->
                val stock = runCatching { resolver.fetchDisplayStock(underlying) }.getOrNull()
                if (stock != null) underlyingStocks[underlying] = stock
            }
            bindWatchlist()
            watchStatus.text = if (underlyingStocks.isEmpty()) {
                "${categoryDisplayName(category)} • gerçek VİOP fiyatı yok; dayanak referansı da alınamadı."
            } else {
                "${categoryDisplayName(category)} • VİOP kontrat fiyatı yok; ${underlyingStocks.size} dayanak referansı gösteriliyor."
            }
        }
    }

'''
replace_once(rel, insert_marker, insert_method + insert_marker)

write('app/src/main/java/tr/borsatakip/v5/ui/ViopUnderlyingOpportunityAdapter.kt', r'''package tr.borsatakip.v5.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopUnderlyingScanner

class ViopUnderlyingOpportunityAdapter(
    private val items: List<ViopUnderlyingScanner.Candidate>
) : RecyclerView.Adapter<ViopUnderlyingOpportunityAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol: TextView = v.findViewById(R.id.viopSymbol)
        val klass: TextView = v.findViewById(R.id.viopClass)
        val subtitle: TextView = v.findViewById(R.id.viopSubtitle)
        val direction: TextView = v.findViewById(R.id.viopDirection)
        val scores: TextView = v.findViewById(R.id.viopScores)
        val levels: TextView = v.findViewById(R.id.viopLevels)
        val reason: TextView = v.findViewById(R.id.viopReason)
        val detail: TextView = v.findViewById(R.id.viopDetailButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_viop_opportunity, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        val o = x.underlying
        val direction = o.direction.uppercase()
        val isLong = direction == "LONG"
        val isShort = direction == "SHORT"

        holder.symbol.text = x.contract.symbol.uppercase()
        holder.subtitle.text = "${x.contract.underlying} • ${x.contract.expiry} • DAYANAK"
        holder.klass.text = "🟡 DAYANAK İZLEME • VİOP FİYATI YOK"
        holder.direction.text = when {
            isLong -> "↗  LONG DAYANAK"
            isShort -> "↘  SHORT DAYANAK"
            else -> "→  NÖTR DAYANAK"
        }
        holder.direction.setTextColor(
            when {
                isLong -> Color.parseColor("#00F080")
                isShort -> Color.parseColor("#FF5A66")
                else -> Color.parseColor("#AFC2D4")
            }
        )
        holder.scores.text = buildString {
            append("Dayanak skor   ${o.finalSignalScore}/100\n")
            append("Veri Güveni    ${o.dataConfidenceScore}/100\n")
            append("Risk                 ${o.riskScore}/100\n")
            append("Sıralama          ${o.rankingScore}/100\n")
            append("Veri modu       ${o.dataMode.name}")
        }
        holder.levels.text = buildString {
            append("Dayanak fiyatı  ${"%.2f".format(o.price)}\n")
            append("VİOP fiyatı       —\n")
            append("VİOP hacim      —\n")
            append("Açık pozisyon —\n")
            append("Emir seviyesi   ÜRETİLMEZ")
        }
        holder.reason.text = "${o.signalValidityReason}\nKaynak: ${o.source}\nBu kart VİOP kontrat verisi değil, dayanak teknik izlemesidir."
        holder.detail.text = "DAYANAK"
        holder.detail.setOnClickListener(null)
        holder.itemView.setOnClickListener(null)
    }
}
''')

rel='app/src/main/java/tr/borsatakip/v5/ui/ViopWatchlistAdapter.kt'
replace_once(rel, 'import tr.borsatakip.v5.model.ViopContract\n', 'import tr.borsatakip.v5.model.ViopContract\nimport tr.borsatakip.v5.model.Stock\n')
replace_once(rel, '    data class Row(val symbol: String, val contract: ViopContract?)\n', '    data class Row(val symbol: String, val contract: ViopContract?, val underlyingStock: Stock? = null)\n')
replace_once(rel,
'''        holder.status.text = c?.let {
            when {
                it.providerId == "builtin_catalog" -> "Katalog"
''',
'''        val underlyingPrice = row.underlyingStock?.quotePrice?.takeIf { it.isFinite() && it > 0.0 }
        holder.status.text = c?.let {
            when {
                underlyingPrice != null -> "Dayanak ${"%.2f".format(underlyingPrice)}"
                it.providerId == "builtin_catalog" -> "Katalog"
''')

rel='app/src/main/java/tr/borsatakip/v5/worker/OpportunityWorker.kt'
replace_once(rel,
'''            if (analyzed.isEmpty()) return Result.success()
            val alertStore = AlertEventStore(applicationContext)
            alertStore.evaluate(analyzed)
            // VİOP olayları aynı kalıcı alarm pipeline'ına yalnız provider READY ise bağlanır.
            runCatching {
''',
'''            val alertStore = AlertEventStore(applicationContext)
            // VİOP taraması BIST sonucundan bağımsızdır. BIST aday çıkarmasa bile production VİOP provider READY ise çalışır.
            runCatching {
''')
replace_once(rel,
'''                    alertStore.evaluateViop(viop.opportunities)
                }
            }
            val hits = analyzed.filter {
''',
'''                    alertStore.evaluateViop(viop.opportunities)
                }
            }
            if (analyzed.isEmpty()) return Result.success()
            alertStore.evaluate(analyzed)
            val hits = analyzed.filter {
''')

rel='app/src/main/java/tr/borsatakip/v5/ui/MarketInstrumentDetailActivity.kt'
replace_once(rel,
'''        if (!settings.baseUrl.startsWith("https://") || settings.apiKey.isBlank()) {
            showError("VİOP30 için production HTTPS backend ve API erişimi gerekli.", showSettings = true)
            return
        }
''',
'''        if (!settings.baseUrl.startsWith("https://") || settings.apiKey.isBlank()) {
            return loadViop30Underlying("Production VİOP backend bağlı değil")
        }
''')
replace_once(rel,
'''        if (contract == null) {
            showError("XU030 dayanaklı aktif VİOP sözleşmesi alınamadı.", showSettings = true)
            return
        }
        val quote = backend.loadViopQuote(contract.symbol).getOrNull()
        if (quote == null) showError("${contract.symbol} için VİOP fiyatı alınamadı.", showSettings = true)
        else bindViop("VİOP 30 • ${contract.symbol}", quote)
    }
''',
'''        if (contract == null) {
            return loadViop30Underlying("XU030 dayanaklı production VİOP kontratı alınamadı")
        }
        val quote = backend.loadViopQuote(contract.symbol).getOrNull()
        if (quote == null) loadViop30Underlying("${contract.symbol} production VİOP quote alınamadı")
        else bindViop("VİOP 30 • ${contract.symbol}", quote)
    }

    private suspend fun loadViop30Underlying(reason: String) {
        val result = YahooFallbackProvider(this).fetchExternalQuote("XU030", listOf("XU030.IS", "^XU030", "XU030"))
        val stock = result.stock
        if (stock == null) {
            showError("$reason. XU030 dayanak referansı da alınamadı. ${result.error ?: ""}".trim(), showSettings = true)
            return
        }
        bindStock("VİOP 30 • DAYANAK XU030", stock)
        findViewById<TextView>(R.id.marketDetailStats).text = buildString {
            append("BU DEĞER VİOP KONTRAT FİYATI DEĞİLDİR.\n")
            append("XU030 spot/dayanak referansıdır; VİOP kontrat fiyatı, hacim ve açık pozisyon üretilmez.\n")
            append("Neden: ").append(reason)
        }
        findViewById<Button>(R.id.marketDetailSettings).visibility = View.VISIBLE
    }
''')

rel='app/src/main/java/tr/borsatakip/v5/ui/MainActivity.kt'
replace_once(rel,
'''    private fun bindQuoteTile(tile: TileViews, quote: tr.borsatakip.v5.model.ViopQuote?) {
        if (quote == null) return
        bindMarketTile(tile, quote.price, quote.dailyChangePct, emptyList())
    }
''',
'''    private fun bindQuoteTile(tile: TileViews, quote: tr.borsatakip.v5.model.ViopQuote?) {
        if (quote == null) {
            tile.value.text = "—"
            tile.change.text = "Dayanak tara"
            tile.change.setTextColor(getColor(R.color.text_muted))
            tile.spark.text = "→"
            tile.spark.setTextColor(getColor(R.color.text_muted))
            return
        }
        bindMarketTile(tile, quote.price, quote.dailyChangePct, emptyList())
    }
''')

replace_once('app/src/test/java/tr/borsatakip/v5/data/B117EngineIntegritySourceTest.kt', 'versionCode = 123', 'versionCode = 124')

write('app/src/test/java/tr/borsatakip/v5/data/B124ViopScanRecoveryTest.kt', r'''package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B124ViopScanRecoveryTest {
    private fun read(path: String) = File(path).readText()

    @Test fun viopScreenCanRunUnderlyingScanWithoutBackend() {
        val s = read("src/main/java/tr/borsatakip/v5/ui/ViopActivity.kt")
        assertTrue(s.contains("runUnderlyingScan("))
        assertTrue(s.contains("ProviderState.PROVIDER_NOT_CONFIGURED -> runUnderlyingScan"))
        assertTrue(s.contains("DAYANAK TARAMASINI BAŞLAT"))
    }

    @Test fun underlyingScanNeverCreatesFakeViopQuote() {
        val s = read("src/main/java/tr/borsatakip/v5/analysis/ViopUnderlyingScanner.kt")
        assertTrue(s.contains("VİOP fiyatı, hacmi veya açık pozisyon üretmez"))
        assertFalse(s.contains("ViopQuote("))
        assertFalse(s.contains("loadViopQuote"))
    }

    @Test fun viopBackgroundScanIsIndependentFromBistEmptyResult() {
        val s = read("src/main/java/tr/borsatakip/v5/worker/OpportunityWorker.kt")
        val viop = s.indexOf("ViopScanner(viopBackend")
        val early = s.indexOf("if (analyzed.isEmpty()) return Result.success()")
        assertTrue(viop >= 0)
        assertTrue(early > viop)
    }

    @Test fun contractsCanShowExplicitUnderlyingReferenceWithoutFakeContractPrice() {
        val a = read("src/main/java/tr/borsatakip/v5/ui/ViopWatchlistAdapter.kt")
        val c = read("src/main/java/tr/borsatakip/v5/ui/ViopContractsActivity.kt")
        assertTrue(a.contains("Dayanak"))
        assertTrue(c.contains("refreshUnderlyingReferences()"))
        assertTrue(c.contains("VİOP kontrat fiyatı yok"))
    }
}
''')

write('app/src/test/java/tr/borsatakip/v5/data/B124ViopFallbackUxTest.kt', r'''package tr.borsatakip.v5.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B124ViopFallbackUxTest {
    private fun read(path: String) = File(path).readText()

    @Test fun viopUnderlyingScanStartsAutomaticallyWhenBackendIsMissing() {
        val s = read("src/main/java/tr/borsatakip/v5/ui/ViopActivity.kt")
        assertTrue(s.contains("underlyingScanInFlight"))
        assertTrue(s.contains("underlyingScanCompleted"))
        assertTrue(s.contains("runUnderlyingScan(\"Production VİOP backend bağlı değil\")"))
    }

    @Test fun viop30DetailFallsBackOnlyToExplicitUnderlyingReference() {
        val s = read("src/main/java/tr/borsatakip/v5/ui/MarketInstrumentDetailActivity.kt")
        assertTrue(s.contains("loadViop30Underlying"))
        assertTrue(s.contains("BU DEĞER VİOP KONTRAT FİYATI DEĞİLDİR"))
        assertTrue(s.contains("XU030.IS"))
    }

    @Test fun viopTileDoesNotInventContractPriceWhenBackendIsMissing() {
        val s = read("src/main/java/tr/borsatakip/v5/ui/MainActivity.kt")
        assertTrue(s.contains("tile.value.text = \"—\""))
        assertTrue(s.contains("tile.change.text = \"Dayanak tara\""))
    }
}
''')

print('B124_APPLIED')
