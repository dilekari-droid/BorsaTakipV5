package tr.borsatakip.v5.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.model.ViopContract

class ViopActivity : BaseActivity() {
    private lateinit var repo: ViopRepository
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var providerStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop)
        setupBottomNav()

        repo = ViopRepository(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        providerStatus = findViewById(R.id.providerStatus)
        list.layoutManager = LinearLayoutManager(this)

        refreshProviderLabel()
        render(repo.loadLocal())
        if (repo.loadLocal().isEmpty()) status.text = "Henüz kayıtlı VİOP sözleşmesi yok. Manuel ekleyebilir veya backend'den yenileyebilirsiniz."

        findViewById<Button>(R.id.refresh).setOnClickListener { refreshContracts() }
        findViewById<Button>(R.id.addContract).setOnClickListener { showAddDialog() }
    }

    private fun refreshProviderLabel() {
        val s = SettingsStore(this)
        providerStatus.text = if (s.baseUrl.startsWith("https://")) {
            "Provider: Ana Backend • Canlı veri: yapılandırılmış"
        } else {
            "Provider: yapılandırılmamış • Canlı veri: BAĞLI DEĞİL"
        }
    }

    private fun refreshContracts() {
        status.text = "VİOP sözleşmeleri yenileniyor..."
        lifecycleScope.launch {
            val (items, message) = repo.refresh()
            render(items)
            status.text = if (items.isEmpty()) "$message\nKayıtlı sözleşme yok." else message
            refreshProviderLabel()
        }
    }

    private fun render(items: List<ViopContract>) {
        list.adapter = ViopAdapter(items) { contract -> confirmDelete(contract) }
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_viop_contract, null, false)
        val underlying = view.findViewById<EditText>(R.id.inputUnderlying)
        val expiry = view.findViewById<EditText>(R.id.inputExpiry)
        val symbol = view.findViewById<EditText>(R.id.inputSymbol)
        val provider = view.findViewById<Spinner>(R.id.inputProvider)
        val providers = listOf("Manuel / Veri Yok", "Ana Backend")
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)

        val dialog = AlertDialog.Builder(this)
            .setTitle("VİOP Sözleşme Ekle")
            .setView(view)
            .setNegativeButton("İPTAL", null)
            .setPositiveButton("EKLE", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = underlying.text.toString().trim().uppercase()
                val e = expiry.text.toString().trim()
                val s = symbol.text.toString().trim().uppercase()

                when {
                    u.isBlank() -> underlying.error = "Dayanak zorunlu"
                    !e.matches(Regex("\\d{4}-\\d{2}")) -> expiry.error = "Vade YYYY-MM biçiminde olmalı"
                    s.isBlank() -> symbol.error = "Gerçek sözleşme kodu zorunlu"
                    else -> {
                        val backendSelected = provider.selectedItemPosition == 1
                        val result = repo.addManual(
                            ViopContract(
                                symbol = s,
                                underlying = u,
                                expiry = e,
                                providerId = if (backendSelected) "backend" else "manual",
                                providerLabel = if (backendSelected) "Ana Backend" else "Manuel",
                                isManual = true,
                                status = if (backendSelected) "Provider doğrulaması bekleniyor" else "Veri bekleniyor",
                                dataTimestamp = System.currentTimeMillis()
                            )
                        )
                        result.onSuccess {
                            render(repo.loadLocal())
                            status.text = "$s sözleşmesi kaydedildi."
                            dialog.dismiss()
                        }.onFailure {
                            symbol.error = it.message ?: "Sözleşme kaydedilemedi."
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(contract: ViopContract) {
        AlertDialog.Builder(this)
            .setTitle("Sözleşmeyi sil")
            .setMessage("${contract.symbol} manuel kaydı silinsin mi?")
            .setNegativeButton("İPTAL", null)
            .setPositiveButton("SİL") { _, _ ->
                repo.removeManual(contract.symbol)
                render(repo.loadLocal())
                status.text = "${contract.symbol} silindi."
            }
            .show()
    }
}
