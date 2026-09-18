package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Stock

/**
 * Veri sağlayıcı katmanında her sembol için mutlaka üretilen sonuç.
 * null ile sessiz atlama yapılmaz; hata nedeni ve kaçıncı denemede oluştuğu korunur.
 */
enum class ProviderSymbolStatus {
    SUCCESS,
    TIMEOUT,
    RATE_LIMIT,
    HTTP_ERROR,
    NETWORK_ERROR,
    PARSE_ERROR,
    DATA_INSUFFICIENT,
    UNKNOWN_ERROR
}

data class ProviderSymbolResult(
    val symbol: String,
    val status: ProviderSymbolStatus,
    val stock: Stock? = null,
    val attempt: Int = 1,
    val httpCode: Int? = null,
    val errorMessage: String? = null
) {
    init {
        require(status != ProviderSymbolStatus.SUCCESS || stock != null) {
            "SUCCESS sonucu Stock içermelidir: $symbol"
        }
    }
}

data class ProviderScanReport(
    val total: Int,
    val results: List<ProviderSymbolResult>
) {
    val terminalCount: Int get() = results.size
    val successfulStocks: List<Stock>
        get() = results.filter { it.status == ProviderSymbolStatus.SUCCESS }.map { requireNotNull(it.stock) }
}
