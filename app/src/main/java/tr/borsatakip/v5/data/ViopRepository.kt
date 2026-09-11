package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.ViopContract

enum class ViopRunMode {
    PRODUCTION_BACKEND,
    EXPERIMENTAL_LOCAL,
    UNAVAILABLE
}

data class ViopRefreshResult(
    val items: List<ViopContract>,
    val mode: ViopRunMode,
    val message: String
)

class ViopRepository(context: Context) {
    private val settings = SettingsStore(context)
    private val local = ViopLocalStore(context)
    private val backend = BackendProvider(context)

    fun loadLocal(): List<ViopContract> = local.load()

    fun mode(): ViopRunMode = when {
        settings.baseUrl.startsWith("https://") -> ViopRunMode.PRODUCTION_BACKEND
        settings.experimentalProvidersEnabled -> ViopRunMode.EXPERIMENTAL_LOCAL
        else -> ViopRunMode.UNAVAILABLE
    }

    fun addExperimentalContract(symbol: String, underlying: String, expiry: String): Result<Unit> {
        val normalizedSymbol = symbol.trim().uppercase()
        val normalizedUnderlying = underlying.trim().uppercase()
        val normalizedExpiry = expiry.trim()
        return runCatching {
            require(normalizedSymbol.matches(Regex("[A-Z0-9_\\-]{3,40}"))) { "Geçerli bir sözleşme kodu girin." }
            require(normalizedUnderlying.matches(Regex("[A-Z0-9_\\.\\-]{2,30}"))) { "Geçerli bir dayanak kodu girin." }
            require(normalizedExpiry.matches(Regex("\\d{4}-\\d{2}"))) { "Vade YYYY-MM biçiminde olmalıdır." }
            local.add(
                ViopContract(
                    symbol = normalizedSymbol,
                    underlying = normalizedUnderlying,
                    expiry = normalizedExpiry,
                    providerId = "viop_experimental_manual",
                    providerLabel = "DENEYSEL • MANUEL",
                    isManual = true,
                    status = "PİYASA VERİSİ YOK • geliştirme kaydı",
                    dataTimestamp = System.currentTimeMillis()
                )
            ).getOrThrow()
        }
    }

    fun removeExperimentalContract(symbol: String) = local.remove(symbol)

    suspend fun refresh(): ViopRefreshResult {
        val localItems = local.load()
        return when (mode()) {
            ViopRunMode.PRODUCTION_BACKEND -> refreshProduction(localItems)
            ViopRunMode.EXPERIMENTAL_LOCAL -> ViopRefreshResult(
                items = localItems,
                mode = ViopRunMode.EXPERIMENTAL_LOCAL,
                message = if (localItems.isEmpty()) {
                    "DENEYSEL VİOP MODU hazır. Geliştirme için sözleşme metadata'sı ekleyin. Fiyat, hacim ve açık pozisyon uydurulmaz."
                } else {
                    "DENEYSEL VİOP MODU • ${localItems.size} yerel geliştirme kaydı. Piyasa verisi değildir."
                }
            )
            ViopRunMode.UNAVAILABLE -> ViopRefreshResult(
                items = localItems,
                mode = ViopRunMode.UNAVAILABLE,
                message = "VİOP backend yapılandırılmamış ve deneysel mod kapalı."
            )
        }
    }

    private suspend fun refreshProduction(localItems: List<ViopContract>): ViopRefreshResult {
        val remote = backend.loadViop()
        if (remote.isSuccess) {
            val remoteItems = remote.getOrDefault(emptyList())
            if (remoteItems.isNotEmpty()) {
                return ViopRefreshResult(
                    items = merge(localItems, remoteItems),
                    mode = ViopRunMode.PRODUCTION_BACKEND,
                    message = "Üretim backend VİOP kaynağı: ${remoteItems.size} sözleşme."
                )
            }
            return ViopRefreshResult(
                items = localItems,
                mode = ViopRunMode.PRODUCTION_BACKEND,
                message = "Üretim backend VİOP sözleşmesi döndürmedi. Yerel geliştirme kayıtları korunuyor."
            )
        }

        val message = remote.exceptionOrNull()?.message ?: "Backend VİOP verisi alınamadı."
        return ViopRefreshResult(
            items = localItems,
            mode = ViopRunMode.PRODUCTION_BACKEND,
            message = "$message • Sahte/TradingView VİOP fallback kullanılmadı."
        )
    }

    private fun merge(localItems: List<ViopContract>, remoteItems: List<ViopContract>): List<ViopContract> {
        val merged = LinkedHashMap<String, ViopContract>()
        localItems.forEach { merged[it.symbol.uppercase()] = it }
        remoteItems.forEach { r -> merged[r.symbol.uppercase()] = r }
        return merged.values.toList()
    }
}
