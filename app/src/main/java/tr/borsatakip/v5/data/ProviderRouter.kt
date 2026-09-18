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

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        if (!settings.baseUrl.startsWith("https://")) {
            if (!allowExperimentalFallback()) {
                throw IllegalStateException(
                    "Üretim veri sağlayıcısı yapılandırılmamış. Ayarlar'da gerçek HTTPS backend tanımlayın veya yalnız test için deneysel sağlayıcı modunu açın."
                )
            }
            return fallbackScan(onProgress)
        }

        var primaryFailure: Throwable? = null
        val primaryResult = try {
            primary.scan(onProgress)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            primaryFailure = t
            emptyList()
        }
        if (primaryResult.isNotEmpty()) {
            mark(primary.id, primaryResult.firstOrNull()?.source ?: primary.displayName)
            return primaryResult
        }

        if (!allowExperimentalFallback()) {
            val detail = primaryFailure?.message?.takeIf { it.isNotBlank() }
                ?: "Üretim backend'i veri döndürmedi."
            throw IllegalStateException("$detail Deneysel fallback kapalı.", primaryFailure)
        }

        return try {
            fallbackScan(onProgress)
        } catch (ce: CancellationException) {
            throw ce
        } catch (fallbackFailure: Throwable) {
            val primaryDetail = primaryFailure?.message?.takeIf { it.isNotBlank() }
            val fallbackDetail = fallbackFailure.message?.takeIf { it.isNotBlank() }
                ?: "Deneysel fallback başarısız."
            val combined = buildString {
                if (primaryDetail != null) append("Production provider: $primaryDetail ")
                append("Deneysel provider: $fallbackDetail")
            }
            throw IllegalStateException(combined, fallbackFailure)
        }
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

    private suspend fun fallbackScan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val fallbackResult = fallback.scan(onProgress)
        if (fallbackResult.isEmpty()) {
            throw IllegalStateException("Deneysel Yahoo yedeğinden kullanılabilir OHLCV verisi alınamadı.")
        }
        mark(fallback.id, "${fallback.displayName} • DENEYSEL/YEDEK")
        return fallbackResult
    }

    private fun allowExperimentalFallback(): Boolean =
        settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled

    private fun mark(providerId: String, label: String) {
        settings.lastProviderId = providerId
        settings.lastProviderLabel = label
        settings.lastProviderTimestamp = System.currentTimeMillis()
    }
}
