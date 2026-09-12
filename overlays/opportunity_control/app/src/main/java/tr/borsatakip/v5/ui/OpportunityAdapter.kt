package tr.borsatakip.v5.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityDiscoveryPresentation
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import java.util.Locale

class OpportunityAdapter(
    private val items: List<Opportunity>,
    private val favoriteSymbols: Set<String>,
    private val click: (Opportunity) -> Unit,
    private val toggleFavorite: (Opportunity) -> Unit
) : RecyclerView.Adapter<OpportunityAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.symbolBadge)
        val favorite: TextView = v.findViewById(R.id.favoriteToggle)
        val company: TextView = v.findViewById(R.id.company)
        val symbol: TextView = v.findViewById(R.id.symbol)
        val label: TextView = v.findViewById(R.id.discoveryLabel)
        val score: TextView = v.findViewById(R.id.opportunityScore)
        val reason: TextView = v.findViewById(R.id.discoveryReason)
        val scoreBar: ProgressBar = v.findViewById(R.id.scoreBar)
        val risk: TextView = v.findViewById(R.id.riskMetric)
        val volume: TextView = v.findViewById(R.id.volumeMetric)
        val catalyst: TextView = v.findViewById(R.id.catalystMetric)
        val factors: TextView = v.findViewById(R.id.factorSummary)
        val coverage: TextView = v.findViewById(R.id.coverageText)
        val graph: TextView = v.findViewById(R.id.openGraph)
        val detail: TextView = v.findViewById(R.id.openDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_opportunity, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        val p = OpportunityDiscoveryPresentation.from(x)
        val symbolUpper = x.symbol.trim().uppercase(Locale.ROOT)
        val companyText = x.companyName?.trim().takeUnless { it.isNullOrBlank() } ?: symbolUpper
        val normalized = FavoriteRepository.normalizeSymbol(symbolUpper)
        val isFav = favoriteSymbols.contains(normalized)

        holder.badge.text = symbolUpper.take(5)
        holder.symbol.text = symbolUpper
        holder.company.text = companyText
        holder.favorite.text = if (isFav) "★" else "☆"
        holder.favorite.contentDescription = if (isFav) "Favorilerden çıkar" else "Favoriye ekle"
        holder.favorite.setOnClickListener { toggleFavorite(x) }

        holder.label.text = p.classification
        holder.score.text = p.opportunityScore.toString()
        holder.reason.text = p.reason.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("tr", "TR")) else it.toString() }
        holder.scoreBar.progress = p.opportunityScore

        val accent = when (p.classification) {
            "GÜÇLÜ AL" -> Color.rgb(0, 230, 145)
            "AL" -> Color.rgb(63, 213, 155)
            else -> Color.rgb(255, 193, 62)
        }
        holder.label.setTextColor(accent)
        holder.score.setTextColor(accent)
        holder.scoreBar.progressTintList = ColorStateList.valueOf(accent)
        holder.scoreBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 36))

        val riskLabel = when {
            x.riskScore <= 30 -> "Düşük"
            x.riskScore <= 60 -> "Orta"
            else -> "Yüksek"
        }
        holder.risk.text = "Risk\n$riskLabel"
        holder.volume.text = "Hacim\n${x.technical.volumeRatio?.let { "%.1fx".format(it) } ?: "Veri yok"}"
        holder.catalyst.text = "Katalizör\n${if (p.catalystAvailable) x.kapLabel.take(18) else "Veri yok"}"

        val prioritized = p.factors.filter { it.score != null }.sortedByDescending { it.score }.take(5)
        holder.factors.text = buildString {
            append("ÇOK FAKTÖRLÜ ÖZET\n")
            prioritized.forEachIndexed { index, f ->
                if (index > 0) append("   •   ")
                append(f.label).append(" ").append(f.score).append("/100")
            }
            val unavailable = p.factors.count { it.score == null }
            if (unavailable > 0) append("\nEksik faktör: ").append(unavailable).append("/12")
        }
        holder.coverage.text = "Kanıt kapsamı %${p.coveragePct} • Veri kalitesi ${x.dataConfidenceScore}/100 • Temel/KAP verisi yoksa güçlü öneri üretilmez."

        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * holder.itemView.resources.displayMetrics.density
            setColor(Color.rgb(5, 28, 45))
            setStroke((1.2f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 150))
        }

        holder.graph.setOnClickListener { click(x) }
        holder.detail.setOnClickListener { click(x) }
        holder.itemView.setOnClickListener { click(x) }
    }
}
