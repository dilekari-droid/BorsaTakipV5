package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OpportunityAdapter(
    private var items: List<Opportunity>,
    private val favoriteSymbols: Set<String>,
    private val click: (Opportunity) -> Unit,
    private val toggleFavorite: (Opportunity) -> Unit
) : RecyclerView.Adapter<OpportunityAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val favorite = v.findViewById<TextView>(R.id.favoriteToggle)
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
        val normalized = FavoriteRepository.normalizeSymbol(x.symbol)
        val favorite = favoriteSymbols.contains(normalized)
        holder.favorite.text = if (favorite) "★" else "☆"
        holder.favorite.contentDescription = if (favorite) "Favorilerden çıkar" else "Favoriye ekle"
        holder.favorite.setOnClickListener { toggleFavorite(x) }

        val riskLabel = when {
            x.riskScore <= 30 -> "DÜŞÜK RİSK"
            x.riskScore <= 60 -> "ORTA RİSK"
            else -> "YÜKSEK RİSK"
        }
        val finalLabel = when {
            x.finalSignalScore >= 90 -> "ÇOK GÜÇLÜ NİHAİ SİNYAL"
            x.finalSignalScore >= 80 -> "GÜÇLÜ NİHAİ SİNYAL"
            x.finalSignalScore >= 70 -> "İZLE"
            x.finalSignalScore >= 60 -> "ZAYIF SİNYAL"
            else -> "FIRSAT YOK"
        }
        val reason = x.scoreBreakdown
            .asSequence()
            .takeWhile { !it.startsWith("KAP:") }
            .filter { it.contains(": +") }
            .map { it.substringBefore(":").trim() }
            .distinct()
            .joinToString(" + ")
            .ifBlank { "Yeterli teknik bileşen açıklaması yok" }

        val now = System.currentTimeMillis()
        val ageMs = if (x.dataTimestamp > 0L) (now - x.dataTimestamp).coerceAtLeast(0L) else Long.MAX_VALUE
        val ageText = when {
            ageMs == Long.MAX_VALUE -> "bilinmiyor"
            ageMs < 60_000L -> "${ageMs / 1000L} sn"
            ageMs < 3_600_000L -> "${ageMs / 60_000L} dk"
            else -> "${ageMs / 3_600_000L} sa"
        }
        val timeText = if (x.dataTimestamp > 0L) {
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(x.dataTimestamp))
        } else {
            "bilinmiyor"
        }
        val staleLabel = when {
            ageMs == Long.MAX_VALUE -> " • TAZELİK BİLİNMİYOR"
            ageMs > 15 * 60_000L -> " • BAYAT VERİ"
            else -> ""
        }

        holder.symbol.text = x.symbol
        holder.score.text = "${x.finalSignalScore}/100"
        holder.company.text = x.companyName ?: ""
        holder.meta.text = buildString {
            append("${x.direction} • $finalLabel • $riskLabel\n")
            append("Snapshot Teknik Puanı ${x.score}/100 • Risk ${x.riskScore}/100 • Veri Güveni ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\n")
            append("Nihai Sinyal ${x.finalSignalScore}/100\n")
            append("Hacim ${x.volumeLabel} • ${x.volumeDirectionLabel} • Günlük değişim ${"%.2f".format(x.dailyChangePct)}%\n")
            append("Kaynak: ${x.source} • Veri: $timeText • Yaş: $ageText$staleLabel\n")
            append("KAP: ${x.kapLabel}\n")
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
