package tr.borsatakip.v5.scan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.data.LocalBistTestProvider
import tr.borsatakip.v5.model.Opportunity
import kotlin.coroutines.coroutineContext

object SafeScanStatus {
    const val IDLE = "IDLE"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val ERROR = "ERROR"
    const val CANCELLED = "CANCELLED"
}

data class SafeScanState(
    val status: String = SafeScanStatus.IDLE,
    val progress: Int = 0,
    val processed: Int = 0,
    val total: Int = 10,
    val successful: Int = 0,
    val skipped: Int = 0,
    val lastSymbol: String = "-",
    val errors: Int = 0,
    val results: List<Opportunity> = emptyList(),
    val message: String = "Hazır"
)

/**
 * V5.1.5 için provider'dan tamamen ayrılmış, sıralı ve deterministik test tarama motoru.
 * Enum/WhenMappings kullanmaz. Scanner mutable listesini UI ile paylaşmaz.
 */
class SafeBistScanner(
    private val provider: LocalBistTestProvider = LocalBistTestProvider()
) {
    suspend fun scan(onState: (SafeScanState) -> Unit): SafeScanState {
        val symbols = provider.symbols.toList()
        var processed = 0
        var successful = 0
        var skipped = 0
        var errors = 0
        val results = ArrayList<Opportunity>(symbols.size)

        emit(onState, SafeScanState(status = SafeScanStatus.RUNNING, total = symbols.size, message = "Test taraması başladı"))

        try {
            for (symbol in symbols) {
                coroutineContext.ensureActive()
                CrashDiagnostics.stage = "FETCH"
                CrashDiagnostics.symbol = symbol
                Log.i(TAG, "[BIST_SCAN] SYMBOL=$symbol DATA_REQUEST")

                val stock = provider.fetchOne(symbol)
                if (stock == null) {
                    processed++; skipped++; errors++
                    emit(onState, state(processed, symbols.size, successful, skipped, symbol, errors, results, "Veri alınamadı — bu hisse atlandı."))
                    continue
                }

                val opportunity = withContext(Dispatchers.Default) {
                    CrashDiagnostics.stage = "ANALYSIS"
                    OpportunityEngine.score(stock)
                }

                processed++
                if (opportunity != null) {
                    results += opportunity
                    successful++
                    Log.i(TAG, "[BIST_SCAN] ANALYSIS_COMPLETE $symbol")
                } else {
                    skipped++
                    errors++
                    Log.w(TAG, "[BIST_SCAN] SYMBOL_SKIPPED $symbol")
                }

                emit(onState, state(processed, symbols.size, successful, skipped, symbol, errors, results, "Tarama sürüyor"))
            }

            CrashDiagnostics.stage = "COMPLETE"
            val finalState = SafeScanState(
                status = SafeScanStatus.COMPLETED,
                progress = 100,
                processed = symbols.size,
                total = symbols.size,
                successful = successful,
                skipped = skipped,
                lastSymbol = symbols.lastOrNull() ?: "-",
                errors = errors,
                results = results.toList().sortedByDescending { it.score },
                message = "Test taraması tamamlandı"
            )
            emit(onState, finalState)
            return finalState
        } catch (ce: CancellationException) {
            CrashDiagnostics.stage = "CANCELLED"
            val cancelled = SafeScanState(
                status = SafeScanStatus.CANCELLED,
                progress = safeProgress(processed, symbols.size),
                processed = processed,
                total = symbols.size,
                successful = successful,
                skipped = skipped,
                lastSymbol = CrashDiagnostics.symbol,
                errors = errors,
                results = results.toList(),
                message = "Tarama durduruldu"
            )
            emit(onState, cancelled)
            return cancelled
        }
    }

    private fun state(processed:Int,total:Int,successful:Int,skipped:Int,last:String,errors:Int,results:List<Opportunity>,message:String) =
        SafeScanState(SafeScanStatus.RUNNING, safeProgress(processed,total), processed,total,successful,skipped,last,errors,results.toList(),message)

    private suspend fun emit(onState: (SafeScanState) -> Unit, state: SafeScanState) {
        withContext(Dispatchers.Main.immediate) { onState(state) }
    }

    companion object {
        private const val TAG = "BIST_SCAN"
        fun safeProgress(processed: Int, total: Int): Int = if (total <= 0) 0 else ((processed.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
    }
}

object CrashDiagnostics {
    @Volatile var stage: String = "IDLE"
    @Volatile var symbol: String = "-"
}
