package tr.borsatakip.v5.data.favorites

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FavoriteStock::class], version = 1, exportSchema = false)
abstract class FavoritesDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var INSTANCE: FavoritesDatabase? = null

        fun get(context: Context): FavoritesDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                FavoritesDatabase::class.java,
                "borsa_favorites.db"
            ).build().also { INSTANCE = it }
        }
    }
}
