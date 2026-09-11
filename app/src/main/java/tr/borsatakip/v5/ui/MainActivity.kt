package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.model.Opportunity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupBottomNav()

        findViewById<TextView>(R.id.txtVersionSubtitle).text =
            "V${BuildConfig.VERSION_NAME} • Profesyonel fırsat takibi"
        findViewById<TextView>(R.id.txtVersionBadge).text = "V${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.btnBist).setOnClickListener {
            startActivity(Intent(this, BistScanActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpportunity).setOnClickListener {
            startActivity(Intent(this, OpportunityActivity::class.java))
        }
        findViewById<Button>(R.id.btnViop).setOnClickListener {
            startActivity(Intent(this, ViopActivity::class.java))
        }
        findViewById<Button>(R.id.btnFav).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        findViewById<Button>(R.id.btnNotifications).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        renderDashboard()
    }

    override fun onResume() {
        super.onResume()
        renderDashboard()
    }

    private fun renderDashboard() {
        val settings = SettingsStore(this)
        val opportunities = AppSession.lastOpportunities
            .sortedByDescending { it.finalSignalScore }

        val providerTime = settings.lastProviderTimestamp
            .takeIf { it > 0L }
            ?.let { SimpleDateFormat("HH:mm:ss", Locale("tr", "TR")).format(Date(it)) }
            ?: "—"

        val marketState = when {
            opportunities.any { it.isRealtime } -> "● CANLI VERİ"
            opportunities.isNotEmpty() -> "● GECİKMELİ / DOĞRULANMIŞ VERİ"
            settings.lastProviderTimestamp > 0L -> "● VERİ ALINDI"
            else -> "● VERİ BEKLENİYOR"
        }

        findViewById<TextView>(R.id.txtMarketStatus).text = buildString {
            append(marketState)
            append("\nKaynak: ${settings.lastProviderLabel}")
            append("\nSon güncelleme: $providerTime")
        }

        val longCount = opportunities.count { it.direction.equals("LONG", true) }
        val shortCount = opportunities.count { it.direction.equals("SHORT", true) }
        val highCount = opportunities.count { it.finalSignalScore >= 85 }
        findViewById<TextView>(R.id.txtScanSummary).text = if (opportunities.isEmpty()) {
            "Henüz doğrulanmış tarama sonucu yok."
        } else {
            "${opportunities.size} fırsat adayı • LONG $longCount • SHORT $shortCount • 85+ $highCount"
        }

        val top = opportunities.take(4)
        findViewById<TextView>(R.id.txtToday).text = if (top.isEmpty()) {
            "Henüz fırsat adayı yok. Tarama başlatıldığında doğrulanmış adaylar burada gösterilir."
        } else {
            buildTodayText(top)
        }
    }

    private fun buildTodayText(items: List<Opportunity>): CharSequence {
        val out = SpannableStringBuilder()
        items.forEachIndexed { index, opportunity ->
            if (index > 0) out.append("\n\n")
            val start = out.length
            val direction = opportunity.direction.uppercase(Locale.ROOT).ifBlank { "TREND BİLİNMİYOR" }
            out.append("${opportunity.symbol} $direction ${opportunity.finalSignalScore}/100")
            out.setSpan(
                ForegroundColorSpan(trendColor(opportunity.direction)),
                start,
                out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.append("\nRSI ${opportunity.technical.rsi14?.let { "%.1f".format(it) } ?: "—"} • MACD ${macdLabel(opportunity)}")
            opportunity.lrc?.let { lrc ->
                val arrow = when (lrc.trend) {
                    "YÜKSELEN" -> "↑"
                    "DÜŞEN" -> "↓"
                    else -> "→"
                }
                out.append("\nLRC100 $arrow R ${"%.2f".format(lrc.pearsonR)} • ${lrc.channelPosition}")
            }
        }
        return out
    }

    private fun trendColor(direction: String?): Int = when (direction?.uppercase(Locale.ROOT)) {
        "LONG" -> ContextCompat.getColor(this, R.color.green)
        "SHORT" -> ContextCompat.getColor(this, R.color.red)
        else -> ContextCompat.getColor(this, R.color.text_primary)
    }

    private fun macdLabel(opportunity: Opportunity): String {
        val macd = opportunity.technical.macd
        val signal = opportunity.technical.macdSignal
        return when {
            macd == null || signal == null || !macd.isFinite() || !signal.isFinite() -> "—"
            macd > signal -> "Pozitif"
            macd < signal -> "Negatif"
            else -> "Nötr"
        }
    }
}
