package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R

class OpportunityActivity:BaseActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_opportunity);setupBottomNav()
        val items=AppSession.lastOpportunities.sortedByDescending{it.score};findViewById<TextView>(R.id.txtSummary).text=if(items.isEmpty())"Önce BIST taraması çalıştırılmalıdır. Demo sonuç gösterilmez." else "${items.size} sonuç • Skor yüksekten düşüğe • Risk ayrı hesaplanır"
        findViewById<RecyclerView>(R.id.list).apply{layoutManager=LinearLayoutManager(this@OpportunityActivity);adapter=OpportunityAdapter(items){AppSession.selected=it;startActivity(Intent(this@OpportunityActivity,StockDetailActivity::class.java))}}
    }
}
