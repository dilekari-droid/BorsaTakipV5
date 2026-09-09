package tr.borsatakip.v5.data.favorites

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_stocks ORDER BY createdAt DESC")
    suspend fun getAll(): List<FavoriteStock>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_stocks WHERE symbol = :symbol)")
    suspend fun contains(symbol: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteStock)

    @Query("DELETE FROM favorite_stocks WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)
}
