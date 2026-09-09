package tr.borsatakip.v5.scan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock

enum class ScanStatus { IDLE, RUNNING, COMPLETED, ERROR, CANCELLED }

data class ScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val progress: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    val successful: Int = 0,
    val skipped: Int = 0,
    val results: List<Opportunity> = emptyList(),
    val errorMessage: String? = null
)

/**
 * BIST taramasının Activity'den bağımsız çalışma motoru.
 * Network/provider işi provider katmanında IO'da, teknik analiz ise Default dispatcher'da yapılır.
 * Tek bir semboldeki analiz hatası diğer sembollerin çalışmasını durdurmaz.
 */
class BistScanner(private val provider: MarketDataProvider) {

    suspend fun scan(onState: (ScanState) -> Unit): ScanState {
        Log.i(TAG, "[BIST_SCAN] START")
        var total = 0
        var processed = 0
        var fetched = 0

        onState(ScanState(status = ScanStatus.RUNNING))

        return try {
            val stocks = provider.scan { done, providerTotal ->
                processed = done.coerceAtLeast(0)
                total = providerTotal.coerceAtLeast(0)
                val progress = safeProgress(processed, total)
                Log.d(TAG, "[BIST_SCAN] PROGRESS=$processed/$total")
                onState(
                    ScanState(
                        status = ScanStatus.RUNNING,
                        progress = progress,
                        processed = processed,
                        total = total,
                        successful = fetched,
                        skipped = (processed - fetched).coerceAtLeast(0)
                    )
                )
            }

            fetched = stocks.size
            if (total == 0) {
                val error = ScanState(
                    status = ScanStatus.ERROR,
                    progress = 0,
                    processed = 0,
                    total = 0,
                    successful = 0,
                    skipped = 0,
                    errorMessage = "BIST sembol listesi alınamadı."
                )
                Log.e(TAG, "[BIST_SCAN] ERROR empty universe")
                onState(error)
                return error
            }

            val opportunities = analyzeSafely(stocks)
            val skipped = (total - fetched).coerceAtLeast(0) + (fetched - opportunities.analyzedCount).coerceAtLeast(0)
            val finalState = ScanState(
                status = ScanStatus.COMPLETED,
                progress = 100,
                processed = total,
                total = total,
                successful = opportunities.analyzedCount,
                skipped = skipped,
                results = opportunities.results
            )
            Log.i(TAG, "[BIST_SCAN] COMPLETE total=$total success=${finalState.successful} skipped=$skipped results=${finalState.results.size}")
            onState(finalState)
            finalState
        } catch (ce: CancellationException) {
            val cancelled = ScanState(
                status = ScanStatus.CANCELLED,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                successful = fetched,
                skipped = (processed - fetched).coerceAtLeast(0),
                errorMessage = "Tarama kullanıcı tarafından durduruldu."
            )
            Log.i(TAG, "[BIST_SCAN] CANCELLED")
            onState(cancelled)
            throw ce
        } catch (t: Throwable) {
            val message = when {
                t.message?.contains("tanımlı değil", ignoreCase = true) == true -> "Canlı veri sağlayıcısı yapılandırılmamış."
                t.message?.contains("sembol", ignoreCase = true) == true -> "BIST sembol listesi alınamadı."
                else -> t.message ?: "Tarama başlatılamadı."
            }
            val error = ScanState(
                status = ScanStatus.ERROR,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                successful = fetched,
                skipped = (processed - fetched).coerceAtLeast(0),
                errorMessage = message
            )
            Log.e(TAG, "[BIST_SCAN] ERROR $message", t)
            onState(error)
            error
        }
    }

    private data class AnalysisResult(val results: List<Opportunity>, val analyzedCount: Int)

    private suspend fun analyzeSafely(stocks: List<Stock>): AnalysisResult = withContext(Dispatchers.Default) {
        supervisorScope {
            val analyzed = stocks.map { stock ->
                async {
                    try {
                        Log.d(TAG, "[BIST_SCAN] ANALYSIS_START ${stock.symbol}")
                        val result = OpportunityEngine.score(stock)
                        Log.d(TAG, "[BIST_SCAN] ANALYSIS_COMPLETE ${stock.symbol}")
                        Pair(true, result)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "[BIST_SCAN] SYMBOL_SKIPPED ${stock.symbol}: ${t.message}")
                        Pair(false, null)
                    }
                }
            }.awaitAll()

            val validResults = analyzed.mapNotNull { it.second }.sortedByDescending { it.score }
            AnalysisResult(validResults, analyzed.count { it.first })
        }
    }

    companion object {
        const val TAG = "BIST_SCAN"

        fun safeProgress(processed: Int, total: Int): Int {
            if (total <= 0) return 0
            return ((processed.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
        }
    }
}
