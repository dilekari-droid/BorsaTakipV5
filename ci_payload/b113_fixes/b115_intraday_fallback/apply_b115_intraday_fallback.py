#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
java = root / "app/src/main/java/tr/borsatakip/v5"


def read(rel: str) -> str:
    return (java / rel).read_text()


def write(rel: str, text: str) -> None:
    (java / rel).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 1) Manual scan: missing Production Backend must not hard-block selected
#    intraday timeframe. Fall back explicitly to Yahoo delayed observations.
# ---------------------------------------------------------------------------
rel = "ui/BistScanActivity.kt"
s = read(rel)
old = '''            val delayedDailyAllowed = timeframe.isDaily && experimentalOnly()
            if (!productionBackendConfigured() && !delayedDailyAllowed) {
                progress.visibility = View.GONE
                txt.text = "TARAMA BAŞLAMADI"
                status.text = "VERİ SERVİSİ YAPILANDIRILMAMIŞ"
                heroSubtitle.text = "${timeframe.label} taraması için Production Backend HTTPS bağlantısı ve API anahtarı gereklidir."
                debug.text = "BACKEND_NOT_CONFIGURED • TIMEFRAME=${timeframe.apiInterval} • API çağrısı yapılmadı • sahte/veri fallback kullanılmadı."
                configure.setTextColor(getColor(R.color.white))
                Toast.makeText(this, "${timeframe.label} VERİ SERVİSİ KULLANILAMIYOR • Production Backend bağlantısı gerekli.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
'''
new = '''            val productionReady = productionBackendConfigured()
'''
s = replace_once(s, old, new, "manual production hard gate")

s = replace_once(
    s,
    '''                    if (productionBackendConfigured()) {
''',
    '''                    if (productionReady) {
''',
    "manual preflight production branch",
)

s = replace_once(
    s,
    '''                    } else {
                        status.text = "Gecikmeli analiz"
                        heroSubtitle.text = "Yahoo günlük OHLCV ile teknik analiz yapılır; AL/SAT sinyali üretilmez."
                        debug.text = "Yahoo Finance • DENEYSEL/GECİKMELİ • 1D OHLCV • işlem sinyali kapalı"
                    }

                    val scanMode = if (delayedDailyAllowed) BistScanMode.DELAYED_ANALYSIS else BistScanMode.REALTIME_ONLY
                    val baseProvider = ProviderRouter(
                        this@BistScanActivity,
                        experimentalFallbackOverride = scanMode == BistScanMode.DELAYED_ANALYSIS
                    )
                    val scanProvider = if (scanMode == BistScanMode.REALTIME_ONLY) {
                        IntervalMarketDataProvider(baseProvider, analysisTimeframeMinutes)
                    } else {
                        baseProvider
                    }
''',
    '''                    } else {
                        status.text = "Yedek/gecikmeli analiz"
                        heroSubtitle.text = "Yahoo Finance ${timeframe.label} OHLCV ile gecikmeli teknik analiz • doğrulanmış AL/SAT sinyali üretilmez."
                        debug.text = "Yahoo Finance • YEDEK/GECİKMELİ • ${timeframe.apiInterval} OHLCV • doğrulanmış işlem sinyali kapalı"
                    }

                    val scanMode = if (productionReady) BistScanMode.REALTIME_ONLY else BistScanMode.DELAYED_ANALYSIS
                    val baseProvider = ProviderRouter(
                        this@BistScanActivity,
                        experimentalFallbackOverride = !productionReady
                    )
                    // Seçili timeframe hem Production Backend hem de Yahoo gecikmeli yolda
                    // aynı adapter üzerinden uygulanır. 3 DK gerçek 1 DK OHLCV'den üretilir.
                    val scanProvider = IntervalMarketDataProvider(baseProvider, analysisTimeframeMinutes)
''',
    "manual delayed provider selection",
)

s = replace_once(
    s,
    '''                                intent.putExtra(OpportunityActivity.EXTRA_SCAN_WARNING, "GECİKMELİ/GÜNLÜK TEKNİK ANALİZ • AL/SAT SİNYALİ DEĞİLDİR")
''',
    '''                                intent.putExtra(OpportunityActivity.EXTRA_SCAN_WARNING, "YAHOO FINANCE • YEDEK/GECİKMELİ • ${timeframe.label} TEKNİK GÖZLEM • DOĞRULANMIŞ AL/SAT SİNYALİ DEĞİLDİR")
''',
    "delayed result warning",
)

