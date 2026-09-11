package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SignalHistoryStore

class SignalHistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signal_history)
        setupBottomNav()
        val status=findViewById<TextView>(R.id.historyStatus)
        val text=findViewById<TextView>(R.id.historyText)
        lifecycleScope.launch {
            val lines=SignalHistoryStore(this@SignalHistoryActivity).summaryLines()
            status.text=if(lines.isEmpty()) "Henüz COMPLETE taramadan kilitlenmiş sinyal kaydı yok." else "${lines.size} sinyal kaydı • Outcome zamanları WorkManager'ın gerçek çalışma anına göre yazılır."
            text.text=if(lines.isEmpty()) "Kayıt yok." else lines.joinToString("\n\n────────────────────\n\n")
        }
    }
}
