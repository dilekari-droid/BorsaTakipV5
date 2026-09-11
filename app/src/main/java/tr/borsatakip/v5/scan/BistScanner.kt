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
import tr.borsatakip.v5.data.ProviderSymbolResult
import tr.borsatakip.v5.data.ProviderSymbolStatus
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

enum class ScanStatus { IDLE, RUNNING, COMPLETED, ERROR, CANCELLED }

enum class SymbolTerminalStatus {
    SIGNAL,
    NO_SIGNAL,
    TIMEOUT,
    RATE_LIMIT,
    HTTP_ERROR,
    NETWORK_ERROR,
    PARSE_ERROR,
    DATA_INSUFFICIENT,
    INTEGRITY_REJECTED,
    ANALYSIS_ERROR,
    UNKNOWN_ERROR
}

data class SymbolTerminalResult(
    val symbol: String,
    val status: SymbolTerminalStatus,
    val opportunity: Opportunity? = null,
    val attempt: Int = 1,
    val httpCode: Int? = null,
    val errorMessage: String? = null
)

data class ScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val progress: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    val successful: Int = 0,
    val skipped: Int = 0,
    val dataReceived: Int = 0,
    val timeout: Int = 0,
    val rateLimited: Int = 0,
    val httpErrors: Int = 0,
    val networkErrors: Int = 0,
    val parseErrors: Int = 0,
    val dataInsufficient: Int = 0,
    val integrityRejected: Int = 0,
    val analysisErrors: Int = 0,
    val noSignal: Int = 0,
    val signalCount: Int = 0,
    val terminalResults: List<SymbolTerminalResult> = emptyList(),
    val results: List<Opportunity> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Her sembol terminal duruma ulaşır. null/filterNotNull ile sessiz atlama yapılmaz.
 * Provider hataları, veri bütünlüğü reddi, analiz hatası ve sinyal yok durumu ayrı tutulur.
 */
class BistScanner(private val provider: MarketDataProvider) {

