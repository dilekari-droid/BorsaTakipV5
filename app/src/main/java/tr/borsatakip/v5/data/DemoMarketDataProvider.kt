package tr.borsatakip.v5.data

import kotlinx.coroutines.delay
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import kotlin.math.sin

/**
 * İnternetten tamamen bağımsız, deterministik yerel demo veri sağlayıcısı.
 * Gerçek piyasa verisi değildir ve yalnızca uygulamanın tarama/analiz akışını test eder.
 */
class DemoMarketDataProvider : MarketDataProvider {
    override val id: String = "demo_local"
    override val displayName: String = "Yerel Demo Veri"

    private val symbols = listOf("DEMO1", "DEMO2", "DEMO3", "DEMO4", "DEMO5", "DEMO6", "DEMO7", "DEMO8")

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val out = mutableListOf<Stock>()
        onProgress(0, symbols.size)
        symbols.forEachIndexed { index, symbol ->
            delay(90)
            out += buildStock(symbol, index)
            onProgress(index + 1, symbols.size)
        }
        return out
    }

    override suspend fun fetchOne(symbol: String): Stock? {
        val index = symbols.indexOf(symbol).takeIf { it >= 0 } ?: 0
        return buildStock(symbol, index)
    }

    private fun buildStock(symbol: String, index: Int): Stock {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val bullish = index % 2 == 0
        val base = 35.0 + index * 8.0
        val candles = ArrayList<Candle>(260)

        for (i in 0 until 260) {
            val trend = if (bullish) i * (0.10 + index * 0.004) else -i * (0.055 + index * 0.002)
            val wave = sin(i / 6.0 + index) * (0.45 + index * 0.03)
            val close = (base + trend + wave).coerceAtLeast(4.0)
            val open = close * (1.0 + sin(i / 3.5) * 0.003)
            val high = maxOf(open, close) * 1.008
            val low = minOf(open, close) * 0.992
            val volumeBoost = if (i > 245) 1.8 else 1.0
            val volume = (1_000_000.0 + index * 170_000.0 + i * 2_500.0) * volumeBoost
            candles += Candle(
                timestamp = now - (259 - i) * day,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
        }

        return Stock(
            symbol = symbol,
            companyName = "Demo Hisse ${index + 1}",
            candles = candles,
            source = displayName,
            dataTimestamp = candles.last().timestamp
        )
    }
}
