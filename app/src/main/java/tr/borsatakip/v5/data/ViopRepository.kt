package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.ViopContract

class ViopRepository(context: Context) {
    private val settings = SettingsStore(context)
    private val local = ViopLocalStore(context)
    private val backend = BackendProvider(context)

    fun loadLocal(): List<ViopContract> = local.load()

    fun addManual(contract: ViopContract): Result<Unit> = local.add(contract)

    fun removeManual(symbol: String) = local.remove(symbol)

    /**
     * V5.1.25 üretim mimarisi: HTTPS backend + manuel kayıtlar.
     * TradingView VİOP veri kaynağı değildir ve hiçbir katman sahte fiyat üretmez.
     */
    suspend fun refresh(): Pair<List<ViopContract>, String> {
        val localItems = local.load()
        if (!settings.baseUrl.startsWith("https://")) {
            return localItems to
                "Üretim VİOP backend'i yapılandırılmamış. Yalnız manuel kayıtlar gösteriliyor; TradingView veri kaynağı değildir."
        }

        val remote = backend.loadViop()
        if (remote.isSuccess) {
            val remoteItems = remote.getOrDefault(emptyList())
            if (remoteItems.isNotEmpty()) {
                return merge(localItems, remoteItems) to
                    "Üretim backend VİOP kaynağı: ${remoteItems.size} sözleşme."
            }
            return localItems to
                "Üretim backend VİOP sözleşmesi döndürmedi. Yalnız manuel kayıtlar gösteriliyor."
        }

        val message = remote.exceptionOrNull()?.message ?: "Backend VİOP verisi alınamadı."
        return localItems to "$message • TradingView fallback kullanılmadı."
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
