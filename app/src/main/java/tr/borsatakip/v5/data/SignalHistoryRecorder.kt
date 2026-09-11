package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.HistoryRecordResult
import tr.borsatakip.v5.scan.HistoryRecorder
import tr.borsatakip.v5.scan.ScanState
import tr.borsatakip.v5.scan.ScanStatus

/** Merkezi tarama -> history köprüsü. UI katmanına bağlı değildir. */
class SignalHistoryRecorder(context: Context) : HistoryRecorder {
    private val store = SignalHistoryStore(context.applicationContext)

    override suspend fun record(state: ScanState): HistoryRecordResult {
        val run = state.scanRun ?: return HistoryRecordResult()
        val eligible = state.status == ScanStatus.COMPLETED &&
            state.successful > 0 &&
            (run.status == ScanRunStatus.COMPLETE || run.status == ScanRunStatus.PARTIAL)
        if (!eligible) return HistoryRecordResult()

        return try {
            HistoryRecordResult(inserted = store.recordScan(run, state.results))
        } catch (t: Throwable) {
            HistoryRecordResult(errorMessage = t.message ?: "Sinyal geçmişi kaydı başarısız")
        }
    }
}
