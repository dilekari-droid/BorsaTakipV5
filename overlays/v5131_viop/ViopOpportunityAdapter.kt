package tr.borsatakip.v5.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.ViopOpportunity
import kotlin.math.abs

class ViopOpportunityAdapter(
    private val items: List<ViopOpportunity>,
    private val click: ((ViopOpportunity) -> Unit)? = null
) : RecyclerView.Adapter<ViopOpportunityAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol: TextView = v.findViewById(R.id.viopSymbol)
        val klass: TextView = v.findViewById(R.id.viopClass)
        val subtitle: TextView = v.findViewById(R.id.viopSubtitle)
        val direction: TextView = v.findViewById(R.id.viopDirection)
        val scores: TextView = v.findViewById(R.id.viopScores)
        val levels: TextView = v.findViewById(R.id.viopLevels)
        val reason: TextView = v.findViewById(R.id.viopReason)
        val detail: TextView = v.findViewById(R.id.viopDetailButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_viop, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        val q = x.quote
        val c = x.contract
        val isLong = x.direction.equals("LONG", true)
        val strong = x.finalScore >= 85 && x.validity.name == "VALID"
        holder.symbol.text = c.symbol.uppercase()
        holder.subtitle.text = "${c.underlying} • ${c.expiry}"
        holder.klass.text = when {
            strong -> "A SINIFI"
            x.finalScore >= 70 -> "B • İZLE"
            else -> "C • BEKLE"
        }
        holder.direction.text = if (isLong) "↗  LONG ADAYI" else "↘  SHORT ADAYI"
        holder.direction.setTextColor(if (isLong) Color.parseColor("#00F080") else Color.parseColor("#FF5A66"))
        val confidence = (100 - x.riskScore).coerceIn(0, 100)
        holder.scores.text = "Skor                 ${x.finalScore}/100\nGüven              $confidence/100\nRisk                 ${x.riskScore}/100"

        val entry = q.price
        val atr = x.technical.atr?.takeIf { it.isFinite() && it > 0.0 }
        val stop = atr?.let { if (isLong) entry - 1.25 * it else entry + 1.25 * it }
        val target1 = atr?.let { if (isLong) entry + 1.5 * it else entry - 1.5 * it }
        val target2 = atr?.let { if (isLong) entry + 2.5 * it else entry - 2.5 * it }
        val rr = if (stop != null && target2 != null && abs(entry - stop) > 1e-9) abs(target2 - entry) / abs(entry - stop) else null
        holder.levels.text = buildString {
            append("Giriş               ${"%.2f".format(entry)}\n")
            append("Stop                ${stop?.let { "%.2f".format(it) } ?: "—"}\n")
            append("Hedef 1          ${target1?.let { "%.2f".format(it) } ?: "—"}\n")
            append("Hedef 2          ${target2?.let { "%.2f".format(it) } ?: "—"}\n")
            append("Risk / Ödül     ${rr?.let { "1 : %.1f".format(it) } ?: "—"}")
        }
        holder.reason.text = x.signalReason
        val tap = View.OnClickListener { click?.invoke(x) }
        holder.itemView.setOnClickListener(tap)
        holder.detail.setOnClickListener(tap)
    }
}