    suspend fun scan(onState: (ScanState) -> Unit): ScanState {
        Log.i(TAG, "[BIST_SCAN] START TERMINAL_STATE_MODE")
        var total = 0
        var processed = 0
        val providerObserved = Collections.synchronizedList(mutableListOf<ProviderSymbolResult>())

        onState(ScanState(status = ScanStatus.RUNNING))

        return try {
            val report = provider.scanDetailed { result, done, providerTotal ->
                providerObserved += result
                processed = done.coerceAtLeast(0)
                total = providerTotal.coerceAtLeast(0)
                val snapshot = synchronized(providerObserved) { providerObserved.toList() }
                onState(providerPhaseState(snapshot, processed, total))
            }

            total = report.total
            if (total <= 0) {
                val error = ScanState(status = ScanStatus.ERROR, errorMessage = "BIST sembol listesi alınamadı.")
                onState(error)
                return error
            }

            val normalized = normalizeProviderResults(report.results, total)
            val fetchFailures = normalized.filter { it.status != ProviderSymbolStatus.SUCCESS }
                .map { it.toTerminal() }
            val stocks = normalized.filter { it.status == ProviderSymbolStatus.SUCCESS }
                .mapNotNull { it.stock }

            val terminalResults = Collections.synchronizedList(fetchFailures.toMutableList())
            val analyzedDone = AtomicInteger(0)
            val successfulDataCount = stocks.size

            val analysisResults = withContext(Dispatchers.Default) {
                supervisorScope {
                    stocks.map { stock ->
                        async {
                            val terminal = analyzeStock(stock)
                            terminalResults += terminal
                            val done = analyzedDone.incrementAndGet()
                            val snapshot = synchronized(terminalResults) { terminalResults.toList() }
                            val finished = fetchFailures.size + done
                            onState(buildState(
                                status = ScanStatus.RUNNING,
                                processed = finished,
                                total = total,
                                dataReceived = successfulDataCount,
                                terminals = snapshot
                            ))
                            terminal
                        }
                    }.awaitAll()
                }
            }

            val allTerminals = (fetchFailures + analysisResults).sortedBy { it.symbol }
            val opportunities = allTerminals.mapNotNull { it.opportunity }
                .sortedByDescending { it.finalSignalScore }

            val finalState = buildState(
                status = ScanStatus.COMPLETED,
                processed = total,
                total = total,
                dataReceived = successfulDataCount,
                terminals = allTerminals,
                results = opportunities
            )
            Log.i(TAG, "[BIST_SCAN] COMPLETE total=$total terminal=${allTerminals.size} signal=${finalState.signalCount} noSignal=${finalState.noSignal} timeout=${finalState.timeout} http=${finalState.httpErrors} rateLimit=${finalState.rateLimited} insufficient=${finalState.dataInsufficient} integrity=${finalState.integrityRejected} analysis=${finalState.analysisErrors}")
            onState(finalState)
            finalState
        } catch (ce: CancellationException) {
            val snapshot = synchronized(providerObserved) { providerObserved.toList().map { it.toTerminal() } }
            val cancelled = buildState(
                status = ScanStatus.CANCELLED,
                processed = processed,
                total = total,
                dataReceived = providerObserved.count { it.status == ProviderSymbolStatus.SUCCESS },
                terminals = snapshot,
                errorMessage = "Tarama kullanıcı tarafından durduruldu."
            )
            onState(cancelled)
            throw ce
        } catch (t: Throwable) {
            val message = when {
                t.message?.contains("tanımlı değil", ignoreCase = true) == true -> "Canlı veri sağlayıcısı yapılandırılmamış."
                t.message?.contains("sembol", ignoreCase = true) == true -> "BIST sembol listesi alınamadı."
                else -> t.message ?: "Tarama başlatılamadı."
            }
            val snapshot = synchronized(providerObserved) { providerObserved.toList().map { it.toTerminal() } }
            val error = buildState(
                status = ScanStatus.ERROR,
                processed = processed,
                total = total,
                dataReceived = providerObserved.count { it.status == ProviderSymbolStatus.SUCCESS },
                terminals = snapshot,
                errorMessage = message
            )
            Log.e(TAG, "[BIST_SCAN] ERROR $message", t)
            onState(error)
            error
        }
    }

