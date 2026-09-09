package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore

class BistScanActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bist_scan)
        setupBottomNav()

        val progress = findViewById<ProgressBar>(R.id.progress)
        val txt = findViewById<TextView>(R.id.txtProgress)
        val status = findViewById<TextView>(R.id.txtStatus)
        val btn = findViewById<Button>(R.id.btnStartScan)
        val source = findViewById<TextView>(R.id.txtSource)
        val settings = SettingsStore(this)

        fun refreshSourceLabel() {
            val primary = if (settings.baseUrl.startsWith("https://")) settings.baseUrl else "tanımlı değil"
            val fallback = if (settings.yahooFallbackEnabled) "açık" else "kapalı"
            val cached = settings.cachedBistSymbols.size
            source.text = "Ana kaynak: $primary\nYedek/gecikmeli kaynak: Yahoo Finance ($fallback)\nDinamik BIST evren önbelleği: $cached sembol"
        }
        refreshSourceLabel()

        btn.setOnClickListener {
            btn.isEnabled = false
            status.text = "Ana BIST veri sağlayıcısına bağlanılıyor..."
            lifecycleScope.launch {
                try {
                    val stocks = ProviderRouter(this@BistScanActivity).scan { done, total ->
                        runOnUiThread {
                            val pct = if (total == 0) 0 else done * 100 / total
                            progress.progress = pct
                            txt.text = "$done / $total • %$pct"
                        }
                    }
                    var ops = stocks.mapNotNull { OpportunityEngine.score(it) }
                    if (findViewById<Chip>(R.id.chipTechnical).isChecked) ops = ops.filter { it.score >= 65 }
                    if (findViewById<Chip>(R.id.chipVolume).isChecked) ops = ops.filter { (it.technical.volumeRatio ?: 0.0) >= 1.2 }
                    if (findViewById<Chip>(R.id.chipBreakout).isChecked) ops = ops.filter { x ->
                        if (x.direction == "LONG") x.resistance?.let { x.price >= it * 0.995 } == true
                        else x.support?.let { x.price <= it * 1.005 } == true
                    }
                    val longOn = findViewById<Chip>(R.id.chipLong).isChecked
                    val shortOn = findViewById<Chip>(R.id.chipShort).isChecked
                    ops = ops.filter { (it.direction == "LONG" && longOn) || (it.direction == "SHORT" && shortOn) }
                        .sortedByDescending { it.score }
                    AppSession.lastOpportunities = ops
                    val active = settings.lastProviderLabel
                    status.text = "${stocks.size} hisse analiz edildi. ${ops.size} sonuç kriterleri karşıladı. Aktif kaynak: $active"
                    refreshSourceLabel()
                    startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java))
                } catch (e: Exception) {
                    status.text = e.message ?: "Veri sağlayıcıya bağlanılamadı."
                } finally {
                    btn.isEnabled = true
                }
            }
        }
    }
}
