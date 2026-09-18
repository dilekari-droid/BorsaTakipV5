package tr.borsatakip.v5.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B117EngineIntegritySourceTest {
    private fun source(path: String): String = File(path).readText()

    @Test fun scanResultsUsesCanonicalRankingAndDoesNotDoubleCountFailures() {
        val s = source("src/main/java/tr/borsatakip/v5/ui/ScanResultsActivity.kt")
        assertTrue(s.contains("OpportunityRankingPolicy.sort(filteredItems)"))
        assertTrue(s.contains("scanState.skipped.coerceAtLeast(scanState.failedSymbols.size)"))
        assertTrue(s.contains("val failed = scanState.skipped.coerceAtLeast(scanState.failedSymbols.size)"))
    }

    @Test fun versionCodeTracksCurrentIntegrityBuild() {
        val s = File("build.gradle.kts").readText()
        assertTrue(s.contains("versionCode = 136"))
    }
}
