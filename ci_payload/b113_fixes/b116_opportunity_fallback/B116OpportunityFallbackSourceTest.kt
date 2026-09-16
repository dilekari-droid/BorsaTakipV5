package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B116OpportunityFallbackSourceTest {
    private fun mainRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(
            File(cwd, "src/main"),
            File(cwd, "app/src/main"),
            cwd.parentFile?.let { File(it, "app/src/main") }
        ).filterNotNull().firstOrNull { it.isDirectory }
            ?: error("app/src/main bulunamadı: ${cwd.absolutePath}")
    }

    private fun source(path: String): String = File(mainRoot(), path).readText()

    @Test fun opportunityScreenNoLongerHardDisablesRealDelayedFallback() {
        val src = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        assertTrue(src.contains("experimentalFallbackOverride = true"))
        assertFalse(src.contains("experimentalFallbackOverride = false"))
        assertTrue(src.contains("Yahoo Finance • YEDEK/GECİKMELİ"))
    }

    @Test fun opportunityScanUsesTheSelectedTimeframeProvider() {
        val src = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        assertTrue(src.contains("IntervalMarketDataProvider("))
        assertTrue(src.contains("settings.analysisTimeframeMinutes"))
        assertTrue(src.contains("ScanTimeframe.displayLabel(settings.analysisTimeframeMinutes)"))
    }

    @Test fun fallbackOpportunityPathIsExplicitlyDelayedNotRealtime() {
        val src = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        assertTrue(src.contains("BistScanMode.DELAYED_ANALYSIS"))
        assertTrue(src.contains("YEDEK/GECİKMELİ FIRSAT GÖZLEMİ"))
        assertTrue(src.contains("KISMİ YEDEK/GECİKMELİ VERİ"))
    }

    @Test fun delayedOpportunityResultsDoNotOverwriteVerifiedOpportunityStore() {
        val src = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        assertFalse(src.contains("historyStore.save(run, results)"))
        assertTrue(src.contains("doğrulanmış sinyal geçmişine yazılmadı"))
    }

    @Test fun productionRealtimeSocketRequiresBothHttpsAndApiKey() {
        val src = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        assertTrue(src.contains("if (!productionBackendConfigured(settings))"))
        assertTrue(src.contains("settings.baseUrl.startsWith(\"https://\") && settings.apiKey.isNotBlank()"))
        assertTrue(src.contains("realtimeSocket.connect("))
    }

    @Test fun missingBenchmarkStaysExplicitAndNeutral() {
        val activity = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        val regime = source("java/tr/borsatakip/v5/analysis/MarketRegimeEngine.kt")
        assertTrue(activity.contains("Benchmark verisi alınamadı"))
        assertTrue(regime.contains("UNKNOWN"))
        assertTrue(regime.contains("benchmark verisi yok", ignoreCase = true))
    }

    @Test fun watchedMetricUsesCurrentScanUniverseBeforeOldVerifiedRun() {
        val src = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        assertTrue(src.contains("AppSession.lastScanState?.total?.takeIf { it > 0 }"))
        assertTrue(src.contains("lastSuccessfulRun?.count?.takeIf { it > 0 }"))
    }

    @Test fun providerRouterFallbackCanLoadSymbolsInsteadOfThrowingProductionNotReady() {
        val router = source("java/tr/borsatakip/v5/data/ProviderRouter.kt")
        val yahoo = source("java/tr/borsatakip/v5/data/YahooFallbackProvider.kt")
        assertTrue(router.contains("experimentalFallbackOverride"))
        assertTrue(router.contains("fallback.listSymbols()"))
        assertTrue(yahoo.contains("override suspend fun listSymbols(): List<String> = loadExperimentalSymbols()"))
    }

    @Test fun yahooFallbackRemainsDelayedAndNeverClaimsRealtime() {
        val yahoo = source("java/tr/borsatakip/v5/data/YahooFallbackProvider.kt")
        val interval = source("java/tr/borsatakip/v5/data/IntervalMarketDataProvider.kt")
        assertTrue(yahoo.contains("isRealtime = false"))
        assertTrue(interval.contains("delegate.id == \"yahoo_fallback\""))
        assertTrue(interval.contains("isRealtime = false"))
        assertTrue(interval.contains("currentSessionIncluded = false"))
    }
}
