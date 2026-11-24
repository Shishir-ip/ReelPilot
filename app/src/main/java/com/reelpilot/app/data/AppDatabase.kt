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
    val postAt: Long = 0,               // scheduled upload time (0 = automatic slot)
    val uploadedAt: Long = 0,           // when the reel actually went live (for auto-delete)
    val doneTargets: String = "",       // target apps this video has already been posted to
    val generatedCaption: String = "",  // AI caption from SocialKit (link videos)
    val captionStatus: String = "",     // "", "pending", "done", "failed", "skipped"
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY createdAt DESC")
    fun all(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestWithStatus(status: String): QueueItem?

    @Query("SELECT * FROM queue WHERE status = :status ORDER BY createdAt ASC")
    suspend fun byStatus(status: String): List<QueueItem>

    @Query("SELECT * FROM queue WHERE status IN ('QUEUED','DOWNLOADING','READY','POSTING','FAILED') ORDER BY createdAt ASC")
    suspend fun activeItems(): List<QueueItem>

    @Query("DELETE FROM queue WHERE status IN ('QUEUED','DOWNLOADING','READY','POSTING','FAILED')")
    suspend fun clearActive()

    @Query("SELECT * FROM queue WHERE status = 'READY' AND postAt > 0 AND postAt <= :now ORDER BY postAt ASC LIMIT 1")
    suspend fun dueReady(now: Long): QueueItem?

    @Query("UPDATE queue SET postAt = :postAt WHERE id = :id")
    suspend fun setPostAt(id: Long, postAt: Long)

    // ---- Targeted column updates -----------------------------------------
    // Workers run in parallel (download + caption + posting). Whole-row
    // @Update writes from stale snapshots used to overwrite each other's
    // fields (the "stuck on DOWNLOADING" bug). Every concurrent writer must
    // use these single-purpose updates instead of update(item.copy(...)).

    @Query("UPDATE queue SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE queue SET progress = :progress WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun setProgress(id: Long, progress: Int)

    @Query("UPDATE queue SET status = 'READY', localUri = :uri, progress = 100, error = NULL WHERE id = :id")
    suspend fun markDownloaded(id: Long, uri: String)

    @Query("UPDATE queue SET status = 'FAILED', error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String?)

    @Query("UPDATE queue SET captionStatus = :status WHERE id = :id")
    suspend fun setCaptionStatus(id: Long, status: String)

    @Query("UPDATE queue SET generatedCaption = :caption, captionStatus = 'done' WHERE id = :id")
    suspend fun setGeneratedCaption(id: Long, caption: String)

    @Query("UPDATE queue SET doneTargets = :done WHERE id = :id")
    suspend fun setDoneTargets(id: Long, done: String)

    @Query("UPDATE queue SET status = 'POSTED', uploadedAt = :at WHERE id = :id")
    suspend fun markPosted(id: Long, at: Long)

    @Query("UPDATE queue SET localUri = NULL WHERE id = :id")
    suspend fun clearLocalUri(id: Long)

    @Query("UPDATE queue SET status = 'QUEUED', progress = 0 WHERE id = :id")
    suspend fun requeue(id: Long)

    @Query("SELECT * FROM queue WHERE captionStatus = 'pending'")
    suspend fun pendingCaptions(): List<QueueItem>

    @Query("SELECT localUri FROM queue WHERE localUri IS NOT NULL")
    suspend fun allLocalUris(): List<String>

    @Query("SELECT * FROM queue WHERE id = :id")
    suspend fun byId(id: Long): QueueItem?

    @Query("SELECT COUNT(*) FROM queue WHERE status IN ('QUEUED','DOWNLOADING','READY','POSTING')")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM queue WHERE status = 'READY'")
    fun readyCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queue WHERE url = :url AND status IN ('QUEUED','DOWNLOADING','READY','POSTING')")
    suspend fun activeCountForUrl(url: String): Int

    @Query("SELECT COUNT(*) FROM queue WHERE status = 'POSTED'")
    suspend fun postedCount(): Int

    @Query("SELECT * FROM queue WHERE status = 'POSTED' AND uploadedAt > 0 AND uploadedAt < :cutoff AND localUri IS NOT NULL LIMIT 50")
    suspend fun expiredPosted(cutoff: Long): List<QueueItem>

    @Insert
    suspend fun insert(item: QueueItem): Long

    @Update
    suspend fun update(item: QueueItem)

    @Query("DELETE FROM queue WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM queue WHERE status = 'POSTED'")
    suspend fun clearPosted()

    @Query("UPDATE queue SET status = 'READY', error = NULL, progress = 100, postAt = 0 WHERE id = :id")
    suspend fun retry(id: Long)

    /**
     * Only runCheck sets POSTING, and it is serialized by its busy lock.
     * So any POSTING row seen at the start of a check is an orphan from a
     * killed/crashed attempt — put it back to READY (keeping postAt and
     * doneTargets so the schedule and partial progress survive).
     */
    @Query("UPDATE queue SET status = 'READY' WHERE status = 'POSTING'")
    suspend fun resetStalePosting(): Int
}

@Database(entities = [QueueItem::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue ADD COLUMN postAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue ADD COLUMN uploadedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue ADD COLUMN doneTargets TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue ADD COLUMN generatedCaption TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE queue ADD COLUMN captionStatus TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "reelpilot.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}
