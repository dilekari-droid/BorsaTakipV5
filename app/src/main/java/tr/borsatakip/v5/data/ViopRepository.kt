package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.ViopContract

class ViopRepository(context: Context) {
    private val local = ViopLocalStore(context)
    private val backend = BackendProvider(context)

    fun loadLocal(): List<ViopContract> = local.load()

    fun addManual(contract: ViopContract): Result<Unit> = local.add(contract)

    fun removeManual(symbol: String) = local.remove(symbol)

    suspend fun refresh(): Pair<List<ViopContract>, String> {
        val localItems = local.load()
        val remote = backend.loadViop()
        if (remote.isFailure) {
            val msg = remote.exceptionOrNull()?.message ?: "VİOP sağlayıcısından veri alınamadı."
            return localItems to msg
        }
        val remoteItems = remote.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, ViopContract>()
        localItems.forEach { merged[it.symbol.uppercase()] = it }
        remoteItems.forEach { r ->
            val key = r.symbol.uppercase()
            val manual = merged[key]
            merged[key] = if (manual != null) r.copy(isManual = true) else r
        }
        return merged.values.toList() to "${remoteItems.size} uzak, ${localItems.size} manuel sözleşme yüklendi."
    }
}
