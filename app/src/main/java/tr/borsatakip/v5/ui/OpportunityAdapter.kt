package tr.borsatakip.v5.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
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
            x.riskScore <= 30 -> "DÜŞÜK RİSK"
            x.riskScore <= 60 -> "ORTA RİSK"
            else -> "YÜKSEK RİSK"
        }
        val validityLabel = when (x.signalValidity) {
            SignalValidity.VALID -> "DOĞRULANMIŞ FIRSAT"
            SignalValidity.WATCH -> "İZLEME"
            SignalValidity.INSUFFICIENT -> "YETERSİZ VERİ"
            SignalValidity.REJECTED -> "REDDEDİLDİ"
        }
        val modeLabel = when (x.dataMode) {
            DataMode.REALTIME -> "REALTIME"
            DataMode.DELAYED -> "DELAYED"
            DataMode.EOD -> "EOD"
            DataMode.UNVERIFIED -> "UNVERIFIED"
            DataMode.HISTORICAL -> "HISTORICAL"
        }
        val reason = x.scoreBreakdown.asSequence()
            .takeWhile { !it.startsWith("KAP:") }
            .filter { it.contains(": +") }
            .map { it.substringBefore(":").trim() }
            .distinct()
            .joinToString(" + ")
            .ifBlank { "Yeterli teknik bileşen açıklaması yok" }

        val exchangeText = if (x.exchangeTimestamp > 0L) {
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(x.exchangeTimestamp))
        } else "bilinmiyor"
        val receivedText = if (x.receivedAt > 0L) {
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(x.receivedAt))
        } else "bilinmiyor"
        val measuredAgeText = if (x.receivedElapsedRealtime > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - x.receivedElapsedRealtime).coerceAtLeast(0L)
            val ageAtReceipt = if (x.exchangeTimestamp > 0L && x.receivedAt > 0L) (x.receivedAt - x.exchangeTimestamp).coerceAtLeast(0L) else 0L
            formatAge(ageAtReceipt + elapsed)
        } else "yeniden başlatma sonrası doğrulanamıyor"

        val hasPrice = x.price.isFinite() && x.price > 0.0
        val hasVolume = !x.volumeLabel.equals("Veri yok", true) && x.volumeLabel.isNotBlank()
        val hasOhlcv = x.candles.isNotEmpty()
        val hasKap = !x.kapLabel.equals("Veri yok", true) && x.kapLabel.isNotBlank()
        val hasLevels = x.support != null && x.resistance != null
        val hasVwap = x.technical.vwap != null
        fun mark(ok: Boolean): String = if (ok) "✓" else "⚠ veri yok"

        holder.symbol.text = x.symbol
        holder.score.text = "${x.direction} • Nihai Sinyal $strength/100"
        holder.strengthBar.progress = strength
        holder.strengthValue.text = "$strength/100"
        holder.company.text = x.companyName ?: ""
        holder.meta.text = buildString {
            append("$validityLabel • $riskLabel • Veri Modu: $modeLabel\n")
            append("Kaynak: ${x.source}\n")
            append("Piyasa Veri Zamanı: $exchangeText • Uygulamaya Ulaşma: $receivedText\n")
            append("Ölçülen Veri Yaşı: $measuredAgeText • Sağlayıcı gecikmesi: ${x.delaySeconds?.let { "$it sn" } ?: "bilinmiyor"}\n")
            append("Teknik Skor ${x.score}/100 • Risk ${x.riskScore}/100 • Veri Güveni ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\n")
            append("VERİ KAPSAMI: Fiyat ${mark(hasPrice)} • Hacim ${mark(hasVolume)} • OHLCV ${mark(hasOhlcv)} • KAP ${mark(hasKap)} • Destek/Direnç ${mark(hasLevels)} • VWAP ${mark(hasVwap)}\n")
            append("${x.direction} nedeni: $reason")
        }
        holder.risk.text = "${x.signalValidityReason} • Destek ${x.support?.let { "%.2f".format(it) } ?: "veri yok"} • Direnç ${x.resistance?.let { "%.2f".format(it) } ?: "veri yok"}"
        holder.itemView.setOnClickListener { click(x) }
    }

    private fun formatAge(ms:Long):String = when {
        ms < 60_000L -> "${ms / 1000L} sn"
        ms < 3_600_000L -> "${ms / 60_000L} dk"
        else -> "${ms / 3_600_000L} sa"
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
        val glowColor = ColorUtils.setAlphaComponent(neon, (90 + (150 * t)).toInt().coerceIn(0,255))
        holder.score.setShadowLayer(glowRadius, 0f, 0f, glowColor)
        holder.strengthValue.setShadowLayer(glowRadius * 0.7f, 0f, 0f, glowColor)
        holder.itemView.elevation = dp(holder.itemView, if (strength >= 85) 8f else 2f + (4f * t))
    }

    private fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density
}
