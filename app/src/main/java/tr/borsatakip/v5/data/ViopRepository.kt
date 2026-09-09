package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.ViopContract

class ViopRepository(context: Context) {
    private val local = ViopLocalStore(context)
    private val backend = BackendProvider(context)
    private val tradingView = TradingViewViopProvider()

    fun loadLocal(): List<ViopContract> = local.load()

    fun addManual(contract: ViopContract): Result<Unit> = local.add(contract)

    fun removeManual(symbol: String) = local.remove(symbol)

    /**
     * Öncelik: TradingView VİOP (deneysel) -> isteğe bağlı backend -> manuel kayıtlar.
     * Hiçbir katman sahte fiyat üretmez.
     */
    suspend fun refresh(): Pair<List<ViopContract>, String> {
        val localItems = local.load()

        val tvResult = tradingView.load()
        if (tvResult.isSuccess) {
            val out = tvResult.getOrThrow()
            if (out.contracts.isNotEmpty()) {
                return merge(localItems, out.contracts) to out.message
            }
        }

        val remote = backend.loadViop()
        if (remote.isSuccess && remote.getOrDefault(emptyList()).isNotEmpty()) {
            val remoteItems = remote.getOrDefault(emptyList())
            return merge(localItems, remoteItems) to
                "TradingView VİOP veri üretmedi • backend yedeği: ${remoteItems.size} sözleşme."
        }

        val tvMessage = tvResult.exceptionOrNull()?.message
            ?: tvResult.getOrNull()?.message
            ?: "TradingView VİOP veri alınamadı."
        val backendMessage = remote.exceptionOrNull()?.message ?: "Backend verisi yok."
        return localItems to "$tvMessage • $backendMessage"
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
