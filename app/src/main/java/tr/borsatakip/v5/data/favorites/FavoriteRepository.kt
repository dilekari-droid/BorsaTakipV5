package tr.borsatakip.v5.data.favorites

import android.content.Context

class FavoriteRepository private constructor(private val context: Context) {
    private val dao = FavoritesDatabase.get(context).favoriteDao()

    suspend fun getAll(): List<FavoriteStock> = dao.getAll()

    suspend fun symbols(): Set<String> = dao.getAll().map { it.symbol }.toSet()

    suspend fun isFavorite(rawSymbol: String): Boolean = dao.contains(normalizeSymbol(rawSymbol))

    suspend fun add(rawSymbol: String, displayName: String? = null, market: String = "BIST") {
        val symbol = normalizeSymbol(rawSymbol)
        require(symbol.matches(Regex("[A-Z0-9]{3,12}"))) { "Geçersiz sembol" }
        dao.upsert(FavoriteStock(symbol = symbol, displayName = displayName?.trim()?.takeIf { it.isNotBlank() }, market = market))
    }

    suspend fun remove(rawSymbol: String) {
        dao.deleteBySymbol(normalizeSymbol(rawSymbol))
    }

    suspend fun toggle(rawSymbol: String, displayName: String? = null, market: String = "BIST"): Boolean {
        val symbol = normalizeSymbol(rawSymbol)
        return if (dao.contains(symbol)) {
            dao.deleteBySymbol(symbol)
            false
        } else {
            add(symbol, displayName, market)
            true
        }
    }

    suspend fun migrateLegacyIfNeeded() {
        val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
        if (prefs.getBoolean("room_migrated", false)) return
        val legacy = prefs.getStringSet("bist", emptySet()).orEmpty()
        legacy.forEach { raw ->
            runCatching { add(raw) }
        }
        prefs.edit().remove("bist").putBoolean("room_migrated", true).apply()
    }

    companion object {
        @Volatile private var INSTANCE: FavoriteRepository? = null

        fun get(context: Context): FavoriteRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: FavoriteRepository(context.applicationContext).also { INSTANCE = it }
        }

        fun normalizeSymbol(raw: String): String = raw
            .trim()
            .uppercase()
            .removePrefix("BIST:")
            .removeSuffix(".IS")
            .trim()
    }
}
