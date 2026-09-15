package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-contract acceptance tests derived from the B113/B114 technical cross-check report. */
class B113B114AcceptanceSourceTest {
    private fun mainRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return sequenceOf(
            File(cwd, "src/main"),
            File(cwd, "app/src/main"),
            cwd.parentFile?.let { File(it, "app/src/main") }
        ).filterNotNull().firstOrNull { it.isDirectory }
            ?: error("app/src/main bulunamadı: ${cwd.absolutePath}")
    }

    private fun source(path: String): String = File(mainRoot(), path).readText()

    @Test
    fun analysisTimeframeAndScanCadenceAreSeparateDomainFields() {
        val models = source("java/tr/borsatakip/v5/model/Models.kt")
        val settings = source("java/tr/borsatakip/v5/data/SettingsStore.kt")
        assertTrue(models.contains("val analysisTimeframeMinutes:Int"))
        assertTrue(models.contains("val scanCadenceMinutes:Int"))
        assertTrue(models.contains("val scanMode:ScanMode"))
        assertTrue(settings.contains("analysis_timeframe_minutes"))
        assertTrue(settings.contains("scan_cadence_minutes"))
        assertTrue(settings.contains("scan_mode"))
    }

    @Test
    fun legacyScanIntervalIsNotUsedAsRuntimeScanContract() {
        val allowed = setOf(
            "java/tr/borsatakip/v5/model/Models.kt",
            "java/tr/borsatakip/v5/data/SettingsStore.kt"
        )
        val violations = mainRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(mainRoot()).path !in allowed }
            .filter { it.readText().contains("scanIntervalMinutes") }
            .map { it.relativeTo(mainRoot()).path }
            .toList()
        assertTrue("scanIntervalMinutes runtime kullanımında kaldı: $violations", violations.isEmpty())
    }

    @Test
    fun restClientCarriesAndVerifiesFullScanContract() {
        val src = source("java/tr/borsatakip/v5/data/RealtimeScannerClient.kt")
        assertTrue(src.contains("analysisTimeframeMinutes=${'$'}safeTimeframe"))
        assertTrue(src.contains("scanCadenceMinutes=${'$'}safeCadence"))
        assertTrue(src.contains("scanMode=${'$'}{scanMode.name}"))
        assertTrue(src.contains("snapshot.analysisTimeframeMinutes == safeTimeframe"))
        assertTrue(src.contains("snapshot.scanCadenceMinutes == safeCadence"))
        assertTrue(src.contains("snapshot.scanMode == scanMode"))
    }

    @Test
    fun websocketCarriesAndVerifiesSameScanContract() {
        val src = source("java/tr/borsatakip/v5/data/RealtimeScannerSocket.kt")
        assertTrue(src.contains(".put(\"analysisTimeframeMinutes\", requestedTimeframe)"))
        assertTrue(src.contains(".put(\"scanCadenceMinutes\", requestedCadence)"))
        assertTrue(src.contains(".put(\"scanMode\", scanMode.name)"))
        assertTrue(src.contains("snapshot.analysisTimeframeMinutes == requestedTimeframe"))
        assertTrue(src.contains("snapshot.scanCadenceMinutes == requestedCadence"))
    }

    @Test
    fun realtimeParserUsesExplicitRiskQualityAndCorrectEmaFields() {
        val src = source("java/tr/borsatakip/v5/data/RealtimeScannerClient.kt")
        assertTrue(src.contains("val riskScore = optInt(\"riskScore\", -1)"))
        assertTrue(src.contains("val dataQuality = optInt(\"dataQualityScore\", -1)"))
        assertFalse(src.contains("100 - score"))
        assertFalse(src.contains("dataConfidenceScore = 100"))
        assertTrue(src.contains("val ema20 = finite(\"ema20\")"))
        assertTrue(src.contains("val ema50 = finite(\"ema50\")"))
        assertTrue(src.contains("val ema200 = finite(\"ema200\")"))
        assertFalse(src.contains("ema20 = finite(\"emaFast\")"))
        assertFalse(src.contains("ema50 = finite(\"emaSlow\")"))
    }

    @Test
    fun realtimeParserKeepsUnknownDailyChangeAndKapSemanticsFailClosed() {
        val src = source("java/tr/borsatakip/v5/data/RealtimeScannerClient.kt")
        assertTrue(src.contains("val dailyChange = finite(\"dailyChangePct\")"))
        assertFalse(src.contains("finite(\"dailyChangePct\") ?: 0.0"))
        assertTrue(src.contains("kapLabel = \"Veri yok\""))
        assertFalse(src.contains("kapLabel = \"Canlı tarama\""))
        assertTrue(src.contains("val marketRegimeRaw = optString(\"marketRegime\")"))
    }

    @Test
    fun realtimeOpportunityRequiresConfirmedClosedBar() {
        val src = source("java/tr/borsatakip/v5/data/RealtimeScannerClient.kt")
        assertTrue(src.contains("!optBoolean(\"confirmedClosedBar\", false)"))
        assertTrue(src.contains("if (!optBoolean(\"ready\", false)"))
    }

    @Test
    fun emptyOrDisconnectedLiveSnapshotInvalidatesOldOpportunities() {
        val src = source("java/tr/borsatakip/v5/ui/OpportunityActivity.kt")
        assertFalse(src.contains("if (snapshot.readySymbols <= 0) return"))
        assertTrue(src.contains("AppSession.lastOpportunities = snapshot.opportunities"))
        assertTrue(src.contains("eski fırsatlar geçersizleştirildi"))
        assertFalse(src.contains("if (AppSession.lastOpportunities.isEmpty()) summary.text = \"Canlı scanner: ${'$'}message\""))
    }

    @Test
    fun universeUiNeverPromotesNinetyFivePercentToFullBist() {
        val src = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(src.contains("universeVerified"))
        assertFalse(src.contains(">= 95"))
        assertFalse(src.contains("%95"))
        assertFalse(src.contains("Tüm BIST canlı hazır"))
    }

    @Test
    fun liveForegroundServiceUsesExplicitLiveScanContract() {
        val src = source("java/tr/borsatakip/v5/worker/RealtimeAlertService.kt")
        assertTrue(src.contains("analysisTimeframeMinutes = settings.analysisTimeframeMinutes"))
        assertTrue(src.contains("scanCadenceMinutes = settings.scanCadenceMinutes"))
        assertTrue(src.contains("scanMode = ScanMode.LIVE"))
    }
}