s = replace_once(
    s,
    '''            if (!productionBackendConfigured() && enabled) {
                status.text = "VERİ SERVİSİ BEKLENİYOR"
                heroSubtitle.text = "${selected.label} otomatik tarama AÇIK • Production Backend bekleniyor."
            }
''',
    '''            if (!productionBackendConfigured() && enabled) {
                status.text = "YEDEK/GECİKMELİ KAYNAK"
                heroSubtitle.text = "${selected.label} otomatik tarama AÇIK • Yahoo Finance yedek/gecikmeli kaynak kullanılacak."
            }
''',
    "auto switch fallback status",
)

s = replace_once(
    s,
    '''            !productionBackendConfigured() -> "Açık • ${tf.label} • VERİ SERVİSİ BEKLENİYOR"
''',
    '''            !productionBackendConfigured() -> "Açık • ${tf.label} • Yahoo YEDEK/GECİKMELİ"
''',
    "auto scan UI fallback status",
)

old = '''    private fun experimentalOnly(): Boolean =
        !settings.baseUrl.startsWith("https://") &&
            settings.experimentalProvidersEnabled &&
            settings.yahooFallbackEnabled

'''
if old in s:
    s = s.replace(old, "", 1)

old = '''    private fun refreshSourceLabel() {
        source.text = if (productionBackendConfigured()) {
            "Kaynak: Production Backend • gerçek BIST OHLCV • TradingView veri kaynağı değildir"
        } else if (experimentalOnly()) {
            "Kaynak: Yahoo Finance • yalnız GECİKMELİ/GÜNLÜK teknik analiz • intraday tarama yok"
        } else {
            "Kaynak: Production Backend yapılandırması eksik • HTTPS adresi + API anahtarı gerekli"
        }
        findViewById<Button>(R.id.btnConfigureProvider)?.setTextColor(
            getColor(if (productionBackendConfigured()) R.color.text_secondary else R.color.white)
        )
    }
'''
new = '''    private fun refreshSourceLabel() {
        source.text = if (productionBackendConfigured()) {
            "Kaynak: Production Backend • gerçek BIST OHLCV • TradingView veri kaynağı değildir"
        } else {
            "Kaynak: Yahoo Finance • YEDEK/GECİKMELİ • seçili timeframe OHLCV • doğrulanmış AL/SAT sinyali değildir"
        }
        findViewById<Button>(R.id.btnConfigureProvider)?.setTextColor(
            getColor(if (productionBackendConfigured()) R.color.text_secondary else R.color.white)
        )
    }
'''
s = replace_once(s, old, new, "source label")

old = '''    private fun defaultScanButtonLabel(): String {
        val tf = if (::settings.isInitialized) ScanTimeframe.fromStored(settings.analysisTimeframeMinutes) else ScanTimeframe.minute(5, false)
        return if (tf.isDaily && experimentalOnly() && !productionBackendConfigured()) {
            "▶  GECİKMELİ GÜNLÜK ANALİZİ BAŞLAT  ›"
        } else {
            "▶  TARAMAYI BAŞLAT  ›"
        }
    }
'''
new = '''    private fun defaultScanButtonLabel(): String {
        val tf = if (::settings.isInitialized) ScanTimeframe.fromStored(settings.analysisTimeframeMinutes) else ScanTimeframe.minute(5, false)
        return if (!productionBackendConfigured()) {
            "▶  YEDEK/GECİKMELİ ${tf.label} ANALİZİ BAŞLAT  ›"
        } else {
            "▶  TARAMAYI BAŞLAT  ›"
        }
    }
'''
s = replace_once(s, old, new, "default scan label")

