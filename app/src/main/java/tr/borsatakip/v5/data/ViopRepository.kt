package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.ViopContract

class ViopRepository(context: Context) {
    private val settings = SettingsStore(context)
    private val local = ViopLocalStore(context)
    private val backend = BackendProvider(context)
    private val tradingView = TradingViewViopProvider()

    fun loadLocal(): List<ViopContract> = local.load()

    fun addManual(contract: ViopContract): Result<Unit> = local.add(contract)

    fun removeManual(symbol: String) = local.remove(symbol)

    /**
     * Üretim önceliği: HTTPS backend -> yalnız açıkça etkinse deneysel TradingView -> manuel kayıtlar.
     * Hiçbir katman sahte fiyat üretmez.
     */
    suspend fun refresh(): Pair<List<ViopContract>, String> {
        val localItems = local.load()
        val hasBackend = settings.baseUrl.startsWith("https://")

        if (hasBackend) {
            val remote = backend.loadViop()
            if (remote.isSuccess && remote.getOrDefault(emptyList()).isNotEmpty()) {
                val remoteItems = remote.getOrDefault(emptyList())
                return merge(localItems, remoteItems) to
                    "Üretim backend VİOP kaynağı: ${remoteItems.size} sözleşme."
            }
            if (!settings.experimentalProvidersEnabled) {
                val message = remote.exceptionOrNull()?.message ?: "Backend VİOP verisi yok."
                return localItems to "$message • Deneysel TradingView fallback kapalı."
            }
        } else if (!settings.experimentalProvidersEnabled) {
            return localItems to
                "Üretim VİOP backend'i yapılandırılmamış. Deneysel TradingView modu kapalı; yalnız manuel kayıtlar gösteriliyor."
        }

        val tvResult = tradingView.load()
        if (tvResult.isSuccess) {
            val out = tvResult.getOrThrow()
            if (out.contracts.isNotEmpty()) {
                return merge(localItems, out.contracts) to
                    "DENEYSEL TradingView VİOP: ${out.message}"
            }
        }

        val tvMessage = tvResult.exceptionOrNull()?.message
            ?: tvResult.getOrNull()?.message
            ?: "TradingView VİOP veri alınamadı."
        return localItems to "$tvMessage • Sahte fiyat üretilmedi."
    }

    private fun merge(localItems: List<ViopContract>, remoteItems: List<ViopContract>): List<ViopContract> {
        val merged = LinkedHashMap<String, ViopContract>()
        localItems.forEach { merged[it.symbol.uppercase()] = it }
        remoteItems.forEach { r ->
            val key = r.symbol.uppercase()
            val manual = merged[key]
            merged[key] = if (manual != null) r.copy(isManual = true) else r
        }
        return merged.values.toList()
    }
}
