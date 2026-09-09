package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.favorites.FavoriteStock
import tr.borsatakip.v5.model.Opportunity

class FavoriteAdapter(
    private var items: List<Pair<FavoriteStock, Opportunity?>>, 
    private val onRemove: (FavoriteStock) -> Unit,
    private val onOpen: (Opportunity) -> Unit
) : RecyclerView.Adapter<FavoriteAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol = v.findViewById<TextView>(R.id.favoriteSymbol)
        val company = v.findViewById<TextView>(R.id.favoriteCompany)
        val details = v.findViewById<TextView>(R.id.favoriteDetails)
        val remove = v.findViewById<TextView>(R.id.favoriteRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(
        LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val (fav, opportunity) = items[position]
        holder.symbol.text = "★ ${fav.symbol}"
        holder.company.text = opportunity?.companyName ?: fav.displayName.orEmpty()
        holder.details.text = if (opportunity == null) {
            "Son tarama sonucu yok • Favori kaydı korunuyor"
        } else {
            buildString {
                append("${opportunity.direction} • Nihai ${opportunity.finalSignalScore}/100 • Teknik ${opportunity.score}/100\n")
                append("Risk ${opportunity.riskScore}/100 • Veri Güveni ${opportunity.dataConfidenceScore}/100 • Hacim ${opportunity.volumeLabel}\n")
                append("Günlük ${"%.2f".format(opportunity.dailyChangePct)}% • Kaynak: ${opportunity.source}")
            }
        }
        holder.remove.text = "★ Favoriden çıkar"
        holder.remove.setOnClickListener { onRemove(fav) }
        holder.itemView.setOnClickListener { opportunity?.let(onOpen) }
    }
}
