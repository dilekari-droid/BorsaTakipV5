package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B121ProviderAndMarketTilesTest {
    private fun source(path: String): String = File(path).readText()

    @Test fun yahooDisplayQuoteDoesNotRequireDailyHistoryFirst() {
        val s = source("src/main/java/tr/borsatakip/v5/data/YahooFallbackProvider.kt")
        val fetchOne = s.substringAfter("override suspend fun fetchOne").substringBefore("override suspend fun listSymbols")
        assertTrue(fetchOne.contains("fetchDisplayQuoteOnly(normalized)"))
        assertTrue(fetchOne.contains("val daily = fetch(normalized) ?: return@withContext quoteStock"))
        assertTrue(s.contains("fetchDisplayQuoteOnly"))
    }

    @Test fun watchlistDisplayRequestsAreBoundedAndProductionFallbackIsExplicit() {
        val s = source("src/main/java/tr/borsatakip/v5/data/WatchlistDataResolver.kt")
        assertTrue(s.contains("Semaphore(4)"))
        assertTrue(s.contains("experimentalFallbackOverride = false"))
        assertTrue(s.contains("fetchDisplayQuoteOnly(normalized)"))
        assertTrue(s.contains("FetchResult"))
    }

    @Test fun emptyManualWatchlistHasAutomaticStarterSeed() {
        val catalog = source("src/main/java/tr/borsatakip/v5/data/BistBootstrapCatalog.kt")
        val ui = source("src/main/java/tr/borsatakip/v5/ui/FavoritesActivity.kt")
        assertTrue(catalog.contains("defaultWatchlist"))
        assertTrue(ui.contains("BistBootstrapCatalog.defaultWatchlist"))
        assertTrue(ui.contains("watchlistStore.seedIfEmpty(automaticSeed)"))
    }

    @Test fun marketStripRequestsAllNonViopTilesWithoutHttpsEarlyReturn() {
        val s = source("src/main/java/tr/borsatakip/v5/ui/MainActivity.kt")
        val method = s.substringAfter("private fun refreshMarketTiles()").substringBefore("private fun bindStockTile")
        assertTrue(method.contains("XU100.IS"))
        assertTrue(method.contains("XU030.IS"))
        assertTrue(method.contains("TRY=X"))
        assertTrue(method.contains("XAUUSD=X"))
        assertTrue(method.contains("GC=F"))
        assertFalse(method.contains("if (!settings.baseUrl.startsWith(\"https://\")) return@launch"))
    }

    @Test fun viop30StillRequiresRealBackendQuote() {
        val s = source("src/main/java/tr/borsatakip/v5/ui/MainActivity.kt")
        assertTrue(s.contains("backend.loadViopQuote(contract.symbol).getOrNull()"))
        assertTrue(s.contains("it.underlying.equals(\"XU030\", true)"))
        assertFalse(s.contains("VIOP30_FAKE"))
    }

    @Test fun marketValuesAreSingleLineAndAutosized() {
        val xml = source("src/main/res/layout/activity_main.xml")
        listOf("marketBist100Value", "marketBist30Value", "marketUsdValue", "marketGoldValue", "marketViopValue").forEach { id ->
            val block = xml.substringAfter("@+id/$id").substringBefore("/>")
            assertTrue(block.contains("android:maxLines=\"1\""))
            assertTrue(block.contains("android:singleLine=\"true\""))
            assertTrue(block.contains("android:autoSizeTextType=\"uniform\""))
        }
    }
}
