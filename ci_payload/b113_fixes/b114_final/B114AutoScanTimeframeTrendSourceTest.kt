package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B114AutoScanTimeframeTrendSourceTest {
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

    @Test fun autoScanPreferenceIsNotForcedOffWhenBackendIsMissing() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertFalse(src.contains("if (enabled && !productionBackendConfigured())"))
        assertTrue(src.contains("settings.autoScanEnabled = enabled"))
        assertTrue(src.contains("VERİ SERVİSİ BEKLENİYOR"))
    }

    @Test fun foregroundServiceStartDependsOnUserPreferenceNotBackendConfiguration() {
        val src = source("java/tr/borsatakip/v5/worker/AutoScanForegroundService.kt")
        val start = src.substringAfter("fun start(context: Context): Boolean").substringBefore("fun stop(context: Context)")
        assertTrue(start.contains("if (!settings.autoScanEnabled) return false"))
        assertFalse(start.contains("settings.baseUrl.startsWith"))
        assertFalse(start.contains("settings.apiKey.isBlank"))
    }

    @Test fun enabledAutoScanIsReconciledOnResumeEvenWhileBackendWaits() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(src.contains("if (settings.autoScanEnabled)"))
        assertTrue(src.contains("AutoScanScheduler.reconcile(this, allowForegroundStart = true)"))
        assertFalse(src.contains("if (settings.autoScanEnabled && productionBackendConfigured())"))
    }

    @Test fun schedulerUsesForegroundBelow15AndWorkManagerAtOrAbove15() {
        val src = source("java/tr/borsatakip/v5/worker/AutoScanScheduler.kt")
        assertTrue(src.contains("WORK_MANAGER_MINUTES = 15"))
        assertTrue(src.contains("cadence < WORK_MANAGER_MINUTES"))
        assertTrue(src.contains("AutoScanForegroundService.start"))
        assertTrue(src.contains("PeriodicWorkRequestBuilder"))
    }

    @Test fun requiredTimeframesUseRealDirectOrOneMinuteAggregationPaths() {
        val timeframe = source("java/tr/borsatakip/v5/data/ScanTimeframe.kt")
        val provider = source("java/tr/borsatakip/v5/data/IntervalMarketDataProvider.kt")
        assertTrue(timeframe.contains("setOf(1, 3, 5, 10, 15, 30, 60)"))
        assertTrue(provider.contains("DIRECT_INTERVALS = setOf(1, 5, 15, 30, 60)"))
        assertTrue(provider.contains("OhlcvResampler.aggregate"))
        assertTrue(provider.contains("1"))
        assertTrue(provider.contains("3"))
        assertTrue(provider.contains("5"))
        assertTrue(provider.contains("15"))
        assertTrue(provider.contains("60"))
    }

    @Test fun oneDayIsRealDailyAnd240MinuteContractIsRejected() {
        val src = source("java/tr/borsatakip/v5/data/ScanTimeframe.kt")
        assertTrue(src.contains("DAILY_STORED_MINUTES = 1440"))
        assertTrue(src.contains("\"1d\""))
        assertTrue(src.contains("240 DK desteklenmez"))
    }

    @Test fun resultCardsUseSelectedTimeframeTechnicalTrendForCardColour() {
        val scan = source("java/tr/borsatakip/v5/ui/ScanResultsAdapter.kt")
        val opportunity = source("java/tr/borsatakip/v5/ui/OpportunityAdapter.kt")
        val trend = source("java/tr/borsatakip/v5/analysis/TrendUiPolicy.kt")
        assertTrue(scan.contains("TrendUiPolicy.resolve(item)"))
        assertTrue(opportunity.contains("TrendUiPolicy.resolve(x)"))
        assertTrue(scan.contains("TREND:"))
        assertTrue(opportunity.contains("Trend"))
        assertTrue(trend.contains("Daily price change is deliberately NOT used"))
        assertTrue(trend.contains("STRONG_BULLISH"))
        assertTrue(trend.contains("STRONG_BEARISH"))
    }
}
