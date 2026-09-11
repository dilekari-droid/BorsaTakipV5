package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.TextView
import tr.borsatakip.v5.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StockDetailActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_detail)
        setupBottomNav()
        val x = AppSession.selected ?: return

        findViewById<TextView>(R.id.title).text = x.symbol
        findViewById<TextView>(R.id.subtitle).text =
            "%.2f • Günlük %+.2f • ${x.direction} • Nihai ${x.finalSignalScore}/100 • Risk ${x.riskScore}/100"
                .format(x.price, x.dailyChangePct)
        findViewById<PriceChartView>(R.id.chart).candles = x.candles

        val t = x.technical
        val chartNote = if (x.candles.isEmpty()) {
            "\n\nGRAFİK\nDoğrulanmış geçmiş mum bulunmadığından grafik çizilmedi; veri uydurulmadı."
        } else ""

        findViewById<TextView>(R.id.details).text = buildString {
            append("TREND\n")
            append("EMA20   ${fmt(t.ema20)}\n")
            append("EMA50   ${fmt(t.ema50)}\n")
            append("EMA200  ${fmt(t.ema200)}\n\n")

            append("MOMENTUM\n")
            append("RSI14   ${fmt(t.rsi14)}\n")
            append("MACD    ${fmt(t.macd)} • Sinyal ${fmt(t.macdSignal)}\n\n")

            append("VOLATİLİTE / FİYAT ALANI\n")
            append("Bollinger   ${fmt(t.bbLower)} – ${fmt(t.bbUpper)}\n")
            append("ATR14       ${fmt(t.atr14)}\n")
            append("VWAP        ${fmt(t.vwap)}\n")
            append("VWMA        ${fmt(t.vwma)}\n\n")

            append("DESTEK / DİRENÇ\n")
            append("Destek      ${fmt(t.support)}\n")
            append("Direnç      ${fmt(t.resistance)}\n\n")

            append("HACİM / KALİTE\n")
            append("Hacim oranı ${t.volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok"}\n")
            append("KAP          ${x.kapLabel}\n")
            append("Likidite     ${x.liquidityLabel}\n")
            append("Veri Güveni ${x.dataConfidenceScore}/100\n")

            append(chartNote)
            append("\n\nVERİ KAYNAĞI\n")
            append("${x.source}\n")
            append("Veri zamanı: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(Date(x.dataTimestamp))}")
        }
    }

    private fun fmt(v: Double?) = v?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "Veri yok"
}
