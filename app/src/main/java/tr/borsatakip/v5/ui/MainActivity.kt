package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import tr.borsatakip.v5.R

class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupBottomNav()
        findViewById<Button>(R.id.btnBist).setOnClickListener { startActivity(Intent(this, BistScanActivity::class.java)) }
        findViewById<Button>(R.id.btnOpportunity).setOnClickListener {
            startActivity(Intent(this, OpportunityActivity::class.java))
        }
        findViewById<Button>(R.id.btnViop).setOnClickListener { startActivity(Intent(this, ViopActivity::class.java)) }
        findViewById<Button>(R.id.btnFav).setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        findViewById<Button>(R.id.btnNotifications).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val top = AppSession.lastOpportunities.take(4)
        findViewById<TextView>(R.id.txtToday).text = if (top.isEmpty()) {
            "Günün Fırsatları\nHenüz gerçek tarama yapılmadı."
        } else {
            "Günün Fırsatları\n" + top.joinToString("\n") { "${it.symbol}  ${it.score}/100 • ${it.direction} • Risk ${it.riskScore}/100" }
        }
    }
}
