package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.favorites.FavoriteStock
import tr.borsatakip.v5.model.Opportunity
import java.util.Locale

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
        val direction = opportunity?.direction?.uppercase(Locale.ROOT)
        holder.symbol.text = if (opportunity == null) {
            "★ ${fav.symbol} • TREND BİLİNMİYOR"
        } else {
            "★ ${fav.symbol} $direction ${opportunity.finalSignalScore}/100"
        }
        holder.symbol.setTextColor(
            when (direction) {
                "LONG" -> ContextCompat.getColor(holder.itemView.context, R.color.green)
                "SHORT" -> ContextCompat.getColor(holder.itemView.context, R.color.red)
                else -> ContextCompat.getColor(holder.itemView.context, R.color.text_primary)
            }
        )

        holder.company.text = opportunity?.companyName ?: fav.displayName.orEmpty()
        holder.details.text = if (opportunity == null) {
            "Son tarama sonucu yok • Favori kaydı korunuyor"
        } else {
            buildString {
                append("Teknik uyum ${opportunity.finalSignalScore}/100 • Risk ${opportunity.riskScore}/100 • Veri Güveni ${opportunity.dataConfidenceScore}/100 (${opportunity.dataConfidenceLabel})\n")
                append("Mod ${opportunity.analysisMode} • Hacim ${opportunity.volumeLabel} • Günlük ${"%.2f".format(opportunity.dailyChangePct)}%\n")
                opportunity.lrc?.let { lrc ->
                    val arrow = when (lrc.trend) {
                        "YÜKSELEN" -> "↑"
                        "DÜŞEN" -> "↓"
                        else -> "→"
                    }
                    append("LRC100 $arrow R ${"%.2f".format(lrc.pearsonR)} • ${lrc.channelPosition}\n")
                }
                append("Kaynak: ${opportunity.source}")
            }
        }
        holder.remove.text = "★ Favoriden çıkar"
        holder.remove.setOnClickListener { onRemove(fav) }
        holder.itemView.setOnClickListener { opportunity?.let(onOpen) }
    }
}
