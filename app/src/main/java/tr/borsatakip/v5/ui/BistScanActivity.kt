package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.data.MobileMarketDataProvider
import tr.borsatakip.v5.data.SettingsStore

class BistScanActivity:BaseActivity(){
    override fun onCreate(savedInstanceState:Bundle?){ super.onCreate(savedInstanceState); setContentView(R.layout.activity_bist_scan); setupBottomNav()
        val progress=findViewById<ProgressBar>(R.id.progress); val txt=findViewById<TextView>(R.id.txtProgress); val status=findViewById<TextView>(R.id.txtStatus); val btn=findViewById<Button>(R.id.btnStartScan)
        val settings = SettingsStore(this)
        findViewById<TextView>(R.id.txtSource).text=if(settings.baseUrl.isBlank()) "Kaynak: Mobil veri servisi tanımlı değil" else "Kaynak: ${settings.baseUrl}"
        btn.setOnClickListener{
            btn.isEnabled=false; status.text="Mobil BIST veri servisine bağlanılıyor..."
            lifecycleScope.launch{
                try {
                    val stocks=MobileMarketDataProvider(this@BistScanActivity).scan{done,total-> runOnUiThread{ val pct=if(total==0)0 else done*100/total; progress.progress=pct; txt.text="$done / $total • %$pct" }}
                    var ops=stocks.mapNotNull{OpportunityEngine.score(it)}
                    if(findViewById<com.google.android.material.chip.Chip>(R.id.chipTechnical).isChecked) ops=ops.filter{it.score>=65}
                    if(findViewById<com.google.android.material.chip.Chip>(R.id.chipVolume).isChecked) ops=ops.filter{(it.technical.volumeRatio?:0.0)>=1.2}
                    if(findViewById<com.google.android.material.chip.Chip>(R.id.chipBreakout).isChecked) ops=ops.filter{ x ->
                        if(x.direction=="LONG") x.resistance?.let{x.price>=it*0.995}==true else x.support?.let{x.price<=it*1.005}==true
                    }
                    val longOn=findViewById<com.google.android.material.chip.Chip>(R.id.chipLong).isChecked
                    val shortOn=findViewById<com.google.android.material.chip.Chip>(R.id.chipShort).isChecked
                    ops=ops.filter{(it.direction=="LONG"&&longOn)||(it.direction=="SHORT"&&shortOn)}.sortedByDescending{it.score}
                    AppSession.lastOpportunities=ops
                    status.text="${stocks.size} hisse için yeterli veri alındı. ${ops.size} sonuç seçili kriterleri karşıladı. Bedelsiz/temettü filtreleri doğrulanmış kurumsal olay API'si bağlanana kadar devre dışıdır."
                    startActivity(Intent(this@BistScanActivity,OpportunityActivity::class.java))
                } catch (e: Exception) {
                    status.text = e.message ?: "Mobil veri servisine bağlanılamadı."
                } finally {
                    btn.isEnabled=true
                }
            }
        }
    }
}
