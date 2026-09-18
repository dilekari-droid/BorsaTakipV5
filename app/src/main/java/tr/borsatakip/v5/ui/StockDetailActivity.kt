package tr.borsatakip.v5.ui

import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StockDetailActivity : BaseActivity() {
    private lateinit var x: Opportunity
    private lateinit var chart: PriceChartView
    private lateinit var chartStatus: TextView
    private val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr","TR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_detail)
        setupBottomNav()
        x = AppSession.selected ?: run { finish(); return }
        chart = findViewById(R.id.chart)
        chartStatus = findViewById(R.id.chartStatus)

        findViewById<TextView>(R.id.title).text = "${x.symbol}  ${money(x.price)}"
        findViewById<TextView>(R.id.subtitle).text = buildString {
            append(x.companyName ?: "Şirket adı yok")
            append(" • Günlük ${"%+.2f".format(x.dailyChangePct)}%")
        }
        findViewById<TextView>(R.id.scoreSummary).text = summaryText()
        findViewById<TextView>(R.id.overview).text = overviewText()
        bindFactorCards()
        findViewById<TextView>(R.id.technicalDetails).text = technicalText()
        findViewById<TextView>(R.id.dataQuality).text = qualityText()
        findViewById<TextView>(R.id.dataStatus).text = dataStatusText()
        findViewById<TextView>(R.id.validity).text = "SİNYAL GEÇERLİLİĞİ: ${validityLabel()}\n${x.signalValidityReason}"

        chart.candles = x.candles
        bindRange(R.id.btnRange1D, PriceChartView.Range.DAY)
        bindRange(R.id.btnRange1W, PriceChartView.Range.WEEK)
        bindRange(R.id.btnRange1M, PriceChartView.Range.MONTH)
        bindRange(R.id.btnRange3M, PriceChartView.Range.THREE_MONTHS)
        bindRange(R.id.btnRange1Y, PriceChartView.Range.YEAR)
        setRange(PriceChartView.Range.THREE_MONTHS)
    }

    private fun bindRange(id:Int, range:PriceChartView.Range) {
        findViewById<Button>(id).setOnClickListener { setRange(range) }
    }

    private fun setRange(range:PriceChartView.Range) {
        chart.setRange(range)
        chartStatus.text = when {
            x.candles.isEmpty() -> "Yeterli OHLCV verisi bulunamadı. Sahte grafik üretilmedi."
            range.sessions > 1 && x.candles.size < range.sessions -> "${range.label} için gerekli gerçek veri mevcut değil (${x.candles.size}/${range.sessions} mum)."
            else -> "Dönem: ${range.label} • Kaynak: ${x.source} • Grafik verisi ve teknik göstergeler aynı OHLCV dizisinden hesaplanır."
        }
    }

    private fun summaryText():String {
        val strength = when {
            x.finalSignalScore >= 85 -> "YÜKSEK GÜÇ"
            x.finalSignalScore >= 70 -> "İZLE"
            x.finalSignalScore >= 60 -> "ZAYIF SİNYAL"
            else -> "DÜŞÜK GÜÇ"
        }
        return "${x.direction}\nNihai Sinyal ${x.finalSignalScore}/100 • $strength\nTeknik Skor ${x.score}/100 • Risk ${x.riskScore}/100 • Veri Güveni ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\n${validityLabel()}"
    }

    private fun overviewText():String {
        val t = x.technical
        val emaState = when {
            listOf(t.ema20,t.ema50,t.ema200).all { it != null } && x.price < t.ema20!! && t.ema20!! < t.ema50!! && t.ema50!! < t.ema200!! ->
                "Fiyat EMA20, EMA50 ve EMA200 seviyelerinin altında; mevcut trend aşağı yönlü."
            listOf(t.ema20,t.ema50,t.ema200).all { it != null } && x.price > t.ema20!! && t.ema20!! > t.ema50!! && t.ema50!! > t.ema200!! ->
                "Fiyat EMA20, EMA50 ve EMA200 seviyelerinin üzerinde; mevcut trend yukarı yönlü."
            else -> "EMA dizilimi tek yönlü güçlü trend teyidi vermiyor."
        }
        val volume = x.technical.volumeRatio?.let {
            if (it < 0.8) "İşlem hacmi zayıf olduğu için sinyal teyidi azalıyor." else "Hacim sinyal değerlendirmesine veri sağlıyor."
        } ?: "Hacim teyidi doğrulanamadı."
        return "$emaState $volume Nihai Sinyal ${x.finalSignalScore}/100 bir başarı olasılığı değildir; veri ve teknik koşulların mevcut sürümdeki birleşik skorudur."
    }

    private fun bindFactorCards() {
        val t = x.technical
        val trend = when {
            t.ema20 != null && t.ema50 != null && t.ema200 != null && x.price < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200 -> "NEGATİF\nFiyat EMA20/50/200 altında. SHORT yönünü destekliyor."
            t.ema20 != null && t.ema50 != null && t.ema200 != null && x.price > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200 -> "POZİTİF\nFiyat EMA20/50/200 üzerinde. LONG yönünü destekliyor."
            else -> "KARIŞIK\nEMA dizilimi güçlü tek yön teyidi vermiyor."
        }
        findViewById<TextView>(R.id.trendCard).text = "TREND\n$trend"

        val rsi = t.rsi14
        val momentum = when {
            rsi == null -> "YETERSİZ VERİ\nRSI14 hesaplanamadı."
            rsi < 30 -> "AŞIRI SATIM BÖLGESİNE YAKIN/ALTINDA\nRSI14 ${fmt(rsi)}. Tepki hareketi riski izlenmeli."
            rsi < 48 -> "ZAYIF\nRSI14 ${fmt(rsi)}. Alıcı momentumu zayıf."
            rsi <= 68 -> "DENGELİ/POZİTİF\nRSI14 ${fmt(rsi)}. Momentum aşırı bölge dışında."
            else -> "YÜKSEK\nRSI14 ${fmt(rsi)}. Aşırı alım/geri çekilme riski izlenmeli."
        }
        findViewById<TextView>(R.id.momentumCard).text = "MOMENTUM\n$momentum"

        val macd = if (t.macd != null && t.macdSignal != null) {
            val state = if (t.macd >= t.macdSignal) "POZİTİF" else "NEGATİF"
            "$state\nMACD ${fmt(t.macd)} / Signal ${fmt(t.macdSignal)}. Kısa vadeli momentum ${if (state=="POZİTİF") "yukarı" else "aşağı"} yönde."
        } else "YETERSİZ VERİ\nMACD/Signal hesaplanamadı."
        findViewById<TextView>(R.id.macdCard).text = "MACD\n$macd"

        val volume = t.volumeRatio?.let { ratio ->
            val state = when { ratio >= 1.5 -> "GÜÇLÜ TEYİT"; ratio >= 1.0 -> "ORTA TEYİT"; else -> "ZAYIF TEYİT" }
            "$state\nHacim ${"%.2f".format(ratio)}x. ${if (ratio < 1.0) "Düşük katılım sinyal teyidini azaltıyor." else "Hacim katılımı sinyal değerlendirmesini destekliyor."}"
        } ?: "YETERSİZ VERİ\nHacim oranı hesaplanamadı."
        findViewById<TextView>(R.id.volumeCard).text = "HACİM\n$volume"

        findViewById<TextView>(R.id.extraCard).text = buildString {
            append("EK FAKTÖRLER\n")
            append("VWAP: ${fmt(t.vwap)} • ${if (t.vwap != null) if (x.price >= t.vwap) "fiyat üzerinde" else "fiyat altında" else "veri yok"}\n")
            append("VWMA: ${fmt(t.vwma)}\n")
            append("ATR14: ${fmt(t.atr14)} • volatilite/risk girdisi\n")
            append("Destek: ${fmt(t.support)} • Direnç: ${fmt(t.resistance)}")
        }
    }

    private fun technicalText():String {
        val t=x.technical
        return "EMA20: ${fmt(t.ema20)}\nEMA50: ${fmt(t.ema50)}\nEMA200: ${fmt(t.ema200)}\nRSI14: ${fmt(t.rsi14)}\nMACD: ${fmt(t.macd)}\nSignal: ${fmt(t.macdSignal)}\nATR14: ${fmt(t.atr14)}\nVWAP: ${fmt(t.vwap)}\nVWMA: ${fmt(t.vwma)}\nHacim oranı: ${t.volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok"}\nBollinger alt/üst: ${fmt(t.bbLower)} / ${fmt(t.bbUpper)}\nDestek/Direnç: ${fmt(t.support)} / ${fmt(t.resistance)}"
    }

    private fun qualityText():String {
        fun mark(ok:Boolean)=if(ok)"✓" else "⚠ Veri yok"
        val hasPrice=x.price.isFinite()&&x.price>0
        val hasVol=x.technical.volumeRatio!=null
        val hasOhlcv=x.candles.size>=220
        val hasKap=!x.kapLabel.equals("Veri yok",true)&&x.kapLabel.isNotBlank()
        val hasLevels=x.support!=null&&x.resistance!=null
        val hasVwap=x.technical.vwap!=null
        return "VERİ GÜVENİ ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\nFiyat ${mark(hasPrice)}\nHacim ${mark(hasVol)}\nOHLCV ${mark(hasOhlcv)}\nKAP ${mark(hasKap)}\nDestek/Direnç ${mark(hasLevels)}\nVWAP ${mark(hasVwap)}\nEksik alanlar uydurulmaz ve Veri Güveni ile Nihai Sinyal birbirinin yerine kullanılmaz."
    }

    private fun dataStatusText():String {
        val measured = if (x.receivedElapsedRealtime > 0L) {
            val elapsed=(SystemClock.elapsedRealtime()-x.receivedElapsedRealtime).coerceAtLeast(0L)
            val atReceipt=if(x.receivedAt>0&&x.exchangeTimestamp>0)(x.receivedAt-x.exchangeTimestamp).coerceAtLeast(0L) else 0L
            formatAge(atReceipt+elapsed)
        } else "yeniden başlatma sonrası monotonic yaş doğrulanamıyor"
        return "Kaynak: ${x.source}\nVeri Modu: ${x.dataMode.name}\nPiyasa Veri Zamanı: ${time(x.exchangeTimestamp)}\nUygulamaya Ulaşma: ${time(x.receivedAt)}\nÖlçülen Veri Yaşı: $measured\nSağlayıcı gecikmesi: ${x.delaySeconds?.let { "$it sn" } ?: "bilinmiyor"}\nTarama başladı: ${time(x.scanStartedAt)}\nTarama tamamlandı: ${time(x.scanCompletedAt)}\nScanRun: ${x.scanRunId ?: "yok"}\nNot: Uygulamaya ulaşma süresi, tek başına piyasa gecikmesi olarak yorumlanmaz."
    }

    private fun validityLabel() = when(x.signalValidity) {
        SignalValidity.VALID -> "DOĞRULANMIŞ FIRSAT"
        SignalValidity.WATCH -> "İZLEME"
        SignalValidity.INSUFFICIENT -> "YETERSİZ VERİ"
        SignalValidity.REJECTED -> "REDDEDİLDİ"
    }
    private fun time(v:Long)=if(v>0)df.format(Date(v)) else "bilinmiyor"
    private fun money(v:Double)=if(v.isFinite())"%.2f TL".format(v) else "Veri yok"
    private fun fmt(v:Double?)=v?.takeIf{it.isFinite()}?.let{"%.2f".format(it)}?:"Veri yok"
    private fun formatAge(ms:Long)=when { ms<60_000L->"${ms/1000L} sn"; ms<3_600_000L->"${ms/60_000L} dk"; else->"${ms/3_600_000L} sa" }
}
