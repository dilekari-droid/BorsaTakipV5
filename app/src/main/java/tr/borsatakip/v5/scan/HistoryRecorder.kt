package tr.borsatakip.v5.scan

/**
 * Tarama sonucunun kalıcı sinyal geçmişine aktarılmasını UI katmanından ayırır.
 * BistScanner bir HistoryRecorder olmadan oluşturulamaz; böylece taramayı hangi ekran
 * başlatırsa başlatsın COMPLETE/PARTIAL başarılı sonuçlar aynı merkezi akıştan geçer.
 */
interface HistoryRecorder {
    suspend fun record(state: ScanState): HistoryRecordResult
}

data class HistoryRecordResult(
    val inserted: Int = 0,
    val errorMessage: String? = null
)
