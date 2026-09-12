package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.Stock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BistWatchlistAdapter(
    private val items: List<Row>,
    private val onOpen: (Row) -> Unit,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<BistWatchlistAdapter.H>() {

    data class Row(val symbol: String, val stock: Stock?)

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol = v.findViewById<TextView>(R.id.watchSymbol)
        val company = v.findViewById<TextView>(R.id.watchCompany)
        val last = v.findViewById<TextView>(R.id.watchLast)
        val bid = v.findViewById<TextView>(R.id.watchBid)
        val ask = v.findViewById<TextView>(R.id.watchAsk)
        val change = v.findViewById<TextView>(R.id.watchChange)
        val meta = v.findViewById<TextView>(R.id.watchMeta)
        val remove = v.findViewById<TextView>(R.id.watchRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(
        LayoutInflater.from(parent.context).inflate(R.layout.item_bist_watchlist, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val row = items[position]
        val stock = row.stock
        holder.symbol.text = row.symbol
        holder.company.text = stock?.companyName ?: "BIST hissesi"
        val lastPrice = stock?.quotePrice ?: stock?.candles?.lastOrNull()?.close
        holder.last.text = lastPrice?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        holder.bid.text = stock?.bid?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        holder.ask.text = stock?.ask?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        val change = stock?.let { s ->
            val price = s.quotePrice ?: s.candles.lastOrNull()?.close
            val prev = s.previousClose
            if (price != null && prev != null && price.isFinite() && prev.isFinite() && prev > 0.0) (price / prev - 1.0) * 100.0 else null
        }
        holder.change.text = change?.let { "%+.2f%%".format(it) } ?: "—"
        holder.change.setTextColor(
            holder.itemView.context.getColor(
                when {
                    change == null -> R.color.text_secondary
                    change > 0.0 -> R.color.green
                    change < 0.0 -> R.color.red
                    else -> R.color.text_secondary
                }
            )
        )
        holder.meta.text = if (stock == null) {
            "Gerçek fiyat verisi alınamadı • Yenile ile tekrar deneyin"
        } else {
            val time = stock.exchangeTimestamp.takeIf { it > 0L }?.let {
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
            } ?: "zaman yok"
            val quoteType = if (stock.isRealtime) "Gerçek zamanlı" else stock.source
            buildString {
                append("Kaynak: ").append(stock.source).append(" • ").append(quoteType).append(" • ").append(time)
                if (stock.bid == null || stock.ask == null) append(" • Alış/Satış sağlayıcıda yok")
            }
        }
        holder.remove.setOnClickListener { onRemove(row.symbol) }
        holder.itemView.setOnClickListener { onOpen(row) }
    }
}
