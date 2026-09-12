package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.ViopContract

class ViopSuggestionAdapter(
    private val items: List<ViopContract>,
    private val onSelect: (ViopContract) -> Unit
) : RecyclerView.Adapter<ViopSuggestionAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol: TextView = v.findViewById(R.id.suggestionSymbol)
        val meta: TextView = v.findViewById(R.id.suggestionMeta)
        val action: TextView = v.findViewById(R.id.suggestionAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H = H(
        LayoutInflater.from(parent.context).inflate(R.layout.item_viop_suggestion, parent, false)
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val item = items[position]
        holder.symbol.text = item.symbol
        holder.meta.text = buildString {
            append(item.underlying.ifBlank { "Dayanak bilinmiyor" })
            if (item.expiry.isNotBlank() && item.expiry != "-") append(" • ${item.expiry}")
            if (item.contractType.isNotBlank()) append(" • ${item.contractType}")
        }
        holder.itemView.setOnClickListener { onSelect(item) }
        holder.action.setOnClickListener { onSelect(item) }
    }
}