old = '''    private fun initialHeroMessage(): String {
        if (!::settings.isInitialized) return "Kriterlerinizi seçip taramayı başlatın."
        val tf = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        return when {
            productionBackendConfigured() -> "${tf.label} gerçek OHLCV ile manuel tarama hazır. Otomatik tarama ${if (settings.autoScanEnabled) "açık" else "kapalı"}. TradingView piyasa veri kaynağı değildir."
            tf.isDaily && experimentalOnly() -> "Yahoo günlük verisi yalnız gecikmeli teknik izleme içindir; AL/SAT sinyali üretilmez."
            else -> "${tf.label} taraması için Production Backend HTTPS adresi ve API anahtarı yapılandırın."
        }
    }
'''
new = '''    private fun initialHeroMessage(): String {
        if (!::settings.isInitialized) return "Kriterlerinizi seçip taramayı başlatın."
        val tf = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        return if (productionBackendConfigured()) {
            "${tf.label} gerçek OHLCV ile manuel tarama hazır. Otomatik tarama ${if (settings.autoScanEnabled) "açık" else "kapalı"}. TradingView piyasa veri kaynağı değildir."
        } else {
            "Yahoo Finance ${tf.label} yedek/gecikmeli teknik analiz hazır • doğrulanmış AL/SAT sinyali üretilmez."
        }
    }
'''
s = replace_once(s, old, new, "initial hero message")

# The delayed-mode history comment is no longer daily-only.
s = s.replace("Gecikmeli günlük analiz", "Gecikmeli analiz")
write(rel, s)


# ---------------------------------------------------------------------------
# 2) Automatic runner: choose Production Backend when configured, otherwise
#    use Yahoo delayed selected-timeframe path. Delayed observations never
#    overwrite the verified last-successful realtime scan store.
# ---------------------------------------------------------------------------
rel = "worker/AutomaticScanRunner.kt"
s = read(rel)
start_marker = '''        if (!settings.autoScanEnabled) {
'''
end_marker = '''            val scanner = BistScanner(provider, SignalHistoryRecorder(app), BistScanMode.REALTIME_ONLY)
'''
start = s.find(start_marker)
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("automatic runner prefix markers not found")
end += len(end_marker)
new_prefix = '''        if (!settings.autoScanEnabled) {
            return finish(Result(Outcome.STOPPED, "Otomatik tarama kapalı.", timeframeLabel = timeframe.label))
        }
        val productionReady = settings.baseUrl.startsWith("https://") && settings.apiKey.isNotBlank()

        return try {
            val preflight = if (productionReady) BackendPreflightClient(app).checkBist() else null
            if (preflight != null && !preflight.ok) {
                return finish(Result(
                    classifyPreflight(preflight.failureKind),
                    "${preflight.failureKind} • ${preflight.message}",
                    total = preflight.symbolCount,
                    timeframeLabel = timeframe.label
                ))
            }

            val baseProvider = ProviderRouter(app, experimentalFallbackOverride = !productionReady)
            val provider = IntervalMarketDataProvider(baseProvider, timeframe.storedMinutes)

            if (productionReady) {
                val checked = requireNotNull(preflight)
                val sample = checked.sampleSymbol
                if (sample.isNullOrBlank()) {
                    return finish(Result(
                        Outcome.CONFIG_ERROR,
                        "BIST HİSSE LİSTESİ ALINAMADI: örnek sembol yok.",
                        total = checked.symbolCount,
                        timeframeLabel = timeframe.label
                    ))
                }

                // Production yolunda tüm evrene geçmeden seçilen timeframe OHLCV'sini doğrula.
                val probe = provider.fetchOne(sample)
                if (probe == null || probe.candles.size < RealTimeIntegrityPolicy.MIN_HISTORY_BARS) {
                    return finish(Result(
                        Outcome.NO_DATA,
                        "${timeframe.label} VERİ SERVİSİ HAZIR DEĞİL • $sample için yeterli gerçek OHLCV alınamadı.",
                        total = checked.symbolCount,
                        timeframeLabel = timeframe.label
                    ))
                }
            }

            // Backend yoksa Yahoo hiçbir zaman REALTIME sayılmaz; sonuçlar yalnız gecikmeli teknik gözlemdir.
            val scanMode = if (productionReady) BistScanMode.REALTIME_ONLY else BistScanMode.DELAYED_ANALYSIS
            val scanner = BistScanner(provider, SignalHistoryRecorder(app), scanMode)
'''
s = s[:start] + new_prefix + s[end:]

