package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import tr.borsatakip.v5.model.Stock

/**
 * Üretim yönlendiricisi: ana kaynak her zaman yapılandırılmış HTTPS backend'dir.
 * Yahoo yalnızca kullanıcı deneysel sağlayıcıları ve Yahoo yedeğini açıkça etkinleştirirse çalışır.
 */
class ProviderRouter(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    private val primary = MobileMarketDataProvider(context)
    private val fallback = YahooFallbackProvider(context)

    override val id: String get() = "provider_router"
    override val displayName: String get() = settings.lastProviderLabel

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> =
        scanDetailed { _, done, total -> onProgress(done, total) }.successfulStocks

    override suspend fun scanDetailed(
        onProgress: (result: ProviderSymbolResult, done: Int, total: Int) -> Unit
    ): ProviderScanReport {
        if (!settings.baseUrl.startsWith("https://")) {
            if (!allowExperimentalFallback()) {
                throw IllegalStateException(
                    "Üretim veri sağlayıcısı yapılandırılmamış. Ayarlar'da gerçek HTTPS backend tanımlayın veya yalnız test için deneysel sağlayıcı modunu açın."
                )
            }
            return fallbackScanDetailed(onProgress)
        }

        val primaryReport = try {
            primary.scanDetailed(onProgress)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (!allowExperimentalFallback()) throw t
            null
        }

        if (primaryReport != null && primaryReport.total > 0) {
            val success = primaryReport.results.firstOrNull { it.status == ProviderSymbolStatus.SUCCESS && it.stock != null }
            if (success != null) {
                mark(primary.id, success.stock?.source ?: primary.displayName)
                return primaryReport
            }
            if (!allowExperimentalFallback()) return primaryReport
        }

        return fallbackScanDetailed(onProgress)
    }

    override suspend fun fetchOne(symbol: String): Stock? {
        if (settings.baseUrl.startsWith("https://")) {
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
        }
        if (!allowExperimentalFallback()) return null
        val fallbackResult = fallback.fetchOne(symbol)
        if (fallbackResult != null) mark(fallback.id, fallback.displayName)
        return fallbackResult
    }

    private suspend fun fallbackScanDetailed(
        onProgress: (result: ProviderSymbolResult, done: Int, total: Int) -> Unit
    ): ProviderScanReport {
        val report = fallback.scanDetailed(onProgress)
        if (report.total <= 0) throw IllegalStateException("Deneysel Yahoo yedeğinden sembol evreni alınamadı.")
        mark(fallback.id, "${fallback.displayName} • DENEYSEL/YEDEK")
        return report
    }

    private fun allowExperimentalFallback(): Boolean =
        settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled

    private fun mark(providerId: String, label: String) {
        settings.lastProviderId = providerId
        settings.lastProviderLabel = label
        settings.lastProviderTimestamp = System.currentTimeMillis()
    }
}
