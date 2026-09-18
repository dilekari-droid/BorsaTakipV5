package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B115IntradayFallbackSourceTest {
    private fun mainRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            File(cwd, "src/main"),
            File(cwd, "app/src/main"),
            cwd.parentFile?.let { File(it, "app/src/main") }
        ).filterNotNull().firstOrNull { it.isDirectory }
            ?: error("app/src/main bulunamadı: ${cwd.absolutePath}")
    }

    private fun source(path: String): String = File(mainRoot(), path).readText()

    @Test fun manualIntradayNoLongerStopsAtMissingProductionBackend() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertFalse(src.contains("VERİ SERVİSİ KULLANILAMIYOR • Production Backend bağlantısı gerekli"))
        assertFalse(src.contains("delayedDailyAllowed"))
        assertTrue(src.contains("val productionReady = productionBackendConfigured()"))
        assertTrue(src.contains("if (productionReady) BistScanMode.REALTIME_ONLY else BistScanMode.DELAYED_ANALYSIS"))
    }

    @Test fun delayedManualPathStillUsesSelectedTimeframeAdapter() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(src.contains("experimentalFallbackOverride = !productionReady"))
        assertTrue(src.contains("val scanProvider = IntervalMarketDataProvider(baseProvider, analysisTimeframeMinutes)"))
        assertTrue(src.contains("Yahoo Finance • YEDEK/GECİKMELİ • ${'$'}{timeframe.apiInterval} OHLCV"))
    }

    @Test fun automaticRunnerFallsBackInsteadOfReturningBackendConfigError() {
        val src = source("java/tr/borsatakip/v5/worker/AutomaticScanRunner.kt")
        assertFalse(src.contains("otomatik taraması için Production Backend ve API anahtarı gerekli"))
        assertTrue(src.contains("val productionReady = settings.baseUrl.startsWith(\"https://\") && settings.apiKey.isNotBlank()"))
        assertTrue(src.contains("experimentalFallbackOverride = !productionReady"))
        assertTrue(src.contains("if (productionReady) BistScanMode.REALTIME_ONLY else BistScanMode.DELAYED_ANALYSIS"))
    }

    @Test fun delayedAutomaticResultsCannotOverwriteVerifiedLastSuccessfulStore() {
        val src = source("java/tr/borsatakip/v5/worker/AutomaticScanRunner.kt")
        assertTrue(src.contains("if (productionReady) LastSuccessfulScanStore(app).save(run, final.results)"))
        assertTrue(src.contains("Yahoo YEDEK/GECİKMELİ"))
    }

    @Test fun foregroundLoopAlwaysReachesAutomaticRunnerWhenEnabled() {
        val src = source("java/tr/borsatakip/v5/worker/AutoScanForegroundService.kt")
        val loop = src.substringAfter("private suspend fun runLoop()").substringBefore("override fun onDestroy()")
        assertFalse(loop.contains("BACKEND_NOT_CONFIGURED"))
        assertFalse(loop.contains("Production Backend ve API anahtarı gerekli"))
        assertTrue(loop.contains("Yahoo YEDEK/GECİKMELİ"))
        assertTrue(loop.contains("AutomaticScanRunner.runOnce(this)"))
    }

    @Test fun yahooIntradayHasDedicatedDelayedNonRealtimeScanPath() {
        val src = source("java/tr/borsatakip/v5/data/IntervalMarketDataProvider.kt")
        assertTrue(src.contains("delegate.id == \"yahoo_fallback\""))
        assertTrue(src.contains("scanDelayedIntraday(onProgress)"))
        assertTrue(src.contains("isRealtime = false"))
        assertTrue(src.contains("currentSessionIncluded = false"))
        assertTrue(src.contains("dataTimestamp = last.timestamp"))
        assertTrue(src.contains("quotePrice = last.close"))
    }

    @Test fun oneMinuteYahooHistoryIsBoundedAndThreeMinuteUsesRealOneMinuteAggregation() {
        val yahoo = source("java/tr/borsatakip/v5/data/YahooFallbackProvider.kt")
        val interval = source("java/tr/borsatakip/v5/data/IntervalMarketDataProvider.kt")
        assertTrue(yahoo.contains("ONE_MINUTE_HISTORY_CHUNK_MS"))
        assertTrue(yahoo.contains("boundedHistoryWindows"))
        assertTrue(yahoo.contains("interval == \"1m\""))
        assertTrue(yahoo.contains("distinctBy { it.timestamp }"))
        assertTrue(interval.contains("fetchHistoryWithRetry(symbol, fromTime, toTime, 1)"))
        assertTrue(interval.contains("OhlcvResampler.aggregate(oneMinute, 1, timeframe.storedMinutes)"))
    }

    @Test fun userInterfaceDisclosesDelayedFallbackAndDoesNotClaimRealtimeOrReadyBeforeScan() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(src.contains("Kaynak: Yahoo Finance • YEDEK/GECİKMELİ • seçili timeframe OHLCV"))
        assertTrue(src.contains("DOĞRULANMIŞ AL/SAT SİNYALİ DEĞİLDİR"))
        assertTrue(src.contains("Yahoo Finance ${'$'}{tf.label} yedek/gecikmeli kaynak seçili. Analiz henüz başlatılmadı; doğrulanmış AL/SAT sinyali üretilmez."))
        assertFalse(src.contains("Yahoo Finance ${'$'}{tf.label} yedek/gecikmeli teknik analiz hazır"))
        assertFalse(src.contains("yalnız GECİKMELİ/GÜNLÜK teknik analiz • intraday tarama yok"))
    }
}
