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
        val reliability = dataReliability(x)
        val volumeArrow = when {
            x.dailyChangePct > 0.0 -> "↑"
            x.dailyChangePct < 0.0 -> "↓"
            else -> "→"
        }
        val riskLabel = when {
            x.riskScore <= 30 -> "DÜŞÜK RİSK"
            x.riskScore <= 60 -> "ORTA RİSK"
            else -> "YÜKSEK RİSK"
        }
        val signalLabel = when {
            x.score >= 80 -> "GÜÇLÜ TEKNİK SİNYAL"
            x.score >= 65 -> "ORTA TEKNİK SİNYAL"
            else -> "ZAYIF/İZLEME SİNYALİ"
        }
        val reason = x.scoreBreakdown
            .asSequence()
            .filter { it.contains("+") && !it.startsWith("KAP") && !it.startsWith("VWAP") }
            .take(4)
            .map { it.substringBefore(":").trim() }
            .joinToString(" + ")
            .ifBlank { "Yeterli pozitif teknik bileşen açıklaması yok" }

        holder.symbol.text = x.symbol
        holder.score.text = "${x.score}/100"
        holder.company.text = x.companyName ?: ""
        holder.meta.text = buildString {
            append("${x.direction} • $signalLabel • $riskLabel\n")
            append("Teknik Fırsat ${x.score}/100 • Risk ${x.riskScore}/100 • Veri Güveni $reliability/100\n")
            append("Hacim ${x.volumeLabel} $volumeArrow • Günlük değişim ${"%.2f".format(x.dailyChangePct)}%\n")
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

    private fun dataReliability(x: Opportunity): Int {
        var score = 100
        if (x.kapLabel.isBlank() || x.kapLabel.equals("Veri yok", ignoreCase = true)) score -= 10
        if (x.technical.vwap == null) score -= 8
        if (x.support == null) score -= 5
        if (x.resistance == null) score -= 5
        if (x.technical.rsi14 == null) score -= 8
        if (x.technical.macd == null || x.technical.macdSignal == null) score -= 8
        if (x.technical.ema20 == null || x.technical.ema50 == null || x.technical.ema200 == null) score -= 10
        if (x.technical.volumeRatio == null) score -= 8
        if (x.technical.atr14 == null) score -= 5
        return score.coerceIn(0, 100)
    }
}
