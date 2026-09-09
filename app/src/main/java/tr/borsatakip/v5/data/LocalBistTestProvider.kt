package tr.borsatakip.v5.data

import kotlinx.coroutines.delay
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import kotlin.math.sin

/**
 * V5.1.5 DEBUG BIST motor testi için tamamen yerel ve deterministik veri sağlayıcısı.
 * İnternet, WebSocket, backend veya Yahoo kullanmaz.
 */
class LocalBistTestProvider : MarketDataProvider {
    override val id: String = "local_bist_test"
    override val displayName: String = "Yerel BIST Test Verisi"

    val symbols: List<String> = listOf(
        "THYAO", "ASELS", "GARAN", "AKBNK", "EREGL",
        "SISE", "TUPRS", "FROTO", "KCHOL", "SAHOL"
    )

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val out = ArrayList<Stock>(symbols.size)
        onProgress(0, symbols.size)
        symbols.forEachIndexed { index, symbol ->
            delay(70)
            out += buildStock(symbol, index)
            onProgress(index + 1, symbols.size)
        }
        return out.toList()
    }

    override suspend fun fetchOne(symbol: String): Stock? {
        val normalized = symbol.trim().uppercase()
        val index = symbols.indexOf(normalized)
        if (index < 0) return null
        return buildStock(normalized, index)
    }

    private fun buildStock(symbol: String, index: Int): Stock {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val bullish = index % 2 == 0
        val base = 42.0 + index * 9.0
        val candles = ArrayList<Candle>(260)

        for (i in 0 until 260) {
            val slope = if (bullish) 0.11 + index * 0.003 else -(0.065 + index * 0.002)
            val trend = slope * i
            val wave = sin((i + index) / 7.0) * (0.55 + index * 0.025)
            val close = (base + trend + wave).coerceAtLeast(5.0)
            val open = close * (1.0 + sin(i / 4.5) * 0.0025)
            val high = maxOf(open, close) * 1.009
            val low = minOf(open, close) * 0.991
            val volumeBoost = if (i >= 245) 1.7 else 1.0
            val volume = (1_300_000.0 + index * 180_000.0 + i * 2_800.0) * volumeBoost
            candles += Candle(
                timestamp = now - (259L - i) * day,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
        }

        return Stock(
            symbol = symbol,
            companyName = "$symbol Test Verisi",
            candles = candles.toList(),
            source = displayName,
            dataTimestamp = candles.last().timestamp
        )
    }
}
