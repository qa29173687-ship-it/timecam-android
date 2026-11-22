package tw.timecam.upload

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class UploadRepo(private val ctx: Context) {
    private val dao = UploadDb.get(ctx).dao()
    private val app = ctx.applicationContext

    fun observeOpenCount(): Flow<Int> = dao.observeOpenCount()
    suspend fun openCount() = dao.openCount()
    suspend fun toUpload() = dao.toUpload()

    /** 拍照／錄影存到相簿後呼叫：複製一份到 App 目錄再入列，不影響拍照速度（在背景執行） */
    suspend fun enqueue(mediaUri: Uri, mime: String, name: String): Long = withContext(Dispatchers.IO) {
        val dir = File(app.filesDir, "upload-queue").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        val dest = File(dir, "${System.currentTimeMillis()}_$safe")
        app.contentResolver.openInputStream(mediaUri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("無法讀取已存檔案")
        if (dest.length() == 0L) { dest.delete(); error("複製檔案失敗") }
        dao.insert(
            UploadItem(
                mediaUri = mediaUri.toString(),
                localPath = dest.absolutePath,
                name = name,
                mime = mime,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun markUploading(id: Long) = dao.markUploading(id)
    suspend fun markDone(item: UploadItem, fileId: String) {
        dao.markDone(item.id, fileId)
        File(item.localPath).delete()
    }
    suspend fun markError(item: UploadItem, err: String) {
        dao.mark(item.id, UploadItem.ST_ERROR, err.take(300))
    }
}