old = '''            if (final.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE) {
                LastSuccessfulScanStore(app).save(run, final.results)
                finish(Result(
                    Outcome.COMPLETED,
                    "TARAMA TAMAMLANDI • ${timeframe.label} • ${final.results.size} fırsat",
'''
new = '''            if (final.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE) {
                if (productionReady) LastSuccessfulScanStore(app).save(run, final.results)
                val completedMessage = if (productionReady) {
                    "TARAMA TAMAMLANDI • ${timeframe.label} • ${final.results.size} fırsat"
                } else {
                    "TARAMA TAMAMLANDI • ${timeframe.label} • Yahoo YEDEK/GECİKMELİ • ${final.results.size} teknik gözlem"
                }
                finish(Result(
                    Outcome.COMPLETED,
                    completedMessage,
'''
s = replace_once(s, old, new, "auto complete result")

old = '''            } else if (final.status == ScanStatus.COMPLETED && final.successful > 0) {
                finish(Result(
                    Outcome.PARTIAL,
                    "KISMİ TARAMA • ${timeframe.label} • Başarılı ${final.successful}/${final.total} • Veri yok ${final.noData} • Hata ${final.errors}",
'''
new = '''            } else if (final.status == ScanStatus.COMPLETED && final.successful > 0) {
                val partialMessage = if (productionReady) {
                    "KISMİ TARAMA • ${timeframe.label} • Başarılı ${final.successful}/${final.total} • Veri yok ${final.noData} • Hata ${final.errors}"
                } else {
                    "KISMİ TARAMA • ${timeframe.label} • Yahoo YEDEK/GECİKMELİ • Teknik gözlem ${final.successful}/${final.total} • Veri yok ${final.noData} • Hata ${final.errors}"
                }
                finish(Result(
                    Outcome.PARTIAL,
                    partialMessage,
'''
s = replace_once(s, old, new, "auto partial result")
write(rel, s)


# ---------------------------------------------------------------------------
# 3) Foreground service: it must execute the same runner instead of looping on
#    BACKEND_NOT_CONFIGURED. The runner itself safely selects delayed fallback.
# ---------------------------------------------------------------------------
rel = "worker/AutoScanForegroundService.kt"
s = read(rel)
old = '''            if (!settings.baseUrl.startsWith("https://") || settings.apiKey.isBlank()) {
                updateForeground("${timeframe.label} • veri servisi yapılandırılmamış")
                settings.autoScanLastStatus = "BACKEND_NOT_CONFIGURED"
                settings.autoScanLastMessage = "Production Backend ve API anahtarı gerekli."
                delay(cadence * 60_000L)
                continue
            }

            updateForeground("${timeframe.label} • otomatik tarama çalışıyor")
'''
new = '''            val sourceMode = if (settings.baseUrl.startsWith("https://") && settings.apiKey.isNotBlank()) {
                "Production Backend"
            } else {
                "Yahoo YEDEK/GECİKMELİ"
            }
            updateForeground("${timeframe.label} • $sourceMode • otomatik tarama çalışıyor")
'''
s = replace_once(s, old, new, "foreground backend gate")
write(rel, s)


# ---------------------------------------------------------------------------
# 4) Interval provider: Yahoo delayed intraday gets a direct selected-timeframe
#    scan path, avoiding an unnecessary full daily fetch before every intraday
#    request. Realtime freshness gate remains strict only for realtime stocks.
# ---------------------------------------------------------------------------
rel = "data/IntervalMarketDataProvider.kt"
s = read(rel)
s = replace_once(
    s,
    '''    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        diagnostics = ProviderScanDiagnostics()
''',
    '''    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        diagnostics = ProviderScanDiagnostics()
        if (!timeframe.isDaily && delegate.id == "yahoo_fallback") {
            return scanDelayedIntraday(onProgress)
        }
''',
    "delayed intraday fast path",
)

insert_before = '''    override suspend fun fetchOne(symbol: String): Stock? = delegate.fetchOne(symbol)?.let { reframe(it) }
'''
idx = s.find(insert_before)
if idx < 0:
    raise SystemExit("Interval provider fetchOne insertion marker not found")
