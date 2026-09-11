package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Stock

/** Veri sağlayıcılarından bağımsız ortak sözleşme. */
interface MarketDataProvider {
    val id: String
    val displayName: String

    /**
     * Geriye dönük uyumluluk için korunur. Yeni tarama motoru scanDetailed() kullanır.
     */
    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock>

    /**
     * Her sembol için terminal provider sonucu üretir. Gerçek sağlayıcılar bunu override eder.
     * Varsayılan uygulama yalnız eski/test sağlayıcılarını desteklemek içindir.
     */
    suspend fun scanDetailed(
        onProgress: (result: ProviderSymbolResult, done: Int, total: Int) -> Unit
    ): ProviderScanReport {
        var lastDone = 0
        var lastTotal = 0
        val stocks = scan { done, total ->
            lastDone = done
            lastTotal = total
        }
        val results = mutableListOf<ProviderSymbolResult>()
        stocks.forEachIndexed { index, stock ->
            val result = ProviderSymbolResult(
                symbol = stock.symbol,
                status = ProviderSymbolStatus.SUCCESS,
                stock = stock
            )
            results += result
            onProgress(result, index + 1, maxOf(lastTotal, stocks.size))
        }
        val missing = (lastTotal - results.size).coerceAtLeast(0)
        repeat(missing) { index ->
            val result = ProviderSymbolResult(
                symbol = "BILINMEYEN_${index + 1}",
                status = ProviderSymbolStatus.UNKNOWN_ERROR,
                errorMessage = "Eski sağlayıcı terminal hata nedeni döndürmedi."
            )
            results += result
            onProgress(result, results.size, lastTotal)
        }
        return ProviderScanReport(total = maxOf(lastTotal, lastDone, results.size), results = results)
    }

    suspend fun fetchOne(symbol: String): Stock?
}
