package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.Stock

/**
 * Sağlayıcı yönlendirici: ana kaynak her zaman mobil backend'dir. Yahoo yalnızca isteğe bağlı
 * gecikmeli yedektir. UI ve analiz motoru somut sağlayıcı sınıfını bilmez.
 */
class ProviderRouter(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    private val primary = MobileMarketDataProvider(context)
    private val fallback = YahooFallbackProvider(context)

    override val id: String get() = "provider_router"
    override val displayName: String get() = settings.lastProviderLabel

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val primaryResult = runCatching { primary.scan(onProgress) }.getOrNull().orEmpty()
        if (primaryResult.isNotEmpty()) {
            mark(primary.id, primaryResult.firstOrNull()?.source ?: primary.displayName)
            return primaryResult
        }
        if (!settings.yahooFallbackEnabled) {
            throw IllegalStateException("Ana veri sağlayıcısından veri alınamadı ve gecikmeli yedek kaynak kapalı.")
        }
        val fallbackResult = fallback.scan(onProgress)
        if (fallbackResult.isEmpty()) throw IllegalStateException("Ana ve yedek veri sağlayıcılarından veri alınamadı.")
        mark(fallback.id, fallback.displayName)
        return fallbackResult
    }

    override suspend fun fetchOne(symbol: String): Stock? {
        val primaryResult = runCatching { primary.fetchOne(symbol) }.getOrNull()
        if (primaryResult != null) {
            mark(primary.id, primaryResult.source)
            return primaryResult
        }
        if (!settings.yahooFallbackEnabled) return null
        val fallbackResult = fallback.fetchOne(symbol)
        if (fallbackResult != null) mark(fallback.id, fallback.displayName)
        return fallbackResult
    }

    private fun mark(providerId: String, label: String) {
        settings.lastProviderId = providerId
        settings.lastProviderLabel = label
        settings.lastProviderTimestamp = System.currentTimeMillis()
    }
}
