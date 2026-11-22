package tw.timecam.upload

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "upload_items")
data class UploadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUri: String,
    val localPath: String,
    val name: String,
    val mime: String,
    val createdAt: Long,
    val status: String = ST_PENDING, // pending / uploading / done / error
    val lastError: String = "",
    val driveFileId: String = "",
    val attempts: Int = 0,
) {
    companion object {
        const val ST_PENDING = "pending"
        const val ST_UPLOADING = "uploading"
        const val ST_DONE = "done"
        const val ST_ERROR = "error"
    }
}

@Dao
interface UploadDao {
    @Insert suspend fun insert(item: UploadItem): Long
    @Update suspend fun update(item: UploadItem)
    @Query("SELECT * FROM upload_items WHERE status IN ('pending','error') ORDER BY createdAt ASC")
    suspend fun toUpload(): List<UploadItem>
    @Query("SELECT * FROM upload_items WHERE status IN ('pending','error','uploading') ORDER BY createdAt DESC")
    fun observeOpen(): Flow<List<UploadItem>>
    @Query("SELECT COUNT(*) FROM upload_items WHERE status IN ('pending','error','uploading')")
    suspend fun openCount(): Int
    @Query("SELECT COUNT(*) FROM upload_items WHERE status IN ('pending','error','uploading')")
    fun observeOpenCount(): Flow<Int>
    @Query("UPDATE upload_items SET status=:status, lastError=:err, attempts=attempts+1 WHERE id=:id")
    suspend fun mark(id: Long, status: String, err: String)
    @Query("UPDATE upload_items SET status='done', driveFileId=:fid, lastError='' WHERE id=:id")
    suspend fun markDone(id: Long, fid: String)
    @Query("UPDATE upload_items SET status='uploading' WHERE id=:id")
    suspend fun markUploading(id: Long)
}

@Database(entities = [UploadItem::class], version = 1, exportSchema = false)
abstract class UploadDb : RoomDatabase() {
    abstract fun dao(): UploadDao
    companion object {
        @Volatile private var inst: UploadDb? = null
        fun get(c: Context): UploadDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, UploadDb::class.java, "timecam-upload.db").build().also { inst = it }
        }
        fun inMemory(c: Context): UploadDb = Room.inMemoryDatabaseBuilder(c.applicationContext, UploadDb::class.java).allowMainThreadQueries().build()
    }
}
