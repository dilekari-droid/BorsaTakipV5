package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.ViopOpportunity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViopOpportunityAdapter(private val items: List<ViopOpportunity>) : RecyclerView.Adapter<ViopOpportunityAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) { val text: TextView = v.findViewById(R.id.text) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_viop, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        val q = x.quote
        val c = x.contract
        val dataTime = if (q.exchangeTimestamp > 0L) SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(q.exchangeTimestamp)) else "bilinmiyor"
        val change = q.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "—"
        val volume = q.volume ?: c.volume
        val oi = q.openInterest ?: c.openInterest
        holder.text.text = buildString {
            append("${c.symbol} • ${c.underlying} • ${c.expiry}\n")
            append("${x.direction} • Nihai Sinyal ${x.finalScore}/100 • Teknik ${x.technicalScore}/100 • Risk ${x.riskScore}/100\n")
            append("Fiyat %.2f • Değişim %s\n".format(q.price, change))
            append("Hacim ${volume?.let { "%.0f".format(it) } ?: "VERİ YOK"} • Açık Pozisyon ${oi ?: "VERİ YOK"}\n")
            append("Likidite ${x.liquidityScore}/15 • Vade Riski ${x.expiryRisk}/100 • Mum ${x.historyCandleCount}\n")
            append("Veri Zamanı: $dataTime • Veri Yaşı: ${x.dataAgeMs / 1000}s\n")
            append("Kaynak: ${q.source}\n")
            append("Gerekçe: ${x.signalReason}")
        }
    }
}
