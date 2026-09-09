package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.Opportunity

class OpportunityAdapter(
    private var items: List<Opportunity>,
    private val click: (Opportunity) -> Unit
) : RecyclerView.Adapter<OpportunityAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol = v.findViewById<TextView>(R.id.symbol)
        val score = v.findViewById<TextView>(R.id.score)
        val company = v.findViewById<TextView>(R.id.company)
        val meta = v.findViewById<TextView>(R.id.meta)
        val risk = v.findViewById<TextView>(R.id.risk)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_opportunity, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        val riskLabel = when {
            x.riskScore <= 30 -> "DÜŞÜK RİSK"
            x.riskScore <= 60 -> "ORTA RİSK"
            else -> "YÜKSEK RİSK"
        }
        val finalLabel = when {
            x.finalSignalScore >= 80 -> "GÜÇLÜ NİHAİ SİNYAL"
            x.finalSignalScore >= 65 -> "ORTA NİHAİ SİNYAL"
            else -> "İZLEME / ZAYIF SİNYAL"
        }
        val reason = x.scoreBreakdown
            .asSequence()
            .filter { it.contains("+") && !it.startsWith("KAP") && !it.startsWith("VWAP") }
            .take(4)
            .map { it.substringBefore(":").trim() }
            .joinToString(" + ")
            .ifBlank { "Yeterli teknik bileşen açıklaması yok" }

        holder.symbol.text = x.symbol
        holder.score.text = "${x.finalSignalScore}/100"
        holder.company.text = x.companyName ?: ""
        holder.meta.text = buildString {
            append("${x.direction} • $finalLabel • $riskLabel\n")
            append("Teknik Fırsat ${x.score}/100 • Risk ${x.riskScore}/100 • Veri Güveni ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\n")
            append("Nihai Sinyal ${x.finalSignalScore}/100\n")
            append("Hacim ${x.volumeLabel} • ${x.volumeDirectionLabel} • Günlük değişim ${"%.2f".format(x.dailyChangePct)}%\n")
            append("Kaynak: ${x.source} • KAP: ${x.kapLabel}\n")
            append("${x.direction} nedeni: $reason\n")
            append(x.scoreBreakdown.joinToString(" • "))
        }
        holder.risk.text = buildString {
            append("Destek ${x.support?.let { "%.2f".format(it) } ?: "veri yok"}")
            append(" • Direnç ${x.resistance?.let { "%.2f".format(it) } ?: "veri yok"}")
            if (x.technical.vwap == null) append(" • VWAP veri yok")
        }
        holder.itemView.setOnClickListener { click(x) }
    }
}
