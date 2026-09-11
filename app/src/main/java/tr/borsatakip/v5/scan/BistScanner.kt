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
    /** İşleme ilerlemesi: processed / total. Başarı yüzdesi değildir. */
    val progress: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    /** Geçerli bir Opportunity sonucu üreten sembol sayısı. */
    val successful: Int = 0,
    /** Veri çekilemeyen veya analiz sonucu üretilemeyen sembol sayısı. */
    val skipped: Int = 0,
    /** Sonuç üretse bile production/realtime bütünlük kapısından geçmeyen kayıt sayısı. */
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
            scanRunId, scanStartedAt, completedAt, provider.id, status, count, errors
        )

        Log.i(TAG, "[BIST_SCAN] START id=$scanRunId provider=${provider.id}")
        onState(ScanState(status = ScanStatus.RUNNING, scanRun = run(ScanRunStatus.STARTED)))

        return try {
            val stocks = provider.scan { done, providerTotal ->
                processed = done.coerceAtLeast(0)
                total = providerTotal.coerceAtLeast(0)
                onState(
                    ScanState(
                        status = ScanStatus.RUNNING,
                        progress = safeProgress(processed, total),
                        processed = processed,
                        total = total,
                        // Fetch aşamasında henüz analiz başarısı bilinmez; bunu başarı sayısı gibi göstermiyoruz.
                        successful = 0,
                        skipped = 0,
                        scanRun = run(ScanRunStatus.STARTED)
                    )
                )
            }

            fetched = stocks.size
            if (total == 0) {
                val completed = System.currentTimeMillis()
                return ScanState(
                    status = ScanStatus.ERROR,
                    errorMessage = "BIST sembol listesi alınamadı.",
                    scanRun = run(ScanRunStatus.FAILED, completed, errors = 1)
                ).also(onState)
            }

            val analyzed = analyzeSafely(stocks)
            val completed = System.currentTimeMillis()
            val traced = analyzed.results.map { opportunity ->
                opportunity.copy(
                    scanStartedAt = scanStartedAt,
                    scanCompletedAt = completed,
                    scanRunId = scanRunId,
                    snapshot = opportunity.snapshot?.copy(scanRunId = scanRunId)
                )
            }

            val successful = traced.size
            val structuralSkipped = (total - fetched).coerceAtLeast(0) + (fetched - analyzed.analyzedCount).coerceAtLeast(0)
            val productionIntegrityWarnings = analyzed.integrityRejected
            val errorCount = structuralSkipped + productionIntegrityWarnings

            val runStatus = when {
                successful == total && structuralSkipped == 0 && productionIntegrityWarnings == 0 -> ScanRunStatus.COMPLETE
                successful > 0 -> ScanRunStatus.PARTIAL
                else -> ScanRunStatus.FAILED
            }

            val finalState = ScanState(
                status = ScanStatus.COMPLETED,
                progress = 100, // yalnızca döngünün tamamlandığını gösterir
                processed = total,
                total = total,
                successful = successful,
                skipped = structuralSkipped,
                integrityRejected = productionIntegrityWarnings,
                results = traced,
                errorMessage = if (successful == 0) "Hiçbir sembol geçerli analiz sonucu üretmedi." else null,
                scanRun = run(runStatus, completed, successful, errorCount)
            )
            Log.i(
                TAG,
                "[BIST_SCAN] ${runStatus.name} id=$scanRunId processed=$total/$total successful=$successful " +
                    "skipped=$structuralSkipped integrityWarning=$productionIntegrityWarnings"
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
                successful = 0,
                skipped = 0,
                errorMessage = "Tarama kullanıcı tarafından durduruldu.",
                scanRun = run(ScanRunStatus.PARTIAL, completed)
            )
            onState(cancelled)
            throw ce
        } catch (t: Throwable) {
            val completed = System.currentTimeMillis()
            val message = when {
                t.message?.contains("tanımlı değil", true) == true -> "Canlı veri sağlayıcısı yapılandırılmamış."
                t.message?.contains("sembol", true) == true -> "BIST sembol listesi alınamadı."
                else -> t.message ?: "Tarama başlatılamadı."
            }
            val error = ScanState(
                status = ScanStatus.ERROR,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                successful = 0,
                skipped = 0,
                errorMessage = message,
                scanRun = run(ScanRunStatus.FAILED, completed, errors = 1)
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

    /**
     * Teknik olarak analiz edilebilir gecikmeli/EOD veri kaydı tamamen yok edilmez; OpportunityEngine
     * onu WATCH/REJECTED olarak etiketleyebilir. Bu kayıtlar sonuç listesinde kalabilir fakat production
     * bütünlük uyarısı nedeniyle ScanRun COMPLETE olamaz. Malformed/bozuk/yetersiz veri ise sonuç üretmeden atlanır.
     */
    private suspend fun analyzeSafely(stocks: List<Stock>): AnalysisResult = withContext(Dispatchers.Default) {
        supervisorScope {
            data class Row(val opportunity: Opportunity?, val accepted: Boolean)
            val analyzed = stocks.map { stock ->
                async {
                    val verdict = RealTimeIntegrityPolicy.validate(stock)
                    if (!verdict.accepted) Log.w(TAG, "[BIST_SCAN] INTEGRITY_${stock.symbol}: ${verdict.reason}")
                    try {
                        Row(OpportunityEngine.score(stock), verdict.accepted)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "[BIST_SCAN] SYMBOL_SKIPPED ${stock.symbol}: ${t.message}")
                        Row(null, false)
                    }
                }
            }.awaitAll()

            val results = analyzed.mapNotNull { it.opportunity }
                .sortedWith(compareByDescending<Opportunity> { it.finalSignalScore }.thenBy { it.symbol })
            AnalysisResult(
                results = results,
                analyzedCount = analyzed.count { it.opportunity != null },
                integrityRejected = analyzed.count { it.opportunity != null && !it.accepted }
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