    private fun analyzeStock(stock: Stock): SymbolTerminalResult {
        val verdict = RealTimeIntegrityPolicy.validate(stock)
        if (!verdict.accepted) {
            return SymbolTerminalResult(
                symbol = stock.symbol,
                status = SymbolTerminalStatus.INTEGRITY_REJECTED,
                errorMessage = verdict.reason
            )
        }
        return try {
            val opportunity = OpportunityEngine.score(stock)
            when {
                opportunity == null -> SymbolTerminalResult(
                    symbol = stock.symbol,
                    status = if (stock.candles.size < MIN_CANDLES) SymbolTerminalStatus.DATA_INSUFFICIENT else SymbolTerminalStatus.ANALYSIS_ERROR,
                    errorMessage = if (stock.candles.size < MIN_CANDLES) "Teknik analiz için mum sayısı yetersiz" else "Teknik analiz geçerli sonuç üretmedi"
                )
                opportunity.finalSignalScore < SIGNAL_THRESHOLD -> SymbolTerminalResult(
                    symbol = stock.symbol,
                    status = SymbolTerminalStatus.NO_SIGNAL,
                    opportunity = null,
                    errorMessage = "Nihai sinyal eşiği altında: ${opportunity.finalSignalScore}/100"
                )
                else -> SymbolTerminalResult(
                    symbol = stock.symbol,
                    status = SymbolTerminalStatus.SIGNAL,
                    opportunity = opportunity
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            SymbolTerminalResult(
                symbol = stock.symbol,
                status = SymbolTerminalStatus.ANALYSIS_ERROR,
                errorMessage = t.message ?: t.javaClass.simpleName
            )
        }
    }

    private fun providerPhaseState(results: List<ProviderSymbolResult>, processed: Int, total: Int): ScanState {
        val terminals = results.filter { it.status != ProviderSymbolStatus.SUCCESS }.map { it.toTerminal() }
        return buildState(
            status = ScanStatus.RUNNING,
            processed = processed,
            total = total,
            dataReceived = results.count { it.status == ProviderSymbolStatus.SUCCESS },
            terminals = terminals
        )
    }

    private fun buildState(
        status: ScanStatus,
        processed: Int,
        total: Int,
        dataReceived: Int,
        terminals: List<SymbolTerminalResult>,
        results: List<Opportunity> = terminals.mapNotNull { it.opportunity },
        errorMessage: String? = null
    ): ScanState {
        fun count(s: SymbolTerminalStatus) = terminals.count { it.status == s }
        val terminalCount = terminals.size
        val signal = count(SymbolTerminalStatus.SIGNAL)
        val noSignal = count(SymbolTerminalStatus.NO_SIGNAL)
        val successful = signal + noSignal
        val skipped = terminalCount - successful
        return ScanState(
            status = status,
            progress = safeProgress(processed, total),
            processed = processed,
            total = total,
            successful = successful,
            skipped = skipped.coerceAtLeast(0),
            dataReceived = dataReceived,
            timeout = count(SymbolTerminalStatus.TIMEOUT),
            rateLimited = count(SymbolTerminalStatus.RATE_LIMIT),
            httpErrors = count(SymbolTerminalStatus.HTTP_ERROR),
            networkErrors = count(SymbolTerminalStatus.NETWORK_ERROR),
            parseErrors = count(SymbolTerminalStatus.PARSE_ERROR),
            dataInsufficient = count(SymbolTerminalStatus.DATA_INSUFFICIENT),
            integrityRejected = count(SymbolTerminalStatus.INTEGRITY_REJECTED),
            analysisErrors = count(SymbolTerminalStatus.ANALYSIS_ERROR) + count(SymbolTerminalStatus.UNKNOWN_ERROR),
            noSignal = noSignal,
            signalCount = signal,
            terminalResults = terminals,
            results = results,
            errorMessage = errorMessage
        )
    }

    private fun normalizeProviderResults(results: List<ProviderSymbolResult>, total: Int): List<ProviderSymbolResult> {
        if (results.size >= total) return results.take(total)
        val out = results.toMutableList()
        repeat(total - results.size) { index ->
            out += ProviderSymbolResult(
                symbol = "BILINMEYEN_${index + 1}",
                status = ProviderSymbolStatus.UNKNOWN_ERROR,
                errorMessage = "Sağlayıcı terminal sonuç döndürmedi."
            )
        }
        return out
    }

    private fun ProviderSymbolResult.toTerminal(): SymbolTerminalResult {
        val mapped = when (status) {
            ProviderSymbolStatus.SUCCESS -> SymbolTerminalStatus.UNKNOWN_ERROR
            ProviderSymbolStatus.TIMEOUT -> SymbolTerminalStatus.TIMEOUT
            ProviderSymbolStatus.RATE_LIMIT -> SymbolTerminalStatus.RATE_LIMIT
            ProviderSymbolStatus.HTTP_ERROR -> SymbolTerminalStatus.HTTP_ERROR
            ProviderSymbolStatus.NETWORK_ERROR -> SymbolTerminalStatus.NETWORK_ERROR
            ProviderSymbolStatus.PARSE_ERROR -> SymbolTerminalStatus.PARSE_ERROR
            ProviderSymbolStatus.DATA_INSUFFICIENT -> SymbolTerminalStatus.DATA_INSUFFICIENT
            ProviderSymbolStatus.UNKNOWN_ERROR -> SymbolTerminalStatus.UNKNOWN_ERROR
        }
        return SymbolTerminalResult(symbol, mapped, attempt = attempt, httpCode = httpCode, errorMessage = errorMessage)
    }

    companion object {
        const val TAG = "BIST_SCAN"
        private const val MIN_CANDLES = 220
        private const val SIGNAL_THRESHOLD = 60

        fun safeProgress(processed: Int, total: Int): Int {
            if (total <= 0) return 0
            return ((processed.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
        }
    }
}
