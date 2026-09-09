package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import tr.borsatakip.v5.model.Stock

/**
 * Sağlayıcı yönlendirici: ana kaynak mobil backend'dir. Yahoo yalnızca isteğe bağlı gecikmeli
 * yedektir. CancellationException hiçbir zaman fallback'e çevrilmez; iptal normal lifecycle akışıdır.
 */
class ProviderRouter(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    private val primary = MobileMarketDataProvider(context)
    private val fallback = YahooFallbackProvider(context)

    override val id: String get() = "provider_router"
    override val displayName: String get() = settings.lastProviderLabel

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val primaryResult = try {
            primary.scan(onProgress)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
        if (primaryResult.isNotEmpty()) {
            mark(primary.id, primaryResult.firstOrNull()?.source ?: primary.displayName)
            return primaryResult
        }
        if (!settings.yahooFallbackEnabled) {
            throw IllegalStateException("Canlı veri sağlayıcısı yapılandırılmamış veya veri alınamadı.")
        }
        val fallbackResult = try {
            fallback.scan(onProgress)
        } catch (ce: CancellationException) {
            throw ce
        }
        if (fallbackResult.isEmpty()) throw IllegalStateException("Ana ve yedek veri sağlayıcılarından veri alınamadı.")
        mark(fallback.id, fallback.displayName)
        return fallbackResult
    }

    override suspend fun fetchOne(symbol: String): Stock? {
        val primaryResult = try {
            primary.fetchOne(symbol)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
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
