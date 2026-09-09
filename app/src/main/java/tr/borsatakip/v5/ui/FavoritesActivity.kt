package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.favorites.FavoriteRepository

class FavoritesActivity : BaseActivity() {
    private lateinit var repository: FavoriteRepository
    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var emptyState: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        setupBottomNav()

        repository = FavoriteRepository.get(this)
        list = findViewById(R.id.favoriteList)
        summary = findViewById(R.id.favoriteSummary)
        emptyState = findViewById(R.id.favoriteEmptyState)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.openOpportunities).setOnClickListener {
            startActivity(Intent(this, OpportunityActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            repository.migrateLegacyIfNeeded()
            refresh()
        }
    }

    private suspend fun refresh() {
        val favorites = repository.getAll()
        val latestBySymbol = AppSession.lastOpportunities.associateBy { FavoriteRepository.normalizeSymbol(it.symbol) }
        val rows = favorites.map { it to latestBySymbol[it.symbol] }
        val isEmpty = rows.isEmpty()

        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        summary.text = if (isEmpty) {
            "Favoriler Room veritabanında kalıcı olarak saklanır."
        } else {
            "${rows.size} kalıcı favori • Son Fırsat taramasındaki skor/risk verileri eşleştirildi"
        }

        list.adapter = FavoriteAdapter(
            rows,
            onRemove = { favorite ->
                lifecycleScope.launch {
                    repository.remove(favorite.symbol, favorite.market)
                    Toast.makeText(this@FavoritesActivity, "${favorite.symbol} favorilerden çıkarıldı", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            },
            onOpen = { opportunity ->
                AppSession.selected = opportunity
                startActivity(Intent(this@FavoritesActivity, StockDetailActivity::class.java))
            }
        )
    }
}
