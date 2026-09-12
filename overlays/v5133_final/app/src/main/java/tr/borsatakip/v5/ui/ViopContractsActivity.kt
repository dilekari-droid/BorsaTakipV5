package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.model.ViopContract
import java.util.Locale

class ViopContractsActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private lateinit var suggestions: RecyclerView
    private lateinit var suggestionPanel: LinearLayout
    private lateinit var suggestionTitle: TextView
    private lateinit var search: EditText
    private lateinit var status: TextView

    private var all: List<ViopContract> = emptyList()
    private var category = "ALL"
    private var query = ""
    private var suppressWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop_contracts)
        setupBottomNav()

        list = findViewById(R.id.viopContractsList)
        suggestions = findViewById(R.id.viopSuggestions)
        suggestionPanel = findViewById(R.id.viopSuggestionPanel)
        suggestionTitle = findViewById(R.id.viopSuggestionTitle)
        search = findViewById(R.id.viopSearch)
        status = findViewById(R.id.viopStatus)

        list.layoutManager = LinearLayoutManager(this)
        suggestions.layoutManager = LinearLayoutManager(this)

        mapOf(
            R.id.viopBist30 to "BIST",
            R.id.viopFx to "FX",
            R.id.viopCommodity to "EMTIA",
            R.id.viopOther to "OTHER"
        ).forEach { (id, code) ->
            findViewById<Button>(id).setOnClickListener {
                category = code
                bindMainList()
                updateSuggestions()
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressWatcher) return
                query = s?.toString().orEmpty().trim()
                bindMainList()
                updateSuggestions()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        load()
    }

    private fun load() {
        status.text = "Production VİOP sözleşme evreni yükleniyor..."
        lifecycleScope.launch {
            val repository = ViopRepository(this@ViopContractsActivity)
            val localItems = repository.loadLocal()
            val readiness = ProviderReadinessService(this@ViopContractsActivity).localConfigState()

            if (readiness.state != ProviderState.PROVIDER_READY) {
                all = localItems
                status.text = if (localItems.isNotEmpty()) {
                    "${localItems.size} yerel sözleşme hazır • Production backend yapılandırılmadığı için yeni gerçek sözleşme evreni alınamadı."
                } else {
                    "BLOCKED • ${readiness.state} • ${readiness.message} • Otomatik öneriler için production VİOP backend'ini yapılandırın."
                }
                bindMainList()
                updateSuggestions()
                return@launch
            }

            val out = repository.refreshDetailed()
            all = (out.productionItems + out.manualItems)
                .distinctBy { it.symbol.uppercase(Locale.ROOT) }
            status.text = "${out.message} • Arama ilk harften itibaren otomatik öneri verir."
            bindMainList()
            updateSuggestions()
        }
    }

    private fun filteredByCategory(source: List<ViopContract>): List<ViopContract> = when (category) {
        "FX" -> source.filter {
            it.underlying.contains("USD", true) || it.underlying.contains("EUR", true) ||
                it.symbol.contains("USD", true) || it.symbol.contains("EUR", true)
        }
        "EMTIA" -> source.filter {
            listOf("GOLD", "ALTIN", "SILVER", "GUMUS", "BRENT").any { q ->
                it.underlying.contains(q, true) || it.symbol.contains(q, true)
            }
        }
        "BIST" -> source.filter { it.underlying.startsWith("XU") || it.underlying.contains("BIST", true) }
        "OTHER" -> source.filterNot {
            it.underlying.startsWith("XU") || it.underlying.contains("BIST", true) ||
                it.symbol.contains("USD", true) || it.symbol.contains("EUR", true)
        }
        else -> source
    }

    private fun bindMainList() {
        var items = filteredByCategory(all)
        if (query.isNotBlank()) items = items.filter { matches(it, query) }
        list.adapter = ViopAdapter(items, onClick = { openContract(it) })
    }

    private fun updateSuggestions() {
        val q = query.trim()
        if (q.isEmpty()) {
            suggestionPanel.visibility = View.GONE
            suggestions.adapter = ViopSuggestionAdapter(emptyList()) { }
            return
        }

        val candidates = filteredByCategory(all)
            .filter { matches(it, q) }
            .sortedWith(
                compareByDescending<ViopContract> { it.symbol.startsWith(q, ignoreCase = true) }
                    .thenByDescending { it.underlying.startsWith(q, ignoreCase = true) }
                    .thenBy { it.symbol }
            )
            .take(12)

        if (candidates.isEmpty()) {
            suggestionPanel.visibility = View.VISIBLE
            suggestionTitle.text = "'$q' için eşleşen sözleşme bulunamadı"
            suggestions.adapter = ViopSuggestionAdapter(emptyList()) { }
            return
        }

        suggestionPanel.visibility = View.VISIBLE
        suggestionTitle.text = "Otomatik öneriler • ${candidates.size} sonuç"
        suggestions.adapter = ViopSuggestionAdapter(candidates) { contract ->
            suppressWatcher = true
            search.setText(contract.symbol)
            search.setSelection(search.text.length)
            suppressWatcher = false
            query = contract.symbol
            suggestionPanel.visibility = View.GONE
            list.adapter = ViopAdapter(listOf(contract), onClick = { openContract(it) })
            status.text = "${contract.symbol} seçildi • Detaylarını görmek için karta dokunun."
        }
    }

    private fun matches(contract: ViopContract, rawQuery: String): Boolean {
        val q = rawQuery.trim().uppercase(Locale.ROOT)
        if (q.isEmpty()) return true
        return contract.symbol.uppercase(Locale.ROOT).contains(q) ||
            contract.underlying.uppercase(Locale.ROOT).contains(q) ||
            contract.contractType.uppercase(Locale.ROOT).contains(q)
    }

    private fun openContract(contract: ViopContract) {
        AppSession.selectedViopContract = contract
        startActivity(Intent(this, ViopDetailActivity::class.java))
    }
}
