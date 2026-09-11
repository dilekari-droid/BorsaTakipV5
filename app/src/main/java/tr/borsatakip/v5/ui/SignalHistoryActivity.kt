package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.HistoryLoadState
import tr.borsatakip.v5.data.SignalHistoryFilter
import tr.borsatakip.v5.data.SignalHistoryStore
import java.text.SimpleDateFormat
import java.util.Locale

class SignalHistoryActivity : BaseActivity() {
    private lateinit var store: SignalHistoryStore
    private lateinit var status: TextView
    private lateinit var text: TextView
    private lateinit var symbol: EditText
    private lateinit var from: EditText
    private lateinit var to: EditText
    private var direction: String? = null
    private var minScore: Int? = null
    private val dayFormat = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR")).apply { isLenient = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signal_history)
        setupBottomNav()
        store = SignalHistoryStore(this)
        status = findViewById(R.id.historyStatus)
        text = findViewById(R.id.historyText)
        symbol = findViewById(R.id.historySymbol)
        from = findViewById(R.id.historyFrom)
        to = findViewById(R.id.historyTo)

        findViewById<Button>(R.id.historyAll).setOnClickListener { direction = null; minScore = null; load() }
        findViewById<Button>(R.id.historyLong).setOnClickListener { direction = "LONG"; minScore = null; load() }
        findViewById<Button>(R.id.historyShort).setOnClickListener { direction = "SHORT"; minScore = null; load() }
        findViewById<Button>(R.id.history85).setOnClickListener { direction = null; minScore = 85; load() }
        findViewById<Button>(R.id.history75).setOnClickListener { direction = null; minScore = 75; load() }
        findViewById<Button>(R.id.historyApply).setOnClickListener { load() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val fromMs = parseStart(from.text.toString().trim())
            val toMs = parseEnd(to.text.toString().trim())
            val filter = SignalHistoryFilter(
                direction = direction,
                minScore = minScore,
                symbol = symbol.text.toString().trim().ifBlank { null },
                fromTime = fromMs,
                toTime = toMs
            )
            val overview = store.overview()
            val lines = store.summaryLines(filter = filter)
            status.text = when (overview.state) {
                HistoryLoadState.NO_HISTORY -> "NO_HISTORY • Geçerli sinyal geçmişi yok."
                HistoryLoadState.PARTIAL_HISTORY -> "PARTIAL_HISTORY • Kısmi taramadan ${overview.totalRecords} geçerli sinyal kaydı mevcut."
                HistoryLoadState.COMPLETE_HISTORY -> "COMPLETE_HISTORY • ${overview.totalRecords} geçerli sinyal kaydı mevcut."
                HistoryLoadState.HISTORY_LOAD_ERROR -> "HISTORY_LOAD_ERROR • Sinyal geçmişi okunamadı."
            }
            text.text = when {
                overview.state == HistoryLoadState.HISTORY_LOAD_ERROR -> "Geçmiş dosyası okunamadı; kayıt yokmuş gibi gösterilmedi."
                lines.isEmpty() && overview.totalRecords == 0 -> "Kayıt yok."
                lines.isEmpty() -> "Seçili filtreye uyan kayıt yok."
                else -> lines.joinToString("\n\n────────────────────\n\n")
            }
        }
    }

    private fun parseStart(value: String): Long? = if (value.isBlank()) null else runCatching {
        dayFormat.parse(value)?.time
    }.getOrNull()

    private fun parseEnd(value: String): Long? = if (value.isBlank()) null else runCatching {
        (dayFormat.parse(value)?.time ?: return@runCatching null) + 86_400_000L - 1L
    }.getOrNull()
}
