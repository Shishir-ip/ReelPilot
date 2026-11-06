package com.reelpilot.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object Status {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val READY = "READY"
    const val POSTING = "POSTING"
    const val POSTED = "POSTED"
    const val FAILED = "FAILED"

    val active = listOf(QUEUED, DOWNLOADING, READY, POSTING)
}

@Entity(tableName = "queue")
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,                 // "link" or "file"
    val url: String,                    // page link, or telegram fileId for direct files
    val title: String,
    val caption: String,
    val chatId: Long,                   // telegram chat to reply into
    val status: String = Status.QUEUED,
    val progress: Int = 0,              // download progress 0..100
    val localUri: String? = null,       // MediaStore content uri once downloaded
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY createdAt DESC")
    fun all(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestWithStatus(status: String): QueueItem?

    @Query("SELECT * FROM queue WHERE id = :id")
    suspend fun byId(id: Long): QueueItem?

    @Query("SELECT COUNT(*) FROM queue WHERE status IN ('QUEUED','DOWNLOADING','READY','POSTING')")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM queue WHERE status = 'READY'")
    fun readyCountFlow(): Flow<Int>

    @Insert
    suspend fun insert(item: QueueItem): Long

    @Update
    suspend fun update(item: QueueItem)

    @Query("DELETE FROM queue WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM queue WHERE status = 'POSTED'")
    suspend fun clearPosted()

    @Query("UPDATE queue SET status = 'READY', error = NULL, progress = 100 WHERE id = :id")
    suspend fun retry(id: Long)
}

@Database(entities = [QueueItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "reelpilot.db"
                ).build().also { instance = it }
            }
    }
}
