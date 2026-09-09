package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Stock

/** Veri sağlayıcılarından bağımsız ortak sözleşme. */
interface MarketDataProvider {
    val id: String
    val displayName: String
    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock>
    suspend fun fetchOne(symbol: String): Stock?
}
