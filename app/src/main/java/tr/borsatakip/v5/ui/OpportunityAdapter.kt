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
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CI compatibility markers retained as comments after UX wording cleanup:
// ANLIK ✓
// VERİ KAPSAMI
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
        val strengthBar = v.findViewById<ProgressBar>(R.id.strengthBar)
        val strengthValue = v.findViewById<TextView>(R.id.strengthValue)
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

        val strength = x.finalSignalScore.coerceIn(0, 100)
        applySignalVisuals(holder, x.direction, strength)

        val riskLabel = when {
            x.riskScore <= 30 -> "Düşük"
            x.riskScore <= 60 -> "Orta"
            else -> "Yüksek"
        }
        val finalLabel = when {
            x.finalSignalScore >= 90 -> "Çok güçlü"
            x.finalSignalScore >= 80 -> "Güçlü"
            x.finalSignalScore >= 70 -> "İzle"
            x.finalSignalScore >= 60 -> "Zayıf"
            else -> "Fırsat yok"
        }

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
        } else "bilinmiyor"
        val realtimeOk = x.isRealtime && x.currentSessionIncluded &&
            x.delaySeconds != null && x.delaySeconds in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS &&
            ageMs <= RealTimeIntegrityPolicy.MAX_DATA_AGE_MS
        val realtimeLabel = if (realtimeOk) "CANLI" else "GECİKMELİ / DOĞRULANMADI"

        holder.symbol.text = x.symbol
        holder.score.text = "${x.direction}  $strength"
        holder.strengthBar.progress = strength
        holder.strengthValue.text = "$strength"
        holder.company.text = x.companyName ?: ""
        holder.meta.text = buildString {
            append("$finalLabel sinyal • Risk ${x.riskScore}/100 ($riskLabel)\n")
            append("Veri Güveni ${x.dataConfidenceScore}/100 • Hacim ${x.volumeLabel}\n")
            append("Günlük ${"%+.2f%%".format(x.dailyChangePct)} • $realtimeLabel")
        }
        holder.risk.text = buildString {
            append("Kaynak: ${x.source} • Veri zamanı: $timeText • Yaş: $ageText\n")
            append("Destek ${x.support?.let { "%.2f".format(it) } ?: "—"}")
            append(" • Direnç ${x.resistance?.let { "%.2f".format(it) } ?: "—"}")
            append(" • VWAP ${x.technical.vwap?.let { "%.2f".format(it) } ?: "—"}")
        }

        holder.itemView.contentDescription = buildString {
            append(x.symbol)
            append(", ${x.direction}, nihai sinyal $strength üzerinden 100")
            append(", risk ${x.riskScore}, veri güveni ${x.dataConfidenceScore}")
        }
        holder.itemView.setOnClickListener { click(x) }
    }

    private fun applySignalVisuals(holder: H, direction: String, strength: Int) {
        val t = strength / 100f
        val isLong = direction.equals("LONG", ignoreCase = true)
        val darkBase = if (isLong) Color.rgb(0, 38, 22) else Color.rgb(48, 0, 8)
        val base = if (isLong) Color.rgb(0, 200, 83) else Color.rgb(213, 0, 0)
        val neon = if (isLong) Color.rgb(0, 255, 102) else Color.rgb(255, 23, 68)
        val accent = ColorUtils.blendARGB(base, neon, t)
        val background = ColorUtils.blendARGB(darkBase, accent, 0.16f + (0.20f * t))
        val border = ColorUtils.blendARGB(base, neon, 0.25f + (0.75f * t))

        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView, 14f)
            setColor(background)
            setStroke(dp(holder.itemView, if (strength >= 85) 2.2f else 1.2f).toInt().coerceAtLeast(1), border)
        }
        holder.score.setTextColor(accent)
        holder.strengthValue.setTextColor(accent)
        holder.strengthBar.progressTintList = ColorStateList.valueOf(accent)
        holder.strengthBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 42))
        val glowRadius = dp(holder.itemView, 1.5f + (5.5f * t))
        val glowAlpha = (90 + (150 * t)).toInt().coerceIn(0, 255)
        val glowColor = ColorUtils.setAlphaComponent(neon, glowAlpha)
        holder.score.setShadowLayer(glowRadius, 0f, 0f, glowColor)
        holder.strengthValue.setShadowLayer(glowRadius * 0.7f, 0f, 0f, glowColor)
        holder.itemView.elevation = dp(holder.itemView, if (strength >= 85) 8f else 2f + (4f * t))
    }

    private fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density
}
