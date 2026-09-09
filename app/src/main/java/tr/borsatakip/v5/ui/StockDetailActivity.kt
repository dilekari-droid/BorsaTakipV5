package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.TextView
import tr.borsatakip.v5.R
import java.text.SimpleDateFormat
import java.util.*

class StockDetailActivity:BaseActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_stock_detail);setupBottomNav();val x=AppSession.selected?:return;findViewById<TextView>(R.id.title).text="${x.symbol}  %.2f".format(x.price);findViewById<TextView>(R.id.subtitle).text="Günlük %+.2f • ${x.direction} • Fırsat ${x.score}/100 • Risk ${x.riskScore}/100".format(x.dailyChangePct);findViewById<PriceChartView>(R.id.chart).candles=x.candles;val t=x.technical;findViewById<TextView>(R.id.details).text="""EMA20: ${fmt(t.ema20)}
EMA50: ${fmt(t.ema50)}
EMA200: ${fmt(t.ema200)}
RSI14: ${fmt(t.rsi14)}
MACD: ${fmt(t.macd)} / Sinyal ${fmt(t.macdSignal)}
Bollinger: ${fmt(t.bbLower)} – ${fmt(t.bbUpper)}
ATR14: ${fmt(t.atr14)}
VWAP: ${fmt(t.vwap)}
Hacim oranı: ${t.volumeRatio?.let{"%.2fx".format(it)}?:"Veri yok"}
Destek: ${fmt(t.support)}
Direnç: ${fmt(t.resistance)}
KAP: ${x.kapLabel}
Likidite: ${x.liquidityLabel}

Kaynak: ${x.source}
Veri zamanı: ${SimpleDateFormat("dd.MM.yyyy HH:mm",Locale("tr","TR")).format(Date(x.dataTimestamp))}"""}}
private fun fmt(v:Double?)=v?.let{"%.2f".format(it)}?:"Veri yok"}
