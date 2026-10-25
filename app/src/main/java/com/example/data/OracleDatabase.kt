package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "oracle_history")
data class OracleHistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val question: String,
    val answer: String,
    val provider: String,
    val model: String,
    val latencyMs: Long,
    val isStaticDisplay: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface OracleHistoryDao {
    @Query("SELECT * FROM oracle_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<OracleHistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: OracleHistoryItem): Long

    @Query("DELETE FROM oracle_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM oracle_history")
    suspend fun clearAll()
}

@Database(entities = [OracleHistoryItem::class], version = 1, exportSchema = false)
abstract class OracleDatabase : RoomDatabase() {
    abstract fun historyDao(): OracleHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: OracleDatabase? = null

        fun getInstance(context: Context): OracleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OracleDatabase::class.java,
                    "glyph_oracle_db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class OracleHistoryRepository(private val dao: OracleHistoryDao) {
    val allHistory: Flow<List<OracleHistoryItem>> = dao.getAllHistory()

    suspend fun insert(item: OracleHistoryItem): Long = dao.insertItem(item)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