helper = '''    /**
     * Yahoo yedek kaynağında intraday tarama günlük bootstrap fiyatlarını indirmeden doğrudan
     * seçili OHLCV periyodunu alır. Sonuç kasıtlı olarak isRealtime=false kalır.
     */
    private suspend fun scanDelayedIntraday(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val symbols = delegate.listSymbols()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (symbols.isEmpty()) return emptyList()

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val noData = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val messages = linkedMapOf<String, String>()
        val completed = AtomicInteger(0)
        val total = symbols.size
        onProgress(0, total)

        val rows = supervisorScope {
            symbols.map { symbol ->
                async(Dispatchers.IO) {
                    try {
                        semaphore.withPermit {
                            val toTime = System.currentTimeMillis()
                            val fromTime = toTime - lookbackMs(timeframe.storedMinutes, minimumBars)
                            val selected = if (timeframe.storedMinutes in DIRECT_INTERVALS) {
                                OhlcvResampler.sanitize(fetchHistoryWithRetry(symbol, fromTime, toTime, timeframe.storedMinutes))
                            } else {
                                val oneMinute = OhlcvResampler.sanitize(fetchHistoryWithRetry(symbol, fromTime, toTime, 1))
                                OhlcvResampler.aggregate(oneMinute, timeframe.storedMinutes)
                            }
                            if (selected.size < minimumBars) {
                                throw ProviderException(
                                    ProviderFailureCode.EMPTY_DATA,
                                    "$symbol için ${timeframe.label} periyotta en az $minimumBars mum gerekli; ${selected.size} alındı."
                                )
                            }
                            val candles = selected.takeLast(MAX_ANALYSIS_BARS)
                            val last = candles.last()
                            Stock(
                                symbol = symbol,
                                companyName = null,
                                candles = candles,
                                source = "${delegate.displayName} • ${timeframe.label}",
                                dataTimestamp = last.timestamp,
                                isRealtime = false,
                                delaySeconds = null,
                                currentSessionIncluded = false,
                                quotePrice = last.close,
                                market = "BIST",
                                historySymbol = symbol,
                                interval = timeframe.apiInterval,
                                exchangeTimezone = "Europe/Istanbul",
                                lastBarTime = last.timestamp,
                                lastBarClosed = null
                            )
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (pe: ProviderException) {
                        synchronized(messages) {
                            val msg = pe.message ?: pe.code.name
                            messages[symbol] = msg
                            if (pe.code == ProviderFailureCode.EMPTY_DATA || pe.code == ProviderFailureCode.STALE_DATA || pe.code == ProviderFailureCode.BIST_HISTORY_ERROR) {
                                noData += symbol
                            } else {
                                errors += symbol
                            }
                        }
                        logSymbolFailure(symbol, pe.code.name, pe.message, "yahoo-delayed/${timeframe.apiInterval}")
                        null
                    } catch (t: Throwable) {
                        synchronized(messages) {
                            errors += symbol
                            messages[symbol] = t.message ?: t.javaClass.simpleName
                        }
                        logSymbolFailure(symbol, "DATA_ERROR", t.message, "yahoo-delayed/${timeframe.apiInterval}")
                        null
                    } finally {
                        onProgress(completed.incrementAndGet().coerceAtMost(total), total)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        diagnostics = ProviderScanDiagnostics(noData.distinct(), errors.distinct(), messages.toMap())
        if (rows.isEmpty()) {
            val detail = messages.values.firstOrNull() ?: "Yahoo Finance yedek kaynağından seçilen periyot OHLCV alınamadı."
            throw ProviderException(ProviderFailureCode.EMPTY_DATA, "${timeframe.label} tarama yapılamadı. $detail")
        }
        return rows
    }

'''
s = s[:idx] + helper + s[idx:]

s = replace_once(
    s,
    '''        if (!timeframe.isDaily) {
            val barAgeMs = stock.exchangeTimestamp - last.timestamp
''',
    '''        if (!timeframe.isDaily && stock.isRealtime) {
            val barAgeMs = stock.exchangeTimestamp - last.timestamp
''',
    "realtime-only bar age gate",
)

