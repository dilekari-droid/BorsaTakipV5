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
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.Stock
import java.util.UUID

enum class ScanStatus { IDLE, RUNNING, COMPLETED, ERROR, CANCELLED }

data class ScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val progress: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    val successful: Int = 0,
    val skipped: Int = 0,
    val integrityRejected: Int = 0,
    val results: List<Opportunity> = emptyList(),
    val errorMessage: String? = null,
    val scanRun: ScanRun? = null
)

class BistScanner(private val provider: MarketDataProvider) {

    suspend fun scan(onState: (ScanState) -> Unit): ScanState {
        val scanStartedAt = System.currentTimeMillis()
        val scanRunId = UUID.randomUUID().toString()
        var total = 0
        var processed = 0
        var fetched = 0

        fun run(status: ScanRunStatus, completedAt: Long? = null, count: Int = fetched, errors: Int = 0) = ScanRun(
            scanRunId = scanRunId,
            scanStartedAt = scanStartedAt,
            scanCompletedAt = completedAt,
            providerId = provider.id,
            status = status,
            count = count,
            errorCount = errors
        )

        Log.i(TAG, "[BIST_SCAN] START id=$scanRunId provider=${provider.id}")
        onState(ScanState(status = ScanStatus.RUNNING, scanRun = run(ScanRunStatus.STARTED)))

        return try {
            val stocks = provider.scan { done, providerTotal ->
                processed = done.coerceAtLeast(0)
                total = providerTotal.coerceAtLeast(0)
                val progress = safeProgress(processed, total)
                onState(
                    ScanState(
                        status = ScanStatus.RUNNING,
                        progress = progress,
                        processed = processed,
                        total = total,
                        successful = fetched,
                        skipped = (processed - fetched).coerceAtLeast(0),
                        scanRun = run(ScanRunStatus.STARTED)
                    )
                )
            }

            fetched = stocks.size
            if (total == 0) {
                val completed = System.currentTimeMillis()
                val error = ScanState(
                    status = ScanStatus.ERROR,
                    errorMessage = "BIST sembol listesi alınamadı.",
                    scanRun = run(ScanRunStatus.FAILED, completedAt = completed, errors = 1)
                )
                onState(error)
                return error
            }

            val analyzed = analyzeSafely(stocks)
            if (stocks.isNotEmpty() && analyzed.analyzedCount == 0 && analyzed.integrityRejected > 0) {
                val completed = System.currentTimeMillis()
                val error = ScanState(
                    status = ScanStatus.ERROR,
                    progress = 100,
                    processed = total,
                    total = total,
                    successful = 0,
                    skipped = total,
                    integrityRejected = analyzed.integrityRejected,
                    errorMessage = "ANLIK VERİ DOĞRULANAMADI. Gecikmeli/eski/kanıtsız veriyle fırsat üretilmedi.",
                    scanRun = run(ScanRunStatus.FAILED, completedAt = completed, errors = analyzed.integrityRejected)
                )
                onState(error)
                return error
            }

            val completed = System.currentTimeMillis()
            val traced = analyzed.results.map { opportunity ->
                opportunity.copy(
                    scanStartedAt = scanStartedAt,
                    scanCompletedAt = completed,
                    scanRunId = scanRunId,
                    snapshot = opportunity.snapshot?.copy(scanRunId = scanRunId)
                )
            }
            val skipped = (total - fetched).coerceAtLeast(0) + (fetched - analyzed.analyzedCount).coerceAtLeast(0)
            val finalState = ScanState(
                status = ScanStatus.COMPLETED,
                progress = 100,
                processed = total,
                total = total,
                successful = analyzed.analyzedCount,
                skipped = skipped,
                integrityRejected = analyzed.integrityRejected,
                results = traced,
                scanRun = run(
                    if (skipped > 0) ScanRunStatus.PARTIAL else ScanRunStatus.COMPLETE,
                    completedAt = completed,
                    count = traced.size,
                    errors = skipped
                )
            )
            onState(finalState)
            finalState
        } catch (ce: CancellationException) {
            val completed = System.currentTimeMillis()
            val cancelled = ScanState(
                status = ScanStatus.CANCELLED,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                successful = fetched,
                skipped = (processed - fetched).coerceAtLeast(0),
                errorMessage = "Tarama kullanıcı tarafından durduruldu.",
                scanRun = run(ScanRunStatus.PARTIAL, completedAt = completed, errors = (processed - fetched).coerceAtLeast(0))
            )
            onState(cancelled)
            throw ce
        } catch (t: Throwable) {
            val completed = System.currentTimeMillis()
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
                errorMessage = message,
                scanRun = run(ScanRunStatus.FAILED, completedAt = completed, errors = 1)
            )
            Log.e(TAG, "[BIST_SCAN] ERROR $message", t)
            onState(error)
            error
        }
    }

    private data class AnalysisResult(
        val results: List<Opportunity>,
        val analyzedCount: Int,
        val integrityRejected: Int
    )

    private suspend fun analyzeSafely(stocks: List<Stock>): AnalysisResult = withContext(Dispatchers.Default) {
        supervisorScope {
            val analyzed = stocks.map { stock ->
                async {
                    val verdict = RealTimeIntegrityPolicy.validate(stock)
                    if (!verdict.accepted) {
                        Log.w(TAG, "[BIST_SCAN] REALTIME_REJECT ${stock.symbol}: ${verdict.reason}")
                        return@async Triple(false, true, null)
                    }
                    try {
                        val result = OpportunityEngine.score(stock)
                        Triple(true, false, result)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "[BIST_SCAN] SYMBOL_SKIPPED ${stock.symbol}: ${t.message}")
                        Triple(false, false, null)
                    }
                }
            }.awaitAll()

            val validResults = analyzed.mapNotNull { it.third }
                .sortedWith(compareByDescending<Opportunity> { it.finalSignalScore }.thenBy { it.symbol })
            AnalysisResult(
                results = validResults,
                analyzedCount = analyzed.count { it.first },
                integrityRejected = analyzed.count { it.second }
            )
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
