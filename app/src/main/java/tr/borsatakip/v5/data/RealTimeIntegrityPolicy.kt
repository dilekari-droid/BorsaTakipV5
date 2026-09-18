package tr.borsatakip.v5.data

import android.os.SystemClock
import tr.borsatakip.v5.model.Stock

/**
 * Fail-closed gerçek-zaman bütünlük kapısı.
 * exchangeTimestamp piyasa/kaynak zamanıdır; receivedAt uygulamanın alma zamanıdır.
 * Tazelik, alma anındaki piyasa-zaman farkı + monotonic elapsedRealtime ile geçen süre üzerinden ölçülür;
 * çalışma sırasında cihaz duvar saatinin ileri/geri alınması tazelik hesabını bozmaz.
 */
object RealTimeIntegrityPolicy {
    const val MAX_DATA_AGE_MS = 60_000L
    const val MAX_DECLARED_DELAY_SECONDS = 5
    private const val MAX_FUTURE_CLOCK_SKEW_MS = 15_000L

    data class Verdict(val accepted:Boolean, val reason:String, val measuredAgeMs:Long? = null)

    fun validate(stock:Stock, nowElapsed:Long = SystemClock.elapsedRealtime()):Verdict {
        if (!stock.isRealtime) return Verdict(false, "Sağlayıcı veriyi gerçek zamanlı olarak doğrulamadı.")
        if (!stock.currentSessionIncluded) return Verdict(false, "Güncel işlem seansı OHLCV verisine dahil değil.")
        val delay = stock.delaySeconds ?: return Verdict(false, "Sağlayıcı gecikme bilgisini bildirmedi.")
        if (delay !in 0..MAX_DECLARED_DELAY_SECONDS) return Verdict(false, "Sağlayıcı gecikmesi gerçek zaman eşiğini aşıyor: $delay sn.")
        if (stock.exchangeTimestamp <= 0L) return Verdict(false, "Piyasa veri zamanı yok.")
        val marketPrice = stock.marketPrice ?: return Verdict(false, "Anlık quote fiyatı yok.")
        if (!marketPrice.isFinite() || marketPrice <= 0.0) return Verdict(false, "Anlık quote fiyatı geçersiz.")
        if (stock.receivedAt <= 0L || stock.receivedElapsedRealtime <= 0L) return Verdict(false, "Veri alma zamanı doğrulanamadı.")

        val ageAtReceipt = stock.receivedAt - stock.exchangeTimestamp
        if (ageAtReceipt < -MAX_FUTURE_CLOCK_SKEW_MS) return Verdict(false, "Piyasa veri zamanı alma zamanından ileride; saat bütünlüğü doğrulanamadı.")
        val elapsedSinceReceipt = (nowElapsed - stock.receivedElapsedRealtime).coerceAtLeast(0L)
        val measuredAge = ageAtReceipt.coerceAtLeast(0L) + elapsedSinceReceipt
        if (measuredAge > MAX_DATA_AGE_MS) return Verdict(false, "Piyasa verisi güncel değil: ${measuredAge / 1000L} sn ölçülen yaş.", measuredAge)

        if (stock.candles.size < 220) return Verdict(false, "Teknik analiz için en az 220 OHLCV mumu gerekli.", measuredAge)
        val last = stock.candles.lastOrNull() ?: return Verdict(false, "OHLCV verisi yok.", measuredAge)
        if (listOf(last.open,last.high,last.low,last.close,last.volume).any { !it.isFinite() }) return Verdict(false, "Son OHLCV kaydı geçersiz.", measuredAge)
        if (last.close <= 0.0 || last.high < last.low || last.volume < 0.0) return Verdict(false, "Son OHLCV kaydı piyasa kurallarına uymuyor.", measuredAge)
        return Verdict(true, "ANLIK VERİ DOĞRULANDI", measuredAge)
    }
}