s = replace_once(
    s,
    '''        return stock.copy(
            candles = candles,
            interval = timeframe.apiInterval,
''',
    '''        return stock.copy(
            candles = candles,
            // Gecikmeli intraday kaynağın fiyat/zamanı seçili OHLCV'nin son mumundan gelir;
            // realtime bayrakları değiştirilmez ve kaynak hiçbir zaman anlıkmış gibi yükseltilmez.
            dataTimestamp = if (!timeframe.isDaily && !stock.isRealtime) last.timestamp else stock.dataTimestamp,
            quotePrice = if (!timeframe.isDaily && !stock.isRealtime) last.close else stock.quotePrice,
            interval = timeframe.apiInterval,
''',
    "delayed selected-candle timestamp",
)
write(rel, s)


# ---------------------------------------------------------------------------
# 5) Yahoo 1m history: split long requests into bounded windows and merge.
#    This is still delayed/unverified data; no realtime claim is introduced.
# ---------------------------------------------------------------------------
rel = "data/YahooFallbackProvider.kt"
s = read(rel)
start_marker = '''    override suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> = withContext(Dispatchers.IO) {
'''
end_marker = '''    override suspend fun fetchDailyHistory(symbol: String, maximumRange: Boolean): List<Candle> = withContext(Dispatchers.IO) {
'''
start = s.find(start_marker)
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("Yahoo fetchHistory markers not found")
new_history = '''    override suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> = withContext(Dispatchers.IO) {
        if (fromTime <= 0L || toTime <= fromTime) return@withContext emptyList()
        val interval = when {
            intervalMinutes <= 1 -> "1m"
            intervalMinutes <= 5 -> "5m"
            intervalMinutes <= 15 -> "15m"
            intervalMinutes <= 30 -> "30m"
            else -> "60m"
        }
        val encoded = URLEncoder.encode("${symbol.trim().uppercase()}.IS", "UTF-8")
        val windows = if (interval == "1m") {
            boundedHistoryWindows(fromTime, toTime, ONE_MINUTE_HISTORY_CHUNK_MS)
        } else {
            listOf(fromTime to toTime)
        }
        windows
            .flatMap { (windowStart, windowEnd) -> fetchHistoryWindow(encoded, windowStart, windowEnd, interval) }
            .asSequence()
            .filter { it.timestamp in fromTime..toTime }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .toList()
    }

    private fun fetchHistoryWindow(encodedSymbol: String, fromTime: Long, toTime: Long, interval: String): List<Candle> {
        val period1 = fromTime / 1000L
        val period2 = toTime / 1000L
        var con: HttpURLConnection? = null
        return try {
            con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encodedSymbol?period1=$period1&period2=$period2&interval=$interval&events=history")
                .openConnection() as HttpURLConnection
            con.connectTimeout = 7_000
            con.readTimeout = 7_000
            con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
            if (con.responseCode !in 200..299) return emptyList()
            val body = con.inputStream.bufferedReader().use { it.readText() }
            parseCandlesOnly(body)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { con?.disconnect() }
        }
    }

    private fun boundedHistoryWindows(fromTime: Long, toTime: Long, maxWindowMs: Long): List<Pair<Long, Long>> {
        if (fromTime <= 0L || toTime <= fromTime || maxWindowMs <= 0L) return emptyList()
        val out = mutableListOf<Pair<Long, Long>>()
        var cursor = fromTime
        while (cursor < toTime) {
            val end = minOf(toTime, cursor + maxWindowMs)
            out += cursor to end
            if (end >= toTime) break
            cursor = end + 1L
        }
        return out
    }

'''
s = s[:start] + new_history + s[end:]

companion_old = '''    companion object {
        private val SYMBOL_REGEX = Regex("[A-Z0-9_]{3,12}")
    }
'''
companion_new = '''    companion object {
        private val SYMBOL_REGEX = Regex("[A-Z0-9_]{3,12}")
        private const val ONE_MINUTE_HISTORY_CHUNK_MS = 5L * 24 * 60 * 60 * 1000
    }
'''
s = replace_once(s, companion_old, companion_new, "Yahoo chunk constant")
write(rel, s)

print("B115 intraday service fallback fix applied successfully")
