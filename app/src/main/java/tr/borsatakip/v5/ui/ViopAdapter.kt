package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.ViopContract

class ViopAdapter(
    private val items: List<ViopContract>,
    private val onLongPress: ((ViopContract) -> Unit)? = null
) : RecyclerView.Adapter<ViopAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol = v.findViewById<TextView>(R.id.viopSymbol)
        val meta = v.findViewById<TextView>(R.id.viopMeta)
        val last = v.findViewById<TextView>(R.id.viopLast)
        val change = v.findViewById<TextView>(R.id.viopChange)
        val bidAsk = v.findViewById<TextView>(R.id.viopBidAsk)
        val stats = v.findViewById<TextView>(R.id.viopStats)
        val source = v.findViewById<TextView>(R.id.viopSource)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_viop, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        fun f(v: Double?): String = v?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        fun fp(v: Double?): String = v?.takeIf { it.isFinite() }?.let { "%+.2f%%".format(it) } ?: "—"

        holder.symbol.text = x.symbol
        holder.meta.text = "${x.underlying} • ${x.expiry}"
        holder.last.text = f(x.lastPrice)
        holder.change.text = fp(x.dailyChangePct)
        holder.change.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                when {
                    (x.dailyChangePct ?: 0.0) > 0.0 -> R.color.green
                    (x.dailyChangePct ?: 0.0) < 0.0 -> R.color.red
                    else -> R.color.text_secondary
                }
            )
        )
        holder.bidAsk.text = "Alış  ${f(x.bid)}     Satış  ${f(x.ask)}"
        holder.stats.text = "Hacim  ${f(x.volume)}     Açık Poz.  ${x.openInterest ?: "—"}\n" +
            "Fiyat adımı  ${f(x.tickSize)}     Çarpan  ${f(x.multiplier)}"
        holder.source.text = "${x.providerLabel} • ${x.status}"

        holder.itemView.contentDescription = buildString {
            append(x.symbol)
            append(", son fiyat ${f(x.lastPrice)}, değişim ${fp(x.dailyChangePct)}")
            append(", alış ${f(x.bid)}, satış ${f(x.ask)}")
        }

        holder.itemView.setOnLongClickListener {
            if (x.isManual && onLongPress != null) {
                onLongPress.invoke(x)
                true
            } else false
        }
    }
}
