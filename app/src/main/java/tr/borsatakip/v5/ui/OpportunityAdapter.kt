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
        holder.symbol.text = x.symbol
        holder.score.text = "${x.score}/100"
        holder.company.text = x.companyName ?: ""
        holder.meta.text = buildString {
            append("${x.direction} • Teknik ${x.technicalLabel} • Hacim ${x.volumeLabel}\n")
            append("Kaynak: ${x.source} • KAP: ${x.kapLabel}\n")
            append(x.scoreBreakdown.joinToString(" • "))
        }
        holder.risk.text = "Risk ${x.riskScore}/100 • Destek ${x.support?.let { "%.2f".format(it) } ?: "-"} • Direnç ${x.resistance?.let { "%.2f".format(it) } ?: "-"}"
        holder.itemView.setOnClickListener { click(x) }
    }
}
