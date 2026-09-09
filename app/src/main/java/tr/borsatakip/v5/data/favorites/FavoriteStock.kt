package tr.borsatakip.v5.data.favorites

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_stocks")
data class FavoriteStock(
    @PrimaryKey val symbol: String,
    val displayName: String? = null,
    val market: String = "BIST",
    val createdAt: Long = System.currentTimeMillis()
)
