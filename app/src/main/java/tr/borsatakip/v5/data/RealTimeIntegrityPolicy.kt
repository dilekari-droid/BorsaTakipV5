package tr.borsatakip.v5.data

import tr.borsatakip.v5.analysis.CandleSeriesValidator
import tr.borsatakip.v5.model.Stock

/**
 * Üretim ana kuralı: doğrulanmamış, gecikmeli, eski veya güncel seansı içermeyen veri
 * fırsat/sinyal üretiminde KULLANILMAZ. Sistem şüphede kalırsa fail-closed davranır.
 */
object RealTimeIntegrityPolicy {
    const val MAX_DATA_AGE_MS = 60_000L
    const val MAX_DECLARED_DELAY_SECONDS = 5
    private const val MAX_FUTURE_CLOCK_SKEW_MS = 15_000L

    data class Verdict(val accepted: Boolean, val reason: String)

    fun validate(stock: Stock, nowMs: Long = System.currentTimeMillis()): Verdict {
        if (!stock.isRealtime) {
            return Verdict(false, "Sağlayıcı veriyi gerçek zamanlı olarak doğrulamadı.")
        }
        if (!stock.currentSessionIncluded) {
            return Verdict(false, "Güncel işlem seansı OHLCV verisine dahil değil.")
        }
        val delay = stock.delaySeconds
            ?: return Verdict(false, "Sağlayıcı gecikme bilgisini bildirmedi.")
        if (delay < 0 || delay > MAX_DECLARED_DELAY_SECONDS) {
            return Verdict(false, "Sağlayıcı gecikmesi gerçek zaman eşiğini aşıyor: ${delay} sn.")
        }
        if (stock.dataTimestamp <= 0L) {
            return Verdict(false, "Piyasa veri zamanı yok.")
        }
        val age = nowMs - stock.dataTimestamp
        if (age < -MAX_FUTURE_CLOCK_SKEW_MS) {
            return Verdict(false, "Piyasa veri zamanı cihaz saatinden ileride; saat bütünlüğü doğrulanamadı.")
        }
        if (age > MAX_DATA_AGE_MS) {
            return Verdict(false, "Piyasa verisi güncel değil: ${age / 1000L} sn yaşında.")
        }
        if (stock.candles.size < 220) {
            return Verdict(false, "Teknik analiz için en az 220 OHLCV mumu gerekli.")
        }
        val validation = CandleSeriesValidator.validate(stock.candles)
        if (validation.candles.size < 220) {
            return Verdict(false, "Doğrulama sonrasında yeterli OHLCV mumu kalmadı.")
        }
        if (validation.inputIssueCount > 0) {
            return Verdict(false, "Gerçek zamanlı OHLCV serisinde bozuk, tekrarlı veya sırasız kayıt bulundu.")
        }
        val last = stock.candles.lastOrNull()
            ?: return Verdict(false, "OHLCV verisi yok.")
        if (listOf(last.open, last.high, last.low, last.close, last.volume).any { !it.isFinite() }) {
            return Verdict(false, "Son OHLCV kaydı geçersiz.")
        }
        if (last.close <= 0.0 || last.high < last.low || last.volume < 0.0) {
            return Verdict(false, "Son OHLCV kaydı piyasa kurallarına uymuyor.")
        }
        return Verdict(true, "ANLIK VERİ DOĞRULANDI")
    }
}
