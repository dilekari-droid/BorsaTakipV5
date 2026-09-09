package tr.borsatakip.v5.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.ViopContract

class ViopLocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("viop_local", Context.MODE_PRIVATE)

    fun load(): List<ViopContract> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val symbol = x.optString("symbol").trim().uppercase()
                    if (symbol.isBlank()) continue
                    add(
                        ViopContract(
                            symbol = symbol,
                            underlying = x.optString("underlying").ifBlank { "-" },
                            expiry = x.optString("expiry").ifBlank { "-" },
                            contractType = x.optString("contractType").ifBlank { "Vadeli İşlem" },
                            providerId = x.optString("providerId").ifBlank { "manual" },
                            providerLabel = x.optString("providerLabel").ifBlank { "Manuel" },
                            isManual = true,
                            status = "Veri bekleniyor",
                            dataTimestamp = x.optLong("dataTimestamp", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun add(contract: ViopContract): Result<Unit> = runCatching {
        val current = load().toMutableList()
        require(current.none { it.symbol.equals(contract.symbol, true) }) { "Bu sözleşme zaten kayıtlı." }
        current += contract.copy(isManual = true, status = "Veri bekleniyor")
        save(current)
    }

    fun remove(symbol: String) {
        save(load().filterNot { it.symbol.equals(symbol, true) })
    }

    private fun save(items: List<ViopContract>) {
        val a = JSONArray()
        items.forEach { c ->
            a.put(
                JSONObject()
                    .put("symbol", c.symbol)
                    .put("underlying", c.underlying)
                    .put("expiry", c.expiry)
                    .put("contractType", c.contractType)
                    .put("providerId", c.providerId)
                    .put("providerLabel", c.providerLabel)
                    .put("dataTimestamp", c.dataTimestamp)
            )
        }
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    companion object { private const val KEY = "contracts" }
}
