package tr.borsatakip.v5.ui
import android.os.Bundle
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendProvider
class ViopActivity:BaseActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_viop);setupBottomNav();val status=findViewById<TextView>(R.id.status);val list=findViewById<RecyclerView>(R.id.list);list.layoutManager=LinearLayoutManager(this);findViewById<Button>(R.id.refresh).setOnClickListener{lifecycleScope.launch{status.text="VİOP veri sağlayıcısına bağlanılıyor...";BackendProvider(this@ViopActivity).loadViop().onSuccess{status.text="${it.size} sözleşme alındı.";list.adapter=ViopAdapter(it)}.onFailure{status.text=it.message?:"VİOP verisi alınamadı."}}}}}
