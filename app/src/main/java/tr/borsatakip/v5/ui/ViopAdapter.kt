package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.ViopContract

class ViopAdapter(private val items: List<ViopContract>) : RecyclerView.Adapter<ViopAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) { val text = v.findViewById<TextView>(R.id.text) }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(LayoutInflater.from(parent.context).inflate(R.layout.item_viop, parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        holder.text.text = "${x.symbol}   ${"%.2f".format(x.lastPrice)} (${"%+.2f".format(x.dailyChangePct)}%)\n" +
            "Dayanak: ${x.underlying} • Vade: ${x.expiry}\n" +
            "Fiyat adımı: ${x.tickSize ?: "Veri yok"} • Çarpan: ${x.multiplier ?: "Veri yok"}\n" +
            "Açık pozisyon: ${x.openInterest ?: "Veri yok"} • Likidite: ${x.liquidity ?: "Veri yok"}\n" +
            "Rollover: ${x.rollover ?: "Veri yok"}"
    }
}
